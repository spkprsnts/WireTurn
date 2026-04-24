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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wireturn.app.R

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CaptchaWebViewDialog(
    captchaUrl: String,
    onDismiss: () -> Unit,
    onSuccess: (() -> Unit)? = null
) {
    var isLoading by remember { mutableStateOf(true) }
    var webViewHeight by remember { mutableIntStateOf(0) }

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
                                        isLoading = false

                                        view?.evaluateJavascript(
                                            """
                                            (function() {
                                                var style = document.createElement('style');
                                                style.innerHTML = `
                                                    html, body, .vkc__ModalOverlay-module__host { 
                                                        background: transparent !important; 
                                                        background-color: transparent !important; 
                                                    }
                                                `;
                                                document.head.appendChild(style);

                                                var checkCaptcha = function() {
                                                    if (document.body.innerText.includes('Done! You can close the page.')) {
                                                        AndroidBridge.onCaptchaSuccess();
                                                        return true;
                                                    }
                                                    
                                                    var dialog = document.querySelector('[role="dialog"]');
                                                    if (!dialog) dialog = document.querySelector('.vkc__Captcha-module__container');
                                                    if (!dialog) dialog = document.querySelector('body > div');
                                                    
                                                    if (dialog) {
                                                        var height = dialog.offsetHeight || dialog.getBoundingClientRect().height;
                                                        if (height > 0) {
                                                            AndroidBridge.updateSize(Math.ceil(height));
                                                        }
                                                    }
                                                    return false;
                                                };
                                        
                                                if (!checkCaptcha()) {
                                                    var observer = new MutationObserver(function(mutations) {
                                                        checkCaptcha();
                                                    });
                                                    observer.observe(document.body, { 
                                                        childList: true, 
                                                        subtree: true, 
                                                        characterData: true,
                                                        attributes: true 
                                                    });
                                                }
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
                                }, "AndroidBridge")

                                loadUrl(captchaUrl)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    if (isLoading) {
                        CircularWavyProgressIndicator(
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

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
