package com.wireturn.app.ui.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.gson.Gson
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.XrayConfig
import com.wireturn.app.ui.screens.XraySetupScreen
import com.wireturn.app.ui.theme.WireturnTheme
import com.wireturn.app.viewmodel.MainViewModel

class XraySetupActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val showProtocolSelection = intent.getBooleanExtra("SHOW_PROTOCOL_SELECTION", true)
        val defaultProtocolName = intent.getStringExtra("EXTRA_DEFAULT_PROTOCOL")
        val defaultProtocol = if (defaultProtocolName != null) {
            try { com.wireturn.app.data.XrayConfiguration.valueOf(defaultProtocolName) } catch (_: Exception) { null }
        } else null

        val profileName = intent.getStringExtra("EXTRA_PROFILE_NAME") ?: "New Profile"
        val clientConfigJson = intent.getStringExtra("EXTRA_CLIENT_CONFIG_JSON")
        val clientConfig = if (clientConfigJson != null) {
            try { Gson().fromJson(clientConfigJson, ClientConfig::class.java) } catch (_: Exception) { ClientConfig() }
        } else {
            ClientConfig()
        }

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val dynamicTheme by viewModel.dynamicTheme.collectAsStateWithLifecycle()

            WireturnTheme(themeMode = themeMode, dynamicColor = dynamicTheme) {
                XraySetupScreen(
                    showProtocolSelection = showProtocolSelection,
                    defaultProtocol = defaultProtocol,
                    onBack = { finish() },
                    onSave = { type, wg, vless ->
                        viewModel.addFullProfile(
                            name = profileName,
                            clientConfig = clientConfig,
                            xrayConfig = XrayConfig(xrayConfiguration = type),
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
