package com.wireturn.app.ui.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.ui.ProxyTriggerController
import com.wireturn.app.ui.screens.LogsScreen
import com.wireturn.app.ui.theme.WireturnTheme
import com.wireturn.app.viewmodel.MainViewModel

class LogsActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        splashScreen.setKeepOnScreenCondition { !viewModel.isInitialized.value }

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val dynamicTheme by viewModel.dynamicTheme.collectAsStateWithLifecycle()

            WireturnTheme(themeMode = themeMode, dynamicColor = dynamicTheme) {
                ProxyTriggerController(viewModel = viewModel) { onToggle, _ ->
                    LogsScreen(
                        viewModel = viewModel,
                        onBack = { finish() },
                        onToggleProxy = onToggle
                    )
                }
            }
        }
    }
}
