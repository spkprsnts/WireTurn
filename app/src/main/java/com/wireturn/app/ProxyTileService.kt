package com.wireturn.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.viewmodel.XrayState
import com.wireturn.app.viewmodel.VpnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class ProxyTileService : TileService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var statusJob: Job? = null

    companion object {
        /**
         * Запрашивает обновление состояния плитки у системы.
         */
        fun requestUpdate(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, ProxyTileService::class.java))
            } catch (_: Exception) {
                // Игнорируем ошибки на старых API или если плитка не добавлена
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        
        val prefs = AppPreferences(this)

        // Мгновенное обновление при открытии шторки
        val initialAutoLaunch = runBlocking { prefs.autoLaunchSettingsFlow.first() }
        val status = ProxyServiceState.status.value
        val statusText = ProxyServiceState.statusText.value
        val xrayState = XrayServiceState.state.value
        val vpnState = VpnServiceState.state.value
        
        val isDirect = status is ProxyStatus.Suppressed
        val isXrayWorking = xrayState == XrayState.Running || xrayState == XrayState.DirectRoute
        val isVpnRunning = vpnState is VpnState.Running
        val isWorking = status is ProxyStatus.Connected || (isDirect && isXrayWorking) || isVpnRunning

        updateTileState(
            isRunning = status !is ProxyStatus.Idle,
            isWorking = isWorking,
            isWaiting = status is ProxyStatus.WaitingForNetwork,
            isCaptcha = status is ProxyStatus.CaptchaRequired,
            autoLaunchEnabled = initialAutoLaunch.enabled,
            isDirect = isDirect,
            isXrayWorking = isXrayWorking,
            statusText = statusText
        )

        statusJob?.cancel()
        statusJob = serviceScope.launch {
            combine(
                ProxyServiceState.status,
                XrayServiceState.state,
                VpnServiceState.state,
                ProxyServiceState.statusText,
                prefs.autoLaunchSettingsFlow
            ) { status, xrayState, vpnState, statusText, autoLaunch ->
                val isRunning = status !is ProxyStatus.Idle
                val isDirect = status is ProxyStatus.Suppressed
                val isWaiting = status is ProxyStatus.WaitingForNetwork
                val isXrayWorking = xrayState == XrayState.Running || xrayState == XrayState.DirectRoute
                val isVpnRunning = vpnState is VpnState.Running
                val isWorking = status is ProxyStatus.Connected || (isDirect && isXrayWorking) || isVpnRunning
                val isCaptcha = status is ProxyStatus.CaptchaRequired

                updateTileState(
                    isRunning = isRunning,
                    isWorking = isWorking,
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
        val currentlyRunning = ProxyServiceState.status.value !is ProxyStatus.Idle
        val prefs = AppPreferences(this)
        val autoLaunch = runBlocking { prefs.autoLaunchSettingsFlow.first() }

        // Если работает прокси ИЛИ включен автозапуск — мы нажимаем, чтобы ВЫКЛЮЧИТЬ
        val turningOff = currentlyRunning || autoLaunch.enabled
        
        // Оптимистичное обновление: сразу ставим целевое состояние (если выключаем - гасим плитку мгновенно)
        updateTileState(isRunning = !turningOff, isWorking = false, autoLaunchEnabled = false)

        if (autoLaunch.enabled) {
            runBlocking { prefs.updateAutoLaunchSettings(autoLaunch.copy(enabled = false)) }
        }

        if (turningOff) {
            ProxyServiceState.setStatus(ProxyStatus.Idle)
        } else {
            val cfg = runBlocking { prefs.clientConfigFlow.first() }
            cfg.getValidationErrorResId()?.let { errorRes ->
                ProxyServiceState.setStatus(ProxyStatus.Error(getString(errorRes)))
                // В случае ошибки конфига — откатываем плитку в выключенное состояние
                updateTileState(isRunning = false, isWorking = false, autoLaunchEnabled = false)
                return
            }
            ProxyServiceState.setStatus(ProxyStatus.Starting)
        }

        val action = if (turningOff) {
            "$packageName.STOP_PROXY"
        } else {
            "$packageName.START_PROXY"
        }
        
        val intent = Intent(this, ProxyReceiver::class.java).apply {
            this.action = action
        }
        sendBroadcast(intent)
    }

    private fun updateTileState(
        isRunning: Boolean,
        isWorking: Boolean,
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
                isCaptcha -> getString(R.string.tile_captcha)
                isWaiting -> getString(R.string.status_waiting_for_network)
                autoLaunchEnabled && !isRunning -> getString(R.string.settings_auto_launch_title)
                statusText != null -> statusText
                isDirect -> {
                    if (isXrayWorking) getString(R.string.vless_direct_active)
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
