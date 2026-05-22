package com.wireturn.app.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow

// Xray states
sealed class XrayState {
    object Idle : XrayState()
    object Starting : XrayState()
    object Connecting : XrayState()
    object Running : XrayState()
    object DirectRoute : XrayState()
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
    object Connecting : ProxyState()
    object Connected : ProxyState()
    object Suppressed : ProxyState()
    object WaitingForNetwork : ProxyState()
    data class Error(val message: String) : ProxyState()
    data class CaptchaRequired(val url: String, val sessionId: Long = 0L) : ProxyState()
}

// App update states
sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class Available(val version: String, val changelog: String) : UpdateState()
    object NoUpdate : UpdateState()
    object Downloading : UpdateState()
    object ReadyToInstall : UpdateState()
    data class Error(val message: String) : UpdateState()
}

val UpdateState.isImportant: Boolean
    get() = this is UpdateState.Available ||
            this is UpdateState.Downloading ||
            this is UpdateState.ReadyToInstall ||
            this is UpdateState.Error

object AppLifecycleState {
    val isAppInForeground = MutableStateFlow(false)
}
