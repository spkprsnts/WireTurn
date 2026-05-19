package com.wireturn.app

import com.wireturn.app.data.VlessConfig
import com.wireturn.app.data.WgConfig
import com.wireturn.app.data.XrayConfig
import com.wireturn.app.data.XraySettings
import com.wireturn.app.viewmodel.XrayState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Authenticator
import java.net.PasswordAuthentication

object XrayServiceState {
    private val _state = MutableStateFlow<XrayState>(XrayState.Idle)
    val state = _state.asStateFlow()

    private val _statsSocketName = MutableStateFlow<String?>(null)
    val statsSocketName = _statsSocketName.asStateFlow()

    private val _wgConfigSnapshot = MutableStateFlow<WgConfig?>(null)
    val wgConfigSnapshot = _wgConfigSnapshot.asStateFlow()

    private val _xrayConfigSnapshot = MutableStateFlow<XrayConfig?>(null)
    val xrayConfigSnapshot = _xrayConfigSnapshot.asStateFlow()

    private val _xraySettingsSnapshot = MutableStateFlow<XraySettings?>(null)
    val xraySettingsSnapshot = _xraySettingsSnapshot.asStateFlow()

    private val _vlessConfigSnapshot = MutableStateFlow<VlessConfig?>(null)
    val vlessConfigSnapshot = _vlessConfigSnapshot.asStateFlow()

    fun updateStatus(newStatus: XrayState) {
        _state.value = newStatus
        if (newStatus == XrayState.Idle) {
            _wgConfigSnapshot.value = null
            _xrayConfigSnapshot.value = null
            _xraySettingsSnapshot.value = null
            _vlessConfigSnapshot.value = null
            _statsSocketName.value = null
        }
    }

    fun updateStatsSocketName(name: String?) {
        _statsSocketName.value = name
    }

    fun setConfigsSnapshot(wg: WgConfig?, xray: XrayConfig?, vless: VlessConfig?, settings: XraySettings? = null) {
        _wgConfigSnapshot.value = wg
        _xrayConfigSnapshot.value = xray
        _vlessConfigSnapshot.value = vless
        _xraySettingsSnapshot.value = settings

        val activeSettings = settings ?: return // If null, we don't set global auth

        if (activeSettings.isProxyAuthEnabled && activeSettings.proxyUser.isNotBlank()) {
            val u = activeSettings.proxyUser
            val p = activeSettings.proxyPass

            // Set global properties for SOCKS5 auth (fallback for some Java versions/libs)
            System.setProperty("java.net.socks.username", u)
            System.setProperty("java.net.socks.password", p)

            Authenticator.setDefault(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(u, p.toCharArray())
                }
            })
        } else {
            System.clearProperty("java.net.socks.username")
            System.clearProperty("java.net.socks.password")
            Authenticator.setDefault(null)
        }
    }
}
