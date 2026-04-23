package com.wireturn.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wireturn.app.data.AppPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class ProxyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.wireturn.app.START_PROXY" -> {
                val prefs = AppPreferences(context)
                val cfg = runBlocking { prefs.clientConfigFlow.first() }
                ProxyService.start(context, cfg)
            }
            "com.wireturn.app.STOP_PROXY" -> {
                ProxyService.stop(context)
            }
            "com.wireturn.app.START_VPN" -> {
                val prefs = AppPreferences(context)
                runBlocking {
                    val config = prefs.xrayConfigFlow.first()
                    prefs.saveXrayConfig(config.copy(xrayVpnMode = true))
                }
            }
        }
    }
}

