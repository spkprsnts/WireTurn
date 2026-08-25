@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.time.Duration.Companion.milliseconds

/**
 * Опрашивает состояние капчи из Kotlin (evaluateJavascript = pull), вместо
 * addJavascriptInterface (push): нативный JS-мост виден в window.* как
 * Java-backed объект и является одним из самых надёжных сигналов
 * автоматизации для антибот-скриптов на странице.
 */
private suspend fun WebView.evalJs(script: String): String? =
    suspendCancellableCoroutine { cont ->
        evaluateJavascript(script) { result ->
            if (cont.isActive) cont.resume(result) { _, _, _ -> }
        }
    }

/** Только локальный captcha-прокси ядра допускается к загрузке в WebView. */
private fun isLocalCaptchaUrl(url: String): Boolean {
    val uri = url.toUri()
    val host = uri.host ?: return false
    return uri.scheme?.lowercase() == "http" && (host == "127.0.0.1" || host == "localhost")
}

private const val CAPTCHA_POLL_SCRIPT = """
    JSON.stringify({
        s: !!(window.__wireturnCaptcha && window.__wireturnCaptcha.success),
        h: (window.__wireturnCaptcha && window.__wireturnCaptcha.height) || 0,
        v: !!(window.__wireturnCaptcha && window.__wireturnCaptcha.visible)
    })
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CaptchaWebViewDialog(
    viewModel: com.wireturn.app.viewmodel.MainViewModel,
    captchaUrl: String,
    onDismiss: () -> Unit,
    onSuccess: (() -> Unit)? = null
) {
    var isLoading by remember { mutableStateOf(true) }
    val isContentVisible = remember { mutableStateOf(false) }
    var webViewHeight by remember { mutableIntStateOf(0) }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val primaryColor = MaterialTheme.colorScheme.primary

    val isViewModelInitialized by viewModel.isInitialized.collectAsStateWithLifecycle()
    val captchaStyleMod by viewModel.captchaStyleMod.collectAsStateWithLifecycle()
    val captchaForceTint by viewModel.captchaForceTint.collectAsStateWithLifecycle()

    val tintFilter = remember(primaryColor, isDarkTheme) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(primaryColor.toArgb(), hsv)
        val hueRotation = (hsv[0] - 38f).let { if (it < 0) it + 360 else it }
        "grayscale(100%) sepia(100%) hue-rotate(${hueRotation.toInt()}deg) saturate(${hsv[1] * 2.5f}) brightness(${if (isDarkTheme) 0.8f else 1.0f})"
    }

    val captchaCss = remember(captchaStyleMod, captchaForceTint, tintFilter) {
        if (captchaStyleMod) {
            // VK's CSS module class names carry a per-build hash suffix (e.g.
            // "ModalCardBase__container--Obeop") that rotates on every widget deploy - matching
            // on the stable "ModuleName__member--" prefix via [class*=] survives those rotations,
            // unlike a hardcoded full class name.
            """
                html, body, [class*="ModalOverlay__host--"], [class*="ModalCardBase__container--"] {
                    background: transparent !important;
                    background-color: transparent !important;
                    box-shadow: none !important;
                }
                [class*="ModalCardBase__container--"] {
                    padding: 0 !important;
                    ${if (captchaForceTint) "filter: $tintFilter !important;" else ""}
                }
                [class*="NotRobotCaptcha__appRoot--"] > div,
                [class*="ModalCard__hostMobile--"] {
                    animation: none !important;
                    transition: none !important;
                    transform: none !important;
                }
                [class*="ModalCardBase__dismiss--"],
                [class*="CheckboxPopupCaptcha__captchaId--"],
                [class*="CheckboxPopupCaptcha__termsLink--"] {
                    display: none !important;
                }
                [class*="CheckboxPopupCaptcha__checkboxBlock--"] {
                    padding: 0 !important;
                }
                [class*="Checkbox__Checkbox--"] {
                    transform: scale(1.2) !important;
                }
                /* free-turn-proxy's own captcha proxy (когда активное ядро - FreeTurn) инжектит
                   поверх страницы баннер "free turn proxy - captcha" без класса/id - только через
                   style.cssText (internal/provider/vk/internal/captcha/manual/inject.js,
                   showPending()). Ловим его по этому же инлайн-стилю. */
                body > div[style*="z-index:99999"] {
                    display: none !important;
                }
            """.trimIndent()
        } else {
            "html, body { background: transparent !important; }"
        }
    }

    val contentAlpha by animateFloatAsState(
        targetValue = if (isContentVisible.value) 1f else 0f,
        animationSpec = tween(durationMillis = 400),
        label = "CaptchaVisibility"
    )

    LaunchedEffect(webViewRef.value) {
        val webView = webViewRef.value ?: return@LaunchedEffect
        while (isActive) {
            delay(300.milliseconds)
            val raw = webView.evalJs(CAPTCHA_POLL_SCRIPT) ?: continue
            val json = (JSONTokener(raw).nextValue() as? String) ?: continue
            val state = runCatching { JSONObject(json) }.getOrNull() ?: continue

            val height = state.optInt("h")
            if (height > 0) webViewHeight = height
            if (state.optBoolean("v")) {
                isLoading = false
                isContentVisible.value = true
            }
            if (state.optBoolean("s")) {
                onSuccess?.invoke()
                break
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier
                .padding(16.dp)
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .wrapContentHeight()
        ) {
            Column(
                modifier = Modifier.padding(vertical = 24.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.captcha_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.captcha_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(24.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .let {
                            if (webViewHeight > 0) {
                                // Используем полученное значение напрямую как DP, 
                                // так как WebView в мобильном режиме обычно отдает логические пиксели
                                it.height(webViewHeight.dp + 32.dp)
                            } else {
                                it.height(350.dp)
                            }
                        }
                        .clip(MaterialTheme.shapes.large)
                ) {
                    if (isViewModelInitialized) {
                    AndroidView(
                        factory = { ctx ->
                            // Lets chrome://inspect see this WebView over USB for debugging captcha
                            // page changes - only in debug builds, never in release.
                            if ((ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                                WebView.setWebContentsDebuggingEnabled(true)
                            }
                            WebView(ctx).apply {
                                // Устанавливаем программный тип слоя для лучшей поддержки прозрачности
                                setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                                
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )

                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    useWideViewPort = true
                                    loadWithOverviewMode = true
                                    setSupportZoom(false)
                                    builtInZoomControls = false
                                    displayZoomControls = false
                                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                                    // Снимаем только маркер "; wv)" с настоящей UA-строки, вместо
                                    // подмены на выдуманную версию Chrome: реальный UA остаётся
                                    // согласован с реальным движком/Client Hints устройства, без
                                    // риска рассинхрона версии, которую видит антибот через
                                    // feature-detection.
                                    userAgentString = userAgentString.replace("; wv)", ")")
                                }

                                setBackgroundColor(android.graphics.Color.TRANSPARENT)

                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(
                                        view: WebView?,
                                        url: String?,
                                        favicon: Bitmap?
                                    ) {
                                        super.onPageStarted(view, url, favicon)
                                        isLoading = true
                                        // Попытка сделать фон прозрачным как можно раньше
                                        view?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                    }

                                    override fun shouldOverrideUrlLoading(
                                        view: WebView,
                                        request: WebResourceRequest
                                    ): Boolean = !isLocalCaptchaUrl(request.url.toString())

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)

                                        view?.evaluateJavascript(
                                            """
                                            (function() {
                                                window.__wireturnCaptcha = { isDark: $isDarkTheme, styleModEnabled: $captchaStyleMod, success: false, height: 0, visible: false };

                                                const applyTheme = function() {
                                                    if (!window.__wireturnCaptcha.styleModEnabled) return;
                                                    const appRoot = document.querySelector('[class*="AppRoot__host--"]');
                                                    if (!appRoot) return;

                                                    const isDark = window.__wireturnCaptcha.isDark;
                                                    const target = isDark ? 'vkui--vkAccessibility--dark' : 'vkui--vkAccessibility--light';
                                                    const others = isDark ?
                                                        ['vkui--vkAccessibility--light', 'vkui--vkAccessibilityIOS--dark', 'vkui--vkAccessibilityIOS--light'] :
                                                        ['vkui--vkAccessibility--dark', 'vkui--vkAccessibilityIOS--dark', 'vkui--vkAccessibilityIOS--light'];

                                                    let needsUpdate = !appRoot.classList.contains(target);
                                                    if (!needsUpdate) {
                                                        for (let i = 0; i < others.length; i++) {
                                                            if (appRoot.classList.contains(others[i])) {
                                                                needsUpdate = true;
                                                                break;
                                                            }
                                                        }
                                                    }

                                                    if (needsUpdate) {
                                                        others.forEach(function(c) { appRoot.classList.remove(c); });
                                                        appRoot.classList.add(target);
                                                    }
                                                };

                                                window.__wireturnApplyCaptchaStyle = function(css, styleModEnabled, isDark) {
                                                    window.__wireturnCaptcha.styleModEnabled = styleModEnabled;
                                                    window.__wireturnCaptcha.isDark = isDark;

                                                    let style = document.getElementById('__wireturn_captcha_style__');
                                                    if (!style) {
                                                        style = document.createElement('style');
                                                        style.id = '__wireturn_captcha_style__';
                                                        document.head.appendChild(style);
                                                    }
                                                    style.innerHTML = css;

                                                    if (styleModEnabled) applyTheme();
                                                };

                                                window.__wireturnApplyCaptchaStyle(`$captchaCss`, $captchaStyleMod, $isDarkTheme);

                                                // Единственный источник истины - ответ captchaNotRobot.check с
                                                // success_token (тот же сигнал, на который смотрят Go-сторона
                                                // free-turn-proxy и её собственный inject.js). Текст/иконки на
                                                // странице ("Success", чекмарк чекбокса) ненадёжны: чекмарк
                                                // рисуется сразу при клике, до завершения проверки на сервере.
                                                const CHECK_PATH = 'captchaNotRobot.check';
                                                const markSuccess = function(data) {
                                                    if (data && data.response && data.response.success_token) {
                                                        window.__wireturnCaptcha.success = true;
                                                        document.body.style.display = 'none';
                                                    }
                                                };

                                                const origFetch = window.fetch;
                                                window.fetch = function(...args) {
                                                    const urlStr = typeof args[0] === 'object' ? args[0]?.url : args[0];
                                                    const promise = origFetch.apply(this, args);
                                                    if (typeof urlStr === 'string' && urlStr.includes(CHECK_PATH)) {
                                                        promise.then(function(res) { return res.clone().json(); })
                                                            .then(markSuccess)
                                                            .catch(function() {});
                                                    }
                                                    return promise;
                                                };

                                                const xhrOpen = XMLHttpRequest.prototype.open;
                                                XMLHttpRequest.prototype.open = function(...args) {
                                                    this._wtUrl = typeof args[1] === 'string' ? args[1] : '';
                                                    return xhrOpen.apply(this, args);
                                                };
                                                const xhrSend = XMLHttpRequest.prototype.send;
                                                XMLHttpRequest.prototype.send = function(...args) {
                                                    if (this._wtUrl && this._wtUrl.includes(CHECK_PATH)) {
                                                        this.addEventListener('load', function() {
                                                            try { markSuccess(JSON.parse(this.responseText)); } catch (e) {}
                                                        });
                                                    }
                                                    return xhrSend.apply(this, args);
                                                };

                                                // Опрос виден только для авторазмера/показа диалога, к успеху
                                                // отношения не имеет - за исключением проверки ниже.
                                                const checkVisibility = function() {
                                                    if (window.__wireturnCaptcha.styleModEnabled) applyTheme();

                                                    // free-turn-proxy's own captcha proxy (когда активное ядро -
                                                    // FreeTurn) инжектит свой inject.js прямо в HTML на сервере
                                                    // (internal/provider/vk/internal/captcha/manual/inject.js).
                                                    // Он слушает тот же success_token с captchaNotRobot.check, что
                                                    // и markSuccess выше, но при успехе полностью заменяет
                                                    // document.body своим экраном "free turn proxy / gg" вместо
                                                    // виджета VK - тогда дальнейший опрос диалога ниже уже ничего
                                                    // не найдёт. Считаем этот экран равнозначным сигналом успеха.
                                                    if (!window.__wireturnCaptcha.success) {
                                                        const bodyHtml = document.body.innerHTML;
                                                        if (bodyHtml.indexOf('free turn proxy') !== -1 && bodyHtml.indexOf('>gg<') !== -1) {
                                                            window.__wireturnCaptcha.success = true;
                                                        }
                                                    }

                                                    const dialog = document.querySelector('[role="dialog"]') ||
                                                                 document.querySelector('[class*="ModalCardBase__container--"]') ||
                                                                 document.querySelector('[class*="Captcha__container--"]') ||
                                                                 document.querySelector('body > div');

                                                    if (dialog) {
                                                        const height = dialog.offsetHeight || dialog.getBoundingClientRect().height;
                                                        if (height > 0) {
                                                            window.__wireturnCaptcha.height = Math.ceil(height);
                                                            window.__wireturnCaptcha.visible = true;
                                                        }
                                                    }
                                                };

                                                checkVisibility();
                                                const observer = new MutationObserver(function(mutations) {
                                                    checkVisibility();
                                                });
                                                observer.observe(document.body, {
                                                    childList: true,
                                                    subtree: true,
                                                    characterData: true,
                                                    attributes: true
                                                });

                                                // На всякий случай показываем через небольшую задержку, если высота не определилась
                                                setTimeout(function() {
                                                    window.__wireturnCaptcha.visible = true;
                                                }, 500);
                                            })();
                                            """.trimIndent(), null
                                        )
                                    }
                                }

                                loadUrl(captchaUrl)
                                webViewRef.value = this
                            }
                        },
                        update = { webView ->
                            webView.evaluateJavascript(
                                "if (window.__wireturnApplyCaptchaStyle) { window.__wireturnApplyCaptchaStyle(`$captchaCss`, $captchaStyleMod, $isDarkTheme); }",
                                null
                            )
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = contentAlpha }
                    )
                    }

                    if (isLoading) {
                        CircularWavyProgressIndicator(
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.btn_close))
                    }
                }
            }
        }
    }
}
