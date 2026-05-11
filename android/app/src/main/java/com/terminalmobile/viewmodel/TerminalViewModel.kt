package com.terminalmobile.viewmodel

import android.app.Application
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.terminalmobile.data.ConnectionConfig
import com.terminalmobile.data.PreferencesRepository
import com.terminalmobile.network.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class LineType { INPUT, SYSTEM, ERROR }

data class TerminalLine(
    val content: String,
    val type: LineType,
    val id: Long = System.nanoTime(),
)

data class TerminalUiState(
    val connectionState: WsState = WsState.Disconnected,
    val systemLines: List<TerminalLine> = emptyList(),   // SYSTEM / INPUT / ERROR
    val ptyLines: List<AnnotatedString> = emptyList(),   // VT100 emulator output
    val input: String = "",
    val history: List<String> = emptyList(),
    val historyIdx: Int = -1,
    val sessions: List<SessionInfo> = emptyList(),
    val currentSessionId: String = "",
    val autoScroll: Boolean = true,
    val fontSize: Int = 13,
)

class TerminalViewModel(app: Application) : AndroidViewModel(app) {

    val prefs = PreferencesRepository(app)
    val wsManager = WebSocketManager()

    private val _ui = MutableStateFlow(TerminalUiState())
    val ui: StateFlow<TerminalUiState> = _ui.asStateFlow()

    private val emulator = TerminalEmulator(cols = 120, rows = 40)
    private val outputBuffer = StringBuilder()
    private var flushJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            prefs.configFlow.collect { cfg ->
                _ui.update { it.copy(fontSize = cfg.fontSize) }
            }
        }
        viewModelScope.launch {
            wsManager.state.collect { state ->
                _ui.update { it.copy(connectionState = state) }
                when (state) {
                    is WsState.Connected    -> addSystemLine("[Connected - session ${state.sessionId.take(8)}]", LineType.SYSTEM)
                    is WsState.Disconnected -> addSystemLine("[Disconnected]", LineType.SYSTEM)
                    is WsState.Reconnecting -> addSystemLine("[Reconnecting... attempt ${state.attempt}]", LineType.SYSTEM)
                    is WsState.Error        -> addSystemLine("[Error: ${state.message}]", LineType.ERROR)
                    is WsState.Connecting   -> addSystemLine("[Connecting...]", LineType.SYSTEM)
                }
            }
        }
        viewModelScope.launch {
            wsManager.messages.collect { msg -> handleMessage(msg) }
        }
    }

    private fun handleMessage(msg: ServerMsg) {
        when (msg) {
            is ServerMsg.AuthSuccess -> {
                _ui.update { it.copy(currentSessionId = msg.sessionId, sessions = msg.sessions) }
                viewModelScope.launch { prefs.saveSessionId(msg.sessionId) }
            }
            is ServerMsg.Output         -> bufferOutput(msg.data)
            is ServerMsg.SessionCreated -> {
                _ui.update { it.copy(currentSessionId = msg.sessionId, sessions = msg.sessions) }
                addSystemLine("[New session ${msg.sessionId.take(8)}]", LineType.SYSTEM)
            }
            is ServerMsg.SessionSwitched -> {
                _ui.update { it.copy(currentSessionId = msg.sessionId, sessions = msg.sessions) }
                addSystemLine("[Switched to session ${msg.sessionId.take(8)}]", LineType.SYSTEM)
            }
            is ServerMsg.Sessions    -> _ui.update { it.copy(sessions = msg.list) }
            is ServerMsg.SessionExit -> addSystemLine("[Session exited with code ${msg.code}]", LineType.SYSTEM)
            is ServerMsg.Err         -> addSystemLine("[Error: ${msg.message}]", LineType.ERROR)
            is ServerMsg.AuthFailed  -> addSystemLine("[Auth failed: ${msg.message}]", LineType.ERROR)
            else                     -> Unit
        }
    }

    private fun bufferOutput(data: String) {
        outputBuffer.append(data)
        flushJob?.cancel()
        flushJob = viewModelScope.launch {
            delay(16)
            flushBuffer()
        }
    }

    private fun flushBuffer() {
        if (outputBuffer.isEmpty()) return
        val raw = outputBuffer.toString()
        outputBuffer.clear()
        emulator.feed(raw)
        _ui.update { it.copy(ptyLines = emulator.getSnapshot()) }
    }

    fun connect(config: ConnectionConfig) {
        addSystemLine("[Connecting to ${config.host}:${config.port}...]", LineType.SYSTEM)
        wsManager.connect(config, viewModelScope)
    }

    fun disconnect() = wsManager.disconnect()

    fun sendInput(text: String, autoEnter: Boolean = true) {
        val toSend = if (autoEnter && !text.endsWith("\n")) "$text\n" else text
        wsManager.sendInput(toSend)
        if (text.isNotBlank()) {
            _ui.update { state ->
                val newHistory = (listOf(text) + state.history).distinct().take(100)
                state.copy(input = "", historyIdx = -1, history = newHistory)
            }
            addSystemLine("> $text", LineType.INPUT)
        }
    }

    fun updateInput(text: String) = _ui.update { it.copy(input = text, historyIdx = -1) }

    fun historyUp() {
        val history = _ui.value.history
        if (history.isEmpty()) return
        val newIdx = (_ui.value.historyIdx + 1).coerceAtMost(history.size - 1)
        _ui.update { it.copy(input = history[newIdx], historyIdx = newIdx) }
    }

    fun historyDown() {
        val idx = _ui.value.historyIdx
        if (idx <= 0) { _ui.update { it.copy(input = "", historyIdx = -1) }; return }
        _ui.update { it.copy(input = _ui.value.history[idx - 1], historyIdx = idx - 1) }
    }

    fun sendCtrlC() = wsManager.sendInput(Char(3).toString())
    fun sendCtrlD() = wsManager.sendInput(Char(4).toString())
    fun sendTab()   = wsManager.sendInput(Char(9).toString())

    fun newSession()              = wsManager.sendNewSession()
    fun switchSession(id: String) = wsManager.sendSwitchSession(id)
    fun killSession(id: String)   = wsManager.sendKillSession(id)
    fun toggleAutoScroll()        = _ui.update { it.copy(autoScroll = !it.autoScroll) }

    fun clearOutput() {
        emulator.clear()
        _ui.update { it.copy(systemLines = emptyList(), ptyLines = emptyList()) }
    }

    private fun addSystemLine(text: String, type: LineType) {
        _ui.update { state ->
            val newLines = state.systemLines + TerminalLine(text, type)
            val trimmed = if (newLines.size > MAX_SYSTEM_LINES) newLines.drop(newLines.size - MAX_SYSTEM_LINES) else newLines
            state.copy(systemLines = trimmed)
        }
    }

    override fun onCleared() {
        super.onCleared()
        wsManager.disconnect()
    }

    companion object {
        private const val MAX_SYSTEM_LINES = 500
    }
}