@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)

package com.wireturn.app.ui.screens.cores

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.wireturn.app.R
import com.wireturn.app.data.WebdavConfig
import com.wireturn.app.ui.AppDropdownMenu
import com.wireturn.app.ui.AppSnackbar
import com.wireturn.app.ui.AppTopAppBar
import com.wireturn.app.ui.noFlingExpandConnection
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.ItemPosition
import com.wireturn.app.ui.QrCodeDialog
import com.wireturn.app.ui.SectionGroup
import com.wireturn.app.ui.SectionItem
import com.wireturn.app.ui.SwitchRow
import com.wireturn.app.ui.ShareDropdownMenu
import com.wireturn.app.ui.TextFieldRow
import com.wireturn.app.ui.redact
import com.wireturn.app.ui.screens.QrScannerDialog
import com.wireturn.app.ui.showExclusiveSnackbar
import kotlinx.coroutines.launch

@Composable
fun WebdavConfigScreen(
    isEditMode: Boolean = false,
    initialConfig: WebdavConfig = WebdavConfig(),
    profileName: String? = null,
    privacyMode: Boolean = false,
    onBack: () -> Unit,
    onSave: (WebdavConfig) -> Unit
) {
    val isPrivacyActive = privacyMode && isEditMode
    
    var config by remember(initialConfig) { mutableStateOf(initialConfig) }

    val isModified = config != initialConfig

    val showExitDialog = remember { mutableStateOf(false) }
    val showQrDialog = remember { mutableStateOf(false) }
    val showQrScanner = remember { mutableStateOf(false) }
    val showMenu = remember { mutableStateOf(false) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    val handleBack = {
        if (isEditMode && isModified) {
            showExitDialog.value = true
        } else {
            onBack()
        }
    }

    BackHandler(enabled = isEditMode && isModified, onBack = handleBack)

    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        state = topAppBarState,
        flingAnimationSpec = null
    )

    val importSuccessMessage = stringResource(R.string.import_success)
    val importErrorMessage = stringResource(R.string.import_error)

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            uri?.let {
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val text = input.bufferedReader().use { r -> r.readText() }.trim()
                        val parsed = WebdavConfig.parse(text)
                        if (parsed != null) {
                            config = parsed
                            scope.launch { snackbarHostState.showExclusiveSnackbar(importSuccessMessage) }
                        } else {
                            scope.launch { snackbarHostState.showExclusiveSnackbar(importErrorMessage) }
                        }
                    }
                } catch (_: Exception) {
                    scope.launch { snackbarHostState.showExclusiveSnackbar(importErrorMessage) }
                }
            }
        }
    )

    if (showExitDialog.value) {
        AlertDialog(
            onDismissRequest = { showExitDialog.value = false },
            title = { Text(stringResource(R.string.unsaved_changes_title)) },
            text = { Text(stringResource(R.string.unsaved_changes_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    showExitDialog.value = false
                    onSave(config)
                }) {
                    Text(stringResource(R.string.btn_save))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showExitDialog.value = false
                    onBack()
                }) {
                    Text(stringResource(R.string.btn_discard))
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.noFlingExpandConnection()),
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState
            ) { data ->
                AppSnackbar(data)
            }
        },
        topBar = {
            AppTopAppBar(
                title = stringResource(R.string.kernel_webdav),
                subtitle = if (isEditMode) profileName else null,
                onBack = handleBack,
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = { showQrScanner.value = true }) {
                        Icon(
                            painter = painterResource(R.drawable.qr_code_24px),
                            contentDescription = stringResource(R.string.qr_import)
                        )
                    }

                    var showImportMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { showImportMenu = true }) {
                        Icon(
                            painter = painterResource(R.drawable.note_add_24px),
                            contentDescription = stringResource(R.string.profile_import_group)
                        )
                        AppDropdownMenu(
                            expanded = showImportMenu,
                            onDismissRequest = { showImportMenu = false },
                            title = stringResource(R.string.profile_import_group)
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.import_clipboard)) },
                                leadingIcon = { Icon(painterResource(R.drawable.content_paste_24px), null) },
                                onClick = {
                                    showImportMenu = false
                                    scope.launch {
                                        val clipEntry = clipboard.getClipEntry()
                                        val text = clipEntry?.clipData?.getItemAt(0)?.text?.toString() ?: ""
                                        val parsed = WebdavConfig.parse(text)
                                        if (parsed != null) {
                                            config = parsed
                                            snackbarHostState.showExclusiveSnackbar(importSuccessMessage)
                                        } else if (text.isNotBlank()) {
                                            snackbarHostState.showExclusiveSnackbar(importErrorMessage)
                                        }
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.import_file)) },
                                leadingIcon = { Icon(painterResource(R.drawable.file_open_24px), null) },
                                onClick = {
                                    showImportMenu = false
                                    filePickerLauncher.launch("*/*")
                                }
                            )
                        }
                    }

                    if (isEditMode) {
                        Box {
                            IconButton(onClick = { showMenu.value = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.share_24px),
                                    contentDescription = stringResource(R.string.share)
                                )
                            }
                            
                            ShareDropdownMenu(
                                expanded = showMenu.value,
                                onDismissRequest = { showMenu.value = false },
                                textToShare = config.toUri(profileName),
                                onShowQr = { showQrDialog.value = true }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = !isEditMode || isModified,
                enter = scaleIn(
                    initialScale = 0.8f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) + fadeIn(animationSpec = tween(200)),
                exit = scaleOut(
                    targetScale = 0.8f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ) + fadeOut(animationSpec = tween(150))
            ) {
                ExtendedFloatingActionButton(
                    modifier = Modifier.navigationBarsPadding(),
                    onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        onSave(config)
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    icon = {
                        Icon(
                            painter = painterResource(
                                if (isEditMode) R.drawable.save_24px 
                                else R.drawable.arrow_forward_ios_24px
                            ),
                            contentDescription = null
                        )
                    },
                    text = {
                        Text(
                            text = stringResource(if (isEditMode) R.string.btn_save else R.string.btn_next)
                        )
                    }
                )
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .fillMaxWidth()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 840.dp)
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
                .padding(top = 18.dp)
                .navigationBarsPadding()
                .padding(bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(19.dp)
        ) {
            // Connection Details
            SectionGroup(title = stringResource(R.string.connection_details)) {
                SectionItem(position = ItemPosition.Top) {
                    TextFieldRow(
                        label = stringResource(R.string.webdav_url_label),
                        value = config.webdav.redact(isPrivacyActive),
                        onValueChange = { if (!isPrivacyActive) config = config.copy(webdav = it) },
                        readOnly = isPrivacyActive,
                        isError = config.webdav.isBlank(),
                        isModified = isEditMode && config.webdav != initialConfig.webdav,
                        privacyMode = isPrivacyActive,
                        placeholder = "https://dav.example.com"
                    )
                }
                SectionItem {
                    TextFieldRow(
                        label = stringResource(R.string.webdav_login_label),
                        value = config.login.redact(isPrivacyActive),
                        onValueChange = { if (!isPrivacyActive) config = config.copy(login = it) },
                        readOnly = isPrivacyActive,
                        isModified = isEditMode && config.login != initialConfig.login,
                        privacyMode = isPrivacyActive
                    )
                }
                SectionItem(position = ItemPosition.Bottom) {
                    TextFieldRow(
                        label = stringResource(R.string.webdav_password_label),
                        value = config.password.redact(isPrivacyActive),
                        onValueChange = { if (!isPrivacyActive) config = config.copy(password = it) },
                        readOnly = isPrivacyActive,
                        isModified = isEditMode && config.password != initialConfig.password,
                        privacyMode = isPrivacyActive,
                        trailingIcon = {
                            if (!isPrivacyActive) {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        painter = painterResource(
                                            if (passwordVisible) R.drawable.visibility_24px 
                                            else R.drawable.visibility_off_24px
                                        ),
                                        contentDescription = null
                                    )
                                }
                            }
                        },
                        visualTransformation = if (passwordVisible || isPrivacyActive) VisualTransformation.None 
                                             else PasswordVisualTransformation()
                    )
                }
            }

            SectionGroup {
                SectionItem(
                    position = ItemPosition.Single,
                    onClick = {
                        val next = !config.encrypt
                        HapticUtil.perform(context, if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                        config = config.copy(encrypt = next)
                    }
                ) {
                    SwitchRow(
                        label = stringResource(R.string.webdav_encrypt),
                        checked = config.encrypt,
                        onCheckedChange = { config = config.copy(encrypt = it) },
                        supportingText = stringResource(R.string.webdav_encrypt_desc),
                        isModified = isEditMode && config.encrypt != initialConfig.encrypt
                    )
                }
            }

            // Advanced Settings
            SectionGroup(title = stringResource(R.string.webdav_advanced_settings)) {
                SectionItem(position = ItemPosition.Top) {
                    TextFieldRow(
                        label = stringResource(R.string.webdav_poll_min),
                        value = config.pollMin,
                        onValueChange = { config = config.copy(pollMin = it) },
                        isModified = isEditMode && config.pollMin != initialConfig.pollMin
                    )
                }
                SectionItem {
                    TextFieldRow(
                        label = stringResource(R.string.webdav_poll_max),
                        value = config.pollMax,
                        onValueChange = { config = config.copy(pollMax = it) },
                        isModified = isEditMode && config.pollMax != initialConfig.pollMax
                    )
                }
                SectionItem {
                    TextFieldRow(
                        label = stringResource(R.string.webdav_timeout),
                        value = config.timeout,
                        onValueChange = { config = config.copy(timeout = it) },
                        isModified = isEditMode && config.timeout != initialConfig.timeout
                    )
                }
                SectionItem(position = ItemPosition.Bottom) {
                    TextFieldRow(
                        label = stringResource(R.string.webdav_coalesce),
                        value = config.coalesce,
                        onValueChange = { config = config.copy(coalesce = it) },
                        isModified = isEditMode && config.coalesce != initialConfig.coalesce
                    )
                }
            }

            SectionGroup {
                SectionItem(position = ItemPosition.Top) {
                    TextFieldRow(
                        label = stringResource(R.string.webdav_chunk_size),
                        value = config.chunkSize,
                        onValueChange = { config = config.copy(chunkSize = it) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isModified = isEditMode && config.chunkSize != initialConfig.chunkSize
                    )
                }
                SectionItem {
                    TextFieldRow(
                        label = stringResource(R.string.webdav_puts),
                        value = config.puts,
                        onValueChange = { config = config.copy(puts = it) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isModified = isEditMode && config.puts != initialConfig.puts
                    )
                }
                SectionItem {
                    TextFieldRow(
                        label = stringResource(R.string.webdav_read_min),
                        value = config.readMin,
                        onValueChange = { config = config.copy(readMin = it) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isModified = isEditMode && config.readMin != initialConfig.readMin
                    )
                }
                SectionItem(position = ItemPosition.Bottom) {
                    TextFieldRow(
                        label = stringResource(R.string.webdav_read_max),
                        value = config.readMax,
                        onValueChange = { config = config.copy(readMax = it) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isModified = isEditMode && config.readMax != initialConfig.readMax
                    )
                }
            }
        }
    }

    if (showQrDialog.value) {
        QrCodeDialog(
            text = config.toUri(profileName),
            onDismiss = { showQrDialog.value = false }
        )
    }

    if (showQrScanner.value) {
        QrScannerDialog(
            title = stringResource(R.string.qr_import),
            message = stringResource(R.string.qr_scan_desc),
            onDismiss = { showQrScanner.value = false },
            onResult = { result: String ->
                val parsed = WebdavConfig.parse(result)
                if (parsed != null) {
                    config = parsed
                    scope.launch { snackbarHostState.showExclusiveSnackbar(importSuccessMessage) }
                } else {
                    scope.launch { snackbarHostState.showExclusiveSnackbar(importErrorMessage) }
                }
            }
        )
    }
}
