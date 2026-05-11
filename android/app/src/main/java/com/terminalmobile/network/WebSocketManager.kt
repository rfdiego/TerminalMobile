package com.terminalmobile.network

import com.terminalmobile.data.ConnectionConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class WsState {
    object Disconnected : WsState()
    object Connecting : WsState()
    data class Connected(val sessionId: String) : WsState()
    data class Reconnecting(val attempt: Int, val delayMs: Long) : WsState()
    data class Error(val message: String) : WsState()
}

sealed class ServerMsg {
    data class AuthSuccess(val sessionId: String, val sessions: List<SessionInfo>) : ServerMsg()
    data class AuthFailed(val message: String) : ServerMsg()
    data class Output(val data: String) : ServerMsg()
    data class SessionExit(val code: Int, val sessionId: String) : ServerMsg()
    data class SessionCreated(val sessionId: String, val sessions: List<SessionInfo>) : ServerMsg()
    data class SessionSwitched(val sessionId: String, val sessions: List<SessionInfo>) : ServerMsg()
    data class Sessions(val list: List<SessionInfo>) : ServerMsg()
    data class Err(val message: String) : ServerMsg()
    data class Pong(val ts: Long) : ServerMsg()
}

data class SessionInfo(val id: String, val alive: Boolean, val createdAt: Long = 0)

class WebSocketManager {

    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var scope: CoroutineScope? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var config: ConnectionConfig? = null
    private var userDisconnected = false

    private val _state = MutableStateFlow<WsState>(WsState.Disconnected)
    val state: StateFlow<WsState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<ServerMsg>(extraBufferCapacity = 512)
    val messages: SharedFlow<ServerMsg> = _messages.asSharedFlow()

    fun connect(cfg: ConnectionConfig, coroutineScope: CoroutineScope) {
        config = cfg
        scope = coroutineScope
        userDisconnected = false
        reconnectAttempt = 0
        doConnect(cfg)
    }

    private fun doConnect(cfg: ConnectionConfig) {
        val scheme = if (cfg.useTls) "wss" else "ws"
        val url = "$scheme://${cfg.host.trim()}:${cfg.port}"
        _state.value = WsState.Connecting

        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, Listener(cfg))
    }

    private inner class Listener(private val cfg: ConnectionConfig) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempt = 0
            val auth = JSONObject().apply {
                put("type", "auth")
                put("token", cfg.token)
                if (cfg.lastSessionId.isNotBlank()) put("sessionId", cfg.lastSessionId)
                put("cols", 120)
                put("rows", 40)
            }
            webSocket.send(auth.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = parse(text)
            if (msg is ServerMsg.AuthSuccess) {
                _state.value = WsState.Connected(msg.sessionId)
            }
            scope?.launch { _messages.emit(msg) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (userDisconnected) return
            val errMsg = t.message ?: "Connection failed"
            _state.value = WsState.Error(errMsg)
            scope?.launch { _messages.emit(ServerMsg.Err(errMsg)) }
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (userDisconnected || code == 1000) {
                _state.value = WsState.Disconnected
            } else {
                _state.value = WsState.Disconnected
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        val cfg = config ?: return
        if (userDisconnected) return
        reconnectJob?.cancel()
        reconnectAttempt++
        if (reconnectAttempt > MAX_RECONNECT_ATTEMPTS) {
            _state.value = WsState.Error("Falhou apos $MAX_RECONNECT_ATTEMPTS tentativas. Verifique IP/token e reconecte.")
            return
        }
        val delay = minOf(1_000L shl minOf(reconnectAttempt - 1, 5), 30_000L)
        _state.value = WsState.Reconnecting(reconnectAttempt, delay)
        reconnectJob = scope?.launch {
            delay(delay)
            if (!userDisconnected) doConnect(cfg)
        }
    }

    fun sendInput(data: String) = send(JSONObject().put("type", "input").put("data", data))
    fun sendResize(cols: Int, rows: Int) = send(JSONObject().put("type", "resize").put("cols", cols).put("rows", rows))
    fun sendNewSession() = send(JSONObject().put("type", "new_session"))
    fun sendSwitchSession(id: String) = send(JSONObject().put("type", "switch_session").put("sessionId", id))
    fun sendKillSession(id: String) = send(JSONObject().put("type", "kill_session").put("sessionId", id))
    fun sendListSessions() = send(JSONObject().put("type", "list_sessions"))
    fun sendPing() = send(JSONObject().put("type", "ping"))

    private fun send(json: JSONObject): Boolean = ws?.send(json.toString()) ?: false

    fun disconnect() {
        userDisconnected = true
        reconnectJob?.cancel()
        ws?.close(1000, "User disconnect")
        ws = null
        _state.value = WsState.Disconnected
    }

    private fun parse(raw: String): ServerMsg = try {
        val j = JSONObject(raw)
        when (j.optString("type")) {
            "auth_success" -> ServerMsg.AuthSuccess(
                j.getString("sessionId"),
                parseSessions(j.optJSONArray("sessions"))
            )
            "auth_failed" -> ServerMsg.AuthFailed(j.optString("message"))
            "output"      -> ServerMsg.Output(j.getString("data"))
            "session_exit" -> ServerMsg.SessionExit(j.optInt("code", -1), j.optString("sessionId"))
            "session_created" -> ServerMsg.SessionCreated(j.getString("sessionId"), parseSessions(j.optJSONArray("sessions")))
            "session_switched" -> ServerMsg.SessionSwitched(j.getString("sessionId"), parseSessions(j.optJSONArray("sessions")))
            "sessions"    -> ServerMsg.Sessions(parseSessions(j.optJSONArray("list")))
            "error"       -> ServerMsg.Err(j.optString("message"))
            "pong"        -> ServerMsg.Pong(j.optLong("ts"))
            else          -> ServerMsg.Err("Unknown message: ${j.optString("type")}")
        }
    } catch (e: Exception) {
        ServerMsg.Err("Parse error: ${e.message}")
    }

    private fun parseSessions(arr: JSONArray?): List<SessionInfo> {
        arr ?: return emptyList()
        return (0 until arr.length()).map { i ->
            arr.getJSONObject(i).let { o ->
                SessionInfo(o.getString("id"), o.optBoolean("alive", true), o.optLong("createdAt"))
            }
        }
    }

    companion object {
        private const val MAX_RECONNECT_ATTEMPTS = 8
    }
}