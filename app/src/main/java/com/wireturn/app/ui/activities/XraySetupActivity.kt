package com.wireturn.app.ui.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.gson.Gson
import com.wireturn.app.R
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelVariant
import com.wireturn.app.data.OlcrtcConfig
import com.wireturn.app.data.TurnableConfig
import com.wireturn.app.data.WebdavConfig
import com.wireturn.app.ui.screens.XraySetupScreen
import com.wireturn.app.ui.theme.WireturnTheme
import com.wireturn.app.viewmodel.MainViewModel

class XraySetupActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        splashScreen.setKeepOnScreenCondition { !viewModel.isInitialized.value }

        val showProtocolSelection = intent.getBooleanExtra("SHOW_PROTOCOL_SELECTION", true)
        val defaultProtocolName = intent.getStringExtra("EXTRA_DEFAULT_PROTOCOL")
        val defaultProtocol = if (defaultProtocolName != null) {
            try { com.wireturn.app.data.XrayConfiguration.valueOf(defaultProtocolName) } catch (_: Exception) { null }
        } else null

        val profileName = intent.getStringExtra("EXTRA_PROFILE_NAME") ?: getString(R.string.profile_default_name)
        val clientConfigFromIntent = when (intent.getStringExtra("EXTRA_KERNEL_VARIANT")) {
            KernelVariant.OLCRTC.name -> {
                val json = intent.getStringExtra("EXTRA_OLCRTC_CONFIG_JSON")
                val olcrtc = if (json != null) Gson().fromJson(json, OlcrtcConfig::class.java) ?: OlcrtcConfig() else OlcrtcConfig()
                ClientConfig(kernelConfig = KernelConfig.Olcrtc(olcrtc))
            }
            KernelVariant.WEBDAV.name -> {
                val json = intent.getStringExtra("EXTRA_WEBDAV_CONFIG_JSON")
                val webdav = if (json != null) Gson().fromJson(json, WebdavConfig::class.java) ?: WebdavConfig() else WebdavConfig()
                ClientConfig(kernelConfig = KernelConfig.Webdav(webdav))
            }
            else -> {
                val json = intent.getStringExtra("EXTRA_TURNABLE_CONFIG_JSON")
                val turnable = if (json != null) Gson().fromJson(json, TurnableConfig::class.java) ?: TurnableConfig() else TurnableConfig()
                ClientConfig(kernelConfig = KernelConfig.Turnable(turnable))
            }
        }

        setContent {
            val isInitialized by viewModel.isInitialized.collectAsStateWithLifecycle()
            if (!isInitialized) return@setContent

            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val dynamicTheme by viewModel.dynamicTheme.collectAsStateWithLifecycle()
            val privacyMode by viewModel.privacyMode.collectAsStateWithLifecycle()

            val savedXrayConfig by viewModel.xrayConfig.collectAsStateWithLifecycle()
            val vlessLinkHistory by viewModel.vlessLinkHistory.collectAsStateWithLifecycle()

            WireturnTheme(themeMode = themeMode, dynamicColor = dynamicTheme) {
                XraySetupScreen(
                    isEditMode = false,
                    showProtocolSelection = showProtocolSelection,
                    defaultProtocol = defaultProtocol,
                    privacyMode = privacyMode,
                    kernelVariant = clientConfigFromIntent.kernelVariant,
                    vlessLinkHistory = vlessLinkHistory,
                    onRemoveHistoryItem = { viewModel.removeVlessLinkFromHistory(it) },
                    onBack = { finish() },
                    onSave = { type, wg, vless ->
                        viewModel.addFullProfile(
                            name = profileName,
                            clientConfig = clientConfigFromIntent,
                            xrayConfig = savedXrayConfig.copy(protocol = type),
                            wgConfig = wg,
                            vlessConfig = vless
                        )
                        // Close all creation activities
                        val intent = android.content.Intent(this, MainActivity::class.java)
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        startActivity(intent)
                    }
                )
            }
        }
    }
}
