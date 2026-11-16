package com.wireturn.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wireturn.app.data.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Intent API: other apps (Tasker and the like) start and stop the tunnel, or turn VPN mode on and
 * off, with `<package>.START_CORE` / `STOP_CORE` / `START_VPN` / `STOP_VPN`. Exported, so it only
 * acts while the user has allowed it in the settings (AppPreferences.intentApiEnabledFlow) -
 * otherwise any app on the device could stop the tunnel.
 */
class CoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pkg = context.packageName
        val action = intent.action?.removePrefix("$pkg.") ?: return
        if (action !in ACTIONS) return
        val appContext = context.applicationContext
        // Off the main thread: a cold DataStore read can take long enough to risk an ANR.
        val pending = goAsync()
        scope.launch {
            try {
                handle(appContext, action)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(context: Context, action: String) {
        val prefs = AppPreferences(context)
        if (!prefs.intentApiEnabledFlow.first()) {
            AppLogsState.addLog("Intent API: $action ignored - control from other apps is off in the settings")
            return
        }
        AppLogsState.addLog("Intent API: $action")
        // Android 12+ may refuse a foreground service start from the background, and a plain
        // startService to a service that isn't running - logged, not a crash of the app.
        try {
            when (action) {
                START_CORE -> CoreService.start(context, prefs.clientConfigFlow.first())
                STOP_CORE -> {
                    if (CoreServiceState.status.value is CoreStatus.Idle) return
                    CoreServiceState.setStatus(CoreStatus.Idle)
                    NotificationHelper.updateNotification(context)
                    CoreService.stop(context, byUser = true)
                }
                START_VPN -> prefs.setVpnEnabled(true)
                STOP_VPN -> prefs.setVpnEnabled(false)
            }
        } catch (e: Exception) {
            AppLogsState.addLog("Intent API: $action failed - ${e.javaClass.simpleName}: ${e.message}")
            // CoreService.start sets Starting before the start that just failed - nothing's coming.
            if (action == START_CORE && CoreServiceState.status.value is CoreStatus.Starting) {
                CoreServiceState.setStatus(CoreStatus.Idle)
            }
        }
    }

    private companion object {
        const val START_CORE = "START_CORE"
        const val STOP_CORE = "STOP_CORE"
        const val START_VPN = "START_VPN"
        const val STOP_VPN = "STOP_VPN"
        val ACTIONS = setOf(START_CORE, STOP_CORE, START_VPN, STOP_VPN)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
