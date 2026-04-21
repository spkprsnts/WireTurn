package com.wireturn.app.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow

// Wireproxy states
sealed class WireproxyState {
    object Idle : WireproxyState()
    object Starting : WireproxyState()
    object Running : WireproxyState()
}

// VPN states
sealed class VpnState {
    object Idle : VpnState()
    object Starting : VpnState()
    object Running : VpnState()
    data class Error(val message: String) : VpnState()
}

// Local proxy client states
sealed class ProxyState {
    object Idle : ProxyState()
    object Starting : ProxyState()
    object Running : ProxyState()
    object Working : ProxyState()
    data class Error(val message: String) : ProxyState()
    data class CaptchaRequired(val url: String, val sessionId: Long = 0L) : ProxyState()
}

// App update states
sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class Available(val version: String, val changelog: String) : UpdateState()
    object NoUpdate : UpdateState()
    data class Downloading(val progress: Int) : UpdateState()
    object ReadyToInstall : UpdateState()
    data class Error(val message: String) : UpdateState()
}

object AppLifecycleState {
    val isAppInForeground = MutableStateFlow(false)
}
