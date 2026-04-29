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
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
        
        // Мгновенное обновление из текущего состояния при открытии шторки
        updateTileState(
            ProxyServiceState.isRunning.value,
            ProxyServiceState.isWorking.value,
            ProxyServiceState.captchaSession.value != null
        )

        statusJob?.cancel()
        statusJob = combine(
            ProxyServiceState.isRunning,
            ProxyServiceState.isWorking,
            ProxyServiceState.captchaSession
        ) { running, working, captcha ->
            Triple(running, working, captcha != null)
        }.onEach { (isRunning, isWorking, isCaptcha) ->
            updateTileState(isRunning, isWorking, isCaptcha)
        }.launchIn(serviceScope)
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

        if (!currentlyRunning) {
            val prefs = AppPreferences(this)
            val cfg = runBlocking { prefs.clientConfigFlow.first() }

            cfg.getValidationErrorResId()?.let { errorRes ->
                ProxyServiceState.setStartupResult(StartupResult.Failed(getString(errorRes)))
                return
            }
            
            // Оптимистичное обновление: сразу показываем состояние "запуска"
            updateTileState(isRunning = true, isWorking = false)
            ProxyServiceState.setRunning(true)
        } else {
            // Оптимистичное обновление: сразу выключаем плитку
            updateTileState(isRunning = false, isWorking = false)
            ProxyServiceState.setRunning(false)
            ProxyServiceState.setWorking(false)
        }

        val action = if (currentlyRunning) {
            "${packageName}.STOP_PROXY"
        } else {
            "${packageName}.START_PROXY"
        }
        
        val intent = Intent(this, ProxyReceiver::class.java).apply {
            this.action = action
        }
        sendBroadcast(intent)
    }

    private fun updateTileState(isRunning: Boolean, isWorking: Boolean, isCaptcha: Boolean = false) {
        val tile = qsTile ?: return
        
        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                isCaptcha -> getString(R.string.tile_captcha)
                isRunning && isWorking -> getString(R.string.tile_active)
                isRunning -> getString(R.string.starting)
                else -> ""
            }
        }

        tile.updateTile()
    }
}
