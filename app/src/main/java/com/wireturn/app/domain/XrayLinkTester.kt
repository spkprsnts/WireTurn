package com.wireturn.app.domain

import android.content.Context
import com.google.gson.JsonParser
import com.wireturn.app.AppLogsState
import com.wireturn.app.CaBundleUtil
import com.wireturn.app.data.XraySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Measures the real delay of Xray links in one go: vless-client's -test-links mode (see
 * external/vless-client linktest.go) - one process for the whole list, an HTTP request to the
 * ping URL through each server, straight from the device (this app is outside its own VPN, the
 * same way an Xray-only profile connects), one JSON line per link as soon as it is done.
 */
class XrayLinkTester(private val context: Context) {

    sealed class Result {
        data class Ok(val ms: Long) : Result()
        data class Failed(val reason: String) : Result()
    }

    /**
     * Tests [links], calling [onResult] with each one's index in [links] as its result comes in.
     * Cancelling the calling coroutine stops the process.
     */
    suspend fun test(
        links: List<String>,
        pingUrl: String,
        settings: XraySettings,
        useCustomCerts: Boolean,
        concurrency: Int,
        onResult: (index: Int, result: Result) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (links.isEmpty()) return@withContext
        val cmd = mutableListOf(
            "${context.applicationInfo.nativeLibraryDir}/libxray.so",
            "-test-links",
            "-",
            "-test-url",
            pingUrl,
            "-test-timeout",
            TIMEOUT_SEC.toString(),
            "-test-concurrency",
            concurrency.coerceIn(1, XraySettings.MAX_PING_CHECK_CONCURRENCY).toString()
        )
        if (settings.dns.isNotBlank()) cmd.addAll(listOf("-dns", settings.dns))
        // The same ClientHello fragmentation the real connection would use (see XrayService).
        if (settings.fragment) cmd.addAll(listOf("-fragment", "tlshello,100-200,10-20"))

        val builder = ProcessBuilder(cmd)
        if (useCustomCerts) CaBundleUtil.ensure(context)
            ?.let { builder.environment()["SSL_CERT_FILE"] = it }
        val process = builder.start()
        // Reading its output blocks this thread - cancelling must end the process itself.
        val stopOnCancel =
            currentCoroutineContext()[kotlinx.coroutines.Job]?.invokeOnCompletion { process.destroy() }
        try {
            // One link per line - a link never contains a line break, so the indexes line up.
            process.outputStream.bufferedWriter().use { w ->
                links.forEach { w.write(it.trim()); w.newLine() }
            }
            // Its log (stderr) only matters if something goes wrong - drained so it can't block.
            val stderr = StringBuilder()
            val stderrReader = Thread {
                runCatching {
                    process.errorStream.bufferedReader()
                        .forEachLine { if (stderr.length < 4000) stderr.appendLine(it) }
                }
            }.apply { start() }

            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    currentCoroutineContext().ensureActive()
                    parse(line)?.let { (index, result) ->
                        if (index in links.indices) onResult(
                            index,
                            result
                        )
                    }
                }
            }
            val code = process.waitFor()
            stderrReader.join(500)
            if (code != 0) {
                AppLogsState.addLog(
                    "Availability check failed (exit $code): ${
                        stderr.toString().trim().take(300)
                    }"
                )
            }
        } finally {
            stopOnCancel?.dispose()
            process.destroy()
        }
    }

    private fun parse(line: String): Pair<Int, Result>? = try {
        val o = JsonParser.parseString(line).asJsonObject
        val index = o.get("index").asInt
        val ms = o.get("ms")?.takeIf { it.isJsonPrimitive }?.asLong
        index to if (ms != null) Result.Ok(ms) else Result.Failed(o.get("error")?.asString.orEmpty())
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val TIMEOUT_SEC = 5
    }
}
