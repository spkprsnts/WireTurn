package com.wireturn.app

import com.wireturn.app.data.VlessConfig
import com.wireturn.app.data.WgConfig
import com.wireturn.app.data.XrayConfig
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

    private val _vlessConfigSnapshot = MutableStateFlow<VlessConfig?>(null)
    val vlessConfigSnapshot = _vlessConfigSnapshot.asStateFlow()

    fun updateStatus(newStatus: XrayState) {
        _state.value = newStatus
        if (newStatus == XrayState.Idle) {
            _wgConfigSnapshot.value = null
            _xrayConfigSnapshot.value = null
            _vlessConfigSnapshot.value = null
            _statsSocketName.value = null
        }
    }

    fun updateStatsSocketName(name: String?) {
        _statsSocketName.value = name
    }

    fun setConfigsSnapshot(wg: WgConfig?, xray: XrayConfig?, vless: VlessConfig?) {
        _wgConfigSnapshot.value = wg
        _xrayConfigSnapshot.value = xray
        _vlessConfigSnapshot.value = vless

        if (xray != null && xray.isProxyAuthEnabled && xray.proxyUser.isNotBlank()) {
            val u = xray.proxyUser
            val p = xray.proxyPass

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
