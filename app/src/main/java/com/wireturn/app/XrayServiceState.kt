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
    data class RunningSession(
        val wg: WgConfig?,
        val xray: XrayConfig,
        val vless: VlessConfig?,
        val settings: XraySettings
    )

    private val _state = MutableStateFlow<XrayState>(XrayState.Idle)
    val state = _state.asStateFlow()

    private val _statsSocketName = MutableStateFlow<String?>(null)
    val statsSocketName = _statsSocketName.asStateFlow()

    private val _session = MutableStateFlow<RunningSession?>(null)
    val session = _session.asStateFlow()

    fun updateStatus(newStatus: XrayState) {
        _state.value = newStatus
        if (newStatus == XrayState.Idle) {
            _session.value = null
            _statsSocketName.value = null
        }
    }

    fun updateStatsSocketName(name: String?) {
        _statsSocketName.value = name
    }

    fun setSession(session: RunningSession?) {
        _session.value = session
        val activeSettings = session?.settings ?: return
        if (activeSettings.isProxyAuthEnabled && activeSettings.proxyUser.isNotBlank()) {
            val u = activeSettings.proxyUser
            val p = activeSettings.proxyPass
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
