package com.wireturn.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.wireturn.app.viewmodel.XrayState
import com.wireturn.app.viewmodel.VpnState

object NotificationHelper {
    const val NOTIFICATION_ID = 1
    const val CAPTCHA_NOTIFICATION_ID = 2
    const val ERROR_NOTIFICATION_ID = 3
    const val CHANNEL_ID = "ProxyChannel"
    const val CAPTCHA_CHANNEL_ID = "CaptchaChannel"
    const val ERROR_CHANNEL_ID = "ErrorChannel"

    fun createChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_title),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)

        val captchaChannel = NotificationChannel(
            CAPTCHA_CHANNEL_ID,
            context.getString(R.string.captcha_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.captcha_channel_description)
            enableLights(true)
            enableVibration(true)
        }
        nm.createNotificationChannel(captchaChannel)

        val errorChannel = NotificationChannel(
            ERROR_CHANNEL_ID,
            context.getString(R.string.error_notification_title),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableLights(true)
            enableVibration(true)
        }
        nm.createNotificationChannel(errorChannel)
    }

    fun buildNotification(context: Context): Notification {
        val openAppIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

        val statusParts = mutableListOf<String>()
        
        val proxyStatus = ProxyServiceState.status.value
        val proxyStatusText = ProxyServiceState.statusText.value
        val profileNameSnapshot = ProxyServiceState.profileNameSnapshot.value
        val clientConfig = ProxyServiceState.clientConfigSnapshot.value
        val xrayState = XrayServiceState.state.value
        val vpnState = VpnServiceState.state.value

        if (proxyStatus !is ProxyStatus.Idle) {
            val pStatus = proxyStatusText ?: if (proxyStatus is ProxyStatus.Suppressed) {
                if (xrayState == XrayState.Running || xrayState == XrayState.DirectRoute) context.getString(R.string.vless_direct_active)
                else context.getString(R.string.connecting)
            } else {
                when (proxyStatus) {
                    is ProxyStatus.Connected -> context.getString(R.string.proxy_active)
                    is ProxyStatus.Starting -> context.getString(R.string.starting)
                    is ProxyStatus.Connecting -> context.getString(R.string.connecting)
                    is ProxyStatus.WaitingForNetwork -> context.getString(R.string.status_waiting_for_network)
                    is ProxyStatus.CaptchaRequired -> context.getString(R.string.proxy_captcha_required)
                    is ProxyStatus.Error -> proxyStatus.message
                }
            }
            if (pStatus.isNotEmpty()) statusParts.add(pStatus)
        }
        
        if (xrayState != XrayState.Idle && (xrayState == XrayState.Running || xrayState == XrayState.DirectRoute || xrayState == XrayState.Connecting || xrayState == XrayState.Starting)) {
            statusParts.add(context.getString(R.string.vless))
        }
        if (vpnState != VpnState.Idle && vpnState is VpnState.Running) statusParts.add(context.getString(R.string.vpn_short))

        val contentText = if (statusParts.isEmpty()) {
            context.getString(R.string.stopping)
        } else {
            statusParts.joinToString(" • ")
        }

        val limitedProfileName = profileNameSnapshot?.take(15)
        val kernelPart = clientConfig?.getKernelDescription(context)
        val subText = listOfNotNull(limitedProfileName, kernelPart).joinToString(" • ")

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(contentText)
            .setSubText(subText)
            .setSmallIcon(R.drawable.plug_connect_24px)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSound(null)
            .setContentIntent(openAppIntent)

        if (proxyStatus !is ProxyStatus.Idle || xrayState != XrayState.Idle) {
            val stopProxyIntent = Intent(context, ProxyReceiver::class.java).apply {
                action = "${context.packageName}.STOP_PROXY"
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
            
            val changeProfileIntent = Intent(context, ProfileDialogActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val changeProfilePendingIntent = PendingIntent.getActivity(
                context, 
                103, 
                changeProfileIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_menu_manage,
                context.getString(R.string.notification_btn_change_profile),
                changeProfilePendingIntent
            )
        }

        if (vpnState != VpnState.Idle) {
            val stopVpnIntent = Intent(context, ProxyReceiver::class.java).apply {
                action = "${context.packageName}.STOP_VPN"
            }
            val stopVpnPendingIntent = PendingIntent.getBroadcast(
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
        } else if (xrayState != XrayState.Idle) {
            val startVpnIntent = Intent(context, ProxyReceiver::class.java).apply {
                action = "${context.packageName}.START_VPN"
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

    fun notifyCaptcha(context: Context, @Suppress("UNUSED_PARAMETER") url: String, @Suppress("UNUSED_PARAMETER") sessionId: String) {
        val openAppIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 1, it, PendingIntent.FLAG_IMMUTABLE)
        }

        val builder = NotificationCompat.Builder(context, CAPTCHA_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.captcha_notification_title))
            .setContentText(context.getString(R.string.captcha_notification_text))
            .setSmallIcon(R.drawable.plug_connect_24px)
            .setContentIntent(openAppIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)

        context.getSystemService(NotificationManager::class.java)
            .notify(CAPTCHA_NOTIFICATION_ID, builder.build())
    }

    fun cancelCaptchaNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(CAPTCHA_NOTIFICATION_ID)
    }

    fun notifyError(context: Context, message: String) {
        val openAppIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 2, it, PendingIntent.FLAG_IMMUTABLE)
        }

        val builder = NotificationCompat.Builder(context, ERROR_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.error_notification_title))
            .setContentText(message)
            .setSmallIcon(R.drawable.error_24px)
            .setContentIntent(openAppIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        context.getSystemService(NotificationManager::class.java)
            .notify(ERROR_NOTIFICATION_ID, builder.build())
    }

    fun cancelErrorNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(ERROR_NOTIFICATION_ID)
    }

    fun updateNotification(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val status = ProxyServiceState.status.value
        if (status !is ProxyStatus.Idle ||
            XrayServiceState.state.value != XrayState.Idle ||
            VpnServiceState.state.value != VpnState.Idle) {
            nm.notify(NOTIFICATION_ID, buildNotification(context))
        } else {
            nm.cancel(NOTIFICATION_ID)
        }
    }
}
