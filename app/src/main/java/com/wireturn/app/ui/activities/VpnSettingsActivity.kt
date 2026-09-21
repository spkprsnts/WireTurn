package com.wireturn.app.ui.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.ui.screens.VpnSettingsScreen
import com.wireturn.app.ui.theme.WireturnTheme
import com.wireturn.app.viewmodel.MainViewModel

class VpnSettingsActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        splashScreen.setKeepOnScreenCondition { !viewModel.isInitialized.value }

        setContent {
            val isInitialized by viewModel.isInitialized.collectAsStateWithLifecycle()
            if (!isInitialized) return@setContent

            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val dynamicTheme by viewModel.dynamicTheme.collectAsStateWithLifecycle()
            val vpnSettings by viewModel.vpnSettings.collectAsStateWithLifecycle()

            WireturnTheme(themeMode = themeMode, dynamicColor = dynamicTheme) {
                VpnSettingsScreen(
                    initialVpnSettings = vpnSettings,
                    onBack = { finish() },
                    onSave = { vpn ->
                        viewModel.saveVpnSettings(vpn)
                        finish()
                    },
                    onOpenAppFiltering = {
                        startActivity(Intent(this, AppExceptionsActivity::class.java))
                    }
                )
            }
        }
    }
}
