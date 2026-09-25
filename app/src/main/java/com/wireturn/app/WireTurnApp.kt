package com.wireturn.app

import android.app.Application
import com.wireturn.app.data.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WireTurnApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Process-wide rather than tied to a screen: CoreService/XrayService log with no UI
        // around (auto-launch, quick-settings tile, dual-route fallback), and AppLogsState drops
        // lines below this level as they're written.
        appScope.launch {
            AppPreferences(this@WireTurnApp).logsMinLevelFlow.collect { AppLogsState.setMinLevel(it) }
        }
    }
}
