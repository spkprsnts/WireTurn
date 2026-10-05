package com.wireturn.app.ui.activities.kernel

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
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.VlessConfig
import com.wireturn.app.data.WgConfig
import com.wireturn.app.data.XrayConfig
import com.wireturn.app.data.kernel.XrayLinkConfig
import com.wireturn.app.ui.activities.MainActivity
import com.wireturn.app.ui.screens.kernel.XrayLinkConfigScreen
import com.wireturn.app.ui.theme.WireturnTheme
import com.wireturn.app.viewmodel.MainViewModel

class XrayLinkConfigActivity : ComponentActivity() {
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
            val isInitialized by viewModel.isInitialized.collectAsStateWithLifecycle()
            if (!isInitialized) return@setContent

            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val dynamicTheme by viewModel.dynamicTheme.collectAsStateWithLifecycle()
            val privacyMode by viewModel.privacyMode.collectAsStateWithLifecycle()
            val clientConfig by viewModel.clientConfig.collectAsStateWithLifecycle()
            val profiles by viewModel.profiles.collectAsStateWithLifecycle()

            val initialConfig = remember(clientConfig, profiles) {
                if (configJson != null) {
                    try { Gson().fromJson(configJson, XrayLinkConfig::class.java) } catch (_: Exception) { XrayLinkConfig() }
                } else if (profileId != null) {
                    profiles.find { it.id == profileId }?.xrayLinkConfig ?: XrayLinkConfig()
                } else if (isEditMode) {
                    (clientConfig.kernelConfig as? KernelConfig.Xray)?.config ?: XrayLinkConfig()
                } else {
                    XrayLinkConfig()
                }
            }

            WireturnTheme(themeMode = themeMode, dynamicColor = dynamicTheme) {
                XrayLinkConfigScreen(
                    isEditMode = isEditMode,
                    initialConfig = initialConfig,
                    profileName = profileName,
                    privacyMode = privacyMode,
                    onBack = { finish() },
                    onSave = { config ->
                        if (isEditMode) {
                            if (profileId != null) {
                                viewModel.updateProfileById(profileId) { it.copy(kernelConfig = KernelConfig.Xray(config)) }
                                if (profileId == viewModel.currentProfileId.value) {
                                    // Also update the live config so CoreService doesn't keep using the stale one.
                                    viewModel.saveClientConfig(clientConfig.copy(kernelConfig = KernelConfig.Xray(config)))
                                }
                            } else {
                                viewModel.saveClientConfig(clientConfig.copy(kernelConfig = KernelConfig.Xray(config)))
                            }
                            finish()
                        } else {
                            // No Xray step to go through - the link already is the whole profile.
                            viewModel.addFullProfile(
                                name = profileName,
                                clientConfig = ClientConfig(kernelConfig = KernelConfig.Xray(config)),
                                xrayConfig = XrayConfig(enabled = false),
                                wgConfig = WgConfig(),
                                vlessConfig = VlessConfig()
                            )
                            // Close all creation activities
                            startActivity(Intent(this, MainActivity::class.java).addFlags(
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            ))
                        }
                    }
                )
            }
        }
    }
}
