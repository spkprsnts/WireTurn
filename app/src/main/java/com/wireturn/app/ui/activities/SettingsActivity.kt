package com.wireturn.app.ui.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.ui.screens.SettingsScreen
import com.wireturn.app.ui.theme.WireturnTheme
import com.wireturn.app.viewmodel.MainViewModel

class SettingsActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        splashScreen.setKeepOnScreenCondition { !viewModel.isInitialized.value }

        val scrollToUpdate = intent.getLongExtra("EXTRA_SCROLL_TO_UPDATE", 0L)

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val dynamicTheme by viewModel.dynamicTheme.collectAsStateWithLifecycle()

            WireturnTheme(themeMode = themeMode, dynamicColor = dynamicTheme) {
                SettingsScreen(
                    viewModel = viewModel,
                    scrollToUpdate = scrollToUpdate,
                    onBack = { finish() }
                )
            }
        }
    }
}
