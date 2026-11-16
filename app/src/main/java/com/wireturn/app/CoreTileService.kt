package com.wireturn.app

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.ui.activities.MainActivity
import com.wireturn.app.viewmodel.XrayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class CoreTileService : TileService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var statusJob: Job? = null
    // The auto-launch flag as last read while listening - for the tile's instant update, in place
    // of a blocking DataStore read on the main thread. The real value is read right after.
    private var autoLaunchEnabled = false

    companion object {
        const val EXTRA_REQUEST_VPN_CONSENT = "REQUEST_VPN_CONSENT"

        // A tap's action - outlives the tile service instance it came from (see onClick).
        private val clickScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        fun requestUpdate(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, CoreTileService::class.java))
            } catch (_: Exception) {
                // Игнорируем ошибки на старых API или если плитка не добавлена
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        
        val prefs = AppPreferences(this)

        // Мгновенное обновление при открытии шторки - with the last known auto-launch flag; the
        // collector below brings the stored one right after.
        val status = CoreServiceState.status.value
        val restartAttempt = CoreServiceState.restartAttempt.value
        val statusText = CoreServiceState.statusText.value
        val xrayState = XrayServiceState.state.value

        val isDirect = status is CoreStatus.Suppressed
        val isXrayWorking = xrayState == XrayState.Running || xrayState == XrayState.DirectRoute
        // VPN state deliberately doesn't factor in here - the tun interface can stay up while
        // the core tunnel behind it is reconnecting, and "Active" should reflect the core, same
        // as CoreToggleButton's isCoreActuallyConnected and the notification's separate VPN badge.
        val isWorking = status is CoreStatus.Connected || (isDirect && isXrayWorking)

        updateTileState(
            isRunning = status !is CoreStatus.Idle,
            isWorking = isWorking,
            isStopping = status is CoreStatus.Stopping,
            restartAttempt = restartAttempt,
            isWaiting = status is CoreStatus.WaitingForNetwork,
            isCaptcha = status is CoreStatus.CaptchaRequired,
            autoLaunchEnabled = autoLaunchEnabled,
            isDirect = isDirect,
            isXrayWorking = isXrayWorking,
            statusText = statusText
        )

        statusJob?.cancel()
        statusJob = serviceScope.launch {
            combine(
                CoreServiceState.status,
                CoreServiceState.restartAttempt,
                XrayServiceState.state,
                CoreServiceState.statusText,
                prefs.autoLaunchSettingsFlow
            ) { args: Array<Any?> ->
                val status = args[0] as CoreStatus
                val restartAttempt = args[1] as CoreServiceState.RestartAttempt?
                val xrayState = args[2] as XrayState
                val statusText = args[3] as? String
                val autoLaunch = args[4] as com.wireturn.app.data.AutoLaunchSettings
                autoLaunchEnabled = autoLaunch.enabled

                val isRunning = status !is CoreStatus.Idle
                val isDirect = status is CoreStatus.Suppressed
                val isWaiting = status is CoreStatus.WaitingForNetwork
                val isXrayWorking = xrayState == XrayState.Running || xrayState == XrayState.DirectRoute
                val isWorking = status is CoreStatus.Connected || (isDirect && isXrayWorking)
                val isCaptcha = status is CoreStatus.CaptchaRequired
                val isStopping = status is CoreStatus.Stopping

                updateTileState(
                    isRunning = isRunning,
                    isWorking = isWorking,
                    isStopping = isStopping,
                    restartAttempt = restartAttempt,
                    isWaiting = isWaiting,
                    isCaptcha = isCaptcha,
                    autoLaunchEnabled = autoLaunch.enabled,
                    isDirect = isDirect,
                    isXrayWorking = isXrayWorking,
                    statusText = statusText
                )
            }.collect {}
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        statusJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onClick() {
        super.onClick()
        val currentlyRunning = CoreServiceState.status.value !is CoreStatus.Idle

        // Оптимистичное обновление: сразу ставим целевое состояние (если выключаем - гасим плитку мгновенно)
        updateTileState(isRunning = !(currentlyRunning || autoLaunchEnabled), isWorking = false, autoLaunchEnabled = false)

        // The settings are read suspending, not with runBlocking on the main thread - on a scope
        // of its own: the system may unbind the tile (and onDestroy cancel serviceScope) right
        // after the tap, and the tap must still happen. Started and stopped right here, not
        // through CoreReceiver: that's the Intent API for other apps, which may be off.
        clickScope.launch {
            try {
                handleClick(currentlyRunning)
            } catch (e: Exception) {
                AppLogsState.addLog("Tile: ${e.javaClass.simpleName}: ${e.message}")
                if (CoreServiceState.status.value is CoreStatus.Starting) CoreServiceState.setStatus(CoreStatus.Idle)
                updateTileState(isRunning = false, isWorking = false, autoLaunchEnabled = false)
            }
        }
    }

    private suspend fun handleClick(currentlyRunning: Boolean) {
        val prefs = AppPreferences(applicationContext)
        val autoLaunch = prefs.autoLaunchSettingsFlow.first()

        if (currentlyRunning || autoLaunch.enabled) {
            // Auto-launch off included - see stopByUser.
            CoreService.stopByUser(applicationContext)
            return
        }

        val cfg = prefs.clientConfigFlow.first()
        cfg.getValidationErrorResId()?.let { errorRes ->
            CoreServiceState.setStatus(CoreStatus.Error(getString(errorRes)))
            // В случае ошибки конфига — откатываем плитку в выключенное состояние
            updateTileState(isRunning = false, isWorking = false, autoLaunchEnabled = false)
            return
        }

        // A TileService can't show the system VPN consent dialog itself - open the app for
        // that instead of letting establish() silently and permanently fail.
        val vpnSettings = prefs.vpnSettingsFlow.first()
        if (vpnConsentIntent(this, vpnSettings.enabled) != null) {
            updateTileState(isRunning = false, isWorking = false, autoLaunchEnabled = false)
            val consentIntent = Intent(this, MainActivity::class.java).apply {
                putExtra(EXTRA_REQUEST_VPN_CONSENT, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this, 0, consentIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(consentIntent)
            }
            return
        }

        // A refused foreground service start throws - the caller rolls the tile back.
        CoreService.start(applicationContext, cfg)
    }

    private fun updateTileState(
        isRunning: Boolean,
        isWorking: Boolean,
        isStopping: Boolean = false,
        restartAttempt: CoreServiceState.RestartAttempt? = null,
        isWaiting: Boolean = false,
        isCaptcha: Boolean = false,
        autoLaunchEnabled: Boolean = false,
        isDirect: Boolean = false,
        isXrayWorking: Boolean = false,
        statusText: String? = null
    ) {
        val tile = qsTile ?: return
        
        tile.state = if (isRunning || autoLaunchEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                statusText != null -> statusText
                restartAttempt != null -> restartAttempt.label(this)
                isCaptcha -> getString(R.string.tile_captcha)
                isWaiting -> getString(R.string.status_waiting_for_network)
                autoLaunchEnabled && !isRunning -> getString(R.string.settings_auto_launch_title)
                isStopping -> getString(R.string.stopping)
                isDirect -> {
                    if (isXrayWorking) getString(R.string.direct_route_active)
                    else getString(R.string.connecting)
                }
                isWorking -> getString(R.string.tile_active)
                isRunning -> getString(R.string.connecting)
                else -> ""
            }
        }

        tile.updateTile()
    }
}
