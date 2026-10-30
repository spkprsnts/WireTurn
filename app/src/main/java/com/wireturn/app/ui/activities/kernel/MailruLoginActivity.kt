package com.wireturn.app.ui.activities.kernel

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.R
import com.wireturn.app.data.kernel.MailruSignIn
import com.wireturn.app.ui.AppTopAppBar
import com.wireturn.app.ui.showExclusiveToast
import com.wireturn.app.ui.theme.WireturnTheme
import com.wireturn.app.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Signs in to Mail.ru in a WebView and keeps the account's cookies for the mailru carrier (see
 * MailruSignIn). Mail.ru's own pages do the signing in - this only watches the cookie jar for the
 * login cookie and finishes as soon as it shows up, already signed in or not.
 */
class MailruLoginActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val dynamicTheme by viewModel.dynamicTheme.collectAsStateWithLifecycle()
            var isLoading by remember { mutableStateOf(true) }
            var webView by remember { mutableStateOf<WebView?>(null) }
            val signedInMessage = stringResource(R.string.openflux_mailru_signin_done)

            // The sign-in lands in the jar on whichever mail.ru page the login flow ends on - a
            // redirect, an iframe, a script - so the jar is read rather than any one page's URL.
            // The login cookie comes before the rest of the session's, so a jar that has it is
            // only kept once Cloud takes it (MailruSignIn.verify); until then the flow goes on.
            LaunchedEffect(Unit) {
                val cookies = CookieManager.getInstance()
                while (isActive) {
                    val jar = MailruSignIn.parseHeader(cookies.getCookie(MailruSignIn.COOKIE_URL))
                    if (jar != null && withContext(Dispatchers.IO) { MailruSignIn.verify(jar) }) {
                        cookies.flush()
                        withContext(Dispatchers.IO) { MailruSignIn.save(filesDir, jar) }
                        showExclusiveToast(signedInMessage)
                        setResult(RESULT_OK)
                        finish()
                        break
                    }
                    // A check is a request to Cloud: not every poll once the login cookie is there.
                    delay(if (jar != null) 2_000.milliseconds else 700.milliseconds)
                }
            }

            BackHandler {
                val view = webView
                if (view != null && view.canGoBack()) view.goBack() else finish()
            }

            WireturnTheme(themeMode = themeMode, dynamicColor = dynamicTheme) {
                Scaffold(
                    topBar = {
                        AppTopAppBar(
                            title = stringResource(R.string.openflux_mailru_signin_title),
                            onBack = ::finish
                        )
                    },
                    contentWindowInsets = WindowInsets(0, 0, 0, 0)
                ) { padding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .consumeWindowInsets(padding)
                            .navigationBarsPadding()
                            .imePadding()
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.allowFileAccess = false
                                    settings.allowContentAccess = false
                                    // As CaptchaWebViewDialog does: only the "; wv)" marker goes, so
                                    // the UA still matches the real engine.
                                    settings.userAgentString = settings.userAgentString.replace("; wv)", ")")
                                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                    webViewClient = object : WebViewClient() {
                                        // Sign-in through VK ID, OK and the like crosses domains; only
                                        // app links (intent://, mailru://) are kept out.
                                        override fun shouldOverrideUrlLoading(
                                            view: WebView,
                                            request: WebResourceRequest
                                        ): Boolean = request.url.scheme !in setOf("https", "http")

                                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                            isLoading = true
                                        }

                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            isLoading = false
                                        }
                                    }
                                    loadUrl(START_URL)
                                    webView = this
                                }
                            },
                            onRelease = { it.destroy() },
                            modifier = Modifier.fillMaxSize()
                        )
                        if (isLoading) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.TopCenter)
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        // Cloud's own home: it sends an anonymous visitor to the sign-in, and its domain is the
        // one the carrier's cookies are read for.
        private const val START_URL = "https://cloud.mail.ru/home/"

        private val COOKIE_HOSTS = listOf(
            "https://mail.ru/", "https://cloud.mail.ru/", "https://account.mail.ru/",
            "https://auth.mail.ru/", "https://e.mail.ru/"
        )

        /**
         * Signs the WebView out of Mail.ru, so the next sign-in asks for an account again. The
         * shared CookieManager also holds the captcha WebViews' cookies, so only mail.ru's go,
         * expired one by one - both as host cookies and as .mail.ru ones, whichever they were.
         */
        fun clearWebViewCookies() {
            val cookies = CookieManager.getInstance()
            for (url in COOKIE_HOSTS) {
                val names = cookies.getCookie(url).orEmpty().split(';')
                    .map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }
                for (name in names) {
                    cookies.setCookie(url, "$name=; Max-Age=0; Path=/")
                    cookies.setCookie(url, "$name=; Max-Age=0; Path=/; Domain=.mail.ru")
                }
            }
            cookies.flush()
        }
    }
}
