package com.wireturn.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.wireturn.app.data.AppPreferences
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
        updateTileState(
            isRunning = ProxyServiceState.isRunning.value,
            isWorking = ProxyServiceState.isWorking.value,
            isCaptcha = ProxyServiceState.captchaSession.value != null,
            autoLaunchEnabled = initialAutoLaunch.enabled,
            isDirect = ProxyServiceState.isTunnelSuppressed.value
        )

        statusJob?.cancel()
        statusJob = serviceScope.launch {
            combine(
                ProxyServiceState.isRunning,
                ProxyServiceState.isWorking,
                ProxyServiceState.captchaSession,
                ProxyServiceState.isTunnelSuppressed,
                prefs.autoLaunchSettingsFlow
            ) { running, working, captcha, suppressed, autoLaunch ->
                val isCaptcha = captcha != null
                updateTileState(running, working, isCaptcha, autoLaunch.enabled, suppressed)
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
        val currentlyRunning = ProxyServiceState.isRunning.value
        val prefs = AppPreferences(this)
        val autoLaunch = runBlocking { prefs.autoLaunchSettingsFlow.first() }

        if (autoLaunch.enabled) {
            // Если включен автозапуск — выключаем его и останавливаем прокси
            runBlocking { prefs.updateAutoLaunchSettings(autoLaunch.copy(enabled = false)) }
            ProxyService.stop(this)
            return
        }

        if (!currentlyRunning) {
            val cfg = runBlocking { prefs.clientConfigFlow.first() }

            cfg.getValidationErrorResId()?.let { errorRes ->
                ProxyServiceState.setStartupResult(StartupResult.Failed(getString(errorRes)))
                return
            }
            
            // Оптимистичное обновление: сразу показываем состояние "запуска"
            updateTileState(isRunning = true, isWorking = false, autoLaunchEnabled = false)
            ProxyServiceState.setRunning(true)
        } else {
            // Оптимистичное обновление: сразу выключаем плитку
            updateTileState(isRunning = false, isWorking = false, autoLaunchEnabled = false)
            ProxyServiceState.setRunning(false)
            ProxyServiceState.setWorking(false)
        }

        val action = if (currentlyRunning) {
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
        isCaptcha: Boolean = false,
        autoLaunchEnabled: Boolean = false,
        isDirect: Boolean = false
    ) {
        val tile = qsTile ?: return
        
        tile.state = if (isRunning || autoLaunchEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                isCaptcha -> getString(R.string.tile_captcha)
                autoLaunchEnabled && !isRunning -> getString(R.string.settings_auto_launch_title)
                isWorking && isDirect -> getString(R.string.tile_direct)
                isWorking && isRunning -> getString(R.string.tile_active)
                isRunning -> getString(R.string.connecting)
                else -> ""
            }
        }

        tile.updateTile()
    }
}
