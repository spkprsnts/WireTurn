package com.wireturn.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wireturn.app.data.AppPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class ProxyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pkg = context.packageName
        when (intent.action) {
            "$pkg.START_PROXY" -> {
                val prefs = AppPreferences(context)
                val cfg = runBlocking { prefs.clientConfigFlow.first() }
                ProxyService.start(context, cfg)
            }
            "$pkg.STOP_PROXY" -> {
                ProxyService.stop(context, byUser = true)
            }
            "$pkg.START_VPN" -> {
                val prefs = AppPreferences(context)
                runBlocking {
                    val settings = prefs.xraySettingsFlow.first()
                    prefs.saveXraySettings(settings.copy(xrayVpnMode = true))
                }
            }
        }
    }
}

