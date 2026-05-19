package com.wireturn.app.ui.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.gson.Gson
import com.wireturn.app.data.OlcrtcConfig
import com.wireturn.app.ui.screens.OlcRtcConfigScreen
import com.wireturn.app.ui.theme.WireturnTheme
import com.wireturn.app.viewmodel.MainViewModel

class OlcRtcConfigActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        splashScreen.setKeepOnScreenCondition { !viewModel.isInitialized.value }

        val isEditMode = intent.getBooleanExtra("EXTRA_EDIT_MODE", false)
        val profileName = intent.getStringExtra("EXTRA_PROFILE_NAME") ?: ""
        val configJson = intent.getStringExtra("EXTRA_CONFIG_JSON")

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val dynamicTheme by viewModel.dynamicTheme.collectAsStateWithLifecycle()
            val clientConfig by viewModel.clientConfig.collectAsStateWithLifecycle()

            val initialConfig = remember(clientConfig) {
                if (configJson != null) {
                    try { Gson().fromJson(configJson, OlcrtcConfig::class.java) } catch (_: Exception) { OlcrtcConfig() }
                } else if (isEditMode) {
                    clientConfig.olcrtcConfig
                } else {
                    OlcrtcConfig()
                }
            }

            WireturnTheme(themeMode = themeMode, dynamicColor = dynamicTheme) {
                OlcRtcConfigScreen(
                    isEditMode = isEditMode,
                    initialConfig = initialConfig,
                    onBack = { finish() },
                    onSave = { config ->
                        if (isEditMode) {
                            viewModel.saveClientConfig(clientConfig.copy(
                                olcrtcConfig = config,
                                kernelVariant = com.wireturn.app.data.KernelVariant.OLCRTC
                            ))
                            finish()
                        } else {
                            val intent = android.content.Intent(this, XraySetupActivity::class.java).apply {
                                putExtra("SHOW_PROTOCOL_SELECTION", false)
                                putExtra("EXTRA_PROFILE_NAME", profileName)
                                putExtra("EXTRA_CLIENT_CONFIG_JSON", Gson().toJson(
                                    com.wireturn.app.data.ClientConfig(
                                        olcrtcConfig = config,
                                        kernelVariant = com.wireturn.app.data.KernelVariant.OLCRTC
                                    )
                                ))
                            }
                            startActivity(intent)
                        }
                    }
                )
            }
        }
    }
}
