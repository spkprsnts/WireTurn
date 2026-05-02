package com.wireturn.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Глобальное состояние логов приложения (прокси, Xray, VPN и др.)
 */
object AppLogsState {
    
    data class LogEntry(val id: Long, val message: String)

    private const val MAX_LOG_LINES = 500
    private var nextId = 0L
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    fun addLog(msg: String) {
        val cleanMsg = stripAnsi(msg)
        _logs.update { current ->
            val next = current + LogEntry(nextId++, cleanMsg)
            if (next.size > MAX_LOG_LINES) next.drop(next.size - MAX_LOG_LINES) else next
        }
    }

    fun stripAnsi(msg: String): String {
        return msg.replace("\u001B\\[[;\\d]*[mK]".toRegex(), "")
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
