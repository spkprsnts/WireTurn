@file:OptIn(
    ExperimentalMaterial3ExpressiveApi::class,
)

package com.wireturn.app.ui.screens.kernel

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wireturn.app.R
import com.wireturn.app.data.kernel.CsqttConfig
import com.wireturn.app.ui.AppDropdownMenu
import com.wireturn.app.ui.AppTopAppBar
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.ItemPosition
import com.wireturn.app.ui.LabeledButtonGroup
import com.wireturn.app.ui.QrCodeDialog
import com.wireturn.app.ui.ScreenSubtitle
import com.wireturn.app.ui.SectionGroup
import com.wireturn.app.ui.SectionItem
import com.wireturn.app.ui.ShareDropdownMenu
import com.wireturn.app.ui.SliderRow
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
fun CsqttConfigScreen(
    isEditMode: Boolean = false,
    initialConfig: CsqttConfig = CsqttConfig(),
    profileName: String? = null,
    privacyMode: Boolean = false,
    onBack: () -> Unit,
    onSave: (CsqttConfig) -> Unit
) {
    val isPrivacyActive = privacyMode && isEditMode

    var config by remember(initialConfig) { mutableStateOf(initialConfig) }

    val isModified = config != initialConfig

    val showExitDialog = remember { mutableStateOf(false) }
    val showQrDialog = remember { mutableStateOf(false) }
    val showQrScanner = remember { mutableStateOf(false) }
    val showMenu = remember { mutableStateOf(false) }

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

    // A link without hashes keeps the ones already entered (see CsqttConfig.parse).
    fun importLink(text: String, silentIfBlank: Boolean = false) {
        val parsed = CsqttConfig.parse(text, config)
        if (parsed != null) {
            config = parsed
            context.showExclusiveToast(importSuccessMessage)
        } else if (!silentIfBlank || text.isNotBlank()) {
            context.showExclusiveToast(importErrorMessage)
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            uri?.let {
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        importLink(input.bufferedReader().use { r -> r.readText() }.trim())
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
                title = stringResource(R.string.kernel_csqtt),
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
                                        importLink(clipEntry?.clipData?.getItemAt(0)?.text?.toString() ?: "", silentIfBlank = true)
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
            if (isEditMode && profileName != null) {
                ScreenSubtitle(profileName)
            }

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
                        placeholder = "203.0.113.10:${CsqttConfig.DEFAULT_PEER_PORT}",
                        supportingText = stringResource(R.string.qwdtt_peer_desc)
                    )
                }
                SectionItem {
                    TextFieldRow(
                        label = stringResource(R.string.qwdtt_hashes_label),
                        value = config.vkHashes.redact(isPrivacyActive),
                        onValueChange = { if (!isPrivacyActive) config = config.copy(vkHashes = it) },
                        readOnly = isPrivacyActive,
                        isError = config.hashList().isEmpty(),
                        isModified = isEditMode && config.vkHashes != initialConfig.vkHashes,
                        privacyMode = isPrivacyActive,
                        singleLine = false,
                        minLines = 1,
                        maxLines = 5,
                        supportingText = stringResource(R.string.csqtt_hashes_desc, CsqttConfig.MAX_HASHES)
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
                        isSecret = true
                    )
                }
            }

            SectionGroup(title = stringResource(R.string.server_settings_title)) {
                SectionItem(position = ItemPosition.Top) {
                    val group = CsqttConfig.WORKERS_PER_GROUP
                    SliderRow(
                        label = stringResource(R.string.qwdtt_workers_label),
                        value = config.workers.toFloat(),
                        onValueChange = { config = config.copy(workers = (it / group).roundToInt() * group) },
                        valueRange = group.toFloat()..CsqttConfig.MAX_WORKERS.toFloat(),
                        steps = CsqttConfig.MAX_WORKERS / group - 2,
                        supportingText = stringResource(
                            R.string.csqtt_workers_desc,
                            group,
                            CsqttConfig.MAX_WORKERS,
                            config.normalizedWorkers()
                        ),
                        isModified = isEditMode && config.workers != initialConfig.workers
                    )
                }
                SectionItem {
                    LabeledButtonGroup(
                        label = stringResource(R.string.qwdtt_obfs_label),
                        isModified = isEditMode && config.obfsMode != initialConfig.obfsMode
                    ) {
                        val options = listOf("audio", "video")
                        options.forEachIndexed { index, mode ->
                            selectableButtonItem(
                                selected = config.obfsMode == mode,
                                onSelect = { config = config.copy(obfsMode = mode) },
                                label = mode,
                                index = index,
                                count = options.size
                            )
                        }
                    }
                }
                SectionItem(position = ItemPosition.Bottom) {
                    LabeledButtonGroup(
                        label = stringResource(R.string.qwdtt_turn_tcp_label),
                        supportingText = stringResource(R.string.csqtt_turn_tcp_desc),
                        isModified = isEditMode && config.turnTcp != initialConfig.turnTcp
                    ) {
                        val options = listOf("udp", "tcp")
                        options.forEachIndexed { index, t ->
                            selectableButtonItem(
                                selected = config.turnTcp == (t == "tcp"),
                                onSelect = { config = config.copy(turnTcp = t == "tcp") },
                                label = if (t == "tcp") "TCP/TLS" else "UDP",
                                index = index,
                                count = options.size
                            )
                        }
                    }
                }
            }

            SectionGroup(title = stringResource(R.string.qwdtt_advanced_settings)) {
                SectionItem(position = ItemPosition.Single) {
                    SwitchRow(
                        label = stringResource(R.string.qwdtt_manual_captcha_label),
                        checked = config.manualCaptcha,
                        onCheckedChange = { config = config.copy(manualCaptcha = it) },
                        supportingText = stringResource(R.string.qwdtt_manual_captcha_desc),
                        isModified = isEditMode && config.manualCaptcha != initialConfig.manualCaptcha
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
            onResult = { result: String -> importLink(result) }
        )
    }
}
