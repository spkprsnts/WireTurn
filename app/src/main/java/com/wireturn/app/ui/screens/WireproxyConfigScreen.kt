@file:OptIn(ExperimentalMaterial3Api::class)

package com.wireturn.app.ui.screens

import android.annotation.SuppressLint
import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.R
import com.wireturn.app.data.WgConfig
import com.wireturn.app.ui.ConfigFieldIndicator
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.ValidatorUtils
import com.wireturn.app.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun WireproxyConfigScreen(
    viewModel: MainViewModel,
    showFinishButton: Boolean = false,
    onFinish: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val privacyMode by viewModel.privacyMode.collectAsStateWithLifecycle()
    val savedWgConfig by viewModel.wgConfig.collectAsStateWithLifecycle()
    val clientConfig by viewModel.clientConfig.collectAsStateWithLifecycle()
    val runningConfig by com.wireturn.app.WireproxyServiceState.runningConfig.collectAsStateWithLifecycle()

    // Local states for auto-save logic
    var privateKey by rememberSaveable(savedWgConfig.privateKey) { mutableStateOf(savedWgConfig.privateKey) }
    var address by rememberSaveable(savedWgConfig.address) { mutableStateOf(savedWgConfig.address) }
    var dns by rememberSaveable(savedWgConfig.dns) { mutableStateOf(savedWgConfig.dns) }
    var mtu by rememberSaveable(savedWgConfig.mtu) { mutableStateOf(savedWgConfig.mtu) }
    var publicKey by rememberSaveable(savedWgConfig.publicKey) { mutableStateOf(savedWgConfig.publicKey) }
    var endpoint by rememberSaveable(savedWgConfig.endpoint) { mutableStateOf(savedWgConfig.endpoint) }
    var allowedIps by rememberSaveable(savedWgConfig.allowedIps) { mutableStateOf(savedWgConfig.allowedIps) }
    var persistentKeepalive by rememberSaveable(savedWgConfig.persistentKeepalive) { mutableStateOf(savedWgConfig.persistentKeepalive) }
    var socks5BindAddress by rememberSaveable(savedWgConfig.socks5BindAddress) { mutableStateOf(savedWgConfig.socks5BindAddress) }
    var httpBindAddress by rememberSaveable(savedWgConfig.httpBindAddress) { mutableStateOf(savedWgConfig.httpBindAddress) }

    // Sync local state with saved config when it changes externally (e.g. from service)
    LaunchedEffect(savedWgConfig) {
        privateKey = savedWgConfig.privateKey
        address = savedWgConfig.address
        dns = savedWgConfig.dns
        mtu = savedWgConfig.mtu
        publicKey = savedWgConfig.publicKey
        endpoint = savedWgConfig.endpoint
        allowedIps = savedWgConfig.allowedIps
        persistentKeepalive = savedWgConfig.persistentKeepalive
        socks5BindAddress = savedWgConfig.socks5BindAddress
        httpBindAddress = savedWgConfig.httpBindAddress
    }

    // Auto-save debounced for individual fields
    LaunchedEffect(
        privateKey, address, dns, mtu, publicKey, endpoint,
        allowedIps, persistentKeepalive, socks5BindAddress, httpBindAddress
    ) {
        delay(600)
        viewModel.updateWgConfig(
            WgConfig(
                privateKey = privateKey,
                address = address,
                dns = dns,
                mtu = mtu,
                publicKey = publicKey,
                endpoint = endpoint,
                allowedIps = allowedIps,
                persistentKeepalive = persistentKeepalive,
                socks5BindAddress = socks5BindAddress,
                httpBindAddress = httpBindAddress
            )
        )
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            uri?.let {
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val text = input.bufferedReader().use { r -> r.readText() }
                        viewModel.updateWgConfigText(text)
                        scope.launch {
                            snackbarHostState.showSnackbar(context.getString(R.string.wireproxy_import_success))
                        }
                    }
                } catch (e: Exception) {
                    val errorMessage = context.getString(R.string.wireproxy_import_error)
                    val fullError = context.getString(R.string.error_with_details, errorMessage, e.message ?: "")
                    scope.launch {
                        snackbarHostState.showSnackbar(fullError)
                    }
                }
            }
        }
    )

    val isSocks5Valid = remember(socks5BindAddress) {
        ValidatorUtils.isValidHostPort(socks5BindAddress)
    }
    val isHttpValid = remember(httpBindAddress) {
        ValidatorUtils.isValidHostPort(httpBindAddress)
    }
    val isEndpointValid = remember(endpoint) {
        ValidatorUtils.isValidHostPort(endpoint)
    }
    val isTargetEndpoint = remember(endpoint) {
        clientConfig.localPort == endpoint
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wireproxy_config_title)) },
                actions = {
                    IconButton(onClick = {
                        // Копирование всего конфига в буфер
                        scope.launch {
                            clipboard.setClipEntry(ClipData.newPlainText("wg_config", savedWgConfig.toWgString()).toClipEntry())
                            HapticUtil.perform(context, HapticUtil.Pattern.SUCCESS)
                            snackbarHostState.showSnackbar(context.getString(R.string.wireproxy_export_success))
                        }
                    }) {
                        Icon(painterResource(R.drawable.content_copy_24px), stringResource(R.string.copy))
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // ── Способы импорта ─────────────────────────────────────
            Text(stringResource(R.string.wireproxy_import_config), style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { filePickerLauncher.launch("*/*") },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(painterResource(R.drawable.file_open_24px), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.wireproxy_import_file))
                }
                Button(
                    onClick = {
                        scope.launch {
                            val clipEntry = clipboard.getClipEntry()
                            if (clipEntry != null) {
                                val text = clipEntry.clipData.getItemAt(0).text?.toString() ?: ""
                                if (text.isNotBlank()) {
                                    viewModel.updateWgConfigText(text)
                                    snackbarHostState.showSnackbar(context.getString(R.string.wireproxy_import_success))
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(painterResource(R.drawable.content_paste_24px), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.wireproxy_import_clipboard))
                }
            }

            HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)

            Text(stringResource(R.string.wireproxy_proxy_addresses), style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = socks5BindAddress,
                onValueChange = { socks5BindAddress = it },
                label = { Text(stringResource(R.string.wireproxy_socks5)) },
                isError = !isSocks5Valid || socks5BindAddress == httpBindAddress || socks5BindAddress.isBlank(),
                placeholder = { Text(stringResource(R.string.wireproxy_socks5_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    ConfigFieldIndicator(runningConfig != null && socks5BindAddress != runningConfig?.socks5BindAddress)
                }
            )

            OutlinedTextField(
                value = httpBindAddress,
                onValueChange = { httpBindAddress = it },
                label = { Text(stringResource(R.string.wireproxy_http)) },
                isError = !isHttpValid || socks5BindAddress == httpBindAddress,
                placeholder = { Text(stringResource(R.string.wireproxy_http_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    ConfigFieldIndicator(runningConfig != null && httpBindAddress != runningConfig?.httpBindAddress)
                }
            )

            HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)

            Text(stringResource(R.string.wireproxy_edit_config), style = MaterialTheme.typography.titleMedium)

            Text(stringResource(R.string.wireproxy_interface), style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = privateKey.redact(privacyMode),
                onValueChange = { if (!privacyMode) privateKey = it },
                label = { Text(stringResource(R.string.wireproxy_private_key)) },
                isError = privateKey.isBlank(),
                placeholder = { Text(stringResource(R.string.wireproxy_private_key_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                readOnly = privacyMode,
                trailingIcon = {
                    ConfigFieldIndicator(runningConfig != null && privateKey != runningConfig?.privateKey)
                }
            )

            OutlinedTextField(
                value = address.redact(privacyMode),
                onValueChange = { if (!privacyMode) address = it },
                label = { Text(stringResource(R.string.wireproxy_address)) },
                isError = address.isBlank(),
                placeholder = { Text(stringResource(R.string.wireproxy_address_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                readOnly = privacyMode,
                trailingIcon = {
                    ConfigFieldIndicator(runningConfig != null && address != runningConfig?.address)
                }
            )

            OutlinedTextField(
                value = dns,
                onValueChange = { dns = it },
                label = { Text(stringResource(R.string.wireproxy_dns)) },
                placeholder = { Text(stringResource(R.string.wireproxy_dns_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    ConfigFieldIndicator(runningConfig != null && dns != runningConfig?.dns)
                }
            )

            OutlinedTextField(
                value = mtu,
                onValueChange = { mtu = it },
                label = { Text(stringResource(R.string.wireproxy_mtu)) },
                placeholder = { Text(stringResource(R.string.wireproxy_mtu_placeholder)) },
                supportingText = {
                    if (mtu != "1280") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                        ) {
                            Text(stringResource(R.string.wireproxy_mtu_recommendation))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                trailingIcon = {
                    ConfigFieldIndicator(runningConfig != null && mtu != runningConfig?.mtu)
                }
            )

            HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)
            Text(stringResource(R.string.wireproxy_peer), style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = publicKey.redact(privacyMode),
                onValueChange = { if (!privacyMode) publicKey = it },
                label = { Text(stringResource(R.string.wireproxy_public_key)) },
                isError = publicKey.isBlank(),
                placeholder = { Text(stringResource(R.string.wireproxy_public_key_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                readOnly = privacyMode,
                trailingIcon = {
                    ConfigFieldIndicator(runningConfig != null && publicKey != runningConfig?.publicKey)
                }
            )

            OutlinedTextField(
                value = endpoint.redact(privacyMode),
                onValueChange = { if (!privacyMode) endpoint = it },
                label = { Text(stringResource(R.string.wireproxy_endpoint)) },
                isError = !isTargetEndpoint || !isEndpointValid,
                supportingText = {
                    if (!isTargetEndpoint) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.wireproxy_endpoint_mismatch),
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = stringResource(R.string.wireproxy_endpoint_fix),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    endpoint = clientConfig.localPort
                                }
                            )
                        }
                    }
                },
                placeholder = { Text(stringResource(R.string.wireproxy_endpoint_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    ConfigFieldIndicator(runningConfig != null && endpoint != runningConfig?.endpoint)
                }
            )

            OutlinedTextField(
                value = allowedIps,
                onValueChange = { allowedIps = it },
                label = { Text(stringResource(R.string.wireproxy_allowed_ips)) },
                placeholder = { Text(stringResource(R.string.wireproxy_allowed_ips_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    ConfigFieldIndicator(runningConfig != null && allowedIps != runningConfig?.allowedIps)
                }
            )

            OutlinedTextField(
                value = persistentKeepalive,
                onValueChange = { persistentKeepalive = it },
                label = { Text(stringResource(R.string.wireproxy_persistent_keepalive)) },
                placeholder = { Text(stringResource(R.string.wireproxy_persistent_keepalive_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                trailingIcon = {
                    ConfigFieldIndicator(runningConfig != null && persistentKeepalive != runningConfig?.persistentKeepalive)
                }
            )

            if (showFinishButton && onFinish != null) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onFinish,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = privateKey.isNotBlank() && publicKey.isNotBlank() && endpoint.isNotBlank(),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(stringResource(R.string.finish_setup))
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

