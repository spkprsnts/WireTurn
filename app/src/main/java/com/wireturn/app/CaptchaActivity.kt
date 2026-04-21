package com.wireturn.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.wireturn.app.ui.screens.CaptchaWebViewDialog
import com.wireturn.app.ui.theme.WireturnTheme

class CaptchaActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("CAPTCHA_URL") ?: ""

        setContent {
            WireturnTheme {
                CaptchaWebViewDialog(
                    captchaUrl = url,
                    onDismiss = { finish() },
                    onSuccess = { runOnUiThread { finish() } }
                )
            }
        }
    }
}
