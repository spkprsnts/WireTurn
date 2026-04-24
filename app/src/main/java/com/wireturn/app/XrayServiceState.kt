package com.wireturn.app

import com.wireturn.app.data.VlessConfig
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

    private val _runningWgConfig = MutableStateFlow<WgConfig?>(null)
    val runningWgConfig = _runningWgConfig.asStateFlow()

    private val _runningXrayConfig = MutableStateFlow<XrayConfig?>(null)
    val runningXrayConfig = _runningXrayConfig.asStateFlow()

    private val _runningVlessConfig = MutableStateFlow<VlessConfig?>(null)
    val runningVlessConfig = _runningVlessConfig.asStateFlow()

    fun updateStatus(newStatus: XrayState) {
        _state.value = newStatus
        if (newStatus == XrayState.Idle) {
            _runningWgConfig.value = null
            _runningXrayConfig.value = null
            _runningVlessConfig.value = null
        }
    }

    fun updateMetricsPort(port: Int?) {
        _metricsPort.value = port
    }

    fun setRunningConfigs(wg: WgConfig?, xray: XrayConfig?, vless: VlessConfig?) {
        _runningWgConfig.value = wg
        _runningXrayConfig.value = xray
        _runningVlessConfig.value = vless
    }
}
