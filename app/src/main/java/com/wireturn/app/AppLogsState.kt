package com.wireturn.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Глобальное состояние логов приложения (прокси, Xray, VPN и др.)
 */
object AppLogsState {
    private const val MAX_LOG_LINES = 500
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    fun addLog(msg: String) {
        _logs.update { current ->
            val next = current + msg
            if (next.size > MAX_LOG_LINES) next.drop(next.size - MAX_LOG_LINES) else next
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
