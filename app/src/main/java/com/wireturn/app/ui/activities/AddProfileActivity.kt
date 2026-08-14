package com.wireturn.app.ui.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.R
import com.wireturn.app.ui.AppSnackbar
import com.wireturn.app.ui.AppTopAppBar
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.ItemPosition
import com.wireturn.app.ui.SectionGroup
import com.wireturn.app.ui.SectionItem
import com.wireturn.app.ui.StandardLeadingIcon
import com.wireturn.app.ui.TextFieldRow
import com.wireturn.app.ui.showExclusiveSnackbar
import com.wireturn.app.ui.theme.WireturnTheme
import com.wireturn.app.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AddProfileActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val dynamicTheme by viewModel.dynamicTheme.collectAsStateWithLifecycle()
            val scope = rememberCoroutineScope()
            val clipboard = LocalClipboard.current
            val snackbarHostState = remember { SnackbarHostState() }
            val errorNoLink = stringResource(R.string.import_error_no_link)
            val errorInvalidProfile = stringResource(R.string.import_error_invalid_profile)

            val profileImportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenMultipleDocuments()
            ) { uris ->
                if (uris.isEmpty()) return@rememberLauncherForActivityResult
                
                scope.launch(Dispatchers.IO) {
                    var totalImported = 0
                    uris.forEach { uri ->
                        try {
                            val fileName = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (index != -1 && cursor.moveToFirst()) cursor.getString(index) else null
                            } ?: uri.lastPathSegment

                            if (fileName?.endsWith(".zip", ignoreCase = true) == true) {
                                contentResolver.openInputStream(uri)?.use { stream ->
                                    totalImported += viewModel.importProfilesFromZip(stream)
                                }
                            } else {
                                val json = contentResolver.openInputStream(uri)?.use { stream ->
                                    stream.bufferedReader().readText()
                                }
                                if (json != null) {
                                    totalImported += viewModel.importProfiles(listOf(fileName to json))
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    
                    launch(Dispatchers.Main) {
                        if (totalImported > 0) {
                            finish()
                        } else {
                            HapticUtil.perform(this@AddProfileActivity, HapticUtil.Pattern.ERROR)
                            snackbarHostState.showExclusiveSnackbar(errorInvalidProfile)
                        }
                    }
                }
            }

            WireturnTheme(themeMode = themeMode, dynamicColor = dynamicTheme) {
                Scaffold(
                    topBar = {
                        AppTopAppBar(
                            title = stringResource(R.string.profile_add),
                            onBack = { finish() }
                        )
                    },
                    snackbarHost = {
                        SnackbarHost(hostState = snackbarHostState) { data ->
                            AppSnackbar(data)
                        }
                    }
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(19.dp)
                    ) {
                        SectionGroup(title = stringResource(R.string.profile_import_title)) {
                            SectionItem(
                                position = ItemPosition.Top,
                                onClick = {
                                    HapticUtil.perform(
                                        this@AddProfileActivity,
                                        HapticUtil.Pattern.CLICK
                                    )
                                    profileImportLauncher.launch(
                                        arrayOf(
                                            "application/json",
                                            "application/zip"
                                        )
                                    )
                                }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    StandardLeadingIcon {
                                        Icon(
                                            painterResource(R.drawable.file_open_24px),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.profile_import_json_zip),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                            SectionItem(
                                position = ItemPosition.Bottom,
                                onClick = {
                                    HapticUtil.perform(this@AddProfileActivity, HapticUtil.Pattern.CLICK)
                                    scope.launch {
                                        val clipEntry = clipboard.getClipEntry()
                                        val text = clipEntry?.clipData?.getItemAt(0)?.text?.toString() ?: ""
                                        if (text.startsWith("wireturn://") || text.startsWith("wt://")) {
                                            if (viewModel.importProfileFromLink(text)) {
                                                finish()
                                            } else {
                                                HapticUtil.perform(this@AddProfileActivity, HapticUtil.Pattern.ERROR)
                                                snackbarHostState.showExclusiveSnackbar(errorInvalidProfile)
                                            }
                                        } else {
                                            HapticUtil.perform(this@AddProfileActivity, HapticUtil.Pattern.ERROR)
                                            scope.launch {
                                                snackbarHostState.showExclusiveSnackbar(errorNoLink)
                                            }
                                        }
                                    }
                                }
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    StandardLeadingIcon {
                                        Icon(
                                            painterResource(R.drawable.content_paste_24px),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.profile_import_link),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                        SectionGroup(title = stringResource(R.string.profile_create_title)) {
                            SectionItem(
                                position = ItemPosition.Single,
                                onClick = {
                                    HapticUtil.perform(this@AddProfileActivity, HapticUtil.Pattern.CLICK)
                                    startActivity(android.content.Intent(this@AddProfileActivity, CreateProfileActivity::class.java))
                                    finish()
                                }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    StandardLeadingIcon {
                                        Icon(
                                            painterResource(R.drawable.add_24px),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.profile_create),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
