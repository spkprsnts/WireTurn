@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.JavascriptInterface
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.ui.theme.LocalThemeMode
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wireturn.app.R

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
    
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val primaryColor = MaterialTheme.colorScheme.primary
    
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
            """
                html, body, .vkc__ModalOverlay-module__host, .vkc__ModalCardBase-module__container { 
                    background: transparent !important; 
                    background-color: transparent !important; 
                    box-shadow: none !important; 
                }
                .vkc__ModalCardBase-module__container {
                    padding: 0 !important;
                    ${if (captchaForceTint) "filter: $tintFilter !important;" else ""}
                }
                .vkc__NotRobotCaptcha-module__appRoot > div,
                .vkc__ModalCard-module__hostMobile {
                    animation: none !important;
                    transition: none !important;
                    transform: none !important;
                }
                .vkc__ModalCardBase-module__dismiss, 
                .vkc__CheckboxPopupCaptcha-module__captchaId, 
                .vkc__CheckboxPopupCaptcha-module__termsLink { 
                    display: none !important; 
                }
                .vkc__CheckboxPopupCaptcha-module__checkboxBlock { 
                    padding: 0 !important;
                }
                .vkc__Checkbox-module__Checkbox { 
                    transform: scale(1.2) !important;
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
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    AndroidView(
                        factory = { ctx ->
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
                                    userAgentString =
                                        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
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

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        
                                        view?.evaluateJavascript(
                                            """
                                            (function() {
                                                const isDark = $isDarkTheme;
                                                const styleModEnabled = $captchaStyleMod;
                                                
                                                const applyTheme = function() {
                                                    if (!styleModEnabled) return;
                                                    const appRoot = document.querySelector('.vkc__AppRoot-module__host');
                                                    if (!appRoot) return;

                                                    const target = isDark ? 'vkui--vkAccessibilityIOS--dark' : 'vkui--vkAccessibilityIOS--light';
                                                    const others = isDark ? 
                                                        ['vkui--vkAccessibility--dark', 'vkui--vkAccessibilityIOS--light', 'vkui--vkAccessibility--light'] : 
                                                        ['vkui--vkAccessibility--dark', 'vkui--vkAccessibilityIOS--dark', 'vkui--vkAccessibility--light'];
                                                    
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
                                                        AndroidBridge.logDebug('Updating theme classes on .vkc__AppRoot-module__host');
                                                        others.forEach(function(c) { appRoot.classList.remove(c); });
                                                        appRoot.classList.add(target);
                                                    }
                                                };

                                                const style = document.createElement('style');
                                                style.innerHTML = `$captchaCss`;
                                                document.head.appendChild(style);
                                                
                                                if (styleModEnabled) applyTheme();

                                                const checkCaptcha = function() {
                                                    if (styleModEnabled) applyTheme();
                                                    if (document.body.innerText.includes('Done! You can close the page.')) {
                                                        AndroidBridge.onCaptchaSuccess();
                                                        return true;
                                                    }
                                                    
                                                    const dialog = document.querySelector('[role="dialog"]') || 
                                                                 document.querySelector('.vkc__ModalCardBase-module__container') || 
                                                                 document.querySelector('.vkc__Captcha-module__container') || 
                                                                 document.querySelector('body > div');
                                                    
                                                    if (dialog) {
                                                        const height = dialog.offsetHeight || dialog.getBoundingClientRect().height;
                                                        if (height > 0) {
                                                            AndroidBridge.updateSize(Math.ceil(height));
                                                            AndroidBridge.showContent();
                                                        }
                                                    }
                                                    return false;
                                                };
                                        
                                                if (!checkCaptcha()) {
                                                    const observer = new MutationObserver(function(mutations) {
                                                        checkCaptcha();
                                                    });
                                                    observer.observe(document.body, { 
                                                        childList: true, 
                                                        subtree: true, 
                                                        characterData: true,
                                                        attributes: true 
                                                    });
                                                }
                                                
                                                // На всякий случай показываем через небольшую задержку, если высота не определилась
                                                setTimeout(function() {
                                                    AndroidBridge.showContent();
                                                }, 500);
                                            })();
                                            """.trimIndent(), null
                                        )
                                    }
                                }

                                addJavascriptInterface(object {
                                    @JavascriptInterface
                                    fun onCaptchaSuccess() {
                                        post {
                                            onSuccess?.invoke()
                                        }
                                    }

                                    @JavascriptInterface
                                    fun updateSize(height: Int) {
                                        post {
                                            webViewHeight = height
                                        }
                                    }

                                    @JavascriptInterface
                                    fun showContent() {
                                        post {
                                            isLoading = false
                                            isContentVisible.value = true
                                        }
                                    }

                                    @JavascriptInterface
                                    fun logDebug(message: String) {
                                        android.util.Log.d("CaptchaWebView", message)
                                    }
                                }, "AndroidBridge")

                                loadUrl(captchaUrl)
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = contentAlpha }
                    )

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
