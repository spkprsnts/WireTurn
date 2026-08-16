@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui.activities

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.R
import com.wireturn.app.domain.ImportStatus
import com.wireturn.app.domain.isLocalNetworkHost
import com.wireturn.app.ui.AppTopAppBar
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.ItemPosition
import com.wireturn.app.ui.LabelGroup
import com.wireturn.app.ui.SectionGroup
import com.wireturn.app.ui.SectionItem
import com.wireturn.app.ui.StandardLeadingIcon
import com.wireturn.app.ui.activities.cores.FreeTurnConfigActivity
import com.wireturn.app.ui.activities.cores.OlcRtcConfigActivity
import com.wireturn.app.ui.activities.cores.TurnableConfigActivity
import com.wireturn.app.ui.activities.cores.WebdavConfigActivity
import com.wireturn.app.ui.screens.ProfileNameDialog
import com.wireturn.app.ui.screens.QrScannerDialog
import com.wireturn.app.ui.showExclusiveToast
import com.wireturn.app.ui.theme.WireturnTheme
import com.wireturn.app.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val ACCESS_LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

class AddProfileActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val dynamicTheme by viewModel.dynamicTheme.collectAsStateWithLifecycle()
            val scope = rememberCoroutineScope()
            val clipboard = LocalClipboard.current
            val context = androidx.compose.ui.platform.LocalContext.current
            
            var isImporting by remember { mutableStateOf(false) }
            val showQrScanner = remember { mutableStateOf(false) }
            val detectedKernelConfig = remember { mutableStateOf<ImportStatus.KernelConfigDetected?>(null) }
            
            val errorNoLink = stringResource(R.string.import_error_no_link)
            val errorInvalidProfile = stringResource(R.string.import_error_invalid_profile)
            val errorConnection = stringResource(R.string.import_error_connection)
            val errorEmpty = stringResource(R.string.import_error_empty)
            val scrollState = rememberScrollState()

            fun handleImportResult(status: ImportStatus) {
                when (status) {
                    is ImportStatus.Success -> {
                        status.id?.let { com.wireturn.app.ui.UIEventBus.requestScroll(it) }
                        finish()
                    }
                    is ImportStatus.KernelConfigDetected -> {
                        HapticUtil.perform(this@AddProfileActivity, HapticUtil.Pattern.SUCCESS)
                        detectedKernelConfig.value = status
                    }
                    is ImportStatus.NetworkError -> {
                        HapticUtil.perform(this@AddProfileActivity, HapticUtil.Pattern.ERROR)
                        this@AddProfileActivity.showExclusiveToast(errorConnection)
                    }
                    is ImportStatus.ServerError -> {
                        HapticUtil.perform(this@AddProfileActivity, HapticUtil.Pattern.ERROR)
                        val msg = this@AddProfileActivity.getString(R.string.import_error_server, status.code)
                        this@AddProfileActivity.showExclusiveToast(msg)
                    }
                    is ImportStatus.EmptyResponse -> {
                        HapticUtil.perform(this@AddProfileActivity, HapticUtil.Pattern.ERROR)
                        this@AddProfileActivity.showExclusiveToast(errorEmpty)
                    }
                    is ImportStatus.InvalidFormat -> {
                        HapticUtil.perform(this@AddProfileActivity, HapticUtil.Pattern.ERROR)
                        this@AddProfileActivity.showExclusiveToast(errorInvalidProfile)
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
                            viewModel.smartImport(link) 
                        } catch (_: Exception) { 
                            ImportStatus.NetworkError 
                        }
                        isImporting = false
                        handleImportResult(status)
                    }
                }
            }

            fun performSmartImport(text: String) {
                if (text.isBlank()) {
                    HapticUtil.perform(this@AddProfileActivity, HapticUtil.Pattern.ERROR)
                    context.showExclusiveToast(errorNoLink)
                    return
                }

                val needsLocalNetworkPermission = Build.VERSION.SDK_INT >= 37 &&
                        isLocalNetworkHost(text) &&
                        ContextCompat.checkSelfPermission(
                            this@AddProfileActivity,
                            ACCESS_LOCAL_NETWORK_PERMISSION
                        ) != PackageManager.PERMISSION_GRANTED

                if (needsLocalNetworkPermission) {
                    pendingLinkImport = text
                    localNetworkPermissionLauncher.launch(ACCESS_LOCAL_NETWORK_PERMISSION)
                    return
                }

                isImporting = true
                scope.launch {
                    val status = try {
                        viewModel.smartImport(text)
                    } catch (_: Exception) {
                        ImportStatus.NetworkError
                    }
                    isImporting = false
                    handleImportResult(status)
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
                    var filesFailed = 0
                    uris.forEach { uri ->
                        val importedBefore = totalImported
                        try {
                            val fileName = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (index != -1 && cursor.moveToFirst()) cursor.getString(index) else null
                            } ?: uri.lastPathSegment

                            if (fileName?.endsWith(".zip", ignoreCase = true) == true) {
                                contentResolver.openInputStream(uri)?.use { stream ->
                                    totalImported += viewModel.importProfilesFromZip(stream).total
                                }
                            } else {
                                val json = contentResolver.openInputStream(uri)?.use { stream ->
                                    stream.bufferedReader().readText()
                                }
                                if (json != null) {
                                    totalImported += viewModel.importProfiles(listOf(fileName to json)).total
                                }
                            }
                        } catch (_: Exception) {}
                        if (totalImported == importedBefore) filesFailed++
                    }

                    launch(Dispatchers.Main) {
                        when {
                            totalImported > 0 && filesFailed == 0 -> finish()
                            totalImported > 0 -> {
                                HapticUtil.perform(this@AddProfileActivity, HapticUtil.Pattern.ERROR)
                                context.showExclusiveToast(
                                    getString(R.string.profile_import_partial_failure, filesFailed, uris.size),
                                    Toast.LENGTH_LONG
                                )
                                finish()
                            }
                            else -> {
                                HapticUtil.perform(this@AddProfileActivity, HapticUtil.Pattern.ERROR)
                                context.showExclusiveToast(errorInvalidProfile)
                            }
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
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                    showQrScanner.value = true
                                }
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    StandardLeadingIcon {
                                        Icon(
                                            painterResource(R.drawable.qr_code_24px),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    LabelGroup(
                                        label = stringResource(R.string.profile_import_qr),
                                        supportingText = stringResource(R.string.profile_import_qr_hint)
                                    )
                                }
                            }

                            SectionItem(
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
                                    LabelGroup(
                                        label = stringResource(R.string.profile_import_json_zip),
                                        supportingText = stringResource(R.string.profile_import_file_hint)
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
                                        performSmartImport(rawText.trim())
                                    }
                                }
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    StandardLeadingIcon {
                                        if (isImporting) {
                                            androidx.compose.material3.LoadingIndicator(
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .graphicsLayer(scaleX = 1.7f, scaleY = 1.7f)
                                            )
                                        } else {
                                            Icon(
                                                painterResource(R.drawable.content_paste_24px),
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    LabelGroup(
                                        label = stringResource(R.string.profile_import_link),
                                        supportingText = stringResource(R.string.profile_import_link_hint)
                                    )
                                }
                            }
                        }
                        SectionGroup(title = stringResource(R.string.profile_create_title)) {
                            SectionItem(
                                position = ItemPosition.Single,
                                onClick = {
                                    HapticUtil.perform(this@AddProfileActivity, HapticUtil.Pattern.CLICK)
                                    startActivity(Intent(this@AddProfileActivity, CreateProfileActivity::class.java))
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
                                    LabelGroup(
                                        label = stringResource(R.string.profile_create),
                                        supportingText = stringResource(R.string.profile_create_hint)
                                    )
                                }
                            }
                        }
                    }
                }

                if (showQrScanner.value) {
                    QrScannerDialog(
                        title = stringResource(R.string.qr_import),
                        message = stringResource(R.string.qr_scan_desc),
                        onDismiss = { showQrScanner.value = false },
                        onResult = { result ->
                            showQrScanner.value = false
                            performSmartImport(result)
                        }
                    )
                }

                detectedKernelConfig.value?.let { status ->
                    // Try to extract name from source if possible
                    val source = status.source
                    val uriFragment = try { source.toUri().fragment } catch (_: Exception) { null }
                    val olcrtcMimo = if (source.startsWith("olcrtc://") && source.contains("$")) source.substringAfterLast("$") else null
                    
                    val initialNameFromSource = if (status.type == "WebDAV") uriFragment
                    else if (status.type == "olcRTC") olcrtcMimo
                    else if (status.type == "FreeTurn") uriFragment
                    else null

                    val initialName = if (!initialNameFromSource.isNullOrBlank()) {
                        initialNameFromSource
                    } else {
                        viewModel.nextDefaultProfileName()
                    }

                    ProfileNameDialog(
                        title = stringResource(R.string.profile_import_name_title),
                        initialName = initialName,
                        onDismiss = { detectedKernelConfig.value = null },
                        onConfirm = { name ->
                            val intent = when (status.type) {
                                "Turnable" -> Intent(this@AddProfileActivity, TurnableConfigActivity::class.java)
                                "olcRTC" -> Intent(this@AddProfileActivity, OlcRtcConfigActivity::class.java)
                                "WebDAV" -> Intent(this@AddProfileActivity, WebdavConfigActivity::class.java)
                                "FreeTurn" -> Intent(this@AddProfileActivity, FreeTurnConfigActivity::class.java)
                                else -> null
                            }
                            intent?.let {
                                it.putExtra("EXTRA_PROFILE_NAME", name)
                                it.putExtra("EXTRA_CONFIG_JSON", status.json)
                                startActivity(it)
                            }
                            detectedKernelConfig.value = null
                        }
                    )
                }
            }
        }
    }
}
