package com.wireturn.app.ui.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.ui.activities.cores.OlcRtcConfigActivity
import com.wireturn.app.ui.activities.cores.TurnableConfigActivity
import com.wireturn.app.ui.activities.cores.WebdavConfigActivity
import com.wireturn.app.ui.screens.CreateProfileScreen
import com.wireturn.app.ui.theme.WireturnTheme
import com.wireturn.app.viewmodel.MainViewModel

class CreateProfileActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        splashScreen.setKeepOnScreenCondition { !viewModel.isInitialized.value }

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val dynamicTheme by viewModel.dynamicTheme.collectAsStateWithLifecycle()

            WireturnTheme(themeMode = themeMode, dynamicColor = dynamicTheme) {
                CreateProfileScreen(
                    onBack = { finish() },
                    onSelectType = { type, configJson, name ->
                        val intent = when (type) {
                            "Turnable" -> android.content.Intent(this, TurnableConfigActivity::class.java)
                            "olcRTC" -> android.content.Intent(this, OlcRtcConfigActivity::class.java)
                            "WebDAV" -> android.content.Intent(this, WebdavConfigActivity::class.java)
                            else -> null
                        }
                        intent?.let {
                            it.putExtra("EXTRA_PROFILE_NAME", name)
                            if (configJson != null) it.putExtra("EXTRA_CONFIG_JSON", configJson)
                            startActivity(it)
                        }
                    }
                )
            }
        }
    }
}
