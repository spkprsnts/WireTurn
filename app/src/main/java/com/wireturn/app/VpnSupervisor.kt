package com.wireturn.app

import android.app.Service
import android.content.Intent
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.data.VpnSettings
import com.wireturn.app.kernel.usesNativeTun
import com.wireturn.app.viewmodel.VpnState
import com.wireturn.app.viewmodel.XrayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Keeps HevVpnService pointed at what the session serves - Xray when it runs, else a SOCKS5-native
 * kernel's own listener - or out of the way when the kernel holds the tun itself (usesNativeTun).
 * Part of CoreService, on its scope; [restartKernel] restarts the kernel (no watchdog attempt),
 * the only way to retry a kernel-TUN handover.
 */
internal class VpnSupervisor(
    private val service: Service,
    private val scope: CoroutineScope,
    private val restartKernel: suspend (logLine: String) -> Unit
) {

    private var job: Job? = null

    // OLCRTC/WEBDAV run their own local SOCKS5 listener, so VPN mode can target those directly
    // when Xray isn't in the picture. Xray keeps priority when running, since it may itself be
    // wrapping one of those cores as a front proxy (see XraySupervisor).
    // mapDns: false for Xray, which answers the tun's DNS itself (see HevVpnService.buildConfigYaml).
    private data class VpnTarget(val addr: String, val user: String?, val pass: String?, val mapDns: Boolean)

    // terminalError: the core isn't coming back on its own (watchdog gave up / invalid config),
    // as opposed to a transient gap (mid-retry, switching profiles) that's expected to recover.
    // native: the kernel brings up and holds the tun itself (usesNativeTun) - no hev target.
    private data class VpnTargetSignal(val target: VpnTarget?, val terminalError: Boolean, val native: Boolean = false)

    private data class VpnSupervisorBundle(
        val signal: VpnTargetSignal,
        val vpnSettings: VpnSettings,
        val vpnState: VpnState
    )

    fun start() {
        job?.cancel()
        job = scope.launch {
            val prefs = AppPreferences(service.applicationContext)
            var lastVpnSettings: VpnSettings? = null
            var lastTarget: VpnTarget? = null
            // Debounces tearing the VPN down when the target transiently disappears (e.g.
            // switching profiles) - only actually stops if it doesn't reappear in time.
            var pendingStopJob: Job? = null
            // Retries a failed establish() (e.g. another app still held VPN ownership) on a
            // fixed backoff - without this, VpnState.Error only ever gets retried when settings
            // or target happen to change, and can otherwise sit there forever.
            var pendingVpnRetryJob: Job? = null

            fun stopIntent() = Intent(service, HevVpnService::class.java).apply {
                action = HevVpnService.ACTION_STOP
            }
            fun startIntent(target: VpnTarget) = Intent(service, HevVpnService::class.java).apply {
                putExtra(HevVpnService.EXTRA_SOCKS5_ADDR, target.addr)
                if (target.user != null) {
                    putExtra(HevVpnService.EXTRA_SOCKS5_USER, target.user)
                    putExtra(HevVpnService.EXTRA_SOCKS5_PASS, target.pass)
                }
                putExtra(HevVpnService.EXTRA_MAP_DNS, target.mapDns)
            }
            fun retargetIntent(target: VpnTarget) = startIntent(target).apply {
                action = HevVpnService.ACTION_UPDATE_TARGET
            }

            val targetFlow = combine(
                XrayServiceState.state,
                XrayServiceState.session,
                CoreServiceState.status,
                CoreServiceState.session,
                CoreServiceState.errorsRetried
            ) { xrayState, xraySession, coreStatus, coreSession, eternal ->
                // The eternal watchdog retries even an Error (see CoreService.mainSupervisor) - it's
                // another gap on the way to the next attempt, and the VPN stays up through it like
                // through any other: apps don't fall back to the open network meanwhile. A session
                // that does end (stopSelf) goes Idle, which drops the VPN anyway.
                val coreFailed = coreStatus is CoreStatus.Error && !eternal
                if (xrayState != XrayState.Idle && xraySession != null) {
                    val s = xraySession.settings
                    VpnTargetSignal(
                        VpnTarget(
                            s.connectableAddress,
                            s.proxyUser.takeIf { s.isProxyAuthEnabled && it.isNotBlank() },
                            s.proxyPass,
                            mapDns = false
                        ),
                        terminalError = false
                    )
                } else if (coreSession != null &&
                    coreSession.clientConfig.kernelVariant.isSocks5Native &&
                    coreStatus !is CoreStatus.Idle && !coreFailed && coreStatus !is CoreStatus.WaitingForNetwork
                ) {
                    val cc = coreSession.clientConfig
                    VpnTargetSignal(
                        VpnTarget(
                            // socksAddr may be bound to 0.0.0.0; hev needs a literal destination.
                            cc.socksAddr.replace("0.0.0.0:", "127.0.0.1:"),
                            cc.socksUser.takeIf { cc.isSocksAuthEnabled && it.isNotBlank() },
                            cc.socksPass,
                            mapDns = true
                        ),
                        terminalError = false
                    )
                } else {
                    // Target-less and no Xray: mid-retry states are still expected to recover,
                    // but a genuine CoreStatus.Error means nothing is coming - drop right away
                    // instead of waiting out the grace period below for nothing.
                    VpnTargetSignal(null, terminalError = coreFailed)
                }
            }

            // From the prefs rather than what the kernel was started with, so a toggle is acted on
            // right away instead of retargeting hev at a kernel that's about to restart without it.
            val nativeFlow = combine(
                CoreServiceState.session,
                prefs.vpnSettingsFlow,
                prefs.xrayConfigFlow
            ) { coreSession, vpnSettings, xrayConfig ->
                coreSession != null && usesNativeTun(coreSession.clientConfig, vpnSettings.enabled, xrayConfig.enabled)
            }.distinctUntilChanged()

            var wasNative = false

            combine(targetFlow, nativeFlow, prefs.vpnSettingsFlow, VpnServiceState.state) { signal, native, vpnSettings, vpnState ->
                val effective = if (native) VpnTargetSignal(null, signal.terminalError, native = true) else signal
                VpnSupervisorBundle(effective, vpnSettings, vpnState)
            }.collect { bundle ->
                withContext(Dispatchers.Main) {
                    val target = bundle.signal.target
                    val settingsChanged = lastVpnSettings != null && lastVpnSettings != bundle.vpnSettings
                    val targetChanged = lastTarget != null && lastTarget != target
                    lastVpnSettings = bundle.vpnSettings
                    lastTarget = target

                    if (target != null) {
                        pendingStopJob?.cancel()
                        pendingStopJob = null
                    }

                    if (!bundle.vpnSettings.enabled) {
                        // Explicit user opt-out - stop immediately, no grace period.
                        if (bundle.vpnState != VpnState.Idle) service.startService(stopIntent())
                        return@withContext
                    }

                    if (bundle.signal.native) {
                        // The kernel asks for its tun itself (onNativeTunConfig) - nothing to start
                        // or retarget here; settings changes are handled by CoreService.mainSupervisor.
                        wasNative = true
                        pendingStopJob?.cancel()
                        pendingStopJob = null
                        if (bundle.vpnState !is VpnState.Error) {
                            pendingVpnRetryJob?.cancel()
                            pendingVpnRetryJob = null
                        }
                        if (bundle.vpnState != VpnState.Idle && bundle.signal.terminalError) {
                            service.startService(stopIntent())
                        } else if (bundle.vpnState is VpnState.Error && pendingVpnRetryJob == null) {
                            // The kernel takes a tun only once per process, so a failed establish
                            // or handover is retried by restarting it.
                            pendingVpnRetryJob = scope.launch {
                                delay(VPN_ERROR_RETRY_MS.milliseconds)
                                pendingVpnRetryJob = null
                                if (VpnServiceState.state.value is VpnState.Error) {
                                    restartKernel(service.getString(R.string.log_core_tun_retry))
                                }
                            }
                        }
                        return@withContext
                    }
                    if (wasNative) {
                        wasNative = false
                        pendingVpnRetryJob?.cancel()
                        pendingVpnRetryJob = null
                        // Leaving kernel TUN mode: that tun has nothing behind it anymore - drop it,
                        // and the Idle that follows brings hev up on the new target.
                        if (bundle.vpnState != VpnState.Idle) {
                            service.startService(stopIntent())
                            return@withContext
                        }
                    }

                    if (target == null) {
                        // Enabled, but nothing to point the relay at right now.
                        if (bundle.vpnState != VpnState.Idle) {
                            if (bundle.signal.terminalError) {
                                // The core has definitively given up - nothing to wait for.
                                service.startService(stopIntent())
                            } else if (pendingStopJob == null) {
                                // Could be a transient gap - give it a moment before tearing down.
                                pendingStopJob = scope.launch {
                                    delay(VPN_TARGET_LOST_GRACE_MS.milliseconds)
                                    withContext(Dispatchers.Main) { service.startService(stopIntent()) }
                                }
                            }
                        }
                        return@withContext
                    }

                    val vpnRunning = bundle.vpnState == VpnState.Running
                    val vpnError = bundle.vpnState is VpnState.Error

                    if (!vpnError) {
                        pendingVpnRetryJob?.cancel()
                        pendingVpnRetryJob = null
                    }

                    when {
                        bundle.vpnState == VpnState.Idle -> {
                            // Establish as soon as any target exists, before it's even connected -
                            // a kill switch should be up before it's needed.
                            if (VpnServiceState.state.value != VpnState.Starting) {
                                service.startService(startIntent(target))
                            }
                        }
                        vpnError && (settingsChanged || targetChanged) -> {
                            AppLogsState.addLog(service.getString(R.string.log_vpn_restarting_config))
                            service.startService(stopIntent())
                            service.startService(startIntent(target))
                        }
                        vpnError && pendingVpnRetryJob == null -> {
                            // Nothing about the target/settings changed, so nothing else here
                            // will ever retry this on its own - back off and try again anyway.
                            pendingVpnRetryJob = scope.launch {
                                delay(VPN_ERROR_RETRY_MS.milliseconds)
                                pendingVpnRetryJob = null
                                withContext(Dispatchers.Main) {
                                    if (VpnServiceState.state.value is VpnState.Error) {
                                        service.startService(stopIntent())
                                        service.startService(startIntent(target))
                                    }
                                }
                            }
                        }
                        vpnRunning && settingsChanged -> {
                            // Routing config itself changed (filtering/bypass/app list) - that's
                            // baked into the Builder at establish() time, so it must be redone.
                            AppLogsState.addLog(service.getString(R.string.log_vpn_restarting_config))
                            service.startService(stopIntent())
                            service.startService(startIntent(target))
                        }
                        vpnRunning && targetChanged -> {
                            // Only the upstream SOCKS5 hop moved (e.g. switched profiles) - hot-
                            // swap the relay's target without a fresh establish(), so the tun
                            // interface (and the device's default network) never blips.
                            service.startService(retargetIntent(target))
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val VPN_TARGET_LOST_GRACE_MS = 5_000L
        const val VPN_ERROR_RETRY_MS = 15_000L
    }
}
