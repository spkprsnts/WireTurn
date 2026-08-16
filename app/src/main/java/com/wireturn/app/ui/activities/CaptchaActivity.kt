package com.wireturn.app.ui.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.ui.screens.CaptchaWebViewDialog
import com.wireturn.app.ui.theme.WireturnTheme
import com.wireturn.app.viewmodel.MainViewModel

class CaptchaActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val currentUrl = androidx.compose.runtime.mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { !viewModel.isInitialized.value }
        currentUrl.value = intent.getStringExtra("CAPTCHA_URL") ?: ""

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val dynamicTheme by viewModel.dynamicTheme.collectAsStateWithLifecycle()
            val captchaSession by com.wireturn.app.CoreServiceState.captchaSession.collectAsStateWithLifecycle()

            // Закрываем окно, если ядро сообщило, что капча больше не нужна (решена или ошибка)
            androidx.compose.runtime.LaunchedEffect(captchaSession) {
                if (captchaSession == null) {
                    finish()
                }
            }

            WireturnTheme(themeMode = themeMode, dynamicColor = dynamicTheme) {
                androidx.compose.runtime.key(captchaSession?.sessionId) {
                    CaptchaWebViewDialog(
                        viewModel = viewModel,
                        captchaUrl = captchaSession?.url ?: currentUrl.value,
                        onDismiss = {
                            viewModel.dismissCaptcha()
                            finish()
                        },
                        onSuccess = {
                            // Закрываем окно локально как только WebView обнаружил успех.
                            // MainActivity не откроет его повторно благодаря lastHandledCaptchaSessionId.
                            runOnUiThread { finish() }
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newUrl = intent.getStringExtra("CAPTCHA_URL") ?: ""
        if (newUrl.isNotBlank() && newUrl != currentUrl.value) {
            currentUrl.value = newUrl
        }
    }
}
