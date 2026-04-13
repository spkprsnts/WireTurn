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
            "com.wireturn.app.wireproxy.START_PROXY" -> {
                val prefs = AppPreferences(context)
                val cfg = runBlocking { prefs.clientConfigFlow.first() }
                ProxyService.start(context, cfg)
            }
            "com.wireturn.app.wireproxy.STOP_PROXY" -> {
                ProxyService.stop(context)
            }
        }
    }
}

