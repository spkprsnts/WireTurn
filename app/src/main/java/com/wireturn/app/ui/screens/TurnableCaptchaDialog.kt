package com.wireturn.app.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.wireturn.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/**
 * Turnable's manual VK captcha. Once its auto-solver gives up, Turnable serves a userscript on
 * localhost and waits for the VK call page to run it: the script joins the call anonymously and,
 * when the page gets its call token (after the captcha VK shows there), hands both tokens to
 * Turnable by opening its local /done page. A desktop browser would need a userscript manager for
 * that - here the script runs in this WebView, with the bits of the manager's API it uses.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TurnableCaptchaDialog(
    callUrl: String,
    userScriptUrl: String,
    onDismiss: () -> Unit
) {
    var script by remember(userScriptUrl) { mutableStateOf<String?>(null) }
    var failed by remember(userScriptUrl) { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    LaunchedEffect(userScriptUrl) {
        val userScript = withContext(Dispatchers.IO) { runCatching { httpGet(userScriptUrl) }.getOrNull() }
        if (userScript == null) {
            failed = true
            loading = false
        } else {
            script = wrapUserScript(userScript)
        }
        // The page is about to open: Turnable then gives at least 5 more minutes (/ping) - what
        // the script itself would do through the manager's request API.
        withContext(Dispatchers.IO) {
            runCatching { httpGet(userScriptUrl.substringBeforeLast('/') + "/ping") }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.turnable_captcha_title),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = stringResource(R.string.turnable_captcha_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            painter = painterResource(R.drawable.close_24px),
                            contentDescription = stringResource(R.string.btn_close)
                        )
                    }
                }
                if (loading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val readyScript = script
                    when {
                        failed -> Text(
                            text = stringResource(R.string.turnable_captcha_script_failed),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp)
                        )

                        readyScript != null -> AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    // The real UA without WebView's "; wv)" marker, as for the
                                    // other captcha pages.
                                    settings.userAgentString =
                                        settings.userAgentString.replace("; wv)", ")")

                                    // At document start, as the script expects (it wraps fetch/XHR
                                    // before the page's own code holds on to them); otherwise
                                    // once the page has loaded.
                                    val atDocumentStart =
                                        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                                    if (atDocumentStart) {
                                        WebViewCompat.addDocumentStartJavaScript(
                                            this, readyScript, setOf("https://vk.com", "https://vk.ru")
                                        )
                                    }

                                    webViewClient = object : WebViewClient() {
                                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                            super.onPageStarted(view, url, favicon)
                                            loading = true
                                        }

                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            loading = false
                                            if (url != null && isTokensDonePage(url)) {
                                                // Turnable has the tokens: VK auth goes on, and its
                                                // log line clears the captcha session too.
                                                currentOnDismiss()
                                                return
                                            }
                                            if (!atDocumentStart && url != null && isVkPage(url)) {
                                                view?.evaluateJavascript(readyScript, null)
                                            }
                                        }

                                        // Web pages only - no intents or app links out of here.
                                        override fun shouldOverrideUrlLoading(
                                            view: WebView,
                                            request: WebResourceRequest
                                        ): Boolean = request.url.scheme !in setOf("http", "https")
                                    }
                                    loadUrl(callUrl)
                                }
                            },
                            onRelease = { it.destroy() }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Turnable's userscript with the userscript-manager API it uses: unsafeWindow is just the page's
 * window here, and its one request - a liveness ping to Turnable's server - answers right away:
 * the server is up (the script was just fetched from it), and the dialog pings it itself.
 */
private fun wrapUserScript(userScript: String): String = buildString {
    append("(function () {\n")
    append("  if (window.__wireturnTurnableCaptcha) return;\n")
    append("  window.__wireturnTurnableCaptcha = true;\n")
    append("  var unsafeWindow = window;\n")
    append("  var GM_xmlhttpRequest = function (opts) {\n")
    append("    setTimeout(function () { if (opts && opts.onload) opts.onload({ status: 200 }); }, 0);\n")
    append("  };\n")
    append(userScript)
    append("\n})();\n")
}

private fun isVkPage(url: String): Boolean {
    val host = runCatching { url.toUri().host }.getOrNull() ?: return false
    return host == "vk.com" || host == "vk.ru"
}

// The script's last step: the page navigates to Turnable's local /done with the tokens.
private fun isTokensDonePage(url: String): Boolean {
    val uri = runCatching { url.toUri() }.getOrNull() ?: return false
    return (uri.host == "localhost" || uri.host == "127.0.0.1") && uri.path == "/done"
}

private fun httpGet(url: String): String {
    val connection = URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection
    return try {
        connection.connectTimeout = 3_000
        connection.readTimeout = 3_000
        connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}
