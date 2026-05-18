package com.wireturn.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wireturn.app.R
import com.wireturn.app.ui.ConfigRowLabel
import com.wireturn.app.ui.SettingsGroup
import com.wireturn.app.ui.SettingsGroupItem
import com.wireturn.app.ui.StandardLeadingIcon
import com.wireturn.app.ui.TextFieldRow
import com.wireturn.app.data.TurnableConfig
import com.wireturn.app.data.OlcrtcConfig
import com.google.gson.Gson
import com.wireturn.app.ui.HapticUtil
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateProfileScreen(
    onBack: () -> Unit,
    onSelectType: (String, String?, String) -> Unit
) {
    val isDark = com.wireturn.app.ui.theme.LocalIsDark.current
    val blockContainerColor = if (isDark) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface
    val scrollState = rememberScrollState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    
    var profileName by remember { mutableStateOf("") }
    val showQrScanner = remember { mutableStateOf(false) }

    val filePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent(),
        onResult = { uri ->
            uri?.let {
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val text = input.bufferedReader().use { r -> r.readText() }.trim()
                        handleImportText(text, profileName, context, onSelectType)
                    }
                } catch (_: Exception) { }
            }
        }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_create)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back_24px),
                            contentDescription = null
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Profile Name Input
            SettingsGroupItem(
                isTop = true,
                isBottom = true,
                containerColor = blockContainerColor
            ) {
                TextFieldRow(
                    label = stringResource(R.string.profile_name_label),
                    value = profileName,
                    onValueChange = { profileName = it },
                    placeholder = stringResource(R.string.profile_new)
                )
            }

            // Import Group
            SettingsGroup(title = stringResource(R.string.profile_import_group)) {
                SettingsGroupItem(
                    isTop = true,
                    isBottom = false,
                    containerColor = blockContainerColor,
                    onClick = { showQrScanner.value = true }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StandardLeadingIcon {
                            Icon(
                                painter = painterResource(R.drawable.qr_code_24px),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        ConfigRowLabel(text = stringResource(R.string.profile_import_qr))
                    }
                }

                SettingsGroupItem(
                    isTop = false,
                    isBottom = false,
                    containerColor = blockContainerColor,
                    onClick = { 
                        scope.launch {
                            val clipEntry = clipboard.getClipEntry()
                            val text = clipEntry?.clipData?.getItemAt(0)?.text?.toString() ?: ""
                            if (text.isNotBlank()) {
                                handleImportText(text, profileName, context, onSelectType)
                            }
                        }
                    }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StandardLeadingIcon {
                            Icon(
                                painter = painterResource(R.drawable.content_paste_24px),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        ConfigRowLabel(text = stringResource(R.string.profile_import_clipboard))
                    }
                }

                SettingsGroupItem(
                    isTop = false,
                    isBottom = true,
                    containerColor = blockContainerColor,
                    onClick = { filePickerLauncher.launch("*/*") }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StandardLeadingIcon {
                            Icon(
                                painter = painterResource(R.drawable.file_open_24px),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        ConfigRowLabel(text = stringResource(R.string.profile_import_file))
                    }
                }
            }

            // Manual Setup Group
            SettingsGroup(title = stringResource(R.string.profile_manual_setup)) {
                SettingsGroupItem(
                    isTop = true,
                    isBottom = false,
                    containerColor = blockContainerColor,
                    onClick = { onSelectType("Turnable", null, profileName) }
                ) {
                    ConfigRowLabel(text = stringResource(R.string.kernel_turnable))
                }

                SettingsGroupItem(
                    isTop = false,
                    isBottom = true,
                    containerColor = blockContainerColor,
                    onClick = { onSelectType("olcRTC", null, profileName) }
                ) {
                    ConfigRowLabel(text = stringResource(R.string.kernel_olcrtc))
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
                handleImportText(result, profileName, context, onSelectType)
                showQrScanner.value = false
            }
        )
    }
}

private fun handleImportText(
    text: String,
    enteredName: String,
    context: android.content.Context,
    onSelectType: (String, String?, String) -> Unit
) {
    val turnableParsed = TurnableConfig.parse(text)
    val olcrtcParsed = OlcrtcConfig.parse(text)

    if (turnableParsed != null) {
        HapticUtil.perform(context, HapticUtil.Pattern.SUCCESS)
        onSelectType("Turnable", Gson().toJson(turnableParsed), enteredName)
    } else if (olcrtcParsed != null) {
        HapticUtil.perform(context, HapticUtil.Pattern.SUCCESS)
        onSelectType("olcRTC", Gson().toJson(olcrtcParsed), enteredName)
    } else {
        HapticUtil.perform(context, HapticUtil.Pattern.ERROR)
    }
}
