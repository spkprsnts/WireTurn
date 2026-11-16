package com.wireturn.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.URI
import java.util.Locale

/**
 * A captcha page loaded in a WebView that's never shown - FreeTurn's -advanced-captcha: the core
 * serves VK's captcha page locally and waits for a real browser engine to compute its PoW, which
 * runs as the page's own script, no user involved. Owned by CoreService, so it works with the app
 * in the background; the same approach as FreeTurn's own app (AutomaticCaptchaWebViewHostController).
 *
 * Only the core's local captcha proxy is ever loaded (see [isLocalCaptchaUrl]) - that proxy rewrites
 * VK's pages so everything goes through it, nothing navigates anywhere else.
 */
internal class BackgroundCaptchaPage(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null

    // The core gives up on the browser after 30s and says so; this is only for a run that ended
    // without saying (killed, crashed), so a page never outlives it for long.
    private val autoHide = Runnable { destroyWebView() }

    fun show(url: String) {
        if (!isLocalCaptchaUrl(url)) {
            AppLogsState.addLog("Captcha: background page not loaded - not the core's local captcha page")
            return
        }
        main.post {
            destroyWebView()
            webView = createWebView().also { view ->
                view.onResume()
                view.loadUrl(url)
            }
            main.removeCallbacks(autoHide)
            main.postDelayed(autoHide, AUTO_HIDE_MS)
        }
    }

    fun hide() {
        main.post { destroyWebView() }
    }

    private fun destroyWebView() {
        main.removeCallbacks(autoHide)
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            onPause()
            destroy()
        }
        webView = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() = WebView(context).apply {
        // Keeps the renderer bound to the app while the foreground service runs, though the view
        // is never attached to a window.
        setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, false)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowContentAccess = false
        settings.allowFileAccess = false
        // The page is VK's: a WebView marker in the UA ("; wv") is one more thing to look like a bot by.
        settings.userAgentString = settings.userAgentString.replace("; wv)", ")")
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !isLocalCaptchaUrl(request.url.toString())
        }
    }

    companion object {
        private const val AUTO_HIDE_MS = 60_000L
        // free-turn-proxy's local captcha proxy (internal/provider/vk/internal/captcha/manual).
        private const val CAPTCHA_PORT = 8765

        /** Whether [url] is the core's local captcha proxy and nothing else. */
        fun isLocalCaptchaUrl(url: String): Boolean = runCatching {
            val uri = URI(url)
            val host = uri.host?.lowercase(Locale.ROOT)
            uri.scheme?.lowercase(Locale.ROOT) == "http" &&
                uri.rawUserInfo == null &&
                uri.port == CAPTCHA_PORT &&
                (host == "127.0.0.1" || host == "localhost")
        }.getOrDefault(false)
    }
}
