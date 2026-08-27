package com.wireturn.app.ui.activities.cores

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.gson.Gson
import com.wireturn.app.data.FreeTurnConfig
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.XrayConfiguration
import com.wireturn.app.ui.activities.XraySetupActivity
import com.wireturn.app.ui.screens.cores.FreeTurnConfigScreen
import com.wireturn.app.ui.theme.WireturnTheme
import com.wireturn.app.viewmodel.MainViewModel

class FreeTurnConfigActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        enableEdgeToEdge()
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
                    try { Gson().fromJson(configJson, FreeTurnConfig::class.java) } catch (_: Exception) { FreeTurnConfig() }
                } else if (profileId != null) {
                    profiles.find { it.id == profileId }?.freeturnConfig ?: FreeTurnConfig()
                } else if (isEditMode) {
                    (clientConfig.kernelConfig as? KernelConfig.FreeTurn)?.config ?: FreeTurnConfig()
                } else {
                    FreeTurnConfig()
                }
            }

            WireturnTheme(themeMode = themeMode, dynamicColor = dynamicTheme) {
                FreeTurnConfigScreen(
                    isEditMode = isEditMode,
                    initialConfig = initialConfig,
                    profileName = profileName.ifBlank { null },
                    privacyMode = privacyMode,
                    onBack = { finish() },
                    onSave = { config ->
                        if (isEditMode) {
                            if (profileId != null) {
                                viewModel.updateProfileById(profileId) { it.copy(kernelConfig = KernelConfig.FreeTurn(config)) }
                                if (profileId == viewModel.currentProfileId.value) {
                                    viewModel.saveClientConfig(clientConfig.copy(kernelConfig = KernelConfig.FreeTurn(config)))
                                }
                            } else {
                                viewModel.saveClientConfig(clientConfig.copy(kernelConfig = KernelConfig.FreeTurn(config)))
                            }
                            finish()
                        } else {
                            val intent = Intent(this, XraySetupActivity::class.java).apply {
                                putExtra("EXTRA_PROFILE_NAME", profileName)
                                putExtra(
                                    "EXTRA_DEFAULT_PROTOCOL",
                                    if (config.mode == "tcp") XrayConfiguration.VLESS.name
                                    else XrayConfiguration.WIREGUARD.name
                                )

                                putExtra("EXTRA_KERNEL_VARIANT", "FREETURN")
                                putExtra("EXTRA_FREETURN_CONFIG_JSON", Gson().toJson(config))
                            }
                            startActivity(intent)
                        }
                    }
                )
            }
        }
    }
}
