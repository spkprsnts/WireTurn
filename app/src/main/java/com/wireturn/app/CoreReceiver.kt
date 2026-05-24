package com.wireturn.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wireturn.app.data.AppPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class CoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pkg = context.packageName
        when (intent.action) {
            "$pkg.START_CORE" -> {
                val prefs = AppPreferences(context)
                val cfg = runBlocking { prefs.clientConfigFlow.first() }
                CoreService.start(context, cfg)
            }
            "$pkg.STOP_CORE" -> {
                CoreServiceState.setStatus(CoreStatus.Idle)
                NotificationHelper.updateNotification(context)
                CoreService.stop(context, byUser = true)
            }
            "$pkg.START_VPN" -> {
                val prefs = AppPreferences(context)
                runBlocking {
                    prefs.setVpnEnabled(true)
                }
            }
            "$pkg.STOP_VPN" -> {
                val prefs = AppPreferences(context)
                runBlocking {
                    prefs.setVpnEnabled(false)
                }
            }
        }
    }
}

