@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import com.wireturn.app.ui.activities.AppExceptionsActivity
import com.wireturn.app.ui.ProxyTriggerController
import com.wireturn.app.ui.screens.CaptchaWebViewDialog
import com.wireturn.app.ui.screens.HomeScreen
import com.wireturn.app.viewmodel.ProxyState
import com.wireturn.app.viewmodel.MainViewModel

@Composable
fun AppNavigation(
    viewModel: MainViewModel
) {
    val proxyState by viewModel.proxyState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ProxyTriggerController(viewModel = viewModel) { onToggle, onCheckMismatch ->
                HomeScreen(
                    viewModel = viewModel,
                    onNavigateToExclusions = { 
                        context.startActivity(Intent(context, AppExceptionsActivity::class.java))
                    },
                    onNavigateToXrayConfig = {
                        val intent = Intent(context, com.wireturn.app.ui.activities.XrayEditActivity::class.java)
                        context.startActivity(intent)
                    },
                    onNavigateToConnectionSettings = {
                        context.startActivity(Intent(context, com.wireturn.app.ui.activities.ConnectionSettingsActivity::class.java))
                    },
                    onNavigateToSettings = {
                        context.startActivity(Intent(context, com.wireturn.app.ui.activities.SettingsActivity::class.java))
                    },
                    onNavigateToLogs = {
                        context.startActivity(Intent(context, com.wireturn.app.ui.activities.LogsActivity::class.java))
                    },
                    onToggleProxy = onToggle,
                    onCheckMismatch = onCheckMismatch
                )
            }
        }
    }

    val captchaState = proxyState as? ProxyState.CaptchaRequired
    val showCaptcha = remember(captchaState?.sessionId) {
        mutableStateOf(captchaState != null)
    }

    if (showCaptcha.value && captchaState != null) {
        key(captchaState.sessionId) {
            CaptchaWebViewDialog(
                viewModel = viewModel,
                captchaUrl = captchaState.url,
                onDismiss = { viewModel.dismissCaptcha() },
                onSuccess = { showCaptcha.value = false }
            )
        }
    }
}
