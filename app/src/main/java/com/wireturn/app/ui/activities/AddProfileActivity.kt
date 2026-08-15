package com.wireturn.app.ui.activities

import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val ACCESS_LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

/** Android 17+ blocks TCP to LAN/loopback addresses without runtime permission; detect that case. */
private fun isLocalNetworkHost(url: String): Boolean {
    val host = try { java.net.URI(url).host } catch (_: Exception) { null } ?: return false
    if (host.equals("localhost", ignoreCase = true)) return true
    val octets = host.split(".")
    if (octets.size != 4) return false
    val nums = octets.map { it.toIntOrNull() ?: return false }
    if (nums.any { it !in 0..255 }) return false
    val (a, b) = nums
    return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
}

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
            val context = androidx.compose.ui.platform.LocalContext.current
            val snackbarHostState = remember { SnackbarHostState() }
            var isImporting by remember { mutableStateOf(false) }
            val errorNoLink = stringResource(R.string.import_error_no_link)
            val errorInvalidProfile = stringResource(R.string.import_error_invalid_profile)
            val errorConnection = stringResource(R.string.import_error_connection)
            val errorEmpty = stringResource(R.string.import_error_empty)
            val scrollState = rememberScrollState()

            fun handleImportResult(status: com.wireturn.app.domain.ImportStatus) {
                when (status) {
                    com.wireturn.app.domain.ImportStatus.Success -> finish()
                    com.wireturn.app.domain.ImportStatus.NetworkError -> {
                        HapticUtil.perform(this@AddProfileActivity, HapticUtil.Pattern.ERROR)
                        scope.launch { snackbarHostState.showExclusiveSnackbar(errorConnection) }
                    }
                    is com.wireturn.app.domain.ImportStatus.ServerError -> {
                        HapticUtil.perform(this@AddProfileActivity, HapticUtil.Pattern.ERROR)
                        val msg = context.getString(R.string.import_error_server, status.code)
                        scope.launch { snackbarHostState.showExclusiveSnackbar(msg) }
                    }
                    com.wireturn.app.domain.ImportStatus.EmptyResponse -> {
                        HapticUtil.perform(this@AddProfileActivity, HapticUtil.Pattern.ERROR)
                        scope.launch { snackbarHostState.showExclusiveSnackbar(errorEmpty) }
                    }
                    com.wireturn.app.domain.ImportStatus.InvalidFormat -> {
                        HapticUtil.perform(this@AddProfileActivity, HapticUtil.Pattern.ERROR)
                        scope.launch { snackbarHostState.showExclusiveSnackbar(errorInvalidProfile) }
                    }
                }
            }

            var pendingLinkImport by remember { mutableStateOf<String?>(null) }
            val localNetworkPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { _ ->
                val link = pendingLinkImport
                pendingLinkImport = null
                if (link != null) {
                    isImporting = true
                    scope.launch {
                        val status = try { 
                            viewModel.importProfileFromLink(link) 
                        } catch (e: Exception) { 
                            com.wireturn.app.domain.ImportStatus.NetworkError 
                        }
                        isImporting = false
                        handleImportResult(status)
                    }
                }
            }

            val topAppBarState = rememberTopAppBarState()
            val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
                state = topAppBarState,
                flingAnimationSpec = null
            )

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
                    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                    topBar = {
                        AppTopAppBar(
                            title = stringResource(R.string.profile_add),
                            onBack = { finish() },
                            scrollBehavior = scrollBehavior
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
                            .verticalScroll(scrollState)
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
                                enabled = !isImporting,
                                onClick = {
                                    HapticUtil.perform(this@AddProfileActivity, HapticUtil.Pattern.CLICK)
                                    scope.launch {
                                        val clipEntry = clipboard.getClipEntry()
                                        val rawText = clipEntry?.clipData?.getItemAt(0)?.text?.toString() ?: ""
                                        val text = rawText.trim()
                                        
                                        val isValidLink = text.startsWith("wireturn://") || 
                                                        text.startsWith("wt://") || 
                                                        text.startsWith("http://") || 
                                                        text.startsWith("https://")
                                        
                                        if (isValidLink) {
                                            val needsLocalNetworkPermission = Build.VERSION.SDK_INT >= 37 &&
                                                isLocalNetworkHost(text) &&
                                                ContextCompat.checkSelfPermission(
                                                    this@AddProfileActivity,
                                                    ACCESS_LOCAL_NETWORK_PERMISSION
                                                ) != PackageManager.PERMISSION_GRANTED

                                            if (needsLocalNetworkPermission) {
                                                pendingLinkImport = text
                                                localNetworkPermissionLauncher.launch(ACCESS_LOCAL_NETWORK_PERMISSION)
                                                return@launch
                                            }

                                            isImporting = true
                                            val status = try {
                                                viewModel.importProfileFromLink(text)
                                            } catch (e: Exception) {
                                                com.wireturn.app.domain.ImportStatus.NetworkError
                                            }
                                            isImporting = false
                                            handleImportResult(status)
                                        } else {
                                            HapticUtil.perform(this@AddProfileActivity, HapticUtil.Pattern.ERROR)
                                            snackbarHostState.showExclusiveSnackbar(errorNoLink)
                                        }
                                    }
                                }
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    StandardLeadingIcon {
                                        if (isImporting) {
                                            androidx.compose.material3.CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        } else {
                                            Icon(
                                                painterResource(R.drawable.content_paste_24px),
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    Text(
                                        text = stringResource(R.string.profile_import_link),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isImporting) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) 
                                                else MaterialTheme.colorScheme.onSurface
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
