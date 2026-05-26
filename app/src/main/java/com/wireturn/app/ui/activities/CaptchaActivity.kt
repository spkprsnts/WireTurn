package com.wireturn.app.ui.activities

import android.os.Bundle
import androidx.activity.compose.setContent
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

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { !viewModel.isInitialized.value }
        val url = intent.getStringExtra("CAPTCHA_URL") ?: ""

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val dynamicTheme by viewModel.dynamicTheme.collectAsStateWithLifecycle()

            WireturnTheme(themeMode = themeMode, dynamicColor = dynamicTheme) {
                CaptchaWebViewDialog(
                    viewModel = viewModel,
                    captchaUrl = url,
                    onDismiss = { finish() },
                    onSuccess = { runOnUiThread { finish() } }
                )
            }
        }
    }
}
