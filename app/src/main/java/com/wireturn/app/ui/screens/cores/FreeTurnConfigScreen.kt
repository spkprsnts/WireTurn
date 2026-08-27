@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.derivedStateOf
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
import com.wireturn.app.data.FreeTurnConfig
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
fun FreeTurnConfigScreen(
    isEditMode: Boolean = false,
    initialConfig: FreeTurnConfig = FreeTurnConfig(),
    profileName: String? = null,
    privacyMode: Boolean = false,
    onBack: () -> Unit,
    onSave: (FreeTurnConfig) -> Unit
) {
    val isPrivacyActive = privacyMode && isEditMode
    var config by remember(initialConfig) { mutableStateOf(initialConfig) }

    val isModified by remember(config) {
        derivedStateOf { config != initialConfig }
    }

    val showExitDialog = remember { mutableStateOf(false) }
    val showQrDialog = remember { mutableStateOf(false) }
    val showQrScanner = remember { mutableStateOf(false) }
    val showMenu = remember { mutableStateOf(false) }
    val showObfDialog = remember { mutableStateOf(false) }

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
                        val parsed = FreeTurnConfig.parse(text)
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
                title = stringResource(R.string.kernel_freeturn),
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
                                        val parsed = FreeTurnConfig.parse(text)
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
                                textToShare = config.toUri(),
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
            // connection details
            SectionGroup(title = stringResource(R.string.connection_details)) {
                SectionItem(position = ItemPosition.Top) {
                    LabeledButtonGroup(
                        label = stringResource(R.string.freeturn_mode_label),
                        isModified = isEditMode && config.mode != initialConfig.mode
                    ) {
                        val options = listOf("udp", "tcp")
                        options.forEachIndexed { index, m ->
                            selectableButtonItem(
                                selected = config.mode == m,
                                onSelect = { config = config.copy(mode = m) },
                                label = m.uppercase(),
                                index = index,
                                count = options.size
                            )
                        }
                    }
                }
                SectionItem {
                    TextFieldRow(
                        label = stringResource(R.string.freeturn_peer_label),
                        supportingText = stringResource(R.string.freeturn_peer_desc),
                        value = config.peer.redact(isPrivacyActive),
                        onValueChange = { if (!isPrivacyActive) config = config.copy(peer = it) },
                        readOnly = isPrivacyActive,
                        isModified = isEditMode && config.peer != initialConfig.peer,
                        isError = (config.peer.isBlank() && config.sub.isBlank()) || (config.peer.isNotBlank() && !ValidatorUtils.isValidHostPort(config.peer)),
                        privacyMode = isPrivacyActive
                    )
                }
                SectionItem {
                    TextFieldRow(
                        label = stringResource(R.string.freeturn_links_label),
                        supportingText = stringResource(R.string.freeturn_links_desc),
                        value = config.links.redact(isPrivacyActive),
                        onValueChange = { if (!isPrivacyActive) config = config.copy(links = it) },
                        readOnly = isPrivacyActive,
                        isModified = isEditMode && config.links != initialConfig.links,
                        isError = config.links.isBlank(),
                        privacyMode = isPrivacyActive
                    )
                }
                SectionItem(position = ItemPosition.Bottom) {
                    TextFieldRow(
                        label = stringResource(R.string.freeturn_sub_label),
                        supportingText = stringResource(R.string.freeturn_sub_desc),
                        value = config.sub.redact(isPrivacyActive),
                        onValueChange = { if (!isPrivacyActive) config = config.copy(sub = it) },
                        readOnly = isPrivacyActive,
                        isModified = isEditMode && config.sub != initialConfig.sub,
                        isError = (config.peer.isBlank() && config.sub.isBlank()) ||
                            (config.sub.isNotBlank() && !ValidatorUtils.isValidUrl(config.sub)),
                        privacyMode = isPrivacyActive
                    )
                }
            }

            SectionGroup(title = stringResource(R.string.server_settings_title)) {
                SectionItem(position = ItemPosition.Top) {
                    SliderRow(
                        label = stringResource(R.string.freeturn_n_label),
                        supportingText = stringResource(R.string.freeturn_n_desc),
                        value = config.n.toFloat(),
                        onValueChange = { config = config.copy(n = it.roundToInt()) },
                        valueRange = 1f..64f,
                        steps = 62,
                        isModified = isEditMode && config.n != initialConfig.n
                    )
                }
                SectionItem {
                    SliderRow(
                        label = stringResource(R.string.freeturn_spc_label),
                        supportingText = stringResource(R.string.freeturn_spc_desc),
                        value = config.streamsPerCred.toFloat(),
                        onValueChange = { config = config.copy(streamsPerCred = it.roundToInt()) },
                        valueRange = 1f..64f,
                        steps = 62,
                        isModified = isEditMode && config.streamsPerCred != initialConfig.streamsPerCred
                    )
                }
                SectionItem {
                    LabeledButtonGroup(
                        label = stringResource(R.string.freeturn_platform_label),
                        supportingText = stringResource(R.string.freeturn_platform_desc),
                        isModified = isEditMode && config.platform != initialConfig.platform
                    ) {
                        val options = listOf("desktop", "mobile")
                        options.forEachIndexed { index, p ->
                            selectableButtonItem(
                                selected = config.platform == p,
                                onSelect = { config = config.copy(platform = p) },
                                label = p.replaceFirstChar { it.uppercase() },
                                index = index,
                                count = options.size
                            )
                        }
                    }
                }
                SectionItem {
                    LabeledButtonGroup(
                        label = stringResource(R.string.freeturn_transport_label),
                        supportingText = stringResource(R.string.freeturn_transport_desc),
                        isModified = isEditMode && config.transport != initialConfig.transport
                    ) {
                        val options = listOf("tcp", "udp")
                        options.forEachIndexed { index, t ->
                            selectableButtonItem(
                                selected = config.transport == t,
                                onSelect = { config = config.copy(transport = t) },
                                label = t.uppercase(),
                                index = index,
                                count = options.size
                            )
                        }
                    }
                }
            }

            if (config.mode == "tcp") {
                SectionGroup(title = stringResource(R.string.freeturn_kcp_settings_title)) {
                    SectionItem(
                        position = ItemPosition.Top,
                        onClick = {
                            val next = config.kcpNodelay == 0
                            HapticUtil.perform(context, if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                            config = config.copy(kcpNodelay = if (next) 1 else 0)
                        }
                    ) {
                        SwitchRow(
                            label = stringResource(R.string.freeturn_kcp_nodelay_label),
                            supportingText = stringResource(R.string.freeturn_kcp_nodelay_desc),
                            checked = config.kcpNodelay != 0,
                            onCheckedChange = { config = config.copy(kcpNodelay = if (it) 1 else 0) },
                            isModified = isEditMode && config.kcpNodelay != initialConfig.kcpNodelay
                        )
                    }
                    SectionItem {
                        SliderRow(
                            label = stringResource(R.string.freeturn_kcp_interval_label),
                            supportingText = stringResource(R.string.freeturn_kcp_interval_desc),
                            value = config.kcpInterval.toFloat(),
                            onValueChange = { config = config.copy(kcpInterval = it.roundToInt()) },
                            valueRange = 10f..100f,
                            steps = 17,
                            isModified = isEditMode && config.kcpInterval != initialConfig.kcpInterval
                        )
                    }
                    SectionItem {
                        SliderRow(
                            label = stringResource(R.string.freeturn_kcp_resend_label),
                            supportingText = stringResource(R.string.freeturn_kcp_resend_desc),
                            value = config.kcpResend.toFloat(),
                            onValueChange = { config = config.copy(kcpResend = it.roundToInt()) },
                            valueRange = 0f..10f,
                            steps = 9,
                            isModified = isEditMode && config.kcpResend != initialConfig.kcpResend
                        )
                    }
                    SectionItem(
                        onClick = {
                            val next = config.kcpNc == 0
                            HapticUtil.perform(context, if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                            config = config.copy(kcpNc = if (next) 1 else 0)
                        }
                    ) {
                        SwitchRow(
                            label = stringResource(R.string.freeturn_kcp_nc_label),
                            supportingText = stringResource(R.string.freeturn_kcp_nc_desc),
                            checked = config.kcpNc != 0,
                            onCheckedChange = { config = config.copy(kcpNc = if (it) 1 else 0) },
                            isModified = isEditMode && config.kcpNc != initialConfig.kcpNc
                        )
                    }
                    SectionItem {
                        SliderRow(
                            label = stringResource(R.string.freeturn_kcp_sndwnd_label),
                            supportingText = stringResource(R.string.freeturn_kcp_sndwnd_desc),
                            value = config.kcpSndwnd.toFloat(),
                            onValueChange = { config = config.copy(kcpSndwnd = it.roundToInt()) },
                            valueRange = 64f..2048f,
                            steps = 30,
                            isModified = isEditMode && config.kcpSndwnd != initialConfig.kcpSndwnd
                        )
                    }
                    SectionItem {
                        SliderRow(
                            label = stringResource(R.string.freeturn_kcp_rcvwnd_label),
                            supportingText = stringResource(R.string.freeturn_kcp_rcvwnd_desc),
                            value = config.kcpRcvwnd.toFloat(),
                            onValueChange = { config = config.copy(kcpRcvwnd = it.roundToInt()) },
                            valueRange = 64f..2048f,
                            steps = 30,
                            isModified = isEditMode && config.kcpRcvwnd != initialConfig.kcpRcvwnd
                        )
                    }
                    SectionItem {
                        SliderRow(
                            label = stringResource(R.string.freeturn_kcp_mtu_label),
                            supportingText = stringResource(R.string.freeturn_kcp_mtu_desc),
                            value = config.kcpMtu.toFloat(),
                            onValueChange = { config = config.copy(kcpMtu = it.roundToInt()) },
                            valueRange = 300f..1350f,
                            steps = 20,
                            isModified = isEditMode && config.kcpMtu != initialConfig.kcpMtu
                        )
                    }
                    SectionItem(
                        position = ItemPosition.Bottom,
                        onClick = {
                            val next = !config.kcpAcknodelay
                            HapticUtil.perform(context, if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                            config = config.copy(kcpAcknodelay = next)
                        }
                    ) {
                        SwitchRow(
                            label = stringResource(R.string.freeturn_kcp_acknodelay_label),
                            supportingText = stringResource(R.string.freeturn_kcp_acknodelay_desc),
                            checked = config.kcpAcknodelay,
                            onCheckedChange = { config = config.copy(kcpAcknodelay = it) },
                            isModified = isEditMode && config.kcpAcknodelay != initialConfig.kcpAcknodelay
                        )
                    }
                }
            }

            SectionGroup(title = stringResource(R.string.freeturn_advanced_settings)) {
                SectionItem(
                    position = ItemPosition.Top,
                    onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        showObfDialog.value = true
                    }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StandardLeadingIcon {
                            Icon(
                                painter = painterResource(R.drawable.lock_24px),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            RowLabel(
                                text = stringResource(R.string.freeturn_obf_profile_label),
                                isModified = isEditMode && config.obfProfile != initialConfig.obfProfile
                            )
                            Spacer(Modifier.height(2.dp))
                            SupportingText(text = config.obfProfile)
                        }
                    }
                }
                SectionItem {
                    TextFieldRow(
                        label = stringResource(R.string.freeturn_obf_key_label),
                        supportingText = stringResource(R.string.freeturn_obf_key_desc),
                        value = config.obfKey.redact(isPrivacyActive),
                        onValueChange = { if (!isPrivacyActive) config = config.copy(obfKey = it) },
                        readOnly = isPrivacyActive,
                        isModified = isEditMode && config.obfKey != initialConfig.obfKey,
                        isError = config.obfProfile != "none" && config.obfKey.length != 64,
                        privacyMode = isPrivacyActive
                    )
                }
                SectionItem {
                    TextFieldRow(
                        label = stringResource(R.string.freeturn_obf_timing_label),
                        supportingText = stringResource(R.string.freeturn_obf_timing_desc),
                        value = config.obfTiming.redact(isPrivacyActive),
                        onValueChange = { if (!isPrivacyActive) config = config.copy(obfTiming = it) },
                        readOnly = isPrivacyActive,
                        isModified = isEditMode && config.obfTiming != initialConfig.obfTiming,
                        privacyMode = isPrivacyActive
                    )
                }
                SectionItem {
                    LabeledButtonGroup(
                        label = stringResource(R.string.freeturn_dns_mode_label),
                        supportingText = stringResource(R.string.freeturn_dns_mode_desc),
                        isModified = isEditMode && config.dnsMode != initialConfig.dnsMode
                    ) {
                        val options = listOf("auto", "plain", "doh")
                        options.forEachIndexed { index, m ->
                            selectableButtonItem(
                                selected = config.dnsMode == m,
                                onSelect = { config = config.copy(dnsMode = m) },
                                label = m.uppercase(),
                                index = index,
                                count = options.size
                            )
                        }
                    }
                }
                SectionItem {
                    TextFieldRow(
                        label = stringResource(R.string.freeturn_dns_servers_label),
                        supportingText = stringResource(R.string.freeturn_dns_servers_desc),
                        value = config.dnsServers.redact(isPrivacyActive),
                        onValueChange = { if (!isPrivacyActive) config = config.copy(dnsServers = it) },
                        placeholder = stringResource(R.string.freeturn_dns_servers_placeholder),
                        readOnly = isPrivacyActive,
                        isModified = isEditMode && config.dnsServers != initialConfig.dnsServers,
                        privacyMode = isPrivacyActive
                    )
                }
                SectionItem {
                    TextFieldRow(
                        label = stringResource(R.string.freeturn_client_id_label),
                        supportingText = stringResource(R.string.freeturn_client_id_desc),
                        value = config.clientId.redact(isPrivacyActive),
                        onValueChange = { if (!isPrivacyActive) config = config.copy(clientId = it) },
                        readOnly = isPrivacyActive,
                        isModified = isEditMode && config.clientId != initialConfig.clientId,
                        privacyMode = isPrivacyActive
                    )
                }
                SectionItem(
                    position = ItemPosition.Bottom,
                    onClick = {
                        val next = !config.manualCaptcha
                        HapticUtil.perform(context, if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                        config = config.copy(manualCaptcha = next)
                    }
                ) {
                    SwitchRow(
                        label = stringResource(R.string.freeturn_manual_captcha_label),
                        supportingText = stringResource(R.string.freeturn_manual_captcha_desc),
                        checked = config.manualCaptcha,
                        onCheckedChange = { config = config.copy(manualCaptcha = it) },
                        isModified = isEditMode && config.manualCaptcha != initialConfig.manualCaptcha
                    )
                }
            }
        }
    }

    if (showObfDialog.value) {
        val profiles = listOf("none", "rtpopus", "rtpopus2", "rtpopus3")
        SelectionDialog(
            title = stringResource(R.string.freeturn_obf_profile_label),
            items = profiles,
            isSelected = { it == config.obfProfile },
            onSelect = {
                config = config.copy(obfProfile = it)
                showObfDialog.value = false
            },
            onDismiss = { showObfDialog.value = false }
        ) { profile, _ ->
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = profile,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }

    if (showQrDialog.value) {
        QrCodeDialog(
            text = config.toUri(),
            onDismiss = { showQrDialog.value = false }
        )
    }

    if (showQrScanner.value) {
        QrScannerDialog(
            title = stringResource(R.string.qr_import),
            message = stringResource(R.string.qr_scan_desc),
            onDismiss = { showQrScanner.value = false },
            onResult = { result: String ->
                val parsed = FreeTurnConfig.parse(result)
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
