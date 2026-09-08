package com.wireturn.app.viewmodel

import com.wireturn.app.domain.GeoResourceSet
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
sealed class CoreState {
    object Idle : CoreState()
    object Starting : CoreState()
    object Connecting : CoreState()
    object Connected : CoreState()
    object Suppressed : CoreState()
    object Stopping : CoreState()
    object WaitingForNetwork : CoreState()
    data class Error(val message: String) : CoreState()
    data class CaptchaRequired(val url: String, val sessionId: Long = 0L) : CoreState()
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

// Geo resources (geoip.dat/geosite.dat) download states
sealed class GeoAssetsState {
    object Idle : GeoAssetsState()
    data class Downloading(val variant: GeoResourceSet) : GeoAssetsState()
    object Success : GeoAssetsState()
    data class Error(val message: String) : GeoAssetsState()
}

object AppLifecycleState {
    val isAppInForeground = MutableStateFlow(false)
}
