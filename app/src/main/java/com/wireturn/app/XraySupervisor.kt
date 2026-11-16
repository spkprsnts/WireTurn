package com.wireturn.app

import android.app.Service
import android.content.Intent
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelVariant
import com.wireturn.app.viewmodel.XrayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Keeps XrayService running while a session wants it - Xray on in the settings, or an Xray-only
 * profile - and restarts it when what it runs with changes. Part of CoreService, on its scope.
 */
internal class XraySupervisor(private val service: Service, private val scope: CoroutineScope) {

    private var job: Job? = null

    private data class XrayConfigSignals(
        val xrayConfig: com.wireturn.app.data.XrayConfig,
        val vless: com.wireturn.app.data.VlessConfig,
        val wg: com.wireturn.app.data.WgConfig,
        val settings: com.wireturn.app.data.XraySettings,
        // Picks Xray's DNS query strategy (see XrayService's -dns-query-strategy).
        val vpnIpv6: Boolean
    )

    private data class XraySupervisorBundle(
        val shouldBeRunning: Boolean,
        val xrayState: XrayState,
        val signals: XrayConfigSignals,
        val kernelVariant: KernelVariant,
        val connectionTarget: String?,
        val connectionAuth: Triple<Boolean, String, String>?,
        // An Xray-only profile's own settings (link, mux) - Xray restarts when they change.
        val xrayOnly: com.wireturn.app.data.kernel.XrayLinkConfig?
    )

    fun start() {
        job?.cancel()
        job = scope.launch {
            val prefs = AppPreferences(service.applicationContext)
            val configFlow = combine(
                prefs.xrayConfigFlow,
                prefs.vlessConfigFlow,
                prefs.wgConfigFlow,
                prefs.xraySettingsFlow,
                prefs.vpnSettingsFlow
            ) { xray, vless, wg, settings, vpnSettings ->
                // reloadOnNetworkChange is read by the network callback, not by Xray: toggling it
                // shouldn't restart Xray.
                XrayConfigSignals(
                    xray, vless.fillDefaults(), wg.fillDefaults(),
                    settings.fillDefaults().copy(reloadOnNetworkChange = true), vpnSettings.ipv6
                )
            }
            combine(
                configFlow,
                CoreServiceState.status,
                XrayServiceState.state,
                CoreServiceState.session,
                CoreServiceState.errorsRetried
            ) { signals, status, xrayState, coreSession, errorsRetried ->
                val clientConfig = coreSession?.clientConfig ?: return@combine null
                // An Xray-only profile runs Xray whatever its (absent) overlay says - see XrayKernel.
                val xrayOnly = (clientConfig.kernelConfig as? KernelConfig.Xray)?.config

                // An Error the eternal watchdog retries keeps Xray up for the next attempt - no
                // stop and start around every failed one.
                val shouldBeRunning = (signals.xrayConfig.enabled || xrayOnly != null) &&
                        status !is CoreStatus.Idle &&
                        (status !is CoreStatus.Error || errorsRetried) &&
                        status !is CoreStatus.WaitingForNetwork

                val connectionTarget =
                    if (clientConfig.kernelVariant.isSocks5Native) clientConfig.socksAddr else clientConfig.listenAddr
                val connectionAuth = if (clientConfig.kernelVariant.isSocks5Native) {
                    Triple(
                        clientConfig.isSocksAuthEnabled,
                        clientConfig.socksUser,
                        clientConfig.socksPass
                    )
                } else null

                XraySupervisorBundle(
                    shouldBeRunning = shouldBeRunning,
                    xrayState = xrayState,
                    signals = signals,
                    kernelVariant = clientConfig.kernelVariant,
                    connectionTarget = connectionTarget,
                    connectionAuth = connectionAuth,
                    xrayOnly = xrayOnly
                )
            }
                .filterNotNull()
                .distinctUntilChanged { old, new ->
                    if (old.shouldBeRunning != new.shouldBeRunning) return@distinctUntilChanged false
                    if (!new.shouldBeRunning) return@distinctUntilChanged new.xrayState == XrayState.Idle
                    return@distinctUntilChanged new.xrayState != XrayState.Idle &&
                            old.signals == new.signals &&
                            old.kernelVariant == new.kernelVariant &&
                            old.connectionTarget == new.connectionTarget &&
                            old.connectionAuth == new.connectionAuth &&
                            old.xrayOnly == new.xrayOnly
                }
                .collectLatest { data: XraySupervisorBundle ->
                    val needsStart = data.shouldBeRunning && data.xrayState == XrayState.Idle
                    val needsStop = !data.shouldBeRunning && data.xrayState != XrayState.Idle
                    val needsRestart = data.shouldBeRunning && data.xrayState != XrayState.Idle

                    if (needsRestart) {
                        AppLogsState.addLog(service.getString(R.string.log_xray_config_change_restart))
                        withContext(Dispatchers.Main) {
                            service.stopService(Intent(service, XrayService::class.java))
                            delay(500.milliseconds)
                            service.startForegroundService(Intent(service, XrayService::class.java))
                        }
                    } else if (needsStart) {
                        delay(500.milliseconds) // Debounce during profile switches
                        withContext(Dispatchers.Main) {
                            val currentXrayState = XrayServiceState.state.value
                            val currentStatus = CoreServiceState.status.value
                            if (currentXrayState == XrayState.Idle && currentStatus !is CoreStatus.Idle &&
                                (currentStatus !is CoreStatus.Error || CoreServiceState.errorsRetried.value)
                            ) {
                                service.startForegroundService(
                                    Intent(
                                        service,
                                        XrayService::class.java
                                    )
                                )
                            }
                        }
                    } else if (needsStop) {
                        withContext(Dispatchers.Main) {
                            service.stopService(Intent(service, XrayService::class.java))
                        }
                    }
                }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
