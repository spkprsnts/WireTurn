@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wireturn.app.ui.screens

import android.annotation.SuppressLint
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
fun WgConfigScreen(
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
    val runningConfig by com.wireturn.app.XrayServiceState.runningWgConfig.collectAsStateWithLifecycle()

    val showQrScanner = remember { mutableStateOf(false) }

    // Local states for auto-save logic
    var privateKey by rememberSaveable(savedWgConfig.privateKey) { mutableStateOf(savedWgConfig.privateKey) }
    var address by rememberSaveable(savedWgConfig.address) { mutableStateOf(savedWgConfig.address) }
    var dns by rememberSaveable(savedWgConfig.dns) { mutableStateOf(savedWgConfig.dns) }
    var mtu by rememberSaveable(savedWgConfig.mtu) { mutableStateOf(savedWgConfig.mtu) }
    var publicKey by rememberSaveable(savedWgConfig.publicKey) { mutableStateOf(savedWgConfig.publicKey) }
    var endpoint by rememberSaveable(savedWgConfig.endpoint) { mutableStateOf(savedWgConfig.endpoint) }
    var allowedIps by rememberSaveable(savedWgConfig.allowedIps) { mutableStateOf(savedWgConfig.allowedIps) }
    var persistentKeepalive by rememberSaveable(savedWgConfig.persistentKeepalive) { mutableStateOf(savedWgConfig.persistentKeepalive) }

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
    }

    // Auto-save debounced for individual fields
    LaunchedEffect(
        privateKey, address, dns, mtu, publicKey, endpoint,
        allowedIps, persistentKeepalive
    ) {
        delay(200)
        viewModel.updateWgConfig(
            WgConfig(
                privateKey = privateKey,
                address = address,
                dns = dns,
                mtu = mtu,
                publicKey = publicKey,
                endpoint = endpoint,
                allowedIps = allowedIps,
                persistentKeepalive = persistentKeepalive
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
                        val parsed = WgConfig.parse(text)
                        if (parsed.isValid()) {
                            viewModel.updateWgConfigText(text)
                            scope.launch {
                                snackbarHostState.showSnackbar(context.getString(R.string.wg_import_success))
                            }
                        } else {
                            scope.launch {
                                snackbarHostState.showSnackbar(context.getString(R.string.wg_import_error))
                            }
                        }
                    }
                } catch (e: Exception) {
                    val errorMessage = context.getString(R.string.wg_import_error)
                    val fullError = context.getString(R.string.error_with_details, errorMessage, e.message ?: "")
                    scope.launch {
                        snackbarHostState.showSnackbar(fullError)
                    }
                }
            }
        }
    )

    val isEndpointValid = remember(endpoint) {
        ValidatorUtils.isValidHostPort(endpoint)
    }
    val isTargetEndpoint = remember(endpoint, clientConfig.connectableAddress) {
        clientConfig.connectableAddress == endpoint
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wg_config_title)) },
                actions = {
                    var isCopied by remember { mutableStateOf(false) }
                    LaunchedEffect(isCopied) {
                        if (isCopied) {
                            delay(1500)
                            isCopied = false
                        }
                    }
                    IconButton(
                        onClick = {
                            isCopied = true
                            scope.launch {
                                clipboard.setClipEntry(ClipData.newPlainText("wg_config", savedWgConfig.toWgString()).toClipEntry())
                                HapticUtil.perform(context, HapticUtil.Pattern.SUCCESS)
                            }
                        },
                        enabled = savedWgConfig.isValid()
                    ) {
                        Icon(
                            painterResource(if (isCopied) R.drawable.check_circle_24px else R.drawable.content_copy_24px),
                            stringResource(R.string.copy),
                            tint = when {
                                isCopied -> MaterialTheme.colorScheme.primary
                                !savedWgConfig.isValid() -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
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
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // ── Способы импорта ─────────────────────────────────────
            Text(stringResource(R.string.wg_import_config), style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { filePickerLauncher.launch("*/*") },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(painterResource(R.drawable.file_open_24px), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.wg_import_file), maxLines = 1)
                }
                Button(
                    onClick = { showQrScanner.value = true },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(painterResource(R.drawable.qr_code_24px), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.qr_import), maxLines = 1)
                }
                Button(
                    onClick = {
                        scope.launch {
                            val clipEntry = clipboard.getClipEntry()
                            if (clipEntry != null) {
                                val text = clipEntry.clipData.getItemAt(0).text?.toString() ?: ""
                                val parsed = WgConfig.parse(text)
                                if (parsed.isValid()) {
                                    viewModel.updateWgConfigText(text)
                                    snackbarHostState.showSnackbar(context.getString(R.string.wg_import_success))
                                } else {
                                    snackbarHostState.showSnackbar(context.getString(R.string.wg_import_error))
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(painterResource(R.drawable.content_paste_24px), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.wg_import_clipboard), maxLines = 1)
                }
            }

            HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)

            Text(stringResource(R.string.wg_edit_config), style = MaterialTheme.typography.titleMedium)

            Text(stringResource(R.string.wg_interface), style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = privateKey.redact(privacyMode),
                onValueChange = { if (!privacyMode) privateKey = it },
                label = { Text(stringResource(R.string.wg_private_key)) },
                isError = privateKey.isBlank(),
                placeholder = { Text(stringResource(R.string.wg_private_key_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                readOnly = privacyMode,
                trailingIcon = {
                    ConfigFieldIndicator(runningConfig != null && privateKey != runningConfig?.privateKey)
                }
            )

            OutlinedTextField(
                value = address.redact(privacyMode),
                onValueChange = { if (!privacyMode) address = it },
                label = { Text(stringResource(R.string.wg_address)) },
                isError = address.isBlank(),
                placeholder = { Text(stringResource(R.string.wg_address_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                readOnly = privacyMode,
                trailingIcon = {
                    ConfigFieldIndicator(runningConfig != null && address != runningConfig?.address)
                }
            )

            OutlinedTextField(
                value = dns,
                onValueChange = { dns = it },
                label = { Text(stringResource(R.string.wg_dns)) },
                placeholder = { Text(stringResource(R.string.wg_dns_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    ConfigFieldIndicator(runningConfig != null && dns != runningConfig?.dns)
                }
            )

            OutlinedTextField(
                value = mtu,
                onValueChange = { mtu = it },
                label = { Text(stringResource(R.string.wg_mtu)) },
                placeholder = { Text(stringResource(R.string.wg_mtu_placeholder)) },
                supportingText = if (mtu != "1280") {
                    {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                        ) {
                            Text(stringResource(R.string.wg_mtu_recommendation))
                        }
                    }
                } else null,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                trailingIcon = {
                    ConfigFieldIndicator(runningConfig != null && mtu != runningConfig?.mtu)
                }
            )

            HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)
            Text(stringResource(R.string.wg_peer), style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = publicKey.redact(privacyMode),
                onValueChange = { if (!privacyMode) publicKey = it },
                label = { Text(stringResource(R.string.wg_public_key)) },
                isError = publicKey.isBlank(),
                placeholder = { Text(stringResource(R.string.wg_public_key_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                readOnly = privacyMode,
                trailingIcon = {
                    ConfigFieldIndicator(runningConfig != null && publicKey != runningConfig?.publicKey)
                }
            )

            OutlinedTextField(
                value = endpoint.redact(privacyMode),
                onValueChange = { if (!privacyMode) endpoint = it },
                label = { Text(stringResource(R.string.wg_endpoint)) },
                isError = !isTargetEndpoint || !isEndpointValid,
                supportingText = if (!isTargetEndpoint) {
                    {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.wg_endpoint_mismatch),
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = stringResource(R.string.wg_endpoint_fix),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    endpoint = clientConfig.connectableAddress
                                }
                            )
                        }
                    }
                } else null,
                placeholder = { Text(stringResource(R.string.wg_endpoint_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    ConfigFieldIndicator(runningConfig != null && endpoint != runningConfig?.endpoint)
                }
            )

            OutlinedTextField(
                value = allowedIps,
                onValueChange = { allowedIps = it },
                label = { Text(stringResource(R.string.wg_allowed_ips)) },
                placeholder = { Text(stringResource(R.string.wg_allowed_ips_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    ConfigFieldIndicator(runningConfig != null && allowedIps != runningConfig?.allowedIps)
                }
            )

            OutlinedTextField(
                value = persistentKeepalive,
                onValueChange = { persistentKeepalive = it },
                label = { Text(stringResource(R.string.wg_persistent_keepalive)) },
                placeholder = { Text(stringResource(R.string.wg_persistent_keepalive_placeholder)) },
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

    if (showQrScanner.value) {
        QrScannerDialog(
            title = stringResource(R.string.wg_import_qr),
            message = stringResource(R.string.wg_qr_scan_desc),
            onDismiss = { showQrScanner.value = false },
            onResult = { result ->
                val parsed = WgConfig.parse(result)
                if (parsed.isValid()) {
                    viewModel.updateWgConfigText(result)
                    scope.launch {
                        snackbarHostState.showSnackbar(context.getString(R.string.wg_import_success))
                    }
                } else {
                    scope.launch {
                        snackbarHostState.showSnackbar(context.getString(R.string.wg_import_error))
                    }
                }
            }
        )
    }
}
