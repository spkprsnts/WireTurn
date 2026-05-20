package com.wireturn.app.ui.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.ui.screens.XraySetupScreen
import com.wireturn.app.ui.theme.WireturnTheme
import com.wireturn.app.viewmodel.MainViewModel

class XrayEditActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        splashScreen.setKeepOnScreenCondition { !viewModel.isInitialized.value }

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val dynamicTheme by viewModel.dynamicTheme.collectAsStateWithLifecycle()
            
            val privacyMode by viewModel.privacyMode.collectAsStateWithLifecycle()
            val savedWgConfig by viewModel.wgConfig.collectAsStateWithLifecycle()
            val savedVlessConfig by viewModel.vlessConfig.collectAsStateWithLifecycle()
            val savedXrayConfig by viewModel.xrayConfig.collectAsStateWithLifecycle()
            val clientConfig by viewModel.clientConfig.collectAsStateWithLifecycle()
            val vlessLinkHistory by viewModel.vlessLinkHistory.collectAsStateWithLifecycle()

            WireturnTheme(themeMode = themeMode, dynamicColor = dynamicTheme) {
                XraySetupScreen(
                    isEditMode = true,
                    showProtocolSelection = true,
                    initialWgConfig = savedWgConfig,
                    initialVlessConfig = savedVlessConfig,
                    initialXrayConfig = savedXrayConfig,
                    privacyMode = privacyMode,
                    kernelVariant = clientConfig.kernelVariant,
                    vlessLinkHistory = vlessLinkHistory,
                    onRemoveHistoryItem = { viewModel.removeVlessLinkFromHistory(it) },
                    onBack = { finish() },
                    onSave = { type, wg, vless ->
                        val currentXray = savedXrayConfig.copy(protocol = type)
                        viewModel.updateXrayConfig(currentXray)
                        viewModel.updateWgConfig(wg)
                        viewModel.updateVlessConfig(vless)
                        finish()
                    }
                )
            }
        }
    }
}
