package com.wireturn.app.domain

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import com.wireturn.app.R
import com.wireturn.app.viewmodel.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class AppUpdater(private val context: Context) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var latestApkUrl: String? = null

    private val apkFile: File
        get() = File(context.cacheDir, "update.apk")

    private fun getCurrentVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
    } catch (_: PackageManager.NameNotFoundException) {
        "0.0.0"
    }

    /**
     * @param silent true — при ошибке сети остаёмся в [UpdateState.Idle] (автопроверка при запуске).
     *               false — показываем [UpdateState.Error] (ручная проверка из UI).
     */
    suspend fun checkForUpdate(silent: Boolean = false) {
        _state.value = UpdateState.Checking
        try {
            var release = withContext(Dispatchers.IO) { fetchLatestRelease() }
            
            if (release == null) {
                // Try again with xray if conditions are met
                val proxy = getXrayIfRunning()
                if (proxy != null) {
                    release = withContext(Dispatchers.IO) { fetchLatestRelease(proxy) }
                }
            }

            if (release == null) {
                _state.value = if (silent) UpdateState.Idle
                else UpdateState.Error(context.getString(R.string.error_release_info_failed))
                return
            }

            val remoteVersion = release.getString("tag_name").removePrefix("v")

            if (isNewer(remoteVersion, getCurrentVersion())) {
                latestApkUrl = findApkUrl(release)
                if (latestApkUrl != null) {
                    val changelog = release.optString("body", "").trim()
                    _state.value = UpdateState.Available(remoteVersion, changelog)
                } else {
                    _state.value = if (silent) UpdateState.Idle
                    else UpdateState.Error(context.getString(R.string.error_apk_not_found))
                }
            } else {
                _state.value = UpdateState.NoUpdate
            }
        } catch (_: Exception) {
            _state.value = if (silent) UpdateState.Idle
            else UpdateState.Error(context.getString(R.string.error_no_connection))
        }
    }

    suspend fun downloadUpdate() {
        val url = latestApkUrl ?: run {
            _state.value = UpdateState.Error(context.getString(R.string.error_update_url_not_found))
            return
        }

        _state.value = UpdateState.Downloading(0)
        try {
            withContext(Dispatchers.IO) {
                val proxy = getXrayIfRunning()
                val connection = if (proxy != null) {
                    URL(url).openConnection(proxy)
                } else {
                    URL(url).openConnection()
                } as HttpURLConnection
                connection.instanceFollowRedirects = true
                connection.connect()

                val totalSize = connection.contentLength.toLong()
                var downloaded = 0L

                connection.inputStream.use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var lastProgress = -1
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            if (totalSize > 0) {
                                val progress = (downloaded * 100 / totalSize).toInt()
                                if (progress != lastProgress) {
                                    _state.value = UpdateState.Downloading(progress)
                                    lastProgress = progress
                                }
                            }
                        }
                    }
                }
            }
            _state.value = UpdateState.ReadyToInstall
        } catch (e: Exception) {
            apkFile.delete()
            _state.value = UpdateState.Error(context.getString(R.string.error_download_failed, e.message))
        }
    }

    fun installUpdate() {
        if (!apkFile.exists()) {
            _state.value = UpdateState.Error(context.getString(R.string.error_update_file_not_found))
            return
        }

        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }

    fun resetState() {
        _state.value = UpdateState.Idle
    }

    // Private

    private fun fetchLatestRelease(proxy: java.net.Proxy? = null): JSONObject? {
        val connection = if (proxy != null) {
            URL(RELEASES_URL).openConnection(proxy)
        } else {
            URL(RELEASES_URL).openConnection()
        } as HttpURLConnection
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "wireturn-App")
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000

        return try {
            if (connection.responseCode == 200) {
                JSONObject(connection.inputStream.bufferedReader().readText())
            } else null
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun getXrayIfRunning(): java.net.Proxy? {
        val runningXrayConfig = com.wireturn.app.XrayServiceState.runningXrayConfig.value ?: return null
        if (com.wireturn.app.XrayServiceState.state.value != com.wireturn.app.viewmodel.XrayState.Running) return null

        return try {
            val mixedAddr = runningXrayConfig.mixedBindAddress
            if (mixedAddr.isNotBlank()) {
                val parts = mixedAddr.split(":")
                if (parts.size == 2) {
                    val host = parts[0]
                    val port = parts[1].toInt()
                    java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress(host, port))
                } else null
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun findApkUrl(release: JSONObject): String? {
        val assets = release.getJSONArray("assets")
        val apkAssets = mutableListOf<JSONObject>()
        
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.getString("name").endsWith(".apk", ignoreCase = true)) {
                apkAssets.add(asset)
            }
        }

        if (apkAssets.isEmpty()) return null

        // 1. Поиск под конкретную архитектуру устройства (в порядке приоритета ОС)
        for (abi in Build.SUPPORTED_ABIS) {
            val match = apkAssets.find { 
                it.getString("name").lowercase().contains(abi.lowercase()) 
            }
            if (match != null) return match.getString("browser_download_url")
        }

        // 2. Поиск универсальной сборки
        val universal = apkAssets.find { 
            it.getString("name").lowercase().contains("universal") 
        }
        if (universal != null) return universal.getString("browser_download_url")

        // 3. Фолбэк на любой найденный APK
        return apkAssets.firstOrNull()?.getString("browser_download_url")
    }

    companion object {
        private const val RELEASES_URL =
            "https://api.github.com/repos/spkprsnts/WireTurn/releases/latest"

        fun isNewer(remote: String, current: String): Boolean {
            val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
            val c = current.split(".").map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(r.size, c.size)) {
                val rv = r.getOrElse(i) { 0 }
                val cv = c.getOrElse(i) { 0 }
                if (rv != cv) return rv > cv
            }
            return false
        }
    }
}

