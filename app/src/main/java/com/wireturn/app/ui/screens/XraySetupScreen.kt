@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wireturn.app.R
import com.wireturn.app.data.WgConfig
import com.wireturn.app.data.XrayConfiguration
import com.wireturn.app.data.VlessConfig
import com.wireturn.app.data.XrayConfig
import com.wireturn.app.data.KernelVariant
import androidx.compose.material3.rememberTopAppBarState
import com.wireturn.app.ui.ConfigTopAppBar
import com.wireturn.app.ui.ConfigDropdownMenu
import com.wireturn.app.ui.ConfigRowLabel
import com.wireturn.app.ui.FieldTrailingIcons
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.ItemPosition
import com.wireturn.app.ui.LabeledButtonGroup
import com.wireturn.app.ui.LargeLeadingIcon
import com.wireturn.app.ui.SettingsGroup
import com.wireturn.app.ui.SettingsGroupItem
import com.wireturn.app.ui.SwitchRow
import com.wireturn.app.ui.TextFieldRow
import com.wireturn.app.ui.ValidatorUtils
import com.wireturn.app.ui.configButtonGroupItem
import com.wireturn.app.ui.redact
import com.wireturn.app.ui.showExclusiveSnackbar
import kotlinx.coroutines.launch

@Composable
fun XraySetupScreen(
    isEditMode: Boolean,
    showProtocolSelection: Boolean,
    defaultProtocol: XrayConfiguration? = null,
    initialWgConfig: WgConfig = WgConfig(),
    initialVlessConfig: VlessConfig = VlessConfig(),
    initialXrayConfig: XrayConfig = XrayConfig(),
    privacyMode: Boolean = false,
    kernelVariant: KernelVariant = KernelVariant.TURNABLE,
    vlessLinkHistory: List<String> = emptyList(),
    onRemoveHistoryItem: (String) -> Unit = {},
    onBack: () -> Unit,
    onSave: (XrayConfiguration, WgConfig, VlessConfig) -> Unit
) {
    val isPrivacyActive = privacyMode && isEditMode
    val canChangeProtocol = remember(kernelVariant, showProtocolSelection) {
        kernelVariant != KernelVariant.OLCRTC && showProtocolSelection
    }

    var xrayConfiguration by remember(initialXrayConfig, kernelVariant, canChangeProtocol) {
        mutableStateOf(
            if (!canChangeProtocol) XrayConfiguration.VLESS
            else if (isEditMode) initialXrayConfig.protocol
            else (defaultProtocol ?: XrayConfiguration.WIREGUARD)
        )
    }
    
    // WireGuard states
    var privateKey by remember(initialWgConfig) { mutableStateOf(initialWgConfig.privateKey) }
    var address by remember(initialWgConfig) { mutableStateOf(initialWgConfig.address) }
    var mtu by remember(initialWgConfig) { mutableStateOf(initialWgConfig.mtu) }
    var publicKey by remember(initialWgConfig) { mutableStateOf(initialWgConfig.publicKey) }
    var endpoint by remember(initialWgConfig) { mutableStateOf(initialWgConfig.endpoint) }
    var persistentKeepalive by remember(initialWgConfig) { mutableStateOf(initialWgConfig.persistentKeepalive) }

    // VLESS states
    var vlessLink by remember(initialVlessConfig) { mutableStateOf(initialVlessConfig.vlessLink) }
    var vlessIsDualRoute by remember(initialVlessConfig) { mutableStateOf(initialVlessConfig.isDualRoute) }
    var vlessDirectAddress by remember(initialVlessConfig) { mutableStateOf(initialVlessConfig.directAddress) }
    var vlessHcInterval by remember(initialVlessConfig) { mutableStateOf(initialVlessConfig.hcInterval) }

    val currentWg = remember(privateKey, address, mtu, publicKey, endpoint, persistentKeepalive) {
        WgConfig(privateKey, address, mtu, publicKey, endpoint, persistentKeepalive)
    }
    val currentVless = remember(vlessLink, vlessIsDualRoute, vlessDirectAddress, vlessHcInterval, initialVlessConfig.vlessUseLocalAddress) {
        VlessConfig(vlessLink, initialVlessConfig.vlessUseLocalAddress, vlessIsDualRoute, vlessDirectAddress, vlessHcInterval)
    }

    val isModified by remember(xrayConfiguration, currentWg, currentVless) {
        derivedStateOf {
            xrayConfiguration != initialXrayConfig.protocol ||
            currentWg != initialWgConfig ||
            currentVless != initialVlessConfig
        }
    }

    val showExitDialog = remember { mutableStateOf(false) }

    val handleBack = {
        if (isEditMode && isModified) {
            showExitDialog.value = true
        } else {
            onBack()
        }
    }

    BackHandler(enabled = isEditMode && isModified, onBack = handleBack)

    val scrollState = rememberScrollState()

    var previousDualRoute by remember(initialVlessConfig) { mutableStateOf(vlessIsDualRoute) }
    androidx.compose.runtime.LaunchedEffect(vlessIsDualRoute) {
        if (vlessIsDualRoute && !previousDualRoute) {
            val scrollJob = launch {
                androidx.compose.runtime.snapshotFlow { scrollState.maxValue }.collect {
                    scrollState.scrollTo(it)
                }
            }
            kotlinx.coroutines.delay(300)
            scrollJob.cancel()
        }
        previousDualRoute = vlessIsDualRoute
    }

    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val showQrScanner = remember { mutableStateOf(false) }

    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        state = topAppBarState
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
                        val wgParsed = WgConfig.parse(text)
                        if (wgParsed.isValid()) {
                            xrayConfiguration = XrayConfiguration.WIREGUARD
                            privateKey = wgParsed.privateKey
                            address = wgParsed.address
                            mtu = wgParsed.mtu
                            publicKey = wgParsed.publicKey
                            endpoint = wgParsed.endpoint
                            persistentKeepalive = wgParsed.persistentKeepalive
                            scope.launch { snackbarHostState.showExclusiveSnackbar(importSuccessMessage) }
                        } else if (ValidatorUtils.isValidVlessLink(text)) {
                            xrayConfiguration = XrayConfiguration.VLESS
                            vlessLink = text
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
                    val wg = WgConfig(privateKey, address, mtu, publicKey, endpoint, persistentKeepalive)
                    val vless = VlessConfig(vlessLink, initialVlessConfig.vlessUseLocalAddress, vlessIsDualRoute, vlessDirectAddress, vlessHcInterval)
                    onSave(xrayConfiguration, wg, vless)
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
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState
            ) { data ->
                Snackbar(
                    modifier = Modifier.padding(12.dp),
                    action = data.visuals.actionLabel?.let { label ->
                        {
                            TextButton(onClick = { data.performAction() }) {
                                Text(label)
                            }
                        }
                    },
                    dismissAction = {
                        IconButton(onClick = { data.dismiss() }) {
                            Icon(painterResource(R.drawable.close_24px), contentDescription = null)
                        }
                    }
                ) {
                    Text(data.visuals.message)
                }
            }
        },
        topBar = {
            ConfigTopAppBar(
                title = stringResource(R.string.xray_title),
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
                            contentDescription = stringResource(R.string.xray_import_config)
                        )
                        ConfigDropdownMenu(
                            expanded = showImportMenu,
                            onDismissRequest = { showImportMenu = false },
                            title = stringResource(R.string.xray_import_config)
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.import_clipboard)) },
                                leadingIcon = { Icon(painterResource(R.drawable.content_paste_24px), null) },
                                onClick = {
                                    showImportMenu = false
                                    scope.launch {
                                        val clipEntry = clipboard.getClipEntry()
                                        val text = clipEntry?.clipData?.getItemAt(0)?.text?.toString() ?: ""
                                        val wgParsed = WgConfig.parse(text)
                                        if (wgParsed.isValid()) {
                                            xrayConfiguration = XrayConfiguration.WIREGUARD
                                            privateKey = wgParsed.privateKey
                                            address = wgParsed.address
                                            mtu = wgParsed.mtu
                                            publicKey = wgParsed.publicKey
                                            endpoint = wgParsed.endpoint
                                            persistentKeepalive = wgParsed.persistentKeepalive
                                            snackbarHostState.showExclusiveSnackbar(importSuccessMessage)
                                        } else if (ValidatorUtils.isValidVlessLink(text)) {
                                            xrayConfiguration = XrayConfiguration.VLESS
                                            vlessLink = text
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
                        val canShare = when (xrayConfiguration) {
                            XrayConfiguration.WIREGUARD -> currentWg.isValid()
                            XrayConfiguration.VLESS -> currentVless.isValid()
                        }

                        IconButton(
                            onClick = {
                                val textToShare = when (xrayConfiguration) {
                                    XrayConfiguration.WIREGUARD -> currentWg.toWgString()
                                    XrayConfiguration.VLESS -> currentVless.vlessLink
                                }
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, textToShare)
                                }
                                context.startActivity(Intent.createChooser(intent, null))
                            },
                            enabled = canShare
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.share_24px),
                                contentDescription = stringResource(R.string.share)
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
                        val wg = WgConfig(privateKey, address, mtu, publicKey, endpoint, persistentKeepalive)
                        val vless = VlessConfig(vlessLink, initialVlessConfig.vlessUseLocalAddress, vlessIsDualRoute, vlessDirectAddress, vlessHcInterval)
                        onSave(xrayConfiguration, wg, vless)
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.save_24px),
                            contentDescription = null
                        )
                    },
                    text = {
                        Text(
                            text = stringResource(R.string.btn_save)
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
            // Выбор протокола
            if (canChangeProtocol) {
                SettingsGroup(title = stringResource(R.string.xray_protocol_label)) {
                    SettingsGroupItem(position = ItemPosition.Single) {
                        val configurations = XrayConfiguration.entries
                        val protocolLabels = configurations.associateWith { config ->
                            stringResource(
                                when (config) {
                                    XrayConfiguration.WIREGUARD -> R.string.protocol_wireguard
                                    XrayConfiguration.VLESS -> R.string.vless
                                }
                            )
                        }
                        LabeledButtonGroup(
                            label = stringResource(R.string.xray_protocol_label),
                            supportingText = stringResource(R.string.xray_protocol_desc),
                            isModified = isEditMode && xrayConfiguration != initialXrayConfig.protocol
                        ) {
                            configurations.forEachIndexed { index, config ->
                                configButtonGroupItem(
                                    selected = xrayConfiguration == config,
                                    onSelect = {
                                        HapticUtil.perform(
                                            context,
                                            HapticUtil.Pattern.TOGGLE_ON
                                        )
                                        xrayConfiguration = config
                                    },
                                    label = protocolLabels[config] ?: "",
                                    index = index,
                                    count = configurations.size
                                )
                            }
                        }
                    }
                }
            }

            when (xrayConfiguration) {
                XrayConfiguration.WIREGUARD -> {
                    WireGuardSettingsBlock(
                        privateKey = privateKey,
                        onPrivateKeyChange = { if (!isPrivacyActive) privateKey = it },
                        address = address,
                        onAddressChange = { if (!isPrivacyActive) address = it },
                        mtu = mtu,
                        onMtuChange = { mtu = it },
                        publicKey = publicKey,
                        onPublicKeyChange = { if (!isPrivacyActive) publicKey = it },
                        endpoint = endpoint,
                        persistentKeepalive = persistentKeepalive,
                        onPersistentKeepaliveChange = { persistentKeepalive = it },
                        initialWgConfig = initialWgConfig,
                        isPrivacyActive = isPrivacyActive,
                        kernelVariant = kernelVariant,
                        isEditMode = isEditMode
                    )
                }

                XrayConfiguration.VLESS -> {
                    VlessSettingsBlock(
                        vlessLink = vlessLink,
                        onVlessLinkChange = { if (!isPrivacyActive) vlessLink = it },
                        vlessIsDualRoute = vlessIsDualRoute,
                        onVlessIsDualRouteChange = { vlessIsDualRoute = it },
                        vlessDirectAddress = vlessDirectAddress,
                        onVlessDirectAddressChange = { if (!isPrivacyActive) vlessDirectAddress = it },
                        vlessHcInterval = vlessHcInterval,
                        onVlessHcIntervalChange = { vlessHcInterval = it },
                        vlessLinkHistory = vlessLinkHistory,
                        onRemoveHistoryItem = onRemoveHistoryItem,
                        initialVlessConfig = initialVlessConfig,
                        isPrivacyActive = isPrivacyActive,
                        kernelVariant = kernelVariant,
                        isEditMode = isEditMode
                    )
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
                val wgParsed = WgConfig.parse(result)
                if (wgParsed.isValid()) {
                    xrayConfiguration = XrayConfiguration.WIREGUARD
                    privateKey = wgParsed.privateKey
                    address = wgParsed.address
                    mtu = wgParsed.mtu
                    publicKey = wgParsed.publicKey
                    endpoint = wgParsed.endpoint
                    persistentKeepalive = wgParsed.persistentKeepalive
                    scope.launch { snackbarHostState.showExclusiveSnackbar(importSuccessMessage) }
                } else if (ValidatorUtils.isValidVlessLink(result)) {
                    xrayConfiguration = XrayConfiguration.VLESS
                    vlessLink = result
                    scope.launch { snackbarHostState.showExclusiveSnackbar(importSuccessMessage) }
                } else {
                    scope.launch { snackbarHostState.showExclusiveSnackbar(importErrorMessage) }
                }
            }
        )
    }
}

@Composable
private fun WireGuardSettingsBlock(
    privateKey: String, onPrivateKeyChange: (String) -> Unit,
    address: String, onAddressChange: (String) -> Unit,
    mtu: String, onMtuChange: (String) -> Unit,
    publicKey: String, onPublicKeyChange: (String) -> Unit,
    endpoint: String,
    persistentKeepalive: String, onPersistentKeepaliveChange: (String) -> Unit,
    initialWgConfig: WgConfig,
    isPrivacyActive: Boolean,
    kernelVariant: KernelVariant,
    isEditMode: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(19.dp)) {
        if (kernelVariant == KernelVariant.OLCRTC) {
            SettingsGroupItem(position = ItemPosition.Single) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LargeLeadingIcon {
                        Icon(
                            painter = painterResource(R.drawable.info_24px),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        ConfigRowLabel(stringResource(R.string.wg_not_used_with_olcrtc))
                    }
                }
            }
        }

        SettingsGroup(title = stringResource(R.string.wg_interface)) {
            SettingsGroupItem(position = ItemPosition.Top) {
                TextFieldRow(
                    label = stringResource(R.string.wg_private_key),
                    value = privateKey.redact(isPrivacyActive),
                    onValueChange = { if (!isPrivacyActive) onPrivateKeyChange(it) },
                    placeholder = stringResource(R.string.wg_private_key_placeholder),
                    isError = privateKey.isBlank(),
                    readOnly = isPrivacyActive,
                    isModified = isEditMode && privateKey != initialWgConfig.privateKey,
                    privacyMode = isPrivacyActive
                )
            }
            SettingsGroupItem {
                TextFieldRow(
                    label = stringResource(R.string.wg_address),
                    value = address.redact(isPrivacyActive),
                    onValueChange = { if (!isPrivacyActive) onAddressChange(it) },
                    placeholder = stringResource(R.string.wg_address_placeholder),
                    isError = address.isBlank(),
                    readOnly = isPrivacyActive,
                    isModified = isEditMode && address != initialWgConfig.address,
                    privacyMode = isPrivacyActive
                )
            }
            SettingsGroupItem(position = ItemPosition.Bottom) {
                TextFieldRow(
                    label = stringResource(R.string.wg_mtu),
                    value = mtu,
                    onValueChange = onMtuChange,
                    placeholder = stringResource(R.string.wg_mtu_placeholder),
                    isError = mtu.isNotEmpty() && mtu.toIntOrNull() == null,
                    isModified = isEditMode && mtu != initialWgConfig.mtu,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        }

        SettingsGroup(title = stringResource(R.string.wg_peer)) {
            SettingsGroupItem(position = ItemPosition.Top) {
                TextFieldRow(
                    label = stringResource(R.string.wg_public_key),
                    value = publicKey.redact(isPrivacyActive),
                    onValueChange = { if (!isPrivacyActive) onPublicKeyChange(it) },
                    placeholder = stringResource(R.string.wg_public_key_placeholder),
                    isError = publicKey.isBlank(),
                    readOnly = isPrivacyActive,
                    isModified = isEditMode && publicKey != initialWgConfig.publicKey,
                    privacyMode = isPrivacyActive
                )
            }
            SettingsGroupItem {
                TextFieldRow(
                    label = stringResource(R.string.wg_endpoint),
                    value = endpoint.redact(isPrivacyActive),
                    onValueChange = { },
                    readOnly = true,
                    isModified = isEditMode && endpoint != initialWgConfig.endpoint,
                    privacyMode = isPrivacyActive
                )
            }
            SettingsGroupItem(position = ItemPosition.Bottom) {
                TextFieldRow(
                    label = stringResource(R.string.wg_persistent_keepalive),
                    value = persistentKeepalive,
                    onValueChange = onPersistentKeepaliveChange,
                    placeholder = stringResource(R.string.wg_persistent_keepalive_placeholder),
                    isError = persistentKeepalive.isNotEmpty() && persistentKeepalive.toIntOrNull() == null,
                    isModified = isEditMode && persistentKeepalive != initialWgConfig.persistentKeepalive,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        }
    }
}

@Composable
private fun VlessSettingsBlock(
    vlessLink: String, onVlessLinkChange: (String) -> Unit,
    vlessIsDualRoute: Boolean, onVlessIsDualRouteChange: (Boolean) -> Unit,
    vlessDirectAddress: String, onVlessDirectAddressChange: (String) -> Unit,
    vlessHcInterval: String, onVlessHcIntervalChange: (String) -> Unit,
    vlessLinkHistory: List<String>,
    onRemoveHistoryItem: (String) -> Unit,
    initialVlessConfig: VlessConfig,
    isPrivacyActive: Boolean,
    kernelVariant: KernelVariant,
    isEditMode: Boolean
) {
    val context = LocalContext.current
    val vlessName = remember(vlessLink, isPrivacyActive) {
        if (isPrivacyActive) ""
        else {
            val fragment = vlessLink.substringAfterLast('#', "")
            if (fragment.isNotEmpty()) " #${android.net.Uri.decode(fragment)}" else ""
        }
    }

    val vlessLinkError = if (kernelVariant == KernelVariant.OLCRTC) {
        vlessLink.isNotBlank() && !ValidatorUtils.isValidVlessLink(vlessLink)
    } else {
        !ValidatorUtils.isValidVlessLink(vlessLink)
    }

    Column(verticalArrangement = Arrangement.spacedBy(19.dp)) {
        SettingsGroup(title = stringResource(R.string.vless_settings)) {
            SettingsGroupItem(position = ItemPosition.Single) {
                TextFieldRow(
                    label = stringResource(R.string.vless_link_label) + vlessName,
                    value = vlessLink.redact(isPrivacyActive),
                    onValueChange = { if (!isPrivacyActive) onVlessLinkChange(it) },
                    placeholder = stringResource(R.string.vless_link_placeholder),
                    isError = vlessLinkError,
                    minLines = 4,
                    maxLines = 4,
                    singleLine = false,
                    readOnly = isPrivacyActive,
                    isModified = isEditMode && vlessLink.trim() != initialVlessConfig.vlessLink,
                    privacyMode = isPrivacyActive,
                    trailingIcon = {
                        FieldTrailingIcons(
                            history = vlessLinkHistory,
                            onSelect = onVlessLinkChange,
                            onRemove = onRemoveHistoryItem,
                            privacyMode = isPrivacyActive
                        )
                    }
                )
            }

            Spacer(Modifier.height(12.dp))

            SettingsGroupItem(
                position = if (vlessIsDualRoute) ItemPosition.Top else ItemPosition.Single,
                onClick = { 
                    val next = !vlessIsDualRoute
                    HapticUtil.perform(context, if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                    onVlessIsDualRouteChange(next)
                    if (next && vlessDirectAddress.isBlank()) {
                        ValidatorUtils.parseVlessAddress(vlessLink)?.let { addr ->
                            onVlessDirectAddressChange(addr)
                        }
                    }
                }
            ) {
                SwitchRow(
                    label = stringResource(R.string.vless_dual_route),
                    supportingText = stringResource(R.string.vless_dual_route_desc),
                    checked = vlessIsDualRoute,
                    onCheckedChange = { next ->
                        HapticUtil.perform(context, if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                        onVlessIsDualRouteChange(next)
                        if (next && vlessDirectAddress.isBlank()) {
                            ValidatorUtils.parseVlessAddress(vlessLink)?.let { addr ->
                                onVlessDirectAddressChange(addr)
                            }
                        }
                    },
                    isModified = isEditMode && vlessIsDualRoute != initialVlessConfig.isDualRoute
                )
            }

            AnimatedVisibility(
                visible = vlessIsDualRoute,
                enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                exit = fadeOut(tween(300)) + shrinkVertically(tween(300))
            ) {
                SettingsGroup {
                    SettingsGroupItem {
                        TextFieldRow(
                            label = stringResource(R.string.vless_direct_address),
                            value = vlessDirectAddress.redact(isPrivacyActive),
                            onValueChange = { if (!isPrivacyActive) onVlessDirectAddressChange(it) },
                            placeholder = stringResource(R.string.vless_direct_address_placeholder),
                            isError = !ValidatorUtils.isValidHostPort(vlessDirectAddress),
                            readOnly = isPrivacyActive,
                            isModified = isEditMode && vlessDirectAddress != initialVlessConfig.directAddress,
                            privacyMode = isPrivacyActive,
                            trailingIcon = {
                                IconButton(onClick = {
                                    ValidatorUtils.parseVlessAddress(vlessLink)?.let { addr ->
                                        onVlessDirectAddressChange(addr)
                                    }
                                }) {
                                    Icon(painterResource(R.drawable.sync_24px), stringResource(R.string.vless_parse_from_link))
                                }
                            }
                        )
                    }
                    SettingsGroupItem(position = ItemPosition.Bottom) {
                        TextFieldRow(
                            label = stringResource(R.string.vless_hc_interval),
                            value = vlessHcInterval,
                            onValueChange = onVlessHcIntervalChange,
                            placeholder = "30",
                            isError = vlessHcInterval.isNotEmpty() && vlessHcInterval.toIntOrNull() == null,
                            isModified = isEditMode && vlessHcInterval != initialVlessConfig.hcInterval,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                }
            }
        }
    }
}
