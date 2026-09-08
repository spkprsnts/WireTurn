package com.wireturn.app.ui.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.ui.screens.XraySettingsScreen
import com.wireturn.app.ui.theme.WireturnTheme
import com.wireturn.app.viewmodel.MainViewModel

class XraySettingsActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        splashScreen.setKeepOnScreenCondition { !viewModel.isInitialized.value }

        setContent {
            val isInitialized by viewModel.isInitialized.collectAsStateWithLifecycle()
            if (!isInitialized) return@setContent

            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val dynamicTheme by viewModel.dynamicTheme.collectAsStateWithLifecycle()

            val xraySettings by viewModel.xraySettings.collectAsStateWithLifecycle()
            val privacyMode by viewModel.privacyMode.collectAsStateWithLifecycle()
            val geoAssetsState by viewModel.geoAssetsState.collectAsStateWithLifecycle()
            val geoAssetsProgress by viewModel.geoAssetsProgress.collectAsStateWithLifecycle()
            val geoAssetsInstalledTick by viewModel.geoAssetsInstalledTick.collectAsStateWithLifecycle()
            // Reading the tick alone doesn't help unless something derived from it is actually
            // captured by the WireturnTheme content lambda below - otherwise Compose sees that
            // lambda's captures as unchanged and skips re-invoking it, so geoAssetsInstalled()/
            // geoAssetsInstalledAt() never get re-read after a download or delete completes.
            val geoAssetsInstalled = geoAssetsInstalledTick.let { viewModel.geoAssetsInstalled() }
            val geoAssetsInstalledAt = geoAssetsInstalledTick.let { viewModel.geoAssetsInstalledAt() }

            WireturnTheme(themeMode = themeMode, dynamicColor = dynamicTheme) {
                XraySettingsScreen(
                    initialXraySettings = xraySettings,
                    privacyMode = privacyMode,
                    geoAssetsState = geoAssetsState,
                    geoAssetsProgress = geoAssetsProgress,
                    geoAssetsInstalled = geoAssetsInstalled,
                    geoAssetsInstalledAt = geoAssetsInstalledAt,
                    onDownloadGeoAssets = { viewModel.downloadGeoAssets(it) },
                    onCancelGeoAssetsDownload = { viewModel.cancelGeoAssetsDownload() },
                    onDeleteGeoAssets = { viewModel.deleteGeoAssets() },
                    onBack = { finish() },
                    onSave = { xray ->
                        viewModel.updateXraySettings(xray)
                        finish()
                    }
                )
            }
        }
    }
}
