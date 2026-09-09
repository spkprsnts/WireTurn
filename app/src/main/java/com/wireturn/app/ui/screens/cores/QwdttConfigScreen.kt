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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.wireturn.app.R
import com.wireturn.app.data.QwdttConfig
import com.wireturn.app.ui.AppDropdownMenu
import com.wireturn.app.ui.AppTopAppBar
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.ItemPosition
import com.wireturn.app.ui.LabeledButtonGroup
import com.wireturn.app.ui.QrCodeDialog
import com.wireturn.app.ui.RowLabel
import com.wireturn.app.ui.SectionGroup
import com.wireturn.app.ui.SectionItem
import com.wireturn.app.ui.SelectionDialog
import com.wireturn.app.ui.ShareDropdownMenu
import com.wireturn.app.ui.SliderRow
import com.wireturn.app.ui.StandardLeadingIcon
import com.wireturn.app.ui.SupportingText
import com.wireturn.app.ui.SwitchRow
import com.wireturn.app.ui.TextFieldRow
import com.wireturn.app.ui.ValidatorUtils
import com.wireturn.app.ui.noFlingExpandConnection
import com.wireturn.app.ui.redact
import com.wireturn.app.ui.screens.QrScannerDialog
import com.wireturn.app.ui.selectableButtonItem
import com.wireturn.app.ui.showExclusiveToast
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun QwdttConfigScreen(
    isEditMode: Boolean = false,
    initialConfig: QwdttConfig = QwdttConfig(),
    profileName: String? = null,
    privacyMode: Boolean = false,
    onBack: () -> Unit,
    onSave: (QwdttConfig) -> Unit
) {
    val isPrivacyActive = privacyMode && isEditMode

    var config by remember(initialConfig) { mutableStateOf(initialConfig) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    val isModified = config != initialConfig

    val showExitDialog = remember { mutableStateOf(false) }
    val showQrDialog = remember { mutableStateOf(false) }
    val showQrScanner = remember { mutableStateOf(false) }
    val showMenu = remember { mutableStateOf(false) }
    val showObfsDialog = remember { mutableStateOf(false) }

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
                        val parsed = QwdttConfig.parse(text)
                        if (parsed != null) {
                            config = parsed
                            context.showExclusiveToast(importSuccessMessage)
                        } else {
                            context.showExclusiveToast(importErrorMessage)
                        }
                    }
                } catch (_: Exception) {
                    context.showExclusiveToast(importErrorMessage)
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
        topBar = {
            AppTopAppBar(
                title = stringResource(R.string.kernel_qwdtt),
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
                                        val parsed = QwdttConfig.parse(text)
                                        if (parsed != null) {
                                            config = parsed
                                            context.showExclusiveToast(importSuccessMessage)
                                        } else if (text.isNotBlank()) {
                                            context.showExclusiveToast(importErrorMessage)
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
                ) + fadeIn(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()),
                exit = scaleOut(
                    targetScale = 0.8f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ) + fadeOut(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec())
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
                        label = stringResource(R.string.qwdtt_peer_label),
                        value = config.peer.redact(isPrivacyActive),
                        onValueChange = { if (!isPrivacyActive) config = config.copy(peer = it) },
                        readOnly = isPrivacyActive,
                        isError = config.peer.isBlank() || !ValidatorUtils.isValidHostPort(config.peer),
                        isModified = isEditMode && config.peer != initialConfig.peer,
                        privacyMode = isPrivacyActive,
                        placeholder = "203.0.113.10:56000",
                        supportingText = stringResource(R.string.qwdtt_peer_desc)
                    )
                }
                SectionItem {
                    TextFieldRow(
                        label = stringResource(R.string.qwdtt_hashes_label),
                        value = config.vkHashes.redact(isPrivacyActive),
                        onValueChange = { if (!isPrivacyActive) config = config.copy(vkHashes = it) },
                        readOnly = isPrivacyActive,
                        isError = config.vkHashes.isBlank(),
                        isModified = isEditMode && config.vkHashes != initialConfig.vkHashes,
                        privacyMode = isPrivacyActive,
                        supportingText = stringResource(R.string.qwdtt_hashes_desc)
                    )
                }
                SectionItem(position = ItemPosition.Bottom) {
                    TextFieldRow(
                        label = stringResource(R.string.qwdtt_password_label),
                        value = config.password.redact(isPrivacyActive),
                        onValueChange = { if (!isPrivacyActive) config = config.copy(password = it) },
                        readOnly = isPrivacyActive,
                        isError = config.password.isBlank(),
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

            // Server Settings
            SectionGroup(title = stringResource(R.string.server_settings_title)) {
                SectionItem(position = ItemPosition.Top) {
                    SliderRow(
                        label = stringResource(R.string.qwdtt_workers_label),
                        value = config.workers.toFloat(),
                        onValueChange = { config = config.copy(workers = it.roundToInt()) },
                        valueRange = 1f..108f,
                        steps = 106,
                        supportingText = stringResource(R.string.qwdtt_workers_desc),
                        isModified = isEditMode && config.workers != initialConfig.workers
                    )
                }
                SectionItem(position = ItemPosition.Bottom) {
                    LabeledButtonGroup(
                        label = stringResource(R.string.qwdtt_turn_tcp_label),
                        supportingText = stringResource(R.string.qwdtt_turn_tcp_desc),
                        isModified = isEditMode && config.turnTcp != initialConfig.turnTcp
                    ) {
                        val options = listOf("udp", "tcp")
                        options.forEachIndexed { index, t ->
                            selectableButtonItem(
                                selected = config.turnTcp == (t == "tcp"),
                                onSelect = { config = config.copy(turnTcp = t == "tcp") },
                                label = t.uppercase(),
                                index = index,
                                count = options.size
                            )
                        }
                    }
                }
            }

            // Advanced Settings
            SectionGroup(title = stringResource(R.string.qwdtt_advanced_settings)) {
                SectionItem(
                    position = ItemPosition.Top,
                    onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        showObfsDialog.value = true
                    }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            RowLabel(
                                text = stringResource(R.string.qwdtt_obfs_label),
                                isModified = isEditMode && config.obfsMode != initialConfig.obfsMode
                            )
                            SupportingText(config.obfsMode)
                        }
                    }
                }
                SectionItem {
                    SwitchRow(
                        label = stringResource(R.string.qwdtt_notls_label),
                        checked = config.noTls,
                        onCheckedChange = { config = config.copy(noTls = it) },
                        supportingText = stringResource(R.string.qwdtt_notls_desc),
                        isModified = isEditMode && config.noTls != initialConfig.noTls
                    )
                }
                SectionItem {
                    SwitchRow(
                        label = stringResource(R.string.qwdtt_manual_captcha_label),
                        checked = config.manualCaptcha,
                        onCheckedChange = { config = config.copy(manualCaptcha = it) },
                        supportingText = stringResource(R.string.qwdtt_manual_captcha_desc),
                        isModified = isEditMode && config.manualCaptcha != initialConfig.manualCaptcha
                    )
                }
                SectionItem(position = ItemPosition.Bottom) {
                    TextFieldRow(
                        label = stringResource(R.string.qwdtt_go_dns_label),
                        value = config.goDns,
                        onValueChange = { config = config.copy(goDns = it) },
                        isModified = isEditMode && config.goDns != initialConfig.goDns,
                        supportingText = stringResource(R.string.qwdtt_go_dns_desc)
                    )
                }
            }
        }
    }

    if (showObfsDialog.value) {
        QwdttObfsDialog(
            currentObfs = config.obfsMode,
            onSelect = {
                config = config.copy(obfsMode = it)
                showObfsDialog.value = false
            },
            onDismiss = { showObfsDialog.value = false }
        )
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
                val parsed = QwdttConfig.parse(result)
                if (parsed != null) {
                    config = parsed
                    context.showExclusiveToast(importSuccessMessage)
                } else {
                    context.showExclusiveToast(importErrorMessage)
                }
            }
        )
    }
}

@Composable
private fun QwdttObfsDialog(
    currentObfs: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val modes = listOf("audio", "video")

    SelectionDialog(
        title = stringResource(R.string.qwdtt_obfs_label),
        items = modes,
        isSelected = { it == currentObfs },
        onSelect = { onSelect(it) },
        onDismiss = onDismiss
    ) { value, _ ->
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StandardLeadingIcon {
                Icon(
                    painter = painterResource(R.drawable.route_24px),
                    contentDescription = null
                )
            }
            Text(
                text = value,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
