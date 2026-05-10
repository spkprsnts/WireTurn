@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui.screens

import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.R
import com.wireturn.app.data.KernelVariant
import com.wireturn.app.data.TurnableConfig
import com.wireturn.app.data.TurnableRoute
import com.wireturn.app.ui.ConfigRowLabel
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.ImportButton
import com.wireturn.app.ui.LabeledSegmentedButton
import com.wireturn.app.ui.SelectionDialog
import com.wireturn.app.ui.SettingsGroup
import com.wireturn.app.ui.SettingsGroupItem
import com.wireturn.app.ui.StandardLeadingIcon
import com.wireturn.app.ui.SupportingText
import com.wireturn.app.ui.SwitchRow
import com.wireturn.app.ui.TextFieldRow
import com.wireturn.app.ui.ValidatorUtils
import com.wireturn.app.ui.redact
import com.wireturn.app.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ClientConfigScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    showFinishButton: Boolean = false,
    onFinish: (() -> Unit)? = null
) {
    val saved by viewModel.clientConfig.collectAsStateWithLifecycle()
    val customKernelExists by viewModel.customKernelExists.collectAsStateWithLifecycle()
    val customKernelLastModified by viewModel.customKernelLastModified.collectAsStateWithLifecycle()
    val kernelError by viewModel.kernelError.collectAsStateWithLifecycle()
    val privacyMode by viewModel.privacyMode.collectAsStateWithLifecycle()

    val clientConfigSnapshot by com.wireturn.app.ProxyServiceState.clientConfigSnapshot.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val clipboard = LocalClipboard.current

    val kernelPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.setCustomKernel(it) } }

    var isRawMode by remember { mutableStateOf(saved.isRawMode) }
    var rawCommand by remember { mutableStateOf(saved.rawCommand) }
    var turnableConfig by remember { mutableStateOf(saved.turnableConfig) }
    var olcrtcConfig by remember { mutableStateOf(saved.olcrtcConfig) }
    var olcrtcSocksAddr by remember { mutableStateOf("${saved.olcrtcConfig.socksHost}:${saved.olcrtcConfig.socksPort}") }
    var localPort    by remember { mutableStateOf(saved.localPort) }
    var kernelVariant by remember { mutableStateOf(saved.kernelVariant) }
    
    val showPortHelp = remember { mutableStateOf(false) }
    val showOlcrtcHelp = remember { mutableStateOf(false) }
    val showRoutesDialog = remember { mutableStateOf(false) }
    val showQrScanner = remember { mutableStateOf(false) }
    val showTransportDialog = remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val importSuccessMessage = stringResource(R.string.wg_import_success)
    val importErrorMessage = stringResource(R.string.wg_import_error)
    val copySuccessMessage = stringResource(R.string.config_copied_success)

    // Sync local state with saved config when it changes externally (e.g. profile switch)
    LaunchedEffect(saved) {
        isRawMode = saved.isRawMode
        rawCommand = saved.rawCommand
        turnableConfig = saved.turnableConfig
        turnableConfig = saved.turnableConfig
        olcrtcConfig = saved.olcrtcConfig
        olcrtcSocksAddr = "${saved.olcrtcConfig.socksHost}:${saved.olcrtcConfig.socksPort}"
        localPort = saved.localPort
        kernelVariant = saved.kernelVariant
    }

    val isLocalPortValid = remember(localPort) { ValidatorUtils.isValidHostPort(localPort) }
    val isOlcrtcSocksValid = remember(olcrtcSocksAddr) { ValidatorUtils.isValidHostPort(olcrtcSocksAddr) }

    LaunchedEffect(isRawMode, rawCommand, turnableConfig, olcrtcConfig, localPort, olcrtcSocksAddr, kernelVariant) {
        delay(200)
        val current = viewModel.clientConfig.value
        
        var effectiveOlcrtcConfig = olcrtcConfig
        if (isOlcrtcSocksValid) {
            val parts = olcrtcSocksAddr.split(":")
            effectiveOlcrtcConfig = olcrtcConfig.copy(
                socksHost = parts.getOrNull(0) ?: "127.0.0.1",
                socksPort = parts.getOrNull(1) ?: "9000"
            )
        }

        val next = current.copy(
            isRawMode        = isRawMode,
            rawCommand       = rawCommand,
            turnableConfig   = turnableConfig,
            olcrtcConfig     = effectiveOlcrtcConfig,
            localPort        = localPort.trim(),
            kernelVariant    = kernelVariant
        )

        if (next != current) {
            viewModel.saveClientConfig(next)
        }
    }

    val isDark = com.wireturn.app.ui.theme.LocalIsDark.current
    val screenBackgroundColor = if (isDark) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLow
    val blockContainerColor = if (isDark) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface

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
                title = { Text(stringResource(R.string.client_title)) },
                actions = {
                    IconButton(onClick = { showQrScanner.value = true }) {
                        Icon(
                            painter = painterResource(R.drawable.qr_code_24px),
                            contentDescription = stringResource(R.string.qr_import)
                        )
                    }
                    var isCopied by remember { mutableStateOf(false) }
                    LaunchedEffect(isCopied) {
                        if (isCopied) {
                            delay(1500)
                            isCopied = false
                        }
                    }
                    val canCopy = remember(kernelVariant, turnableConfig, olcrtcConfig) {
                        when (kernelVariant) {
                            KernelVariant.TURNABLE -> turnableConfig.isValid()
                            KernelVariant.OLCRTC -> olcrtcConfig.isValid()
                        }
                    }

                    IconButton(
                        onClick = {
                            val uri = when (kernelVariant) {
                                KernelVariant.TURNABLE -> turnableConfig.toUrl()
                                KernelVariant.OLCRTC -> olcrtcConfig.toUri()
                            }
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("config", uri)))
                                HapticUtil.perform(context, HapticUtil.Pattern.SUCCESS)
                                isCopied = true
                                snackbarHostState.showSnackbar(copySuccessMessage)
                            }
                        },
                        enabled = canCopy
                    ) {
                        Icon(
                            painter = painterResource(if (isCopied) R.drawable.check_circle_24px else R.drawable.content_copy_24px),
                            contentDescription = stringResource(R.string.copy)
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

            // 1. Конфигурация режима и способа подключения
            SettingsGroup(title = stringResource(R.string.connection_title)) {
                // Переключатель Raw Mode
                SettingsGroupItem(
                    isTop = true, 
                    isBottom = false, 
                    containerColor = blockContainerColor,
                    onClick = {
                        val next = !isRawMode
                        HapticUtil.perform(context, if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                        isRawMode = next
                    }
                ) {
                    SwitchRow(
                        label = stringResource(R.string.raw_mode),
                        supportingText = stringResource(R.string.raw_mode_desc),
                        checked = isRawMode,
                        onCheckedChange = { isRawMode = it },
                        isModified = clientConfigSnapshot != null && isRawMode != clientConfigSnapshot?.isRawMode
                    )
                }

                if (isRawMode) {
                    // Строка для команды в Raw Mode
                    SettingsGroupItem(
                        isTop = false,
                        isBottom = customKernelExists,
                        containerColor = blockContainerColor
                    ) {
                        TextFieldRow(
                            label = stringResource(R.string.raw_label),
                            value = rawCommand,
                            placeholder = stringResource(R.string.raw_placeholder),
                            onValueChange = { rawCommand = it },
                            isError = rawCommand.isBlank(),
                            singleLine = false,
                            minLines = 2,
                            maxLines = 4,
                            isModified = clientConfigSnapshot != null && (rawCommand != clientConfigSnapshot?.rawCommand || !clientConfigSnapshot!!.isRawMode)
                        )
                    }
                    // Выбор ядра в Raw Mode
                    if (!customKernelExists) {
                        SettingsGroupItem(isTop = false, isBottom = true, containerColor = blockContainerColor) {
                            LabeledSegmentedButton(
                                label = stringResource(R.string.kernel_label),
                                supportingText = stringResource(R.string.client_variants_desc),
                                isModified = clientConfigSnapshot != null && kernelVariant != clientConfigSnapshot?.kernelVariant
                            ) {
                                KernelVariant.entries.forEachIndexed { index, variant ->
                                    SegmentedButton(
                                        selected = kernelVariant == variant,
                                        onClick = {
                                            HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                            kernelVariant = variant
                                        },
                                        shape = SegmentedButtonDefaults.itemShape(index = index, count = KernelVariant.entries.size)
                                    ) {
                                        Text(stringResource(when(variant) {
                                            KernelVariant.TURNABLE -> R.string.kernel_turnable
                                            KernelVariant.OLCRTC -> R.string.kernel_olcrtc
                                        }))
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Обычный режим: Выбор ядра
                    SettingsGroupItem(isTop = false, isBottom = true, containerColor = blockContainerColor) {
                        LabeledSegmentedButton(
                            label = if (customKernelExists) stringResource(R.string.kernel_config_label) else stringResource(R.string.kernel_label),
                            supportingText = if (customKernelExists) stringResource(R.string.kernel_config_desc) else stringResource(R.string.client_variants_desc),
                            isModified = clientConfigSnapshot != null && kernelVariant != clientConfigSnapshot?.kernelVariant
                        ) {
                            KernelVariant.entries.forEachIndexed { index, variant ->
                                SegmentedButton(
                                    selected = kernelVariant == variant,
                                    onClick = {
                                        HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                        kernelVariant = variant
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = KernelVariant.entries.size)
                                ) {
                                    Text(stringResource(when(variant) {
                                        KernelVariant.TURNABLE -> R.string.kernel_turnable
                                        KernelVariant.OLCRTC -> R.string.kernel_olcrtc
                                    }))
                                }
                            }
                        }
                    }
                }
            }

            if (!isRawMode) {
                Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    if (kernelVariant == KernelVariant.TURNABLE) {
                        // 1.5 Импорт
                        SettingsGroup(title = stringResource(R.string.import_turnable_title)) {
                            SettingsGroupItem(isTop = true, isBottom = true, containerColor = blockContainerColor) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ImportButton(
                                        onClick = { showQrScanner.value = true },
                                        icon = R.drawable.qr_code_24px,
                                        label = stringResource(R.string.qr_import),
                                        modifier = Modifier.weight(1f)
                                    )
                                    ImportButton(
                                        onClick = {
                                            scope.launch {
                                                val clipEntry = clipboard.getClipEntry()
                                                if (clipEntry != null) {
                                                    val text = clipEntry.clipData.getItemAt(0).text?.toString() ?: ""
                                                    val parsed = TurnableConfig.parse(text)
                                                    if (parsed != null) {
                                                        turnableConfig = parsed
                                                        snackbarHostState.showSnackbar(importSuccessMessage)
                                                    } else {
                                                        snackbarHostState.showSnackbar(importErrorMessage)
                                                    }
                                                }
                                            }
                                        },
                                        icon = R.drawable.content_paste_24px,
                                        label = stringResource(R.string.wg_import_clipboard),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    } else if (kernelVariant == KernelVariant.OLCRTC) {
                        SettingsGroup(title = stringResource(R.string.import_olcrtc_title)) {
                            SettingsGroupItem(isTop = true, isBottom = true, containerColor = blockContainerColor) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ImportButton(
                                        onClick = { showQrScanner.value = true },
                                        icon = R.drawable.qr_code_24px,
                                        label = stringResource(R.string.qr_import),
                                        modifier = Modifier.weight(1f)
                                    )
                                    ImportButton(
                                        onClick = {
                                            scope.launch {
                                                val clipEntry = clipboard.getClipEntry()
                                                if (clipEntry != null) {
                                                    val text = clipEntry.clipData.getItemAt(0).text?.toString() ?: ""
                                                    val parsed = com.wireturn.app.data.OlcrtcConfig.parse(text)
                                                    if (parsed != null) {
                                                        olcrtcConfig = parsed
                                                        snackbarHostState.showSnackbar(importSuccessMessage)
                                                    } else {
                                                        snackbarHostState.showSnackbar(importErrorMessage)
                                                    }
                                                }
                                            }
                                        },
                                        icon = R.drawable.content_paste_24px,
                                        label = stringResource(R.string.wg_import_clipboard),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                    
                    SettingsGroup(title = stringResource(R.string.parameters_title)) {
                        if (kernelVariant == KernelVariant.OLCRTC) {
                            SettingsGroupItem(
                                isTop = true,
                                isBottom = true,
                                containerColor = blockContainerColor
                            ) {
                                TextFieldRow(
                                    label = stringResource(R.string.olcrtc_socks_proxy_label),
                                    value = olcrtcSocksAddr.redact(privacyMode),
                                    onValueChange = { if (!privacyMode) olcrtcSocksAddr = it },
                                    placeholder = stringResource(R.string.local_listen_placeholder),
                                    isError = !isOlcrtcSocksValid || olcrtcSocksAddr.isBlank(),
                                    readOnly = privacyMode,
                                    isModified = clientConfigSnapshot != null && olcrtcSocksAddr.trim() != "${clientConfigSnapshot?.olcrtcConfig?.socksHost}:${clientConfigSnapshot?.olcrtcConfig?.socksPort}",
                                    onHelpClick = { showOlcrtcHelp.value = true }
                                )
                            }
                        } else {
                            // 3.1 Local Port
                            SettingsGroupItem(
                                isTop = true,
                                isBottom = true,
                                containerColor = blockContainerColor
                            ) {
                                TextFieldRow(
                                    label = stringResource(R.string.local_listen_address),
                                    value = localPort.redact(privacyMode),
                                    onValueChange = { if (!privacyMode) localPort = it },
                                    placeholder = stringResource(R.string.local_listen_placeholder),
                                    isError = !isLocalPortValid || localPort.isBlank(),
                                    readOnly = privacyMode,
                                    isModified = clientConfigSnapshot != null && localPort.trim() != clientConfigSnapshot?.localPort,
                                    onHelpClick = { showPortHelp.value = true }
                                )
                            }
                        }
                    }

                    when (kernelVariant) {
                        KernelVariant.TURNABLE -> {
                            if (turnableConfig.routes.isNotEmpty()) {
                                SettingsGroup(title = stringResource(R.string.route_title)) {
                                    SettingsGroupItem(
                                        isTop = true,
                                        isBottom = true,
                                        containerColor = blockContainerColor,
                                        onClick = {
                                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                            showRoutesDialog.value = true
                                        }
                                    ) {
                                        RoutesBlock(
                                            config = turnableConfig,
                                            isModified = clientConfigSnapshot != null && turnableConfig.selectedRouteId != clientConfigSnapshot?.turnableConfig?.selectedRouteId
                                        )
                                    }
                                }

                                // 2. Детали подключения
                                SettingsGroup(title = stringResource(R.string.connection_details)) {
                                    // Peers
                                    SettingsGroupItem(
                                        isTop = true,
                                        isBottom = false,
                                        containerColor = blockContainerColor
                                    ) {
                                        com.wireturn.app.ui.SliderRow(
                                            label = stringResource(R.string.peers_label),
                                            value = turnableConfig.peers.toFloat(),
                                            onValueChange = {
                                                turnableConfig =
                                                    turnableConfig.copy(peers = it.roundToInt())
                                            },
                                            valueRange = 1f..32f,
                                            steps = 30,
                                            supportingText = stringResource(R.string.peers_desc),
                                            isModified = clientConfigSnapshot != null && turnableConfig.peers != clientConfigSnapshot?.turnableConfig?.peers
                                        )
                                    }
                                    // Username
                                    SettingsGroupItem(
                                        isTop = false,
                                        isBottom = false,
                                        containerColor = blockContainerColor
                                    ) {
                                        TextFieldRow(
                                            label = stringResource(R.string.username_label),
                                            value = turnableConfig.username.redact(privacyMode),
                                            onValueChange = {
                                                if (!privacyMode) turnableConfig =
                                                    turnableConfig.copy(username = it)
                                            },
                                            isError = turnableConfig.username.isBlank(),
                                            readOnly = privacyMode,
                                            supportingText = stringResource(R.string.username_desc),
                                            isModified = clientConfigSnapshot != null && turnableConfig.username != clientConfigSnapshot?.turnableConfig?.username
                                        )
                                    }
                                    // Call ID
                                    SettingsGroupItem(
                                        isTop = false,
                                        isBottom = true,
                                        containerColor = blockContainerColor
                                    ) {
                                        TextFieldRow(
                                            label = stringResource(R.string.call_id_label),
                                            value = turnableConfig.callId.redact(privacyMode),
                                            onValueChange = {
                                                if (!privacyMode) turnableConfig =
                                                    turnableConfig.copy(callId = it)
                                            },
                                            isError = turnableConfig.callId.isBlank(),
                                            readOnly = privacyMode,
                                            supportingText = stringResource(R.string.call_id_desc),
                                            isModified = clientConfigSnapshot != null && turnableConfig.callId != clientConfigSnapshot?.turnableConfig?.callId
                                        )
                                    }
                                }

                                SettingsGroup(title = stringResource(R.string.server_settings_title)) {
                                    // User UUID
                                    SettingsGroupItem(
                                        isTop = true,
                                        isBottom = false,
                                        containerColor = blockContainerColor
                                    ) {
                                        TextFieldRow(
                                            label = stringResource(R.string.user_uuid_label),
                                            value = (turnableConfig.userUuid ?: "").redact(privacyMode),
                                            onValueChange = {
                                                if (!privacyMode) turnableConfig =
                                                    turnableConfig.copy(userUuid = it)
                                            },
                                            isError = turnableConfig.type == "relay" && turnableConfig.userUuid.isNullOrBlank(),
                                            readOnly = privacyMode,
                                            supportingText = stringResource(R.string.user_uuid_desc),
                                            isModified = clientConfigSnapshot != null && turnableConfig.userUuid != clientConfigSnapshot?.turnableConfig?.userUuid
                                        )
                                    }
                                    // Platform ID
                                    SettingsGroupItem(
                                        isTop = false,
                                        isBottom = false,
                                        containerColor = blockContainerColor
                                    ) {
                                        TextFieldRow(
                                            label = stringResource(R.string.platform_id_label),
                                            value = turnableConfig.platformId.redact(privacyMode),
                                            onValueChange = {
                                                if (!privacyMode) turnableConfig =
                                                    turnableConfig.copy(platformId = it)
                                            },
                                            isError = turnableConfig.platformId.isBlank(),
                                            readOnly = privacyMode,
                                            supportingText = stringResource(R.string.platform_id_desc),
                                            isModified = clientConfigSnapshot != null && turnableConfig.platformId != clientConfigSnapshot?.turnableConfig?.platformId
                                        )
                                    }
                                    // Type
                                    SettingsGroupItem(
                                        isTop = false,
                                        isBottom = false,
                                        containerColor = blockContainerColor
                                    ) {
                                        LabeledSegmentedButton(
                                            label = stringResource(R.string.connection_type_label),
                                            supportingText = stringResource(R.string.connection_type_desc),
                                            isModified = clientConfigSnapshot != null && turnableConfig.type != clientConfigSnapshot?.turnableConfig?.type
                                        ) {
                                            listOf("relay", "direct").forEachIndexed { index, t ->
                                                SegmentedButton(
                                                    selected = turnableConfig.type == t,
                                                    onClick = {
                                                        HapticUtil.perform(
                                                            context,
                                                            HapticUtil.Pattern.TOGGLE_ON
                                                        )
                                                        turnableConfig = turnableConfig.copy(type = t)
                                                    },
                                                    shape = SegmentedButtonDefaults.itemShape(
                                                        index = index,
                                                        count = 2
                                                    )
                                                ) { Text(t.replaceFirstChar { it.uppercase() }) }
                                            }
                                        }
                                    }
                                    // Encryption
                                    SettingsGroupItem(
                                        isTop = false,
                                        isBottom = false,
                                        containerColor = blockContainerColor
                                    ) {
                                        TextFieldRow(
                                            label = stringResource(R.string.encryption_label),
                                            value = (turnableConfig.encryption ?: "").redact(privacyMode),
                                            onValueChange = {
                                                if (!privacyMode) turnableConfig =
                                                    turnableConfig.copy(encryption = it)
                                            },
                                            isError = turnableConfig.type == "relay" && turnableConfig.encryption.isNullOrBlank(),
                                            readOnly = privacyMode,
                                            supportingText = stringResource(R.string.encryption_desc),
                                            isModified = clientConfigSnapshot != null && turnableConfig.encryption != clientConfigSnapshot?.turnableConfig?.encryption
                                        )
                                    }
                                    // Public Key
                                    SettingsGroupItem(
                                        isTop = false,
                                        isBottom = false,
                                        containerColor = blockContainerColor
                                    ) {
                                        TextFieldRow(
                                            label = stringResource(R.string.pub_key_label),
                                            value = (turnableConfig.pubKey ?: "").redact(privacyMode),
                                            onValueChange = {
                                                if (!privacyMode) turnableConfig =
                                                    turnableConfig.copy(pubKey = it)
                                            },
                                            isError = turnableConfig.type == "relay" && turnableConfig.pubKey.isNullOrBlank(),
                                            readOnly = privacyMode,
                                            supportingText = stringResource(R.string.pub_key_desc),
                                            isModified = clientConfigSnapshot != null && turnableConfig.pubKey != clientConfigSnapshot?.turnableConfig?.pubKey
                                        )
                                    }
                                    // Gateway
                                    SettingsGroupItem(
                                        isTop = false,
                                        isBottom = false,
                                        containerColor = blockContainerColor
                                    ) {
                                        TextFieldRow(
                                            label = stringResource(R.string.gateway_label),
                                            value = turnableConfig.gateway.redact(privacyMode),
                                            onValueChange = {
                                                if (!privacyMode) turnableConfig =
                                                    turnableConfig.copy(gateway = it)
                                            },
                                            isError = turnableConfig.gateway.isBlank(),
                                            readOnly = privacyMode,
                                            supportingText = stringResource(R.string.gateway_desc),
                                            isModified = clientConfigSnapshot != null && turnableConfig.gateway != clientConfigSnapshot?.turnableConfig?.gateway
                                        )
                                    }
                                    // Proto
                                    SettingsGroupItem(
                                        isTop = false,
                                        isBottom = false,
                                        containerColor = blockContainerColor
                                    ) {
                                        LabeledSegmentedButton(
                                            label = stringResource(R.string.proto_label),
                                            supportingText = stringResource(R.string.proto_desc),
                                            isModified = clientConfigSnapshot != null && turnableConfig.proto != clientConfigSnapshot?.turnableConfig?.proto
                                        ) {
                                            val options = listOf("dtls", "srtp", "none")
                                            val currentProto = turnableConfig.proto ?: "none"
                                            options.forEachIndexed { index, p ->
                                                SegmentedButton(
                                                    selected = currentProto == p,
                                                    onClick = {
                                                        if (!privacyMode) {
                                                            HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                                            turnableConfig = turnableConfig.copy(proto = if (p == "none") null else p)
                                                        }
                                                    },
                                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                                                    enabled = !privacyMode
                                                ) {
                                                    Text(p.uppercase())
                                                }
                                            }
                                        }
                                    }
                                    // Force Turn
                                    SettingsGroupItem(
                                        isTop = false,
                                        isBottom = true,
                                        containerColor = blockContainerColor,
                                        onClick = {
                                            val next = !turnableConfig.forceTurn
                                            HapticUtil.perform(
                                                context,
                                                if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                                            )
                                            turnableConfig = turnableConfig.copy(forceTurn = next)
                                        }
                                    ) {
                                        SwitchRow(
                                            label = stringResource(R.string.force_turn_label),
                                            supportingText = stringResource(R.string.force_turn_desc),
                                            checked = turnableConfig.forceTurn,
                                            onCheckedChange = {
                                                turnableConfig = turnableConfig.copy(forceTurn = it)
                                            },
                                            isModified = clientConfigSnapshot != null && turnableConfig.forceTurn != clientConfigSnapshot?.turnableConfig?.forceTurn
                                        )
                                    }
                                }
                            }
                        }
                        
                        KernelVariant.OLCRTC -> {
                            SettingsGroup(title = stringResource(R.string.olcrtc_settings_title)) {
                                // Carrier
                                SettingsGroupItem(isTop = true, isBottom = false, containerColor = blockContainerColor) {
                                    LabeledSegmentedButton(
                                        label = stringResource(R.string.olcrtc_carrier_label),
                                        isModified = clientConfigSnapshot != null && olcrtcConfig.carrier != clientConfigSnapshot?.olcrtcConfig?.carrier
                                    ) {
                                        val carriers = listOf("wbstream" to "WB Stream", "telemost" to "Telemost", "jazz" to "Jazz")
                                        carriers.forEachIndexed { index, (value, label) ->
                                            SegmentedButton(
                                                selected = olcrtcConfig.carrier == value,
                                                onClick = {
                                                    HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                                    olcrtcConfig = olcrtcConfig.copy(carrier = value)
                                                },
                                                shape = SegmentedButtonDefaults.itemShape(index = index, count = carriers.size)
                                            ) {
                                                Text(label)
                                            }
                                        }
                                    }
                                }
                                // Transport
                                SettingsGroupItem(
                                    isTop = false,
                                    isBottom = false,
                                    containerColor = blockContainerColor,
                                    onClick = {
                                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                        showTransportDialog.value = true
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        StandardLeadingIcon {
                                            Icon(
                                                painter = painterResource(R.drawable.route_24px),
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                        Spacer(Modifier.width(20.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            ConfigRowLabel(stringResource(R.string.olcrtc_transport_label))
                                            val currentLabel = when (olcrtcConfig.transport) {
                                                "datachannel" -> "DataChannel"
                                                "vp8channel" -> "VP8Channel"
                                                "seichannel" -> "SEIChannel"
                                                "videochannel" -> "VideoChannel"
                                                else -> olcrtcConfig.transport
                                            }
                                            Spacer(Modifier.height(2.dp))
                                            SupportingText(currentLabel)
                                        }
                                        com.wireturn.app.ui.InlineConfigIndicator(
                                            clientConfigSnapshot != null && olcrtcConfig.transport != clientConfigSnapshot?.olcrtcConfig?.transport
                                        )
                                    }
                                }
                                // ID
                                SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                                    TextFieldRow(
                                        label = stringResource(R.string.olcrtc_id_label),
                                        value = olcrtcConfig.id.redact(privacyMode),
                                        onValueChange = { if (!privacyMode) olcrtcConfig = olcrtcConfig.copy(id = it) },
                                        isError = olcrtcConfig.id.isBlank(),
                                        readOnly = privacyMode,
                                        isModified = clientConfigSnapshot != null && olcrtcConfig.id != clientConfigSnapshot?.olcrtcConfig?.id
                                    )
                                }
                                // Client ID
                                SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                                    TextFieldRow(
                                        label = stringResource(R.string.olcrtc_client_id_label),
                                        value = olcrtcConfig.clientId.redact(privacyMode),
                                        onValueChange = { if (!privacyMode) olcrtcConfig = olcrtcConfig.copy(clientId = it) },
                                        isError = olcrtcConfig.clientId.isBlank(),
                                        readOnly = privacyMode,
                                        isModified = clientConfigSnapshot != null && olcrtcConfig.clientId != clientConfigSnapshot?.olcrtcConfig?.clientId
                                    )
                                }
                                // Key
                                SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                                    TextFieldRow(
                                        label = stringResource(R.string.olcrtc_key_label),
                                        value = olcrtcConfig.key.redact(privacyMode),
                                        onValueChange = { if (!privacyMode) olcrtcConfig = olcrtcConfig.copy(key = it) },
                                        isError = olcrtcConfig.key.isBlank(),
                                        readOnly = privacyMode,
                                        isModified = clientConfigSnapshot != null && olcrtcConfig.key != clientConfigSnapshot?.olcrtcConfig?.key
                                    )
                                }
                                // DNS
                                SettingsGroupItem(isTop = false, isBottom = true, containerColor = blockContainerColor) {
                                    TextFieldRow(
                                        label = stringResource(R.string.olcrtc_dns_label),
                                        value = olcrtcConfig.dns,
                                        onValueChange = { olcrtcConfig = olcrtcConfig.copy(dns = it) },
                                        isError = olcrtcConfig.dns.isBlank(),
                                        isModified = clientConfigSnapshot != null && olcrtcConfig.dns != clientConfigSnapshot?.olcrtcConfig?.dns
                                    )
                                }
                            }

                            when (olcrtcConfig.transport) {
                                "vp8channel" -> {
                                    SettingsGroup(title = stringResource(R.string.olcrtc_vp8_settings_title)) {
                                        SettingsGroupItem(isTop = true, isBottom = false, containerColor = blockContainerColor) {
                                            com.wireturn.app.ui.SliderRow(
                                                label = stringResource(R.string.olcrtc_vp8_fps),
                                                value = olcrtcConfig.vp8Fps.toFloat(),
                                                onValueChange = { olcrtcConfig = olcrtcConfig.copy(vp8Fps = it.roundToInt()) },
                                                valueRange = 1f..60f,
                                                steps = 59,
                                                isModified = clientConfigSnapshot != null && olcrtcConfig.vp8Fps != clientConfigSnapshot?.olcrtcConfig?.vp8Fps
                                            )
                                        }
                                        SettingsGroupItem(isTop = false, isBottom = true, containerColor = blockContainerColor) {
                                            com.wireturn.app.ui.SliderRow(
                                                label = stringResource(R.string.olcrtc_vp8_batch),
                                                value = olcrtcConfig.vp8Batch.toFloat(),
                                                onValueChange = { olcrtcConfig = olcrtcConfig.copy(vp8Batch = it.roundToInt()) },
                                                valueRange = 1f..100f,
                                                steps = 99,
                                                isModified = clientConfigSnapshot != null && olcrtcConfig.vp8Batch != clientConfigSnapshot?.olcrtcConfig?.vp8Batch
                                            )
                                        }
                                    }
                                }
                                "seichannel" -> {
                                    SettingsGroup(title = stringResource(R.string.olcrtc_sei_settings_title)) {
                                        SettingsGroupItem(isTop = true, isBottom = false, containerColor = blockContainerColor) {
                                            com.wireturn.app.ui.SliderRow(
                                                label = stringResource(R.string.olcrtc_sei_fps),
                                                value = olcrtcConfig.seiFps.toFloat(),
                                                onValueChange = { olcrtcConfig = olcrtcConfig.copy(seiFps = it.roundToInt()) },
                                                valueRange = 1f..120f,
                                                steps = 119,
                                                isModified = clientConfigSnapshot != null && olcrtcConfig.seiFps != clientConfigSnapshot?.olcrtcConfig?.seiFps
                                            )
                                        }
                                        SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                                            com.wireturn.app.ui.SliderRow(
                                                label = stringResource(R.string.olcrtc_sei_batch),
                                                value = olcrtcConfig.seiBatch.toFloat(),
                                                onValueChange = { olcrtcConfig = olcrtcConfig.copy(seiBatch = it.roundToInt()) },
                                                valueRange = 1f..256f,
                                                steps = 255,
                                                isModified = clientConfigSnapshot != null && olcrtcConfig.seiBatch != clientConfigSnapshot?.olcrtcConfig?.seiBatch
                                            )
                                        }
                                        SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                                            com.wireturn.app.ui.SliderRow(
                                                label = stringResource(R.string.olcrtc_sei_frag),
                                                value = olcrtcConfig.seiFrag.toFloat(),
                                                onValueChange = { olcrtcConfig = olcrtcConfig.copy(seiFrag = it.roundToInt()) },
                                                valueRange = 100f..1500f,
                                                steps = 140,
                                                isModified = clientConfigSnapshot != null && olcrtcConfig.seiFrag != clientConfigSnapshot?.olcrtcConfig?.seiFrag
                                            )
                                        }
                                        SettingsGroupItem(isTop = false, isBottom = true, containerColor = blockContainerColor) {
                                            com.wireturn.app.ui.SliderRow(
                                                label = stringResource(R.string.olcrtc_sei_ack_ms),
                                                value = olcrtcConfig.seiAckMs.toFloat(),
                                                onValueChange = { olcrtcConfig = olcrtcConfig.copy(seiAckMs = it.roundToInt()) },
                                                valueRange = 100f..5000f,
                                                steps = 49,
                                                isModified = clientConfigSnapshot != null && olcrtcConfig.seiAckMs != clientConfigSnapshot?.olcrtcConfig?.seiAckMs
                                            )
                                        }
                                    }
                                }
                                "videochannel" -> {
                                    SettingsGroup(title = stringResource(R.string.olcrtc_video_settings_title)) {
                                        SettingsGroupItem(isTop = true, isBottom = false, containerColor = blockContainerColor) {
                                            TextFieldRow(
                                                label = stringResource(R.string.olcrtc_video_codec),
                                                value = olcrtcConfig.videoCodec,
                                                onValueChange = { olcrtcConfig = olcrtcConfig.copy(videoCodec = it) },
                                                isModified = clientConfigSnapshot != null && olcrtcConfig.videoCodec != clientConfigSnapshot?.olcrtcConfig?.videoCodec
                                            )
                                        }
                                        SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                                TextFieldRow(
                                                    label = stringResource(R.string.olcrtc_video_width),
                                                    value = olcrtcConfig.videoW.toString(),
                                                    onValueChange = { olcrtcConfig = olcrtcConfig.copy(videoW = it.toIntOrNull() ?: 1920) },
                                                    modifier = Modifier.weight(1f),
                                                    isModified = clientConfigSnapshot != null && olcrtcConfig.videoW != clientConfigSnapshot?.olcrtcConfig?.videoW
                                                )
                                                TextFieldRow(
                                                    label = stringResource(R.string.olcrtc_video_height),
                                                    value = olcrtcConfig.videoH.toString(),
                                                    onValueChange = { olcrtcConfig = olcrtcConfig.copy(videoH = it.toIntOrNull() ?: 1080) },
                                                    modifier = Modifier.weight(1f),
                                                    isModified = clientConfigSnapshot != null && olcrtcConfig.videoH != clientConfigSnapshot?.olcrtcConfig?.videoH
                                                )
                                            }
                                        }
                                        SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                                            com.wireturn.app.ui.SliderRow(
                                                label = stringResource(R.string.olcrtc_video_fps),
                                                value = olcrtcConfig.videoFps.toFloat(),
                                                onValueChange = { olcrtcConfig = olcrtcConfig.copy(videoFps = it.roundToInt()) },
                                                valueRange = 1f..60f,
                                                steps = 59,
                                                isModified = clientConfigSnapshot != null && olcrtcConfig.videoFps != clientConfigSnapshot?.olcrtcConfig?.videoFps
                                            )
                                        }
                                        SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                                            TextFieldRow(
                                                label = stringResource(R.string.olcrtc_video_bitrate),
                                                value = olcrtcConfig.videoBitrate,
                                                onValueChange = { olcrtcConfig = olcrtcConfig.copy(videoBitrate = it) },
                                                isModified = clientConfigSnapshot != null && olcrtcConfig.videoBitrate != clientConfigSnapshot?.olcrtcConfig?.videoBitrate
                                            )
                                        }
                                        SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                                            TextFieldRow(
                                                label = stringResource(R.string.olcrtc_video_hw),
                                                value = olcrtcConfig.videoHw,
                                                onValueChange = { olcrtcConfig = olcrtcConfig.copy(videoHw = it) },
                                                isModified = clientConfigSnapshot != null && olcrtcConfig.videoHw != clientConfigSnapshot?.olcrtcConfig?.videoHw
                                            )
                                        }
                                        SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                                            TextFieldRow(
                                                label = stringResource(R.string.olcrtc_video_qr_recovery),
                                                value = olcrtcConfig.videoQrRecovery,
                                                onValueChange = { olcrtcConfig = olcrtcConfig.copy(videoQrRecovery = it) },
                                                isModified = clientConfigSnapshot != null && olcrtcConfig.videoQrRecovery != clientConfigSnapshot?.olcrtcConfig?.videoQrRecovery
                                            )
                                        }
                                        SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                                            com.wireturn.app.ui.SliderRow(
                                                label = stringResource(R.string.olcrtc_video_qr_size),
                                                value = olcrtcConfig.videoQrSize.toFloat(),
                                                onValueChange = { olcrtcConfig = olcrtcConfig.copy(videoQrSize = it.roundToInt()) },
                                                valueRange = 0f..1000f,
                                                steps = 100,
                                                isModified = clientConfigSnapshot != null && olcrtcConfig.videoQrSize != clientConfigSnapshot?.olcrtcConfig?.videoQrSize
                                            )
                                        }
                                        SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                                            com.wireturn.app.ui.SliderRow(
                                                label = stringResource(R.string.olcrtc_video_tile_module),
                                                value = olcrtcConfig.videoTileModule.toFloat(),
                                                onValueChange = { olcrtcConfig = olcrtcConfig.copy(videoTileModule = it.roundToInt()) },
                                                valueRange = 1f..32f,
                                                steps = 31,
                                                isModified = clientConfigSnapshot != null && olcrtcConfig.videoTileModule != clientConfigSnapshot?.olcrtcConfig?.videoTileModule
                                            )
                                        }
                                        SettingsGroupItem(isTop = false, isBottom = true, containerColor = blockContainerColor) {
                                            com.wireturn.app.ui.SliderRow(
                                                label = stringResource(R.string.olcrtc_video_tile_rs),
                                                value = olcrtcConfig.videoTileRs.toFloat(),
                                                onValueChange = { olcrtcConfig = olcrtcConfig.copy(videoTileRs = it.roundToInt()) },
                                                valueRange = 0f..100f,
                                                steps = 100,
                                                isModified = clientConfigSnapshot != null && olcrtcConfig.videoTileRs != clientConfigSnapshot?.olcrtcConfig?.videoTileRs
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 5. Ядро
            SettingsGroup(title = stringResource(R.string.core_title)) {
                SettingsGroupItem(isTop = true, isBottom = kernelError == null, containerColor = blockContainerColor) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.weight(1f)) {
                            StandardLeadingIcon {
                                Icon(
                                    painter = painterResource(if (customKernelExists) R.drawable.check_circle_24px else R.drawable.memory_24px),
                                    contentDescription = null,
                                    tint = if (customKernelExists) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                ConfigRowLabel(if (customKernelExists) stringResource(R.string.custom_core) else stringResource(R.string.builtin_core))
                                SupportingText(if (customKernelExists) {
                                    customKernelLastModified?.let {
                                        SimpleDateFormat("dd.MM.yyyy HH:mm", LocalLocale.current.platformLocale).format(Date(it))
                                    }?.let { stringResource(R.string.kernel_date_format, it) } ?: stringResource(R.string.loaded_from_memory)
                                } else stringResource(R.string.from_apk))
                            }
                        }
                        if (customKernelExists) {
                            IconButton(onClick = {
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                viewModel.clearCustomKernel()
                            }) {
                                Icon(painter = painterResource(R.drawable.delete_24px), contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            FilledTonalButton(onClick = {
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                viewModel.clearKernelError()
                                kernelPickerLauncher.launch(arrayOf("*/*"))
                            }) { Text(stringResource(R.string.btn_load)) }
                        }
                    }
                }
                if (kernelError != null) {
                    SettingsGroupItem(isTop = false, isBottom = true, containerColor = blockContainerColor) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            StandardLeadingIcon {
                                Icon(
                                    painter = painterResource(R.drawable.error_24px),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            SupportingText(kernelError!!, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            if (showFinishButton && onFinish != null) {
                val isValid = remember(isRawMode, rawCommand, turnableConfig, olcrtcConfig, kernelVariant) {
                    com.wireturn.app.data.ClientConfig(
                        isRawMode = isRawMode,
                        rawCommand = rawCommand,
                        turnableConfig = turnableConfig,
                        olcrtcConfig = olcrtcConfig,
                        kernelVariant = kernelVariant
                    ).isValid
                }
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

    if (showRoutesDialog.value) {
        RoutesDialog(
            config = turnableConfig,
            onSelect = {
                turnableConfig = turnableConfig.copy(selectedRouteId = it)
                showRoutesDialog.value = false
            },
            onDismiss = { showRoutesDialog.value = false }
        )
    }

    if (showQrScanner.value) {
        QrScannerDialog(
            title = stringResource(R.string.qr_import),
            message = stringResource(R.string.qr_scan_desc),
            onDismiss = { showQrScanner.value = false },
            onResult = { result ->
                val turnableParsed = TurnableConfig.parse(result)
                val olcrtcParsed = com.wireturn.app.data.OlcrtcConfig.parse(result)

                if (turnableParsed != null) {
                    kernelVariant = KernelVariant.TURNABLE
                    turnableConfig = turnableParsed
                    scope.launch { snackbarHostState.showSnackbar(importSuccessMessage) }
                } else if (olcrtcParsed != null) {
                    kernelVariant = KernelVariant.OLCRTC
                    olcrtcConfig = olcrtcParsed
                    scope.launch { snackbarHostState.showSnackbar(importSuccessMessage) }
                } else {
                    scope.launch { snackbarHostState.showSnackbar(importErrorMessage) }
                }
            }
        )
    }

    if (showTransportDialog.value) {
        TransportDialog(
            currentTransport = olcrtcConfig.transport,
            onSelect = {
                olcrtcConfig = olcrtcConfig.copy(transport = it)
                showTransportDialog.value = false
            },
            onDismiss = { showTransportDialog.value = false }
        )
    }

    if (showOlcrtcHelp.value) {
        AlertDialog(
            onDismissRequest = { showOlcrtcHelp.value = false },
            title = { Text(stringResource(R.string.olcrtc_socks_proxy_label)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.olcrtc_socks_help_text),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.olcrtc_socks_help_secondary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showOlcrtcHelp.value = false }) {
                    Text(stringResource(R.string.btn_close))
                }
            }
        )
    }

    if (showPortHelp.value) {
        AlertDialog(
            onDismissRequest = { showPortHelp.value = false },
            title = { Text(stringResource(R.string.local_listen_address)) },
            text = { 
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.local_port_help_text),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.local_port_help_secondary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showPortHelp.value = false }) {
                    Text(stringResource(R.string.btn_close))
                }
            }
        )
    }
}

@Composable
fun RouteSummary(
    route: TurnableRoute,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Text(
        text = "${route.socket.uppercase()} • ${route.transport?.uppercase() ?: "none"}",
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        modifier = modifier
    )
}

@Composable
fun RoutesBlock(
    config: TurnableConfig,
    modifier: Modifier = Modifier,
    isModified: Boolean = false
) {
    val selectedRoute = config.routes.find { it.routeId == config.selectedRouteId } ?: config.routes.firstOrNull()
    if (selectedRoute != null) {
        Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StandardLeadingIcon {
                Icon(
                    painter = painterResource(R.drawable.route_24px),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = selectedRoute.name.ifBlank { selectedRoute.routeId },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee().weight(1f, fill = false)
                    )
                    com.wireturn.app.ui.InlineConfigIndicator(isModified)
                }
                RouteSummary(route = selectedRoute)
            }
        }
    }
}

@Composable
fun RoutesDialog(
    config: TurnableConfig,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    SelectionDialog(
        title = stringResource(R.string.route_title),
        items = config.routes,
        isSelected = { it.routeId == config.selectedRouteId },
        onSelect = { onSelect(it.routeId) },
        onDismiss = onDismiss
    ) { route, isSelected ->
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StandardLeadingIcon {
                Icon(
                    painter = painterResource(R.drawable.route_24px),
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = route.name.ifBlank { route.routeId },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                RouteSummary(
                    route = route,
                    color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
fun TransportDialog(
    currentTransport: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val transports = listOf(
        "datachannel" to "DataChannel",
        "vp8channel" to "VP8Channel",
        "seichannel" to "SEIChannel",
        "videochannel" to "VideoChannel"
    )

    SelectionDialog(
        title = stringResource(R.string.olcrtc_transport_label),
        items = transports,
        isSelected = { it.first == currentTransport },
        onSelect = { onSelect(it.first) },
        onDismiss = onDismiss
    ) { (_, label), isSelected ->
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StandardLeadingIcon {
                Icon(
                    painter = painterResource(R.drawable.route_24px),
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(20.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
