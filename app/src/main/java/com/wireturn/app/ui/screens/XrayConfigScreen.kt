@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wireturn.app.ui.screens

import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.R
import com.wireturn.app.data.WgConfig
import com.wireturn.app.data.XrayConfiguration
import com.wireturn.app.ui.FieldTrailingIcons
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.LabeledSegmentedButton
import com.wireturn.app.ui.SettingsGroup
import com.wireturn.app.ui.SettingsGroupItem
import com.wireturn.app.ui.SwitchRow
import com.wireturn.app.ui.TextFieldRow
import com.wireturn.app.ui.ValidatorUtils
import com.wireturn.app.ui.redact
import com.wireturn.app.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun XrayConfigScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    showFinishButton: Boolean = false,
    onFinish: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val importSuccessMessage = stringResource(R.string.wg_import_success)
    val importErrorMessage = stringResource(R.string.wg_import_error)
    val errorWithDetailsFormat = stringResource(R.string.error_with_details)

    val privacyMode by viewModel.privacyMode.collectAsStateWithLifecycle()
    val currentProfileId by viewModel.currentProfileId.collectAsStateWithLifecycle()
    val savedWgConfig by viewModel.wgConfig.collectAsStateWithLifecycle()
    val clientConfig by viewModel.clientConfig.collectAsStateWithLifecycle()
    val xrayConfig by viewModel.xrayConfig.collectAsStateWithLifecycle()
    val vlessSaved by viewModel.vlessConfig.collectAsStateWithLifecycle()
    val vlessLinkHistory by viewModel.vlessLinkHistory.collectAsStateWithLifecycle()
    
    val runningWgConfig by com.wireturn.app.XrayServiceState.runningWgConfig.collectAsStateWithLifecycle()
    val runningVlessConfig by com.wireturn.app.XrayServiceState.runningVlessConfig.collectAsStateWithLifecycle()
    val runningXrayConfig by com.wireturn.app.XrayServiceState.runningXrayConfig.collectAsStateWithLifecycle()

    val isDark = com.wireturn.app.ui.theme.LocalIsDark.current
    val screenBackgroundColor = if (isDark) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLow
    val blockContainerColor = if (isDark) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface

    var xrayConfiguration by rememberSaveable(currentProfileId, xrayConfig.xrayConfiguration) { mutableStateOf(xrayConfig.xrayConfiguration) }
    var socksBindAddress by rememberSaveable(currentProfileId, xrayConfig.socksBindAddress) { mutableStateOf(xrayConfig.socksBindAddress) }
    var httpBindAddress by rememberSaveable(currentProfileId, xrayConfig.httpBindAddress) { mutableStateOf(xrayConfig.httpBindAddress) }

    // WireGuard states
    var privateKey by rememberSaveable(currentProfileId, savedWgConfig.privateKey) { mutableStateOf(savedWgConfig.privateKey) }
    var address by rememberSaveable(currentProfileId, savedWgConfig.address) { mutableStateOf(savedWgConfig.address) }
    var mtu by rememberSaveable(currentProfileId, savedWgConfig.mtu) { mutableStateOf(savedWgConfig.mtu) }
    var publicKey by rememberSaveable(currentProfileId, savedWgConfig.publicKey) { mutableStateOf(savedWgConfig.publicKey) }
    var endpoint by rememberSaveable(currentProfileId, savedWgConfig.endpoint) { mutableStateOf(savedWgConfig.endpoint) }
    var persistentKeepalive by rememberSaveable(currentProfileId, savedWgConfig.persistentKeepalive) { mutableStateOf(savedWgConfig.persistentKeepalive) }

    // VLESS states
    var vlessLink by rememberSaveable(currentProfileId, vlessSaved.vlessLink) { mutableStateOf(vlessSaved.vlessLink) }
    var vlessUseLocalAddress by rememberSaveable(currentProfileId, vlessSaved.vlessUseLocalAddress) { mutableStateOf(vlessSaved.vlessUseLocalAddress) }
    var vlessIsDualRoute by rememberSaveable(currentProfileId, vlessSaved.isDualRoute) { mutableStateOf(vlessSaved.isDualRoute) }
    var vlessDirectAddress by rememberSaveable(currentProfileId, vlessSaved.directAddress) { mutableStateOf(vlessSaved.directAddress) }

    val showQrScanner = remember { mutableStateOf(false) }
    val showVlessQrScanner = remember { mutableStateOf(false) }
    val showUniversalQrScanner = remember { mutableStateOf(false) }

    // Sync local state with saved config when it changes externally
    LaunchedEffect(savedWgConfig) {
        privateKey = savedWgConfig.privateKey
        address = savedWgConfig.address
        mtu = savedWgConfig.mtu
        publicKey = savedWgConfig.publicKey
        endpoint = savedWgConfig.endpoint
        persistentKeepalive = savedWgConfig.persistentKeepalive
    }

    LaunchedEffect(xrayConfig) {
        xrayConfiguration = xrayConfig.xrayConfiguration
        socksBindAddress = xrayConfig.socksBindAddress
        httpBindAddress = xrayConfig.httpBindAddress
    }

    LaunchedEffect(vlessSaved) {
        vlessLink = vlessSaved.vlessLink
        vlessUseLocalAddress = vlessSaved.vlessUseLocalAddress
        vlessIsDualRoute = vlessSaved.isDualRoute
        vlessDirectAddress = vlessSaved.directAddress
    }

    // Auto-save debounced
    LaunchedEffect(xrayConfiguration, socksBindAddress, httpBindAddress) {
        delay(200)
        val next = xrayConfig.copy(
            xrayConfiguration = xrayConfiguration,
            socksBindAddress = socksBindAddress,
            httpBindAddress = httpBindAddress
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

    LaunchedEffect(vlessLink, vlessUseLocalAddress, vlessIsDualRoute, vlessDirectAddress) {
        delay(200)
        val next = com.wireturn.app.data.VlessConfig(
            vlessLink = vlessLink,
            vlessUseLocalAddress = vlessUseLocalAddress,
            isDualRoute = vlessIsDualRoute,
            directAddress = vlessDirectAddress
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
                        val text = input.bufferedReader().use { r -> r.readText() }
                        val parsed = WgConfig.parse(text)
                        if (parsed.isValid()) {
                            viewModel.updateWgConfigText(text)
                            scope.launch { snackbarHostState.showSnackbar(importSuccessMessage) }
                        } else {
                            scope.launch { snackbarHostState.showSnackbar(importErrorMessage) }
                        }
                    }
                } catch (e: Exception) {
                    val fullError = errorWithDetailsFormat.format(importErrorMessage, e.message ?: "")
                    scope.launch { snackbarHostState.showSnackbar(fullError) }
                }
            }
        }
    )

    val isEndpointValid = remember(endpoint) { ValidatorUtils.isValidHostPort(endpoint) }
    val isTargetEndpoint = remember(endpoint, clientConfig.connectableAddress) {
        clientConfig.connectableAddress == endpoint
    }
    val isSocksValid = remember(socksBindAddress) { ValidatorUtils.isValidHostPort(socksBindAddress) }
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
                    var isCopied by remember { mutableStateOf(false) }
                    LaunchedEffect(isCopied) {
                        if (isCopied) {
                            delay(1500)
                            isCopied = false
                        }
                    }

                    val canCopy = when (xrayConfiguration) {
                        XrayConfiguration.WIREGUARD -> savedWgConfig.isValid()
                        XrayConfiguration.VLESS -> vlessSaved.isValid()
                    }

                    IconButton(
                        onClick = { showUniversalQrScanner.value = true }
                    ) {
                        Icon(
                            painterResource(R.drawable.qr_code_24px),
                            stringResource(R.string.qr_import),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    IconButton(
                        onClick = {
                            isCopied = true
                            scope.launch {
                                val textToCopy = when (xrayConfiguration) {
                                    XrayConfiguration.WIREGUARD -> savedWgConfig.toWgString()
                                    XrayConfiguration.VLESS -> vlessSaved.vlessLink
                                }
                                clipboard.setClipEntry(ClipData.newPlainText("config", textToCopy).toClipEntry())
                                HapticUtil.perform(context, HapticUtil.Pattern.SUCCESS)
                            }
                        },
                        enabled = canCopy
                    ) {
                        Icon(
                            painterResource(if (isCopied) R.drawable.check_circle_24px else R.drawable.content_copy_24px),
                            stringResource(R.string.copy),
                            tint = when {
                                isCopied -> MaterialTheme.colorScheme.primary
                                !canCopy -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                else -> MaterialTheme.colorScheme.onSurface
                            }
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
                .verticalScroll(rememberScrollState())
                .padding(bottom = 76.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // 1. Локальные адреса
            SettingsGroup(title = stringResource(R.string.xray_local_addresses)) {
                SettingsGroupItem(isTop = true, isBottom = false, containerColor = blockContainerColor) {
                    TextFieldRow(
                        label = stringResource(R.string.xray_socks5),
                        value = socksBindAddress,
                        onValueChange = { socksBindAddress = it },
                        placeholder = stringResource(R.string.xray_socks5_placeholder),
                        isError = !isSocksValid || (socksBindAddress.isNotEmpty() && socksBindAddress == httpBindAddress),
                        supportingText = stringResource(R.string.xray_socks_desc),
                        isModified = runningXrayConfig != null && socksBindAddress != runningXrayConfig?.socksBindAddress
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
                        isModified = runningXrayConfig != null && httpBindAddress != runningXrayConfig?.httpBindAddress
                    )
                }
            }

            // 2. Выбор протокола
            SettingsGroup(title = stringResource(R.string.xray_protocol_label)) {
                SettingsGroupItem(
                    isTop = true,
                    isBottom = true,
                    containerColor = blockContainerColor
                ) {
                    LabeledSegmentedButton(
                        label = stringResource(R.string.xray_protocol_label),
                        supportingText = stringResource(R.string.xray_protocol_desc),
                        isModified = runningXrayConfig != null && (runningWgConfig != null && xrayConfiguration != XrayConfiguration.WIREGUARD || runningVlessConfig != null && xrayConfiguration != XrayConfiguration.VLESS)
                    ) {
                        XrayConfiguration.entries.forEachIndexed { index, config ->
                            SegmentedButton(
                                selected = xrayConfiguration == config,
                                onClick = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                    xrayConfiguration = config
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = XrayConfiguration.entries.size)
                            ) {
                                Text(when (config) {
                                    XrayConfiguration.WIREGUARD -> stringResource(R.string.protocol_wireguard)
                                    XrayConfiguration.VLESS -> stringResource(R.string.vless)
                                })
                            }
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
                        onEndpointChange = { if (!privacyMode) endpoint = it },
                        persistentKeepalive = persistentKeepalive,
                        onPersistentKeepaliveChange = { persistentKeepalive = it },
                        isEndpointValid = isEndpointValid,
                        isTargetEndpoint = isTargetEndpoint,
                        onFixEndpoint = { endpoint = clientConfig.connectableAddress },
                        runningWgConfig = runningWgConfig,
                        privacyMode = privacyMode,
                        onImportFile = { filePickerLauncher.launch("*/*") },
                        onImportQr = { showQrScanner.value = true },
                        onImportClipboard = {
                            scope.launch {
                                val clipEntry = clipboard.getClipEntry()
                                if (clipEntry != null) {
                                    val text = clipEntry.clipData.getItemAt(0).text?.toString() ?: ""
                                    val parsed = WgConfig.parse(text)
                                    if (parsed.isValid()) {
                                        viewModel.updateWgConfigText(text)
                                        snackbarHostState.showSnackbar(importSuccessMessage)
                                    } else {
                                        snackbarHostState.showSnackbar(importErrorMessage)
                                    }
                                }
                            }
                        },
                        blockContainerColor = blockContainerColor
                    )
                }
                XrayConfiguration.VLESS -> {
                    VlessSettings(
                        vlessLink = vlessLink,
                        onVlessLinkChange = { if (!privacyMode) vlessLink = it },
                        vlessUseLocalAddress = vlessUseLocalAddress,
                        onVlessUseLocalAddressChange = {
                            HapticUtil.perform(context, if (it) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                            vlessUseLocalAddress = it
                        },
                        vlessIsDualRoute = vlessIsDualRoute,
                        onVlessIsDualRouteChange = {
                            HapticUtil.perform(context, if (it) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                            vlessIsDualRoute = it
                            if (it) {
                                vlessUseLocalAddress = true
                                if (vlessDirectAddress.isBlank()) {
                                    ValidatorUtils.parseVlessAddress(vlessLink)?.let { addr ->
                                        vlessDirectAddress = addr
                                    }
                                }
                            }
                        },
                        vlessDirectAddress = vlessDirectAddress,
                        onVlessDirectAddressChange = { if (!privacyMode) vlessDirectAddress = it },
                        onParseDirectAddress = {
                            ValidatorUtils.parseVlessAddress(vlessLink)?.let { addr ->
                                vlessDirectAddress = addr
                            }
                        },
                        vlessLinkHistory = vlessLinkHistory,
                        onRemoveHistory = { viewModel.removeVlessLinkFromHistory(it) },
                        runningVlessConfig = runningVlessConfig,
                        privacyMode = privacyMode,
                        onImportQr = { showVlessQrScanner.value = true },
                        blockContainerColor = blockContainerColor
                    )
                }
            }

            if (showFinishButton && onFinish != null) {
                val isValid = when (xrayConfiguration) {
                    XrayConfiguration.WIREGUARD -> savedWgConfig.isValid()
                    XrayConfiguration.VLESS -> vlessSaved.isValid()
                } && isSocksValid && isHttpValid
                
                Button(
                    onClick = onFinish,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    enabled = isValid,
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(stringResource(R.string.finish_setup), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }

    if (showQrScanner.value) {
        QrScannerDialog(
            title = stringResource(R.string.wg_import_qr),
            message = stringResource(R.string.wg_qr_scan_desc),
            onDismiss = { showQrScanner.value = false },
            onResult = { result ->
                val parsed = WgConfig.parse(result)
                if (parsed.isValid()) {
                    viewModel.updateWgConfigText(result)
                    scope.launch { snackbarHostState.showSnackbar(importSuccessMessage) }
                } else {
                    scope.launch { snackbarHostState.showSnackbar(importErrorMessage) }
                }
            }
        )
    }

    if (showVlessQrScanner.value) {
        QrScannerDialog(
            title = stringResource(R.string.vless_import_qr),
            message = stringResource(R.string.vless_qr_scan_desc),
            onDismiss = { showVlessQrScanner.value = false },
            onResult = { result ->
                if (ValidatorUtils.isValidVlessLink(result)) {
                    vlessLink = result
                    scope.launch { snackbarHostState.showSnackbar(importSuccessMessage) }
                } else {
                    scope.launch { snackbarHostState.showSnackbar(importErrorMessage) }
                }
            }
        )
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
                    scope.launch { snackbarHostState.showSnackbar(importSuccessMessage) }
                } else if (ValidatorUtils.isValidVlessLink(result)) {
                    xrayConfiguration = XrayConfiguration.VLESS
                    vlessLink = result
                    scope.launch { snackbarHostState.showSnackbar(importSuccessMessage) }
                } else {
                    scope.launch { snackbarHostState.showSnackbar(importErrorMessage) }
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
    isEndpointValid: Boolean,
    isTargetEndpoint: Boolean,
    onFixEndpoint: () -> Unit,
    runningWgConfig: WgConfig?,
    privacyMode: Boolean,
    onImportFile: () -> Unit,
    onImportQr: () -> Unit,
    onImportClipboard: () -> Unit,
    blockContainerColor: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        SettingsGroup(title = stringResource(R.string.wg_import_config)) {
            SettingsGroupItem(isTop = true, isBottom = true, containerColor = blockContainerColor) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImportButton(onClick = onImportFile, icon = R.drawable.file_open_24px, label = stringResource(R.string.wg_import_file), modifier = Modifier.weight(1f))
                    ImportButton(onClick = onImportQr, icon = R.drawable.qr_code_24px, label = stringResource(R.string.qr_import), modifier = Modifier.weight(1f))
                    ImportButton(onClick = onImportClipboard, icon = R.drawable.content_paste_24px, label = stringResource(R.string.wg_import_clipboard), modifier = Modifier.weight(1f))
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
                    isModified = runningWgConfig != null && privateKey != runningWgConfig.privateKey
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
                    isModified = runningWgConfig != null && address != runningWgConfig.address
                )
            }

            SettingsGroupItem(isTop = false, isBottom = true, containerColor = blockContainerColor) {
                TextFieldRow(
                    label = stringResource(R.string.wg_mtu),
                    value = mtu,
                    onValueChange = onMtuChange,
                    placeholder = stringResource(R.string.wg_mtu_placeholder),
                    isModified = runningWgConfig != null && mtu != runningWgConfig.mtu,
                    supportingText = if (mtu != "1280") stringResource(R.string.wg_mtu_recommendation) else null
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
                    isModified = runningWgConfig != null && publicKey != runningWgConfig.publicKey
                )
            }

            SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                Column {
                    TextFieldRow(
                        label = stringResource(R.string.wg_endpoint),
                        value = endpoint.redact(privacyMode),
                        onValueChange = onEndpointChange,
                        placeholder = stringResource(R.string.wg_endpoint_placeholder),
                        isError = !isTargetEndpoint || !isEndpointValid,
                        readOnly = privacyMode,
                        isModified = runningWgConfig != null && endpoint != runningWgConfig.endpoint
                    )
                    if (!isTargetEndpoint) {
                        Column {
                            Spacer(Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(text = stringResource(R.string.wg_endpoint_mismatch), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                Text(text = stringResource(R.string.wg_endpoint_fix), color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { onFixEndpoint() }, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            SettingsGroupItem(isTop = false, isBottom = true, containerColor = blockContainerColor) {
                TextFieldRow(
                    label = stringResource(R.string.wg_persistent_keepalive),
                    value = persistentKeepalive,
                    onValueChange = onPersistentKeepaliveChange,
                    placeholder = stringResource(R.string.wg_persistent_keepalive_placeholder),
                    isModified = runningWgConfig != null && persistentKeepalive != runningWgConfig.persistentKeepalive
                )
            }
        }
    }
}

@Composable
private fun VlessSettings(
    vlessLink: String,
    onVlessLinkChange: (String) -> Unit,
    vlessUseLocalAddress: Boolean,
    onVlessUseLocalAddressChange: (Boolean) -> Unit,
    vlessIsDualRoute: Boolean,
    onVlessIsDualRouteChange: (Boolean) -> Unit,
    vlessDirectAddress: String,
    onVlessDirectAddressChange: (String) -> Unit,
    onParseDirectAddress: () -> Unit,
    vlessLinkHistory: List<String>,
    onRemoveHistory: (String) -> Unit,
    runningVlessConfig: com.wireturn.app.data.VlessConfig?,
    privacyMode: Boolean,
    onImportQr: () -> Unit,
    blockContainerColor: Color
) {
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
                isModified = runningVlessConfig != null && vlessLink.trim() != runningVlessConfig.vlessLink,
                trailingIcon = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = onImportQr,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(painter = painterResource(R.drawable.qr_code_24px), contentDescription = null, modifier = Modifier.size(20.dp))
                        }
                        FieldTrailingIcons(
                            history = vlessLinkHistory,
                            onSelect = onVlessLinkChange,
                            onRemove = onRemoveHistory,
                            privacyMode = privacyMode
                        )
                    }
                }
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsGroupItem(
            isTop = true,
            isBottom = false,
            containerColor = blockContainerColor,
            onClick = if (!vlessIsDualRoute) { { onVlessUseLocalAddressChange(!vlessUseLocalAddress) } } else null
        ) {
            SwitchRow(
                label = stringResource(R.string.use_local_listen_address),
                supportingText = stringResource(R.string.use_local_listen_desc),
                checked = vlessUseLocalAddress,
                onCheckedChange = onVlessUseLocalAddressChange,
                enabled = !vlessIsDualRoute,
                isModified = runningVlessConfig != null && vlessUseLocalAddress != runningVlessConfig.vlessUseLocalAddress
            )
        }

        SettingsGroupItem(
            isTop = false,
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
                isModified = runningVlessConfig != null && vlessIsDualRoute != runningVlessConfig.isDualRoute
            )
        }

        if (vlessIsDualRoute) {
            SettingsGroupItem(
                isTop = false,
                isBottom = true,
                containerColor = blockContainerColor
            ) {
                TextFieldRow(
                    label = stringResource(R.string.vless_direct_address),
                    value = vlessDirectAddress.redact(privacyMode),
                    onValueChange = onVlessDirectAddressChange,
                    placeholder = stringResource(R.string.vless_direct_address_placeholder),
                    isError = !ValidatorUtils.isValidHostPort(vlessDirectAddress),
                    readOnly = privacyMode,
                    supportingText = stringResource(R.string.vless_direct_address_desc),
                    isModified = runningVlessConfig != null && vlessDirectAddress != runningVlessConfig.directAddress,
                    trailingIcon = {
                        IconButton(onClick = onParseDirectAddress) {
                            Icon(painterResource(R.drawable.sync_24px), stringResource(R.string.vless_parse_from_link))
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ImportButton(
    onClick: () -> Unit,
    icon: Int,
    label: String,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        Icon(painterResource(icon), null, Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
    }
}
