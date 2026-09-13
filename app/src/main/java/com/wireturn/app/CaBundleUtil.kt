package com.wireturn.app

import android.content.Context
import java.io.File

/**
 * Go binaries on Android only trust /system/etc/security/cacerts, which on old/unpatched
 * devices (no OTA updates, no GMS) may be missing CAs that sites rotated in since. Bundling
 * our own up-to-date root store and pointing SSL_CERT_FILE at it sidesteps that entirely.
 */
object CaBundleUtil {
    fun ensure(context: Context): String? {
        val target = File(context.filesDir, "cacert.pem")
        return try {
            val assetBytes = context.assets.open("cacert.pem").use { it.readBytes() }
            if (!target.exists() || target.length() != assetBytes.size.toLong()) {
                target.writeBytes(assetBytes)
            }
            target.absolutePath
        } catch (e: Exception) {
            AppLogsState.addLog("CA bundle extract failed: ${e.message}")
            null
        }
    }
}
