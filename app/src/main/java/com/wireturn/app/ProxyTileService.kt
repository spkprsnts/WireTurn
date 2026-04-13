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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking

class ProxyTileService : TileService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var statusJob: Job? = null

    companion object {
        fun requestUpdate(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, ProxyTileService::class.java))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        statusJob?.cancel()
        statusJob = combine(ProxyServiceState.isRunning, ProxyServiceState.isWorking, ProxyServiceState.captchaSession) { running, working, captchaSession ->
            Triple(running, working, captchaSession != null)
        }
            .onEach { (isRunning, isWorking, isCaptcha) ->
                updateTileState(isRunning, isWorking, isCaptcha)
            }
            .launchIn(serviceScope)
    }

    override fun onStopListening() {
        super.onStopListening()
        statusJob?.cancel()
    }

    override fun onClick() {
        super.onClick()
        val isRunning = ProxyServiceState.isRunning.value

        if (!isRunning) {
            val prefs = AppPreferences(this)
            val cfg = runBlocking { prefs.clientConfigFlow.first() }

            cfg.getValidationErrorResId()?.let { errorRes ->
                ProxyServiceState.setStartupResult(StartupResult.Failed(getString(errorRes)))
                return
            }
        }

        val action = if (isRunning) {
            "com.wireturn.app.wireproxy.STOP_PROXY"
        } else {
            "com.wireturn.app.wireproxy.START_PROXY"
        }
        
        val intent = Intent(this, ProxyReceiver::class.java).apply {
            this.action = action
        }
        sendBroadcast(intent)

        if (!isRunning) {
            ProxyServiceState.setRunning(true)
            updateTileState(isRunning = true, isWorking = false)
        }
    }

    private fun updateTileState(isRunning: Boolean, isWorking: Boolean, isCaptcha: Boolean = false) {
        val tile = qsTile ?: return
        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                isCaptcha -> getString(R.string.tile_captcha)
                isRunning && isWorking -> getString(R.string.tile_active)
                isRunning -> getString(R.string.proxy_starting)
                else -> ""
            }
        }
        tile.updateTile()
    }
}

