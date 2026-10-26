package com.wireturn.app.domain

import android.content.Context
import com.google.gson.annotations.SerializedName
import com.wireturn.app.AppLogsState
import com.wireturn.app.viewmodel.GeoAssetsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/**
 * A geoip.dat/geosite.dat source: one of the built-in ones, mirroring what v2ray-family clients
 * (v2rayNG etc.) ship, or one the user added ([isCustom], with a [name] of their own) - kept in
 * AppPreferences and listed after the built-in ones.
 */
data class GeoResourceSet(
    @SerializedName("id") val id: String,
    @SerializedName("geoip_url") val geoipUrl: String,
    @SerializedName("geosite_url") val geositeUrl: String,
    @SerializedName("name") val name: String = ""
) {
    val isCustom: Boolean get() = id.startsWith(CUSTOM_PREFIX)

    /** The name to show for a custom source: its own, or the host it downloads geosite.dat from. */
    val displayName: String
        get() = name.ifBlank { runCatching { URL(geositeUrl).host }.getOrNull().orEmpty().ifBlank { geositeUrl } }

    companion object {
        const val CUSTOM_PREFIX = "custom:"

        // Default: RU-focused ruleset (blocked-by-Roskomnadzor domains/IPs), most relevant for
        // this app's typical audience - see runetfreedom/russia-v2ray-rules-dat.
        val RUNETFREEDOM = GeoResourceSet(
            id = "runetfreedom",
            geoipUrl = "https://github.com/runetfreedom/russia-v2ray-rules-dat/releases/latest/download/geoip.dat",
            geositeUrl = "https://github.com/runetfreedom/russia-v2ray-rules-dat/releases/latest/download/geosite.dat"
        )
        val V2FLY = GeoResourceSet(
            id = "v2fly",
            geoipUrl = "https://github.com/v2fly/geoip/releases/latest/download/geoip.dat",
            geositeUrl = "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat"
        )
        val LOYALSOLDIER = GeoResourceSet(
            id = "loyalsoldier",
            geoipUrl = "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geoip.dat",
            geositeUrl = "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat"
        )

        // RoscomVPN (hydraponique/roscomvpn-routing): small RU/BY-targeted files made for its own
        // routing profile - geoip has only direct/private/whitelist (no geoip:ru), geosite its own
        // service categories. See RoutingPreset.ROSCOMVPN in XraySettingsScreen.
        val ROSCOMVPN = GeoResourceSet(
            id = "roscomvpn",
            geoipUrl = "https://github.com/hydraponique/roscomvpn-geoip/releases/latest/download/geoip.dat",
            geositeUrl = "https://github.com/hydraponique/roscomvpn-geosite/releases/latest/download/geosite.dat"
        )

        val BUILT_IN = listOf(RUNETFREEDOM, ROSCOMVPN, V2FLY, LOYALSOLDIER)

        /** A new custom source with a fresh id. */
        fun custom(name: String, geoipUrl: String, geositeUrl: String) = GeoResourceSet(
            id = CUSTOM_PREFIX + java.util.UUID.randomUUID().toString(),
            geoipUrl = geoipUrl.trim(),
            geositeUrl = geositeUrl.trim(),
            name = name.trim()
        )

        /** The source with [id], or null - a custom one that was removed since it was installed. */
        fun find(id: String, custom: List<GeoResourceSet>): GeoResourceSet? =
            (BUILT_IN + custom).find { it.id == id } ?: RUNETFREEDOM.takeIf { !id.startsWith(CUSTOM_PREFIX) }
    }
}

/**
 * Downloads geoip.dat/geosite.dat for [-route-direct]/[-route-block] geosite:/geoip: entries
 * (see external/vless-client's -assets-path flag). Installed once, shared by every profile -
 * see XraySettings.geoVariant and the -assets-path wiring in XrayService.
 */
class GeoAssetsManager(private val context: Context) {

    val state: StateFlow<GeoAssetsState> = _state.asStateFlow()
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()

    // Bumped whenever the installed files change (download success, delete) so observers
    // relying on the plain installedFilesExist()/installedUpdatedAt() reads below - which
    // aren't themselves observable - know to re-read them.
    val installedTick: StateFlow<Int> = _installedTick.asStateFlow()

    fun installedFilesExist(): Boolean = filesExist(context)

    fun installedUpdatedAt(): Long {
        if (!installedFilesExist()) return 0L
        return File(assetsDir(context), GEOIP_FILE).lastModified()
    }

    fun cancelDownload() {
        cancelOngoingWork()
        _state.value = GeoAssetsState.Idle
    }

    suspend fun delete() {
        if (_state.value is GeoAssetsState.Downloading) return
        withContext(Dispatchers.IO) {
            val dir = assetsDir(context)
            File(dir, GEOIP_FILE).delete()
            File(dir, GEOSITE_FILE).delete()
        }
        AppLogsState.addLog("Geo resources deleted")
        _installedTick.value++
    }

    suspend fun download(variant: GeoResourceSet) {
        cancelOngoingWork()

        withWorker {
            AppLogsState.addLog("Downloading geo resources (${variant.id})...")
            _state.value = GeoAssetsState.Downloading(variant)
            _downloadProgress.value = 0
            try {
                withContext(Dispatchers.IO) {
                    val dir = assetsDir(context)
                    dir.mkdirs()
                    val proxy = activeLocalSocksProxy()
                    AppLogsState.addLog(
                        "Fetching geo resources" + (if (proxy != Proxy.NO_PROXY) " (via proxy)" else "")
                    )

                    val tmpGeoip = File(dir, "$GEOIP_FILE.tmp")
                    val tmpGeosite = File(dir, "$GEOSITE_FILE.tmp")
                    try {
                        downloadFile(variant.geoipUrl, tmpGeoip, proxy, rangeStart = 0, rangeEnd = 50)
                        ensureActive()
                        downloadFile(variant.geositeUrl, tmpGeosite, proxy, rangeStart = 50, rangeEnd = 100)
                        ensureActive()

                        tmpGeoip.copyTo(File(dir, GEOIP_FILE), overwrite = true)
                        tmpGeosite.copyTo(File(dir, GEOSITE_FILE), overwrite = true)
                    } finally {
                        tmpGeoip.delete()
                        tmpGeosite.delete()
                    }
                }
                AppLogsState.addLog("Geo resources downloaded successfully")
                _installedTick.value++
                _state.value = GeoAssetsState.Success
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLogsState.addLog("Geo resources download failed: ${e.message}")
                _state.value = GeoAssetsState.Error(e.message ?: "")
            }
        }
    }

    private suspend fun downloadFile(url: String, dest: File, proxy: Proxy, rangeStart: Int, rangeEnd: Int) {
        val connection = withContext(Dispatchers.IO) {
            URL(url).openConnection(proxy)
        } as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        withContext(Dispatchers.IO) {
            connection.connect()
        }

        if (connection.responseCode !in 200..299) {
            connection.disconnect()
            throw java.io.IOException("HTTP ${connection.responseCode}")
        }

        val totalSize = connection.contentLength.toLong()
        var downloaded = 0L
        try {
            connection.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        currentCoroutineContext().ensureActive()
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        if (totalSize > 0) {
                            val fraction = downloaded.toDouble() / totalSize
                            _downloadProgress.value = (rangeStart + (fraction * (rangeEnd - rangeStart)).toInt())
                                .coerceIn(0, 100)
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun cancelOngoingWork() {
        activeJob?.cancel()
        activeJob = null
    }

    private suspend inline fun withWorker(crossinline block: suspend () -> Unit) {
        val job = currentCoroutineContext()[Job]
        activeJob = job
        try {
            block()
        } finally {
            if (activeJob == job) activeJob = null
        }
    }

    companion object {
        private const val GEOIP_FILE = "geoip.dat"
        private const val GEOSITE_FILE = "geosite.dat"

        private val _state = MutableStateFlow<GeoAssetsState>(GeoAssetsState.Idle)
        private val _downloadProgress = MutableStateFlow(0)
        private val _installedTick = MutableStateFlow(0)
        private var activeJob: Job? = null

        fun assetsDir(context: Context): File = File(context.filesDir, "geo_assets")

        fun filesExist(context: Context): Boolean {
            val dir = assetsDir(context)
            return File(dir, GEOIP_FILE).exists() && File(dir, GEOSITE_FILE).exists()
        }
    }
}
