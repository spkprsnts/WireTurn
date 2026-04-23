package com.wireturn.app

import com.wireturn.app.data.WgConfig
import com.wireturn.app.data.XrayConfig
import com.wireturn.app.viewmodel.XrayState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object XrayServiceState {
    private val _state = MutableStateFlow<XrayState>(XrayState.Idle)
    val state = _state.asStateFlow()

    private val _metricsPort = MutableStateFlow<Int?>(null)
    val metricsPort = _metricsPort.asStateFlow()

    private val _runningConfig = MutableStateFlow<WgConfig?>(null)
    val runningConfig = _runningConfig.asStateFlow()

    private val _runningXrayConfig = MutableStateFlow<XrayConfig?>(null)
    val runningXrayConfig = _runningXrayConfig.asStateFlow()

    fun updateStatus(newStatus: XrayState) {
        _state.value = newStatus
        if (newStatus == XrayState.Idle) {
            _runningConfig.value = null
            _runningXrayConfig.value = null
        }
    }

    fun updateMetricsPort(port: Int?) {
        _metricsPort.value = port
    }

    fun setRunningConfigs(wg: WgConfig?, xray: XrayConfig?) {
        _runningConfig.value = wg
        _runningXrayConfig.value = xray
    }
}
