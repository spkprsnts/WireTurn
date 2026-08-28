@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui.navigation

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.wireturn.app.ui.CoreTriggerController
import com.wireturn.app.ui.TileVpnConsentBus
import com.wireturn.app.ui.activities.AppExceptionsActivity
import com.wireturn.app.ui.screens.HomeScreen
import com.wireturn.app.viewmodel.MainViewModel

@Composable
fun AppNavigation(
    viewModel: MainViewModel
) {
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
            CoreTriggerController(viewModel = viewModel) { onToggle ->
                LaunchedEffect(onToggle) {
                    TileVpnConsentBus.pending.collect { onToggle() }
                }
                HomeScreen(
                    viewModel = viewModel,
                    onNavigateToExclusions = { 
                        context.startActivity(Intent(context, AppExceptionsActivity::class.java))
                    },
                    onNavigateToXrayConfig = {
                        val intent = Intent(context, com.wireturn.app.ui.activities.XrayEditActivity::class.java)
                        intent.putExtra("EXTRA_PROFILE_ID", viewModel.currentProfileId.value)
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
                    onToggleProxy = onToggle
                )
            }
        }
    }
}
