@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.R
import com.wireturn.app.data.KernelVariant
import com.wireturn.app.data.WgConfig
import com.wireturn.app.data.XrayConfiguration
import com.wireturn.app.ui.ConfigDropdownMenu
import com.wireturn.app.ui.ConfigRowLabel
import com.wireturn.app.ui.FieldTrailingIcons
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.trackScrollDelta
import com.wireturn.app.ui.LabeledButtonGroup
import com.wireturn.app.ui.LargeLeadingIcon
import com.wireturn.app.ui.configButtonGroupItem
import com.wireturn.app.ui.SettingsGroup
import com.wireturn.app.ui.SettingsGroupItem
import com.wireturn.app.ui.SwitchRow
import com.wireturn.app.ui.TextFieldRow
import com.wireturn.app.ui.ValidatorUtils
import com.wireturn.app.ui.redact
import com.wireturn.app.ui.showExclusiveSnackbar
import com.wireturn.app.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun XrayConfigScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val importSuccessMessage = stringResource(R.string.import_success)
    val importErrorMessage = stringResource(R.string.import_error)
    val errorWithDetailsFormat = stringResource(R.string.error_with_details)

    val privacyMode by viewModel.privacyMode.collectAsStateWithLifecycle()
    val currentProfileId by viewModel.currentProfileId.collectAsStateWithLifecycle()
    val savedWgConfig by viewModel.wgConfig.collectAsStateWithLifecycle()
    val clientConfig by viewModel.clientConfig.collectAsStateWithLifecycle()
    val xrayConfig by viewModel.xrayConfig.collectAsStateWithLifecycle()
    val vlessSaved by viewModel.vlessConfig.collectAsStateWithLifecycle()
    val vlessLinkHistory by viewModel.vlessLinkHistory.collectAsStateWithLifecycle()
    
    val scrollState = rememberScrollState()

    val wgConfigSnapshot by com.wireturn.app.XrayServiceState.wgConfigSnapshot.collectAsStateWithLifecycle()
    val vlessConfigSnapshot by com.wireturn.app.XrayServiceState.vlessConfigSnapshot.collectAsStateWithLifecycle()
    val xrayConfigSnapshot by com.wireturn.app.XrayServiceState.xrayConfigSnapshot.collectAsStateWithLifecycle()

    val isDark = com.wireturn.app.ui.theme.LocalIsDark.current
    val screenBackgroundColor = if (isDark) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLow
    val blockContainerColor = if (isDark) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface

    var xrayConfiguration by remember(xrayConfig) { mutableStateOf(xrayConfig.xrayConfiguration) }
    var socksBindAddress by remember(xrayConfig) { mutableStateOf(xrayConfig.socksBindAddress) }
    var httpBindAddress by remember(xrayConfig) { mutableStateOf(xrayConfig.httpBindAddress) }
    var isProxyAuthEnabled by remember(xrayConfig) { mutableStateOf(xrayConfig.isProxyAuthEnabled) }
    var proxyUser by remember(xrayConfig) { mutableStateOf(xrayConfig.proxyUser) }
    var proxyPass by remember(xrayConfig) { mutableStateOf(xrayConfig.proxyPass) }

    // WireGuard states
    var privateKey by remember(savedWgConfig) { mutableStateOf(savedWgConfig.privateKey) }
    var address by remember(savedWgConfig) { mutableStateOf(savedWgConfig.address) }
    var mtu by remember(savedWgConfig) { mutableStateOf(savedWgConfig.mtu) }
    var publicKey by remember(savedWgConfig) { mutableStateOf(savedWgConfig.publicKey) }
    var endpoint by remember(currentProfileId, clientConfig.connectableAddress) { mutableStateOf(clientConfig.connectableAddress) }
    var persistentKeepalive by remember(savedWgConfig) { mutableStateOf(savedWgConfig.persistentKeepalive) }

    // VLESS states
    var vlessLink by remember(vlessSaved) { mutableStateOf(vlessSaved.vlessLink) }
    var vlessIsDualRoute by remember(vlessSaved) { mutableStateOf(vlessSaved.isDualRoute) }
    var vlessDirectAddress by remember(vlessSaved) { mutableStateOf(vlessSaved.directAddress) }
    var vlessHcInterval by remember(vlessSaved) { mutableStateOf(vlessSaved.hcInterval) }

    val showUniversalQrScanner = remember { mutableStateOf(false) }

    // Auto-save debounced
    LaunchedEffect(xrayConfiguration, socksBindAddress, httpBindAddress, isProxyAuthEnabled, proxyUser, proxyPass) {
        delay(200)
        val next = xrayConfig.copy(
            xrayConfiguration = xrayConfiguration,
            socksBindAddress = socksBindAddress,
            httpBindAddress = httpBindAddress,
            isProxyAuthEnabled = isProxyAuthEnabled,
            proxyUser = proxyUser,
            proxyPass = proxyPass
        )
        if (next != xrayConfig) {
            viewModel.updateXrayConfig(next)
        }
    }

    LaunchedEffect(privateKey, address, mtu, publicKey, endpoint, persistentKeepalive) {
        delay(200)
        val next = WgConfig(
            privateKey = privateKey,
            address = address,
            mtu = mtu,
            publicKey = publicKey,
            endpoint = endpoint,
            persistentKeepalive = persistentKeepalive
        )
        if (next != savedWgConfig) {
            viewModel.updateWgConfig(next)
        }
    }

    LaunchedEffect(vlessLink, vlessIsDualRoute, vlessDirectAddress, vlessHcInterval) {
        delay(200)
        val next = com.wireturn.app.data.VlessConfig(
            vlessLink = vlessLink,
            vlessUseLocalAddress = true,
            isDualRoute = vlessIsDualRoute,
            directAddress = vlessDirectAddress,
            hcInterval = vlessHcInterval
        )
        if (next != vlessSaved) {
            viewModel.updateVlessConfig(next)
        }
    }

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
                            viewModel.updateWgConfigText(text)
                            scope.launch { 
                                snackbarHostState.showExclusiveSnackbar(importSuccessMessage) 
                            }
                        } else if (ValidatorUtils.isValidVlessLink(text)) {
                            xrayConfiguration = XrayConfiguration.VLESS
                            vlessLink = text
                            scope.launch { 
                                snackbarHostState.showExclusiveSnackbar(importSuccessMessage) 
                            }
                        } else {
                            scope.launch { 
                                snackbarHostState.showExclusiveSnackbar(importErrorMessage) 
                            }
                        }
                    }
                } catch (e: Exception) {
                    val fullError = errorWithDetailsFormat.format(importErrorMessage, e.message ?: "")
                    scope.launch { 
                        snackbarHostState.showExclusiveSnackbar(fullError) 
                    }
                }
            }
        }
    )

    val isSocksValid = remember(socksBindAddress) { socksBindAddress.isNotBlank() && ValidatorUtils.isValidHostPort(socksBindAddress) }
    val isHttpValid = remember(httpBindAddress) { httpBindAddress.isEmpty() || ValidatorUtils.isValidHostPort(httpBindAddress) }

    Scaffold(
        modifier = modifier,
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .padding(bottom = 64.dp)
            )
        },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.xray_title)) },
                actions = {
                    IconButton(
                        onClick = { showUniversalQrScanner.value = true }
                    ) {
                        Icon(
                            painterResource(R.drawable.qr_code_24px),
                            stringResource(R.string.qr_import)
                        )
                    }

                    var showImportMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { showImportMenu = true }) {
                        Icon(
                            painterResource(R.drawable.note_add_24px),
                            stringResource(R.string.xray_import_config)
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
                                        if (clipEntry != null) {
                                            val text = clipEntry.clipData.getItemAt(0).text?.toString() ?: ""
                                            val wgParsed = WgConfig.parse(text)
                                            if (wgParsed.isValid()) {
                                                xrayConfiguration = XrayConfiguration.WIREGUARD
                                                viewModel.updateWgConfigText(text)
                                                snackbarHostState.showExclusiveSnackbar(importSuccessMessage)
                                            } else if (ValidatorUtils.isValidVlessLink(text)) {
                                                xrayConfiguration = XrayConfiguration.VLESS
                                                vlessLink = text
                                                snackbarHostState.showExclusiveSnackbar(importSuccessMessage)
                                            } else {
                                                snackbarHostState.showExclusiveSnackbar(importErrorMessage)
                                            }
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

                    val canShare = when (xrayConfiguration) {
                        XrayConfiguration.WIREGUARD -> savedWgConfig.isValid()
                        XrayConfiguration.VLESS -> vlessSaved.isValid()
                    }

                    IconButton(
                        onClick = {
                            val textToShare = when (xrayConfiguration) {
                                XrayConfiguration.WIREGUARD -> savedWgConfig.toWgString()
                                XrayConfiguration.VLESS -> vlessSaved.vlessLink
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
                            painterResource(R.drawable.share_24px),
                            stringResource(R.string.share)
                        )
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = screenBackgroundColor
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
                .padding(horizontal = 16.dp)
                .trackScrollDelta(
                    onScrollDelta = { viewModel.onBottomBarScroll(it) },
                    onSettle = { viewModel.settleBottomBar(it) }
                )
                .verticalScroll(scrollState)
                .padding(bottom = 76.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // 1. Настройки прокси
            SettingsGroup(title = stringResource(R.string.xray_proxy_settings)) {
                SettingsGroupItem(isTop = true, isBottom = false, containerColor = blockContainerColor) {
                    TextFieldRow(
                        label = stringResource(R.string.xray_socks5),
                        value = socksBindAddress,
                        onValueChange = { socksBindAddress = it },
                        placeholder = stringResource(R.string.xray_socks5_placeholder),
                        isError = !isSocksValid || (socksBindAddress.isNotEmpty() && socksBindAddress == httpBindAddress),
                        supportingText = stringResource(R.string.xray_socks_desc),
                        isModified = xrayConfigSnapshot != null && socksBindAddress != xrayConfigSnapshot?.socksBindAddress
                    )
                }

                SettingsGroupItem(isTop = false, isBottom = true, containerColor = blockContainerColor) {
                    TextFieldRow(
                        label = stringResource(R.string.xray_http),
                        value = httpBindAddress,
                        onValueChange = { httpBindAddress = it },
                        placeholder = stringResource(R.string.xray_http_placeholder),
                        isError = !isHttpValid || (httpBindAddress.isNotEmpty() && socksBindAddress == httpBindAddress),
                        supportingText = stringResource(R.string.xray_http_desc),
                        isModified = xrayConfigSnapshot != null && httpBindAddress != xrayConfigSnapshot?.httpBindAddress
                    )
                }

                Spacer(Modifier.height(12.dp))

                SettingsGroupItem(
                    isTop = true,
                    isBottom = !isProxyAuthEnabled,
                    containerColor = blockContainerColor,
                    onClick = {
                        val newValue = !isProxyAuthEnabled
                        HapticUtil.perform(context, if (newValue) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                        isProxyAuthEnabled = newValue
                    }
                ) {
                    SwitchRow(
                        label = stringResource(R.string.xray_proxy_auth),
                        supportingText = stringResource(R.string.xray_proxy_auth_desc),
                        checked = isProxyAuthEnabled,
                        onCheckedChange = {
                            HapticUtil.perform(context, if (it) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                            isProxyAuthEnabled = it
                        },
                        isModified = xrayConfigSnapshot != null && isProxyAuthEnabled != xrayConfigSnapshot?.isProxyAuthEnabled
                    )
                }

                AnimatedVisibility(
                    visible = isProxyAuthEnabled,
                    enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                    exit = fadeOut(tween(300)) + shrinkVertically(tween(300))
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SettingsGroupItem(
                            isTop = false,
                            isBottom = false,
                            containerColor = blockContainerColor
                        ) {
                            TextFieldRow(
                                label = stringResource(R.string.xray_proxy_user),
                                value = proxyUser.redact(privacyMode),
                                onValueChange = { proxyUser = it },
                                placeholder = "admin",
                                supportingText = stringResource(R.string.xray_proxy_auth_hint),
                                isError = isProxyAuthEnabled && proxyUser.isNotEmpty() && !ValidatorUtils.isValidProxyUser(proxyUser),
                                readOnly = privacyMode,
                                isModified = xrayConfigSnapshot != null && proxyUser != xrayConfigSnapshot?.proxyUser
                            )
                        }
                        SettingsGroupItem(
                            isTop = false,
                            isBottom = true,
                            containerColor = blockContainerColor
                        ) {
                            TextFieldRow(
                                label = stringResource(R.string.xray_proxy_pass),
                                value = proxyPass.redact(privacyMode),
                                onValueChange = { proxyPass = it },
                                placeholder = "password",
                                supportingText = stringResource(R.string.xray_proxy_auth_hint),
                                isError = isProxyAuthEnabled && proxyPass.isNotEmpty() && !ValidatorUtils.isValidProxyPass(proxyPass),
                                readOnly = privacyMode,
                                isModified = xrayConfigSnapshot != null && proxyPass != xrayConfigSnapshot?.proxyPass
                            )
                        }
                    }
                }
            }

            // 2. Выбор протокола
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
                        supportingText = stringResource(R.string.xray_protocol_desc),
                        isModified = xrayConfigSnapshot != null && (wgConfigSnapshot != null && xrayConfiguration != XrayConfiguration.WIREGUARD || vlessConfigSnapshot != null && xrayConfiguration != XrayConfiguration.VLESS)
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

            // 3. Настройки протокола
            when (xrayConfiguration) {
                XrayConfiguration.WIREGUARD -> {
                    WireGuardSettings(
                        privateKey = privateKey,
                        onPrivateKeyChange = { if (!privacyMode) privateKey = it },
                        address = address,
                        onAddressChange = { if (!privacyMode) address = it },
                        mtu = mtu,
                        onMtuChange = { mtu = it },
                        publicKey = publicKey,
                        onPublicKeyChange = { if (!privacyMode) publicKey = it },
                        endpoint = endpoint,
                        onEndpointChange = { },
                        persistentKeepalive = persistentKeepalive,
                        onPersistentKeepaliveChange = { persistentKeepalive = it },
                        wgConfigSnapshot = wgConfigSnapshot,
                        privacyMode = privacyMode,
                        kernelVariant = clientConfig.kernelVariant,
                        blockContainerColor = blockContainerColor
                    )
                }
                XrayConfiguration.VLESS -> {
                    VlessSettings(
                        vlessLink = vlessLink,
                        onVlessLinkChange = { if (!privacyMode) vlessLink = it },
                        vlessIsDualRoute = vlessIsDualRoute,
                        onVlessIsDualRouteChange = {
                            HapticUtil.perform(context, if (it) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                            vlessIsDualRoute = it
                            if (it) {
                                if (vlessDirectAddress.isBlank()) {
                                    ValidatorUtils.parseVlessAddress(vlessLink)?.let { addr ->
                                        vlessDirectAddress = addr
                                    }
                                }
                            }
                        },
                        vlessDirectAddress = vlessDirectAddress,
                        onVlessDirectAddressChange = { if (!privacyMode) vlessDirectAddress = it },
                        vlessHcInterval = vlessHcInterval,
                        onVlessHcIntervalChange = { vlessHcInterval = it },
                        onParseDirectAddress = {
                            ValidatorUtils.parseVlessAddress(vlessLink)?.let { addr ->
                                vlessDirectAddress = addr
                            }
                        },
                        vlessLinkHistory = vlessLinkHistory,
                        onRemoveHistory = { viewModel.removeVlessLinkFromHistory(it) },
                        vlessConfigSnapshot = vlessConfigSnapshot,
                        privacyMode = privacyMode,
                        blockContainerColor = blockContainerColor,
                        scrollState = scrollState
                    )
                }
            }

        }
    }


    if (showUniversalQrScanner.value) {
        QrScannerDialog(
            title = stringResource(R.string.qr_import),
            message = stringResource(R.string.qr_scan_desc),
            onDismiss = { showUniversalQrScanner.value = false },
            onResult = { result ->
                val wgParsed = WgConfig.parse(result)
                if (wgParsed.isValid()) {
                    xrayConfiguration = XrayConfiguration.WIREGUARD
                    viewModel.updateWgConfigText(result)
                    scope.launch { 
                        snackbarHostState.showExclusiveSnackbar(importSuccessMessage) 
                    }
                } else if (ValidatorUtils.isValidVlessLink(result)) {
                    xrayConfiguration = XrayConfiguration.VLESS
                    vlessLink = result
                    scope.launch { 
                        snackbarHostState.showExclusiveSnackbar(importSuccessMessage) 
                    }
                } else {
                    scope.launch { 
                        snackbarHostState.showExclusiveSnackbar(importErrorMessage)
                    }
                }
            }
        )
    }
}

@Composable
private fun WireGuardSettings(
    privateKey: String,
    onPrivateKeyChange: (String) -> Unit,
    address: String,
    onAddressChange: (String) -> Unit,
    mtu: String,
    onMtuChange: (String) -> Unit,
    publicKey: String,
    onPublicKeyChange: (String) -> Unit,
    endpoint: String,
    onEndpointChange: (String) -> Unit,
    persistentKeepalive: String,
    onPersistentKeepaliveChange: (String) -> Unit,
    wgConfigSnapshot: WgConfig?,
    privacyMode: Boolean,
    kernelVariant: KernelVariant,
    blockContainerColor: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (kernelVariant == KernelVariant.OLCRTC) {
            SettingsGroupItem(
                isTop = true,
                isBottom = true,
                containerColor = blockContainerColor
            ) {
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
            SettingsGroupItem(isTop = true, isBottom = false, containerColor = blockContainerColor) {
                TextFieldRow(
                    label = stringResource(R.string.wg_private_key),
                    value = privateKey.redact(privacyMode),
                    onValueChange = onPrivateKeyChange,
                    placeholder = stringResource(R.string.wg_private_key_placeholder),
                    isError = privateKey.isBlank(),
                    readOnly = privacyMode,
                    isModified = wgConfigSnapshot != null && privateKey != wgConfigSnapshot.privateKey
                )
            }

            SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                TextFieldRow(
                    label = stringResource(R.string.wg_address),
                    value = address.redact(privacyMode),
                    onValueChange = onAddressChange,
                    placeholder = stringResource(R.string.wg_address_placeholder),
                    isError = address.isBlank(),
                    readOnly = privacyMode,
                    isModified = wgConfigSnapshot != null && address != wgConfigSnapshot.address
                )
            }

            SettingsGroupItem(isTop = false, isBottom = true, containerColor = blockContainerColor) {
                TextFieldRow(
                    label = stringResource(R.string.wg_mtu),
                    value = mtu,
                    onValueChange = onMtuChange,
                    placeholder = stringResource(R.string.wg_mtu_placeholder),
                    isError = mtu.isNotEmpty() && mtu.toIntOrNull() == null,
                    isModified = wgConfigSnapshot != null && mtu != wgConfigSnapshot.mtu,
                    supportingText = if (mtu != "1280") stringResource(R.string.wg_mtu_recommendation) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        }

        SettingsGroup(title = stringResource(R.string.wg_peer)) {
            SettingsGroupItem(isTop = true, isBottom = false, containerColor = blockContainerColor) {
                TextFieldRow(
                    label = stringResource(R.string.wg_public_key),
                    value = publicKey.redact(privacyMode),
                    onValueChange = onPublicKeyChange,
                    placeholder = stringResource(R.string.wg_public_key_placeholder),
                    isError = publicKey.isBlank(),
                    readOnly = privacyMode,
                    isModified = wgConfigSnapshot != null && publicKey != wgConfigSnapshot.publicKey
                )
            }

            SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                TextFieldRow(
                    label = stringResource(R.string.wg_endpoint),
                    value = endpoint.redact(privacyMode),
                    onValueChange = onEndpointChange,
                    placeholder = stringResource(R.string.wg_endpoint_placeholder),
                    readOnly = true,
                    isModified = wgConfigSnapshot != null && endpoint != wgConfigSnapshot.endpoint
                )
            }

            SettingsGroupItem(isTop = false, isBottom = true, containerColor = blockContainerColor) {
                TextFieldRow(
                    label = stringResource(R.string.wg_persistent_keepalive),
                    value = persistentKeepalive,
                    onValueChange = onPersistentKeepaliveChange,
                    placeholder = stringResource(R.string.wg_persistent_keepalive_placeholder),
                    isError = persistentKeepalive.isNotEmpty() && persistentKeepalive.toIntOrNull() == null,
                    isModified = wgConfigSnapshot != null && persistentKeepalive != wgConfigSnapshot.persistentKeepalive,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        }
    }
}

@Composable
private fun VlessSettings(
    vlessLink: String,
    onVlessLinkChange: (String) -> Unit,
    vlessIsDualRoute: Boolean,
    onVlessIsDualRouteChange: (Boolean) -> Unit,
    vlessDirectAddress: String,
    onVlessDirectAddressChange: (String) -> Unit,
    vlessHcInterval: String,
    onVlessHcIntervalChange: (String) -> Unit,
    onParseDirectAddress: () -> Unit,
    vlessLinkHistory: List<String>,
    onRemoveHistory: (String) -> Unit,
    vlessConfigSnapshot: com.wireturn.app.data.VlessConfig?,
    privacyMode: Boolean,
    blockContainerColor: Color,
    scrollState: ScrollState
) {
    var previousDualRoute by remember { mutableStateOf(vlessIsDualRoute) }
    LaunchedEffect(vlessIsDualRoute) {
        if (vlessIsDualRoute && !previousDualRoute) {
            val scrollJob = launch {
                snapshotFlow { scrollState.maxValue }.collect {
                    scrollState.scrollTo(it)
                }
            }
            delay(300)
            scrollJob.cancel()
        }
        previousDualRoute = vlessIsDualRoute
    }

    val vlessName = remember(vlessLink) {
        val fragment = vlessLink.substringAfterLast('#', "")
        if (fragment.isNotEmpty()) " #${android.net.Uri.decode(fragment)}" else ""
    }

    SettingsGroup(title = stringResource(R.string.vless_settings)) {
        SettingsGroupItem(isTop = true, isBottom = true, containerColor = blockContainerColor) {
            TextFieldRow(
                label = stringResource(R.string.vless_link_label) + vlessName,
                value = vlessLink.redact(privacyMode),
                onValueChange = onVlessLinkChange,
                placeholder = stringResource(R.string.vless_link_placeholder),
                isError = !ValidatorUtils.isValidVlessLink(vlessLink) || vlessLink.isBlank(),
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 4,
                singleLine = false,
                readOnly = privacyMode,
                supportingText = stringResource(R.string.vless_link_config_desc),
                isModified = vlessConfigSnapshot != null && vlessLink.trim() != vlessConfigSnapshot.vlessLink,
                trailingIcon = {
                    FieldTrailingIcons(
                        history = vlessLinkHistory,
                        onSelect = onVlessLinkChange,
                        onRemove = onRemoveHistory,
                        privacyMode = privacyMode
                    )
                }
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsGroupItem(
            isTop = true,
            isBottom = !vlessIsDualRoute,
            containerColor = blockContainerColor,
            onClick = {
                onVlessIsDualRouteChange(!vlessIsDualRoute)
            }
        ) {
            SwitchRow(
                label = stringResource(R.string.vless_dual_route),
                supportingText = stringResource(R.string.vless_dual_route_desc),
                checked = vlessIsDualRoute,
                onCheckedChange = onVlessIsDualRouteChange,
                isModified = vlessConfigSnapshot != null && vlessIsDualRoute != vlessConfigSnapshot.isDualRoute
            )
        }

        AnimatedVisibility(
            visible = vlessIsDualRoute,
            enter = fadeIn(tween(300)) + expandVertically(tween(300)),
            exit = fadeOut(tween(300)) + shrinkVertically(tween(300))
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingsGroupItem(
                    isTop = false,
                    isBottom = false,
                    containerColor = blockContainerColor
                ) {
                    TextFieldRow(
                        label = stringResource(R.string.vless_direct_address),
                        value = vlessDirectAddress.redact(privacyMode),
                        onValueChange = onVlessDirectAddressChange,
                        placeholder = stringResource(R.string.vless_direct_address_placeholder),
                        isError = vlessDirectAddress.isBlank() || !ValidatorUtils.isValidHostPort(vlessDirectAddress),
                        readOnly = privacyMode,
                        supportingText = stringResource(R.string.vless_direct_address_desc),
                        isModified = vlessConfigSnapshot != null && vlessDirectAddress != vlessConfigSnapshot.directAddress,
                        trailingIcon = {
                            IconButton(onClick = onParseDirectAddress) {
                                Icon(painterResource(R.drawable.sync_24px), stringResource(R.string.vless_parse_from_link))
                            }
                        }
                    )
                }

                SettingsGroupItem(
                    isTop = false,
                    isBottom = true,
                    containerColor = blockContainerColor
                ) {
                    TextFieldRow(
                        label = stringResource(R.string.vless_hc_interval),
                        value = vlessHcInterval,
                        onValueChange = onVlessHcIntervalChange,
                        placeholder = "30",
                        isError = vlessHcInterval.isNotEmpty() && vlessHcInterval.toIntOrNull() == null,
                        supportingText = stringResource(R.string.vless_hc_interval_desc),
                        isModified = vlessConfigSnapshot != null && vlessHcInterval != vlessConfigSnapshot.hcInterval,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            }
        }
    }
}

