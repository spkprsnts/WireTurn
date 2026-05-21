package com.wireturn.app.ui.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.ui.screens.OnboardingScreen
import com.wireturn.app.ui.theme.WireturnTheme
import com.wireturn.app.viewmodel.MainViewModel

class OnboardingActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val dynamicTheme by viewModel.dynamicTheme.collectAsStateWithLifecycle()

            WireturnTheme(themeMode = themeMode, dynamicColor = dynamicTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OnboardingScreen(
                        modifier = Modifier.statusBarsPadding(),
                        onSkip = {
                            viewModel.setOnboardingDone()
                            startActivity(Intent(this@OnboardingActivity, MainActivity::class.java))
                            startActivity(Intent(this@OnboardingActivity, CreateProfileActivity::class.java))
                            finish()
                        }
                    )
                }
            }
        }
    }
}
