package com.wireturn.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.data.Profile
import com.wireturn.app.domain.ProfileManager
import com.wireturn.app.ui.theme.WireturnTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class ProfileDialogActivity : AppCompatActivity() {
    private lateinit var prefs: AppPreferences
    private lateinit var profileManager: ProfileManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPreferences(this)
        profileManager = ProfileManager(prefs, scope)

        setContent {
            WireturnTheme {
                val profiles by profileManager.profiles.collectAsStateWithLifecycle()
                val currentId by profileManager.currentProfileId.collectAsStateWithLifecycle()

                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text(stringResource(R.string.profiles_title)) },
                    text = {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(profiles) { profile ->
                                ProfileItem(
                                    profile = profile,
                                    isSelected = profile.id == currentId,
                                    onClick = {
                                        selectAndCheckRestart(profile.id)
                                    }
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { finish() }) {
                            Text(stringResource(android.R.string.cancel))
                        }
                    }
                )
            }
        }
    }

    @Composable
    private fun ProfileItem(profile: Profile, isSelected: Boolean, onClick: () -> Unit) {
        Surface(
            onClick = onClick,
            shape = MaterialTheme.shapes.medium,
            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    private fun selectAndCheckRestart(id: String) {
        val profileName = profileManager.profiles.value.find { it.id == id }?.name
        profileManager.selectProfile(id) { _, _, _, _, _ ->
            scope.launch {
                // Wait for prefs to be saved
                delay(200)
                checkAndRestartIfNeeded()
                // Update name in case proxy wasn't restarted
                if (ProxyServiceState.isRunning.value) {
                    ProxyServiceState.setRunningProfileName(profileName)
                }
                finish()
            }
        }
    }

    private suspend fun checkAndRestartIfNeeded() {
        val runningConfig = ProxyServiceState.runningConfig.value
        val clientConfig = prefs.clientConfigFlow.first()
        
        if (runningConfig != null && clientConfig != runningConfig) {
            ProxyService.stop(this)
            delay(500)
            ProxyService.start(this, clientConfig)
        }

        val runningWg = XrayServiceState.runningWgConfig.value
        val currentWg = prefs.wgConfigFlow.first()
        val runningVless = XrayServiceState.runningVlessConfig.value
        val currentVless = prefs.vlessConfigFlow.first()
        val runningXray = XrayServiceState.runningXrayConfig.value
        val currentXray = prefs.xrayConfigFlow.first()

        val xraySettings = prefs.xraySettingsFlow.first()

        if ((runningWg != null && currentWg != runningWg) ||
            (runningVless != null && currentVless != runningVless) ||
            (runningXray != null && currentXray != runningXray)) {
            
            stopService(Intent(this, XrayService::class.java))
            delay(500)
            if (xraySettings.xrayEnabled) {
                startForegroundService(Intent(this, XrayService::class.java))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
