package com.wireturn.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
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
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val dynamicTheme by viewModel.dynamicTheme.collectAsStateWithLifecycle()
            val context = LocalContext.current

            WireturnTheme(themeMode = themeMode, dynamicColor = dynamicTheme) {
                val profileImportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenMultipleDocuments()
                ) { uris ->
                    if (uris.isEmpty()) return@rememberLauncherForActivityResult
                    
                    val data = uris.mapNotNull { uri ->
                        try {
                            val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (index != -1 && cursor.moveToFirst()) cursor.getString(index) else null
                            } ?: uri.lastPathSegment

                            val json = context.contentResolver.openInputStream(uri)?.use { stream ->
                                stream.bufferedReader().readText()
                            }
                            if (json != null) fileName to json else null
                        } catch (_: Exception) {
                            null
                        }
                    }
                    if (data.isNotEmpty()) {
                        viewModel.importProfiles(data)
                    }
                }

                ProfilesDialog(
                    viewModel = viewModel,
                    onImport = { profileImportLauncher.launch(arrayOf("application/json")) },
                    onDismiss = { finish() }
                )
            }
        }
    }
}
