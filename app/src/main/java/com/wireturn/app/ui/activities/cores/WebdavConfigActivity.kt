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
import com.wireturn.app.data.WebdavConfig
import com.wireturn.app.ui.activities.XraySetupActivity
import com.wireturn.app.ui.screens.cores.WebdavConfigScreen
import com.wireturn.app.ui.theme.WireturnTheme
import com.wireturn.app.viewmodel.MainViewModel

class WebdavConfigActivity : ComponentActivity() {
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
            val isInitialized by viewModel.isInitialized.collectAsStateWithLifecycle()
            if (!isInitialized) return@setContent

            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val dynamicTheme by viewModel.dynamicTheme.collectAsStateWithLifecycle()
            val privacyMode by viewModel.privacyMode.collectAsStateWithLifecycle()
            val clientConfig by viewModel.clientConfig.collectAsStateWithLifecycle()
            val profiles by viewModel.profiles.collectAsStateWithLifecycle()

            val initialConfig = remember(clientConfig, profiles) {
                if (configJson != null) {
                    try { Gson().fromJson(configJson, WebdavConfig::class.java) } catch (_: Exception) { WebdavConfig() }
                } else if (profileId != null) {
                    profiles.find { it.id == profileId }?.webdavConfig ?: WebdavConfig()
                } else if (isEditMode) {
                    (clientConfig.kernelConfig as? KernelConfig.Webdav)?.config ?: WebdavConfig()
                } else {
                    WebdavConfig()
                }
            }

            WireturnTheme(themeMode = themeMode, dynamicColor = dynamicTheme) {
                WebdavConfigScreen(
                    isEditMode = isEditMode,
                    initialConfig = initialConfig,
                    profileName = profileName,
                    privacyMode = privacyMode,
                    onBack = { finish() },
                    onSave = { config ->
                        if (isEditMode) {
                            if (profileId != null) {
                                viewModel.updateProfileById(profileId) { it.copy(kernelConfig = KernelConfig.Webdav(config)) }
                            } else {
                                viewModel.saveClientConfig(clientConfig.copy(kernelConfig = KernelConfig.Webdav(config)))
                            }
                            finish()
                        } else {
                            val intent = Intent(this, XraySetupActivity::class.java).apply {
                                putExtra("SHOW_PROTOCOL_SELECTION", false)
                                putExtra("EXTRA_PROFILE_NAME", profileName)
                                putExtra("EXTRA_KERNEL_VARIANT", "WEBDAV")
                                putExtra("EXTRA_WEBDAV_CONFIG_JSON", Gson().toJson(config))
                            }
                            startActivity(intent)
                        }
                    }
                )
            }
        }
    }
}
