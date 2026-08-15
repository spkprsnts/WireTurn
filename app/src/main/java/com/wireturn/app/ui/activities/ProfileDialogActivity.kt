package com.wireturn.app.ui.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.ui.screens.ProfilesDialog
import com.wireturn.app.ui.theme.WireturnTheme
import com.wireturn.app.viewmodel.MainViewModel

class ProfileDialogActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Make activity window transparent
        window.setBackgroundDrawableResource(android.R.color.transparent)

        setContent {
            val isInitialized by viewModel.isInitialized.collectAsStateWithLifecycle()
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val dynamicTheme by viewModel.dynamicTheme.collectAsStateWithLifecycle()

            if (isInitialized) {
                WireturnTheme(themeMode = themeMode, dynamicColor = dynamicTheme) {
                    ProfilesDialog(
                        viewModel = viewModel,
                        onDismiss = { finish() }
                    )
                }
            }
        }
    }
}
