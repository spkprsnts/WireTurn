package com.wireturn.app

import com.wireturn.app.data.WgConfig
import com.wireturn.app.viewmodel.WireproxyState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object WireproxyServiceState {
    private val _state = MutableStateFlow<WireproxyState>(WireproxyState.Idle)
    val state = _state.asStateFlow()

    private val _metricsPort = MutableStateFlow<Int?>(null)
    val metricsPort = _metricsPort.asStateFlow()

    private val _runningConfig = MutableStateFlow<WgConfig?>(null)
    val runningConfig = _runningConfig.asStateFlow()

    fun updateStatus(newStatus: WireproxyState) {
        _state.value = newStatus
        if (newStatus == WireproxyState.Idle) {
            _runningConfig.value = null
        }
    }

    fun updateMetricsPort(port: Int?) {
        _metricsPort.value = port
    }

    fun setRunningConfig(config: WgConfig?) {
        _runningConfig.value = config
    }
}
