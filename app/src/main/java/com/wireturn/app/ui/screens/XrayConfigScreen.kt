@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wireturn.app.ui.screens

import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.R
import com.wireturn.app.data.WgConfig
import com.wireturn.app.data.XrayConfiguration
import com.wireturn.app.ui.ConfigLabelRow
import com.wireturn.app.ui.FieldTrailingIcons
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.LabeledSegmentedButton
import com.wireturn.app.ui.SectionHeader
import com.wireturn.app.ui.SwitchRow
import com.wireturn.app.ui.ValidatorUtils
import com.wireturn.app.ui.redact
import com.wireturn.app.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun XrayConfigScreen(
    viewModel: MainViewModel,
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
    val savedWgConfig by viewModel.wgConfig.collectAsStateWithLifecycle()
    val clientConfig by viewModel.clientConfig.collectAsStateWithLifecycle()
    val xrayConfig by viewModel.xrayConfig.collectAsStateWithLifecycle()
    val vlessSaved by viewModel.vlessConfig.collectAsStateWithLifecycle()
    val vlessLinkHistory by viewModel.vlessLinkHistory.collectAsStateWithLifecycle()
    
    val runningWgConfig by com.wireturn.app.XrayServiceState.runningWgConfig.collectAsStateWithLifecycle()
    val runningVlessConfig by com.wireturn.app.XrayServiceState.runningVlessConfig.collectAsStateWithLifecycle()
    val runningXrayConfig by com.wireturn.app.XrayServiceState.runningXrayConfig.collectAsStateWithLifecycle()

    var xrayConfiguration by rememberSaveable(xrayConfig.xrayConfiguration) { mutableStateOf(xrayConfig.xrayConfiguration) }
    var socksBindAddress by rememberSaveable(xrayConfig.socksBindAddress) { mutableStateOf(xrayConfig.socksBindAddress) }
    var httpBindAddress by rememberSaveable(xrayConfig.httpBindAddress) { mutableStateOf(xrayConfig.httpBindAddress) }

    // WireGuard states
    var privateKey by rememberSaveable(savedWgConfig.privateKey) { mutableStateOf(savedWgConfig.privateKey) }
    var address by rememberSaveable(savedWgConfig.address) { mutableStateOf(savedWgConfig.address) }
    var mtu by rememberSaveable(savedWgConfig.mtu) { mutableStateOf(savedWgConfig.mtu) }
    var publicKey by rememberSaveable(savedWgConfig.publicKey) { mutableStateOf(savedWgConfig.publicKey) }
    var endpoint by rememberSaveable(savedWgConfig.endpoint) { mutableStateOf(savedWgConfig.endpoint) }
    var persistentKeepalive by rememberSaveable(savedWgConfig.persistentKeepalive) { mutableStateOf(savedWgConfig.persistentKeepalive) }

    // VLESS states
    var vlessLink by rememberSaveable(vlessSaved.vlessLink) { mutableStateOf(vlessSaved.vlessLink) }
    var vlessUseLocalAddress by rememberSaveable(vlessSaved.vlessUseLocalAddress) { mutableStateOf(vlessSaved.vlessUseLocalAddress) }

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

    LaunchedEffect(vlessLink, vlessUseLocalAddress) {
        delay(200)
        val next = com.wireturn.app.data.VlessConfig(
            vlessLink = vlessLink,
            vlessUseLocalAddress = vlessUseLocalAddress
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

    val contentAnimationSpec = tween<androidx.compose.ui.unit.IntSize>(300, easing = FastOutSlowInEasing)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                .padding(horizontal = 16.dp)
                .animateContentSize(animationSpec = contentAnimationSpec)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // 3. Общие настройки Xray (Bind addresses)
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SectionHeader(stringResource(R.string.xray_local_addresses))
                    
                    OutlinedTextField(
                        value = socksBindAddress,
                        onValueChange = { socksBindAddress = it },
                        label = { 
                            ConfigLabelRow(runningXrayConfig != null && socksBindAddress != runningXrayConfig?.socksBindAddress) {
                                Text(stringResource(R.string.xray_socks5)) 
                            }
                        },
                        placeholder = { Text(stringResource(R.string.xray_socks5_placeholder)) },
                        isError = !isSocksValid || (socksBindAddress.isNotEmpty() && socksBindAddress == httpBindAddress),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = { Text(stringResource(R.string.xray_socks_desc)) }
                    )

                    OutlinedTextField(
                        value = httpBindAddress,
                        onValueChange = { httpBindAddress = it },
                        label = { 
                            ConfigLabelRow(runningXrayConfig != null && httpBindAddress != runningXrayConfig?.httpBindAddress) {
                                Text(stringResource(R.string.xray_http))
                            }
                        },
                        placeholder = { Text(stringResource(R.string.xray_http_placeholder)) },
                        isError = !isHttpValid || (httpBindAddress.isNotEmpty() && socksBindAddress == httpBindAddress),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = { Text(stringResource(R.string.xray_http_desc)) }
                    )
                }
            }

            // 1. Выбор протокола
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SectionHeader(stringResource(R.string.transport_protocol))
                    LabeledSegmentedButton(
                        label = stringResource(R.string.xray_protocol_label),
                        subLabel = stringResource(R.string.xray_protocol_desc),
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

            // 2. Настройки протокола
            AnimatedContent(targetState = xrayConfiguration, label = "protocol_settings") { targetConfig ->
                when (targetConfig) {
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
                            }
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
                            vlessLinkHistory = vlessLinkHistory,
                            onRemoveHistory = { viewModel.removeVlessLinkFromHistory(it) },
                            runningVlessConfig = runningVlessConfig,
                            privacyMode = privacyMode,
                            onImportQr = { showVlessQrScanner.value = true }
                        )
                    }
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
            Spacer(Modifier.height(28.dp))
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
    onImportClipboard: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SectionHeader(stringResource(R.string.wg_import_config))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImportButton(onClick = onImportFile, icon = R.drawable.file_open_24px, label = stringResource(R.string.wg_import_file), modifier = Modifier.weight(1f))
                    ImportButton(onClick = onImportQr, icon = R.drawable.qr_code_24px, label = stringResource(R.string.qr_import), modifier = Modifier.weight(1f))
                    ImportButton(onClick = onImportClipboard, icon = R.drawable.content_paste_24px, label = stringResource(R.string.wg_import_clipboard), modifier = Modifier.weight(1f))
                }
            }
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SectionHeader(stringResource(R.string.wg_interface))
                
                OutlinedTextField(
                    value = privateKey.redact(privacyMode),
                    onValueChange = onPrivateKeyChange,
                    label = { 
                        ConfigLabelRow(runningWgConfig != null && privateKey != runningWgConfig.privateKey) {
                            Text(stringResource(R.string.wg_private_key))
                        }
                    },
                    placeholder = { Text(stringResource(R.string.wg_private_key_placeholder)) },
                    isError = privateKey.isBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = privacyMode
                )

                OutlinedTextField(
                    value = address.redact(privacyMode),
                    onValueChange = onAddressChange,
                    label = { 
                        ConfigLabelRow(runningWgConfig != null && address != runningWgConfig.address) {
                            Text(stringResource(R.string.wg_address))
                        }
                    },
                    placeholder = { Text(stringResource(R.string.wg_address_placeholder)) },
                    isError = address.isBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = privacyMode
                )

                OutlinedTextField(
                    value = mtu,
                    onValueChange = onMtuChange,
                    label = { 
                        ConfigLabelRow(runningWgConfig != null && mtu != runningWgConfig.mtu) {
                            Text(stringResource(R.string.wg_mtu))
                        }
                    },
                    placeholder = { Text(stringResource(R.string.wg_mtu_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = if (mtu != "1280") { { Text(stringResource(R.string.wg_mtu_recommendation)) } } else null
                )
            }
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SectionHeader(stringResource(R.string.wg_peer))

                OutlinedTextField(
                    value = publicKey.redact(privacyMode),
                    onValueChange = onPublicKeyChange,
                    label = { 
                        ConfigLabelRow(runningWgConfig != null && publicKey != runningWgConfig.publicKey) {
                            Text(stringResource(R.string.wg_public_key))
                        }
                    },
                    placeholder = { Text(stringResource(R.string.wg_public_key_placeholder)) },
                    isError = publicKey.isBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = privacyMode
                )

                OutlinedTextField(
                    value = endpoint.redact(privacyMode),
                    onValueChange = onEndpointChange,
                    label = { 
                        ConfigLabelRow(runningWgConfig != null && endpoint != runningWgConfig.endpoint) {
                            Text(stringResource(R.string.wg_endpoint))
                        }
                    },
                    placeholder = { Text(stringResource(R.string.wg_endpoint_placeholder)) },
                    isError = !isTargetEndpoint || !isEndpointValid,
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = privacyMode,
                    supportingText = if (!isTargetEndpoint) {
                        {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(text = stringResource(R.string.wg_endpoint_mismatch), color = MaterialTheme.colorScheme.error)
                                Text(text = stringResource(R.string.wg_endpoint_fix), color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { onFixEndpoint() })
                            }
                        }
                    } else null
                )

                OutlinedTextField(
                    value = persistentKeepalive,
                    onValueChange = onPersistentKeepaliveChange,
                    label = { 
                        ConfigLabelRow(runningWgConfig != null && persistentKeepalive != runningWgConfig.persistentKeepalive) {
                            Text(stringResource(R.string.wg_persistent_keepalive))
                        }
                    },
                    placeholder = { Text(stringResource(R.string.wg_persistent_keepalive_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
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
    vlessUseLocalAddress: Boolean,
    onVlessUseLocalAddressChange: (Boolean) -> Unit,
    vlessLinkHistory: List<String>,
    onRemoveHistory: (String) -> Unit,
    runningVlessConfig: com.wireturn.app.data.VlessConfig?,
    privacyMode: Boolean,
    onImportQr: () -> Unit
) {
    val vlessName = remember(vlessLink) {
        val fragment = vlessLink.substringAfterLast('#', "")
        if (fragment.isNotEmpty()) " #${android.net.Uri.decode(fragment)}" else ""
    }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionHeader(stringResource(R.string.vless_settings))
            
            OutlinedTextField(
                value = vlessLink.redact(privacyMode),
                onValueChange = onVlessLinkChange,
                label = { 
                    ConfigLabelRow(runningVlessConfig != null && vlessLink.trim() != runningVlessConfig.vlessLink) {
                        Text(
                            text = buildAnnotatedString {
                                append(stringResource(R.string.vless_link_label))
                                if (vlessName.isNotEmpty()) {
                                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                        append(vlessName)
                                    }
                                }
                            }
                        )
                    }
                },
                placeholder = { Text(stringResource(R.string.vless_link_placeholder)) },
                isError = !ValidatorUtils.isValidVlessLink(vlessLink) || vlessLink.isBlank(),
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 4,
                readOnly = privacyMode,
                supportingText = { Text(stringResource(R.string.vless_link_config_desc)) },
                trailingIcon = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = onImportQr) {
                            Icon(painter = painterResource(R.drawable.qr_code_24px), contentDescription = null, modifier = Modifier.size(20.dp))
                        }
                        FieldTrailingIcons(
                            history = vlessLinkHistory,
                            onSelect = onVlessLinkChange,
                            onRemove = onRemoveHistory,
                            privacyMode = privacyMode,
                            iconSize = 20.dp
                        )
                    }
                }
            )

            SwitchRow(
                label = stringResource(R.string.use_local_listen_address),
                description = stringResource(R.string.use_local_listen_desc),
                checked = vlessUseLocalAddress,
                onCheckedChange = onVlessUseLocalAddressChange,
                isModified = runningVlessConfig != null && vlessUseLocalAddress != runningVlessConfig.vlessUseLocalAddress
            )
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
