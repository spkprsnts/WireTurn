@file:OptIn(ExperimentalMaterial3Api::class)

package com.wireturn.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.wireturn.app.ui.ConfigDropdownMenu
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.LabeledButtonGroup
import com.wireturn.app.ui.SettingsGroup
import com.wireturn.app.ui.SettingsGroupItem
import com.wireturn.app.ui.SwitchRow
import com.wireturn.app.ui.TextFieldRow
import com.wireturn.app.ui.ValidatorUtils
import com.wireturn.app.ui.configButtonGroupItem
import com.wireturn.app.ui.showExclusiveSnackbar
import kotlinx.coroutines.launch
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun XraySetupScreen(
    showProtocolSelection: Boolean,
    defaultProtocol: XrayConfiguration? = null,
    onBack: () -> Unit,
    onSave: (XrayConfiguration, WgConfig, VlessConfig) -> Unit
) {
    var xrayConfiguration by remember { 
        mutableStateOf(
            defaultProtocol ?: (if (showProtocolSelection) XrayConfiguration.WIREGUARD else XrayConfiguration.VLESS)
        )
    }
    
    // WireGuard states
    var privateKey by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var mtu by remember { mutableStateOf("1280") }
    var publicKey by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("127.0.0.1:9000") }
    var persistentKeepalive by remember { mutableStateOf("25") }

    // VLESS states
    var vlessLink by remember { mutableStateOf("") }
    var vlessIsDualRoute by remember { mutableStateOf(false) }
    var vlessDirectAddress by remember { mutableStateOf("") }
    var vlessHcInterval by remember { mutableStateOf("30") }

    val scrollState = rememberScrollState()
    val isDark = com.wireturn.app.ui.theme.LocalIsDark.current
    val blockContainerColor = if (isDark) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val showQrScanner = remember { mutableStateOf(false) }

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
                            if (showProtocolSelection) {
                                xrayConfiguration = XrayConfiguration.WIREGUARD
                                privateKey = wgParsed.privateKey
                                address = wgParsed.address
                                mtu = wgParsed.mtu
                                publicKey = wgParsed.publicKey
                                endpoint = wgParsed.endpoint
                                persistentKeepalive = wgParsed.persistentKeepalive
                                scope.launch { snackbarHostState.showExclusiveSnackbar(importSuccessMessage) }
                            }
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

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.xray_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back_24px),
                            contentDescription = null
                        )
                    }
                },
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
                                            if (showProtocolSelection) {
                                                xrayConfiguration = XrayConfiguration.WIREGUARD
                                                privateKey = wgParsed.privateKey
                                                address = wgParsed.address
                                                mtu = wgParsed.mtu
                                                publicKey = wgParsed.publicKey
                                                endpoint = wgParsed.endpoint
                                                persistentKeepalive = wgParsed.persistentKeepalive
                                                snackbarHostState.showExclusiveSnackbar(importSuccessMessage)
                                            }
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

                    IconButton(onClick = { 
                        onSave(
                            xrayConfiguration,
                            WgConfig(privateKey, address, mtu, publicKey, endpoint, persistentKeepalive),
                            VlessConfig(vlessLink, true, vlessIsDualRoute, vlessDirectAddress, vlessHcInterval)
                        ) 
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.check_24px),
                            contentDescription = stringResource(R.string.btn_save)
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
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            if (showProtocolSelection) {
                SettingsGroup(title = stringResource(R.string.xray_protocol_label)) {
                    SettingsGroupItem(
                        isTop = true,
                        isBottom = true,
                        containerColor = blockContainerColor
                    ) {
                        val configurations = XrayConfiguration.entries
                        val protocolLabels = configurations.associateWith { config ->
                            stringResource(when (config) {
                                XrayConfiguration.WIREGUARD -> R.string.protocol_wireguard
                                XrayConfiguration.VLESS -> R.string.vless
                            })
                        }
                        LabeledButtonGroup(
                            label = stringResource(R.string.xray_protocol_label),
                            supportingText = stringResource(R.string.xray_protocol_desc)
                        ) {
                            configurations.forEachIndexed { index, config ->
                                configButtonGroupItem(
                                    selected = xrayConfiguration == config,
                                    onSelect = {
                                        HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
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
                        privateKey = privateKey, onPrivateKeyChange = { privateKey = it },
                        address = address, onAddressChange = { address = it },
                        mtu = mtu, onMtuChange = { mtu = it },
                        publicKey = publicKey, onPublicKeyChange = { publicKey = it },
                        endpoint = endpoint,
                        persistentKeepalive = persistentKeepalive, onPersistentKeepaliveChange = { persistentKeepalive = it },
                        blockContainerColor = blockContainerColor
                    )
                }
                XrayConfiguration.VLESS -> {
                    VlessSettingsBlock(
                        vlessLink = vlessLink, onVlessLinkChange = { vlessLink = it },
                        vlessIsDualRoute = vlessIsDualRoute, onVlessIsDualRouteChange = { vlessIsDualRoute = it },
                        vlessDirectAddress = vlessDirectAddress, onVlessDirectAddressChange = { vlessDirectAddress = it },
                        vlessHcInterval = vlessHcInterval, onVlessHcIntervalChange = { vlessHcInterval = it },
                        blockContainerColor = blockContainerColor
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
                    if (showProtocolSelection) {
                        xrayConfiguration = XrayConfiguration.WIREGUARD
                        privateKey = wgParsed.privateKey
                        address = wgParsed.address
                        mtu = wgParsed.mtu
                        publicKey = wgParsed.publicKey
                        endpoint = wgParsed.endpoint
                        persistentKeepalive = wgParsed.persistentKeepalive
                        scope.launch { snackbarHostState.showExclusiveSnackbar(importSuccessMessage) }
                    }
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
    blockContainerColor: androidx.compose.ui.graphics.Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        SettingsGroup(title = stringResource(R.string.wg_interface)) {
            SettingsGroupItem(isTop = true, isBottom = false, containerColor = blockContainerColor) {
                TextFieldRow(
                    label = stringResource(R.string.wg_private_key),
                    value = privateKey,
                    onValueChange = onPrivateKeyChange,
                    placeholder = stringResource(R.string.wg_private_key_placeholder)
                )
            }
            SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                TextFieldRow(
                    label = stringResource(R.string.wg_address),
                    value = address,
                    onValueChange = onAddressChange,
                    placeholder = stringResource(R.string.wg_address_placeholder)
                )
            }
            SettingsGroupItem(isTop = false, isBottom = true, containerColor = blockContainerColor) {
                TextFieldRow(
                    label = stringResource(R.string.wg_mtu),
                    value = mtu,
                    onValueChange = onMtuChange,
                    placeholder = stringResource(R.string.wg_mtu_placeholder),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        }

        SettingsGroup(title = stringResource(R.string.wg_peer)) {
            SettingsGroupItem(isTop = true, isBottom = false, containerColor = blockContainerColor) {
                TextFieldRow(
                    label = stringResource(R.string.wg_public_key),
                    value = publicKey,
                    onValueChange = onPublicKeyChange,
                    placeholder = stringResource(R.string.wg_public_key_placeholder)
                )
            }
            SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                TextFieldRow(
                    label = stringResource(R.string.wg_endpoint),
                    value = endpoint,
                    onValueChange = { },
                    readOnly = true
                )
            }
            SettingsGroupItem(isTop = false, isBottom = true, containerColor = blockContainerColor) {
                TextFieldRow(
                    label = stringResource(R.string.wg_persistent_keepalive),
                    value = persistentKeepalive,
                    onValueChange = onPersistentKeepaliveChange,
                    placeholder = stringResource(R.string.wg_persistent_keepalive_placeholder),
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
    blockContainerColor: androidx.compose.ui.graphics.Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        SettingsGroup(title = stringResource(R.string.vless_settings)) {
            SettingsGroupItem(isTop = true, isBottom = true, containerColor = blockContainerColor) {
                TextFieldRow(
                    label = stringResource(R.string.vless_link_label),
                    value = vlessLink,
                    onValueChange = onVlessLinkChange,
                    placeholder = stringResource(R.string.vless_link_placeholder),
                    minLines = 4,
                    maxLines = 4,
                    singleLine = false
                )
            }

            Spacer(Modifier.height(12.dp))

            SettingsGroupItem(
                isTop = true,
                isBottom = !vlessIsDualRoute,
                containerColor = blockContainerColor,
                onClick = { onVlessIsDualRouteChange(!vlessIsDualRoute) }
            ) {
                SwitchRow(
                    label = stringResource(R.string.vless_dual_route),
                    supportingText = stringResource(R.string.vless_dual_route_desc),
                    checked = vlessIsDualRoute,
                    onCheckedChange = onVlessIsDualRouteChange
                )
            }

            AnimatedVisibility(
                visible = vlessIsDualRoute,
                enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                exit = fadeOut(tween(300)) + shrinkVertically(tween(300))
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                        TextFieldRow(
                            label = stringResource(R.string.vless_direct_address),
                            value = vlessDirectAddress,
                            onValueChange = onVlessDirectAddressChange,
                            placeholder = stringResource(R.string.vless_direct_address_placeholder)
                        )
                    }
                    SettingsGroupItem(isTop = false, isBottom = true, containerColor = blockContainerColor) {
                        TextFieldRow(
                            label = stringResource(R.string.vless_hc_interval),
                            value = vlessHcInterval,
                            onValueChange = onVlessHcIntervalChange,
                            placeholder = "30",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                }
            }
        }
    }
}
