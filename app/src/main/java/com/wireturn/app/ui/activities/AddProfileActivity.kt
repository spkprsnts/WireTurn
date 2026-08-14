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
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.R
import com.wireturn.app.ui.AppTopAppBar
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.ItemPosition
import com.wireturn.app.ui.SectionGroup
import com.wireturn.app.ui.SectionItem
import com.wireturn.app.ui.StandardLeadingIcon
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

            val profileImportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenMultipleDocuments()
            ) { uris ->
                if (uris.isEmpty()) return@rememberLauncherForActivityResult
                
                scope.launch(Dispatchers.IO) {
                    val jsonFiles = mutableListOf<Pair<String?, String>>()
                    uris.forEach { uri ->
                        try {
                            val fileName = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (index != -1 && cursor.moveToFirst()) cursor.getString(index) else null
                            } ?: uri.lastPathSegment

                            if (fileName?.endsWith(".zip", ignoreCase = true) == true) {
                                contentResolver.openInputStream(uri)?.use { stream ->
                                    viewModel.importProfilesFromZip(stream)
                                }
                            } else {
                                val json = contentResolver.openInputStream(uri)?.use { stream ->
                                    stream.bufferedReader().readText()
                                }
                                if (json != null) jsonFiles.add(fileName to json)
                            }
                        } catch (_: Exception) {}
                    }
                    if (jsonFiles.isNotEmpty()) {
                        viewModel.importProfiles(jsonFiles)
                    }
                    launch(Dispatchers.Main) {
                        finish()
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
                    }
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(19.dp)
                    ) {
                        SectionGroup {
                            SectionItem(
                                position = ItemPosition.Single,
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
                                        text = stringResource(R.string.profile_import),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                        SectionGroup {
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
