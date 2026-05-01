package com.wireturn.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.wireturn.app.ui.screens.CaptchaWebViewDialog
import com.wireturn.app.ui.theme.WireturnTheme
import com.wireturn.app.viewmodel.MainViewModel

class CaptchaActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("CAPTCHA_URL") ?: ""

        setContent {
            WireturnTheme {
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
