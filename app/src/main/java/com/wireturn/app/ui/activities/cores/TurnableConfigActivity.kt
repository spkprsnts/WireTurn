package com.wireturn.app.ui.activities.cores

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.gson.Gson
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.TurnableConfig
import com.wireturn.app.data.XrayConfiguration
import com.wireturn.app.ui.activities.XraySetupActivity
import com.wireturn.app.ui.screens.cores.TurnableConfigScreen
import com.wireturn.app.ui.theme.WireturnTheme
import com.wireturn.app.viewmodel.MainViewModel

class TurnableConfigActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        splashScreen.setKeepOnScreenCondition { !viewModel.isInitialized.value }

        val isEditMode = intent.getBooleanExtra("EXTRA_EDIT_MODE", false)
        val profileName = intent.getStringExtra("EXTRA_PROFILE_NAME") ?: ""
        val configJson = intent.getStringExtra("EXTRA_CONFIG_JSON")
        val profileId = intent.getStringExtra("EXTRA_PROFILE_ID")

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val dynamicTheme by viewModel.dynamicTheme.collectAsStateWithLifecycle()
            val privacyMode by viewModel.privacyMode.collectAsStateWithLifecycle()
            val clientConfig by viewModel.clientConfig.collectAsStateWithLifecycle()
            val profiles by viewModel.profiles.collectAsStateWithLifecycle()

            val initialConfig = remember(clientConfig, profiles) {
                if (configJson != null) {
                    try { Gson().fromJson(configJson, TurnableConfig::class.java) } catch (_: Exception) { TurnableConfig() }
                } else if (profileId != null) {
                    profiles.find { it.id == profileId }?.turnableConfig ?: TurnableConfig()
                } else if (isEditMode) {
                    (clientConfig.kernelConfig as? KernelConfig.Turnable)?.config ?: TurnableConfig()
                } else {
                    TurnableConfig()
                }
            }

            WireturnTheme(themeMode = themeMode, dynamicColor = dynamicTheme) {
                TurnableConfigScreen(
                    isEditMode = isEditMode,
                    initialConfig = initialConfig,
                    profileName = profileName.ifBlank { null },
                    privacyMode = privacyMode,
                    onBack = { finish() },
                    onSave = { config ->
                        if (isEditMode) {
                            if (profileId != null) {
                                viewModel.updateProfileById(profileId) { it.copy(kernelConfig = KernelConfig.Turnable(config)) }
                            } else {
                                viewModel.saveClientConfig(clientConfig.copy(kernelConfig = KernelConfig.Turnable(config)))
                            }
                            finish()
                        } else {
                            val selectedRoute = config.routes.find { it.routeId == config.selectedRouteId } ?: config.routes.firstOrNull()
                            val isTcp = selectedRoute?.socket?.lowercase() == "tcp"

                            val intent = Intent(this, XraySetupActivity::class.java).apply {
                                putExtra("SHOW_PROTOCOL_SELECTION", true)
                                putExtra("EXTRA_PROFILE_NAME", profileName)
                                if (isTcp) {
                                    putExtra("EXTRA_DEFAULT_PROTOCOL", XrayConfiguration.VLESS.name)
                                }
                                putExtra("EXTRA_KERNEL_VARIANT", "TURNABLE")
                                putExtra("EXTRA_TURNABLE_CONFIG_JSON", Gson().toJson(config))
                            }
                            startActivity(intent)
                        }
                    }
                )
            }
        }
    }
}
