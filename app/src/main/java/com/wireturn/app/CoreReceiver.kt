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
 * Start/stop the tunnel and turn VPN mode on and off by broadcast: `<package>.START_CORE` /
 * `STOP_CORE` / `START_VPN` / `STOP_VPN`. Two receivers share it: [CoreReceiver] for other apps,
 * [NotificationActionReceiver] for the app's own notification buttons.
 */
abstract class CoreActionReceiver : BroadcastReceiver() {

    /** Whether this receiver may act right now; logs why not. */
    protected open suspend fun allowed(context: Context, action: String): Boolean = true

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action?.removePrefix("${context.packageName}.") ?: return
        if (action !in ACTIONS) return
        val appContext = context.applicationContext
        // Off the main thread: a cold DataStore read can take long enough to risk an ANR.
        val pending = goAsync()
        scope.launch {
            // Everything inside, the settings read in allowed() too: an exception escaping this
            // scope would take the whole process down, the running tunnel with it.
            try {
                if (allowed(appContext, action)) handle(appContext, action)
            } catch (e: Exception) {
                AppLogsState.addLog("$action failed - ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(context: Context, action: String) {
        val prefs = AppPreferences(context)
        // Android 12+ may refuse a foreground service start from the background, and a plain
        // startService to a service that isn't running - logged, not a crash of the app.
        try {
            when (action) {
                START_CORE -> CoreService.start(context, prefs.clientConfigFlow.first())
                STOP_CORE -> CoreService.stopByUser(context)
                START_VPN -> prefs.setVpnEnabled(true)
                STOP_VPN -> prefs.setVpnEnabled(false)
            }
        } catch (e: Exception) {
            AppLogsState.addLog("$action failed - ${e.javaClass.simpleName}: ${e.message}")
            // CoreService.start sets Starting before the start that just failed - nothing's coming.
            if (action == START_CORE && CoreServiceState.status.value is CoreStatus.Starting) {
                CoreServiceState.setStatus(CoreStatus.Idle)
            }
        }
    }

    companion object {
        const val START_CORE = "START_CORE"
        const val STOP_CORE = "STOP_CORE"
        const val START_VPN = "START_VPN"
        const val STOP_VPN = "STOP_VPN"
        private val ACTIONS = setOf(START_CORE, STOP_CORE, START_VPN, STOP_VPN)

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** An explicit broadcast of [action] to [receiver], for a notification button's PendingIntent. */
        fun intent(context: Context, receiver: Class<out CoreActionReceiver>, action: String): Intent =
            Intent(context, receiver).setAction("${context.packageName}.$action")
    }
}

/**
 * Intent API for other apps (Tasker and the like). Exported, so it only acts while the user has
 * allowed it in the settings (AppPreferences.intentApiEnabledFlow) - otherwise any app on the
 * device could stop the tunnel.
 */
class CoreReceiver : CoreActionReceiver() {
    override suspend fun allowed(context: Context, action: String): Boolean {
        if (AppPreferences(context).intentApiEnabledFlow.first()) {
            AppLogsState.addLog("Intent API: $action")
            return true
        }
        AppLogsState.addLog("Intent API: $action ignored - control from other apps is off in the settings")
        return false
    }
}

/** The app's own notification buttons - not exported, so whatever the Intent API setting says. */
class NotificationActionReceiver : CoreActionReceiver()
