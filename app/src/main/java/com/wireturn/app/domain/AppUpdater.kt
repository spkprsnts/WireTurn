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
    suspend fun checkForUpdate(silent: Boolean = false, allowUnstable: Boolean = false) {
        _state.value = UpdateState.Checking
        try {
            var release = withContext(Dispatchers.IO) { 
                if (allowUnstable) fetchReleaseByTag("unstable-latest") else fetchLatestRelease() 
            }
            
            if (release == null) {
                // Try again with xray if conditions are met
                val proxy = getXrayIfRunning()
                if (proxy != null) {
                    release = withContext(Dispatchers.IO) { 
                        if (allowUnstable) fetchReleaseByTag("unstable-latest", proxy) else fetchLatestRelease(proxy) 
                    }
                }
            }

            if (release == null) {
                _state.value = if (silent) UpdateState.Idle
                else UpdateState.Error(context.getString(R.string.error_release_info_failed))
                return
            }

            val remoteTag = release.getString("tag_name")
            val remoteVersion = remoteTag.removePrefix("v")
            val remoteBody = release.optString("body", "")

            if (isNewer(remoteVersion, getCurrentVersion(), remoteBody)) {
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

    // Private

    private fun fetchLatestRelease(proxy: java.net.Proxy? = null): JSONObject? {
        return fetchJson(RELEASES_URL, proxy)
    }

    private fun fetchReleaseByTag(tag: String, proxy: java.net.Proxy? = null): JSONObject? {
        val url = "https://api.github.com/repos/spkprsnts/WireTurn/releases/tags/$tag"
        return fetchJson(url, proxy)
    }

    private fun fetchJson(url: String, proxy: java.net.Proxy? = null): JSONObject? {
        val connection = if (proxy != null) {
            URL(url).openConnection(proxy)
        } else {
            URL(url).openConnection()
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
            val socksAddr = runningXrayConfig.connectableAddress
            if (socksAddr.isNotBlank()) {
                val parts = socksAddr.split(":")
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

        fun isNewer(remote: String, current: String, remoteBody: String = ""): Boolean {
            if (remote == "unstable-latest") {
                // Если мы сами на unstable, проверяем хеш коммита в описании релиза
                if (current.contains("unstable", ignoreCase = true)) {
                    val currentHash = current.split("-").lastOrNull()
                    if (currentHash != null && currentHash.length >= 7 && remoteBody.contains(currentHash)) {
                        return false
                    }
                }
                // В остальных случаях (мы на stable или хеш другой) считаем, что unstable-latest — это обнова
                return true
            }

            val remoteIsUnstable = remote.contains("unstable", ignoreCase = true)
            val currentIsUnstable = current.contains("unstable", ignoreCase = true)

            // Если обе версии нестабильные, сравниваем хеши (если они есть)
            if (remoteIsUnstable && currentIsUnstable) {
                val rHash = remote.split("-").lastOrNull()
                val cHash = current.split("-").lastOrNull()
                if (rHash != null && cHash != null && rHash == cHash && rHash.length >= 7) return false
                // Если хеши разные, идем дальше сравнивать основные номера версий
            }

            // Вспомогательная функция для получения списка чисел из версии (напр. "1.0.2-unstable" -> [1, 0, 2])
            fun String.toVersionList(): List<Int> {
                val basePart = this.split("-").first()
                return basePart.split(".")
                    .map { it.filter { char -> char.isDigit() }.toIntOrNull() ?: 0 }
            }

            val r = remote.toVersionList()
            val c = current.toVersionList()

            // Сравниваем основные числа версии
            for (i in 0 until maxOf(r.size, c.size)) {
                val rv = r.getOrElse(i) { 0 }
                val cv = c.getOrElse(i) { 0 }
                if (rv != cv) return rv > cv
            }
            
            // Если номера версий идентичны (напр. 1.0 и 1.0-unstable)
            // Стабильная версия всегда считается новее нестабильной
            if (!remoteIsUnstable && currentIsUnstable) return true
            
            // Если на GitHub пришла версия с суффиксом unstable, а у нас стабильная того же номера - это не обнова
            // Во всех остальных случаях (версии равны) - false
            return false
        }
    }
}

