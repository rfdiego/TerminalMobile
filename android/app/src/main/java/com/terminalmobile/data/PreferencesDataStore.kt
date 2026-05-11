package com.terminalmobile.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class ConnectionConfig(
    val host: String = "",
    val port: Int = 8765,
    val token: String = "",
    val useTls: Boolean = false,
    val autoConnect: Boolean = false,
    val fontSize: Int = 13,
    val lastSessionId: String = "",
)

object PrefKeys {
    val HOST = stringPreferencesKey("host")
    val PORT = intPreferencesKey("port")
    val TOKEN = stringPreferencesKey("token")
    val USE_TLS = booleanPreferencesKey("use_tls")
    val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
    val FONT_SIZE = intPreferencesKey("font_size")
    val LAST_SESSION_ID = stringPreferencesKey("last_session_id")
}

class PreferencesRepository(private val context: Context) {

    val configFlow: Flow<ConnectionConfig> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            ConnectionConfig(
                host = prefs[PrefKeys.HOST] ?: "",
                port = prefs[PrefKeys.PORT] ?: 8765,
                token = prefs[PrefKeys.TOKEN] ?: "",
                useTls = prefs[PrefKeys.USE_TLS] ?: false,
                autoConnect = prefs[PrefKeys.AUTO_CONNECT] ?: false,
                fontSize = prefs[PrefKeys.FONT_SIZE] ?: 13,
                lastSessionId = prefs[PrefKeys.LAST_SESSION_ID] ?: "",
            )
        }

    suspend fun save(config: ConnectionConfig) {
        context.dataStore.edit { prefs ->
            prefs[PrefKeys.HOST] = config.host
            prefs[PrefKeys.PORT] = config.port
            prefs[PrefKeys.TOKEN] = config.token
            prefs[PrefKeys.USE_TLS] = config.useTls
            prefs[PrefKeys.AUTO_CONNECT] = config.autoConnect
            prefs[PrefKeys.FONT_SIZE] = config.fontSize
            prefs[PrefKeys.LAST_SESSION_ID] = config.lastSessionId
        }
    }

    suspend fun saveSessionId(id: String) {
        context.dataStore.edit { it[PrefKeys.LAST_SESSION_ID] = id }
    }
}
