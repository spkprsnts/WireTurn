package com.wireturn.app.ui.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.XrayServiceState
import com.wireturn.app.ui.screens.XraySetupScreen
import com.wireturn.app.ui.theme.WireturnTheme
import com.wireturn.app.viewmodel.MainViewModel

class XrayEditActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val dynamicTheme by viewModel.dynamicTheme.collectAsStateWithLifecycle()
            
            val privacyMode by viewModel.privacyMode.collectAsStateWithLifecycle()
            val savedWgConfig by viewModel.wgConfig.collectAsStateWithLifecycle()
            val savedVlessConfig by viewModel.vlessConfig.collectAsStateWithLifecycle()
            val savedXrayConfig by viewModel.xrayConfig.collectAsStateWithLifecycle()
            val clientConfig by viewModel.clientConfig.collectAsStateWithLifecycle()
            val vlessLinkHistory by viewModel.vlessLinkHistory.collectAsStateWithLifecycle()

            val wgConfigSnapshot by XrayServiceState.wgConfigSnapshot.collectAsStateWithLifecycle()
            val vlessConfigSnapshot by XrayServiceState.vlessConfigSnapshot.collectAsStateWithLifecycle()
            val xrayConfigSnapshot by XrayServiceState.xrayConfigSnapshot.collectAsStateWithLifecycle()

            WireturnTheme(themeMode = themeMode, dynamicColor = dynamicTheme) {
                XraySetupScreen(
                    isEditMode = true,
                    showProtocolSelection = true,
                    initialWgConfig = savedWgConfig,
                    initialVlessConfig = savedVlessConfig,
                    initialXrayConfig = savedXrayConfig,
                    wgConfigSnapshot = wgConfigSnapshot,
                    vlessConfigSnapshot = vlessConfigSnapshot,
                    xrayConfigSnapshot = xrayConfigSnapshot,
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
