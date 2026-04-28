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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
            val scope = rememberCoroutineScope()

            WireturnTheme(themeMode = themeMode, dynamicColor = dynamicTheme) {
                val profileImportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenMultipleDocuments()
                ) { uris ->
                    if (uris.isEmpty()) return@rememberLauncherForActivityResult
                    
                    scope.launch(Dispatchers.IO) {
                        val jsonFiles = mutableListOf<Pair<String?, String>>()
                        uris.forEach { uri ->
                            try {
                                val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                    if (index != -1 && cursor.moveToFirst()) cursor.getString(index) else null
                                } ?: uri.lastPathSegment

                                if (fileName?.endsWith(".zip", ignoreCase = true) == true) {
                                    context.contentResolver.openInputStream(uri)?.use { stream ->
                                        viewModel.importProfilesFromZip(stream)
                                    }
                                } else {
                                    val json = context.contentResolver.openInputStream(uri)?.use { stream ->
                                        stream.bufferedReader().readText()
                                    }
                                    if (json != null) jsonFiles.add(fileName to json)
                                }
                            } catch (_: Exception) {}
                        }
                        if (jsonFiles.isNotEmpty()) {
                            viewModel.importProfiles(jsonFiles)
                        }
                    }
                }

                ProfilesDialog(
                    viewModel = viewModel,
                    onImport = { profileImportLauncher.launch(arrayOf("application/json", "application/zip")) },
                    onDismiss = { finish() }
                )
            }
        }
    }
}
