package com.wireturn.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.wireturn.app.viewmodel.WireproxyState
import com.wireturn.app.viewmodel.VpnState

object NotificationHelper {
    const val NOTIFICATION_ID = 1
    const val CHANNEL_ID = "ProxyChannel"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_title),
            NotificationManager.IMPORTANCE_LOW
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun buildNotification(context: Context): Notification {
        val openAppIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

        val statusParts = mutableListOf<String>()
        
        val proxyRunning = ProxyServiceState.isRunning.value
        val proxyWorking = ProxyServiceState.isWorking.value
        val proxyStatusText = ProxyServiceState.statusText.value
        val wireproxyState = WireproxyServiceState.state.value
        val vpnState = VpnServiceState.state.value

        if (proxyRunning) {
            val pStatus = proxyStatusText ?: (if (proxyWorking) context.getString(R.string.proxy_active) else context.getString(R.string.proxy_starting))
            statusParts.add(pStatus)
        }
        
        if (wireproxyState != WireproxyState.Idle && wireproxyState is WireproxyState.Running) statusParts.add("WG")
        if (vpnState != VpnState.Idle && vpnState is VpnState.Running) statusParts.add("VPN")

        val contentText = if (statusParts.isEmpty()) {
            context.getString(R.string.proxy_press_to_start)
        } else {
            statusParts.joinToString(" | ")
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .setOngoing(true)
            .setContentIntent(openAppIntent)

        if (proxyRunning || wireproxyState != WireproxyState.Idle) {
            val stopProxyIntent = Intent(context, ProxyReceiver::class.java).apply {
                action = "com.wireturn.app.wireproxy.STOP_PROXY"
            }
            val stopProxyPendingIntent = PendingIntent.getBroadcast(
                context, 
                100, 
                stopProxyIntent,
                PendingIntent.FLAG_IMMUTABLE
            )
            
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.proxy_stop),
                stopProxyPendingIntent
            )
        }

        if (vpnState != VpnState.Idle) {
            val stopVpnIntent = Intent(context, Tun2SocksVpnService::class.java).apply {
                action = Tun2SocksVpnService.ACTION_STOP_BY_USER
            }
            val stopVpnPendingIntent = PendingIntent.getService(
                context, 
                101, 
                stopVpnIntent,
                PendingIntent.FLAG_IMMUTABLE
            )
            
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.vpn_stop),
                stopVpnPendingIntent
            )
        } else if (wireproxyState == WireproxyState.Running) {
            val startVpnIntent = Intent(context, ProxyReceiver::class.java).apply {
                action = "com.wireturn.app.wireproxy.START_VPN"
            }
            val startVpnPendingIntent = PendingIntent.getBroadcast(
                context,
                102,
                startVpnIntent,
                PendingIntent.FLAG_IMMUTABLE
            )

            builder.addAction(
                android.R.drawable.ic_menu_add,
                context.getString(R.string.vpn_start),
                startVpnPendingIntent
            )
        }

        return builder.build()
    }

    fun updateNotification(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (ProxyServiceState.isRunning.value || 
            WireproxyServiceState.state.value != WireproxyState.Idle || 
            VpnServiceState.state.value != VpnState.Idle) {
            nm.notify(NOTIFICATION_ID, buildNotification(context))
        } else {
            nm.cancel(NOTIFICATION_ID)
        }
    }
}
