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

    private val _wgConfigSnapshot = MutableStateFlow<WgConfig?>(null)
    val wgConfigSnapshot = _wgConfigSnapshot.asStateFlow()

    private val _xrayConfigSnapshot = MutableStateFlow<XrayConfig?>(null)
    val xrayConfigSnapshot = _xrayConfigSnapshot.asStateFlow()

    private val _vlessConfigSnapshot = MutableStateFlow<VlessConfig?>(null)
    val vlessConfigSnapshot = _vlessConfigSnapshot.asStateFlow()

    private val _xraySettingsSnapshot = MutableStateFlow<com.wireturn.app.data.XraySettings?>(null)
    val xraySettingsSnapshot = _xraySettingsSnapshot.asStateFlow()

    fun updateStatus(newStatus: XrayState) {
        _state.value = newStatus
        if (newStatus == XrayState.Idle) {
            _wgConfigSnapshot.value = null
            _xrayConfigSnapshot.value = null
            _vlessConfigSnapshot.value = null
            _xraySettingsSnapshot.value = null
            _metricsPort.value = null
        }
    }

    fun updateMetricsPort(port: Int?) {
        _metricsPort.value = port
    }

    fun setConfigsSnapshot(wg: WgConfig?, xray: XrayConfig?, vless: VlessConfig?, settings: com.wireturn.app.data.XraySettings? = null) {
        _wgConfigSnapshot.value = wg
        _xrayConfigSnapshot.value = xray
        _vlessConfigSnapshot.value = vless
        _xraySettingsSnapshot.value = settings
    }
}
