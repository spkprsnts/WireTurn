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
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.R
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.KernelVariant
import com.wireturn.app.data.OlcrtcConfig
import com.wireturn.app.data.TurnableConfig
import com.wireturn.app.data.TurnableRoute
import com.wireturn.app.ui.ConfigDropdownMenu
import com.wireturn.app.ui.ConfigRowLabel
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.LabeledButtonGroup
import com.wireturn.app.ui.LargeLeadingIcon
import com.wireturn.app.ui.configButtonGroupItem
import com.wireturn.app.ui.SelectionDialog
import com.wireturn.app.ui.SettingsGroup
import com.wireturn.app.ui.SettingsGroupItem
import com.wireturn.app.ui.StandardLeadingIcon
import com.wireturn.app.ui.SupportingText
import com.wireturn.app.ui.SwitchRow
import com.wireturn.app.ui.TextFieldRow
import com.wireturn.app.ui.ValidatorUtils
import com.wireturn.app.ui.redact
import com.wireturn.app.ui.showExclusiveSnackbar
import com.wireturn.app.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ClientConfigScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val saved by viewModel.clientConfig.collectAsStateWithLifecycle()
    val customKernelExists by viewModel.customKernelExists.collectAsStateWithLifecycle()
    val customKernelLastModified by viewModel.customKernelLastModified.collectAsStateWithLifecycle()
    val kernelError by viewModel.kernelError.collectAsStateWithLifecycle()
    val privacyMode by viewModel.privacyMode.collectAsStateWithLifecycle()

    val clientConfigSnapshot by com.wireturn.app.ProxyServiceState.clientConfigSnapshot.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val uriHandler = LocalUriHandler.current

    val kernelPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.setCustomKernel(it) } }

    var isRawMode by remember(saved) { mutableStateOf(saved.isRawMode) }
    var rawCommand by remember(saved) { mutableStateOf(saved.rawCommand) }
    var turnableConfig by remember(saved) { mutableStateOf(saved.turnableConfig) }
    var olcrtcConfig by remember(saved) { mutableStateOf(saved.olcrtcConfig) }
    var olcrtcSocksAddr by remember(saved) { mutableStateOf("${saved.olcrtcConfig.socksHost}:${saved.olcrtcConfig.socksPort}") }
    var videoW by remember(saved) { mutableStateOf(saved.olcrtcConfig.videoW.let { if (it == 0) "" else it.toString() }) }
    var videoH by remember(saved) { mutableStateOf(saved.olcrtcConfig.videoH.let { if (it == 0) "" else it.toString() }) }
    var localPort by remember(saved) { mutableStateOf(saved.localPort) }
    var kernelVariant by remember(saved) { mutableStateOf(saved.kernelVariant) }

    val showPortHelp = remember { mutableStateOf(false) }
    val showOlcrtcHelp = remember { mutableStateOf(false) }
    val showKernelHelp = remember { mutableStateOf(false) }
    val showRoutesDialog = remember { mutableStateOf(false) }
    val showQrScanner = remember { mutableStateOf(false) }
    val showTransportDialog = remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val importSuccessMessage = stringResource(R.string.import_success)
    val importErrorMessage = stringResource(R.string.import_error)

    val configPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            uri?.let {
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val text = input.bufferedReader().use { r -> r.readText() }.trim()
                        val turnableParsed = TurnableConfig.parse(text)
                        val olcrtcParsed = OlcrtcConfig.parse(text)

                        if (turnableParsed != null) {
                            HapticUtil.perform(context, HapticUtil.Pattern.SUCCESS)
                            kernelVariant = KernelVariant.TURNABLE
                            turnableConfig = turnableParsed
                            scope.launch { snackbarHostState.showExclusiveSnackbar(importSuccessMessage) }
                        } else if (olcrtcParsed != null) {
                            HapticUtil.perform(context, HapticUtil.Pattern.SUCCESS)
                            kernelVariant = KernelVariant.OLCRTC
                            olcrtcConfig = olcrtcParsed
                            videoW = olcrtcParsed.videoW.let { if (it == 0) "" else it.toString() }
                            videoH = olcrtcParsed.videoH.let { if (it == 0) "" else it.toString() }
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

    val isLocalPortValid = remember(localPort) { ValidatorUtils.isValidHostPort(localPort) }
    val isOlcrtcSocksValid = remember(olcrtcSocksAddr) { ValidatorUtils.isValidHostPort(olcrtcSocksAddr) }

    LaunchedEffect(isRawMode, rawCommand, turnableConfig, olcrtcConfig, localPort, olcrtcSocksAddr, kernelVariant, videoW, videoH) {
        delay(200)
        val current = viewModel.clientConfig.value
        
        var effectiveOlcrtcConfig = olcrtcConfig.copy(
            videoW = videoW.toIntOrNull() ?: 0,
            videoH = videoH.toIntOrNull() ?: 0
        )
        if (isOlcrtcSocksValid) {
            val parts = olcrtcSocksAddr.split(":")
            effectiveOlcrtcConfig = effectiveOlcrtcConfig.copy(
                socksHost = (parts.getOrNull(0) ?: "").ifBlank { ClientConfig.DEFAULT_SOCKS_HOST },
                socksPort = (parts.getOrNull(1) ?: "").ifBlank { ClientConfig.DEFAULT_SOCKS_PORT }
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
                    if (!isRawMode) {
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
                                contentDescription = stringResource(R.string.import_clipboard)
                            )
                            ConfigDropdownMenu(
                                expanded = showImportMenu,
                                onDismissRequest = { showImportMenu = false },
                                title = stringResource(R.string.client_import_config)
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.import_clipboard)) },
                                    leadingIcon = { Icon(painterResource(R.drawable.content_paste_24px), null) },
                                    onClick = {
                                        showImportMenu = false
                                        scope.launch {
                                            val clipEntry = clipboard.getClipEntry()
                                            val text = clipEntry?.clipData?.getItemAt(0)?.text?.toString() ?: ""
                                            val turnableParsed = TurnableConfig.parse(text)
                                            val olcrtcParsed = OlcrtcConfig.parse(text)

                                            if (turnableParsed != null) {
                                                HapticUtil.perform(context, HapticUtil.Pattern.SUCCESS)
                                                kernelVariant = KernelVariant.TURNABLE
                                                turnableConfig = turnableParsed
                                                snackbarHostState.showExclusiveSnackbar(importSuccessMessage)
                                            } else if (olcrtcParsed != null) {
                                                HapticUtil.perform(context, HapticUtil.Pattern.SUCCESS)
                                                kernelVariant = KernelVariant.OLCRTC
                                                olcrtcConfig = olcrtcParsed
                                                videoW = olcrtcParsed.videoW.let { if (it == 0) "" else it.toString() }
                                                videoH = olcrtcParsed.videoH.let { if (it == 0) "" else it.toString() }
                                                snackbarHostState.showExclusiveSnackbar(importSuccessMessage)
                                            } else if (text.isNotBlank()) {
                                                HapticUtil.perform(context, HapticUtil.Pattern.ERROR)
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
                                        configPickerLauncher.launch("*/*")
                                    }
                                )
                            }
                        }
                        val canShare = remember(kernelVariant, turnableConfig, olcrtcConfig) {
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
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, uri)
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
                        val variants = KernelVariant.entries
                        val variantLabels = variants.associateWith { variant ->
                            stringResource(when(variant) {
                                KernelVariant.TURNABLE -> R.string.kernel_turnable
                                KernelVariant.OLCRTC -> R.string.kernel_olcrtc
                            })
                        }
                        SettingsGroupItem(isTop = false, isBottom = true, containerColor = blockContainerColor) {
                            LabeledButtonGroup(
                                label = stringResource(R.string.kernel_label),
                                supportingText = stringResource(R.string.client_variants_desc),
                                isModified = clientConfigSnapshot != null && kernelVariant != clientConfigSnapshot?.kernelVariant,
                                onHelpClick = { showKernelHelp.value = true }
                            ) {
                                variants.forEachIndexed { index, variant ->
                                    configButtonGroupItem(
                                        selected = kernelVariant == variant,
                                        onSelect = {
                                            HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                            kernelVariant = variant
                                        },
                                        label = variantLabels[variant] ?: "",
                                        index = index,
                                        count = variants.size
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // Обычный режим: Выбор ядра
                    val variants = KernelVariant.entries
                    val variantLabels = variants.associateWith { variant ->
                        stringResource(when(variant) {
                            KernelVariant.TURNABLE -> R.string.kernel_turnable
                            KernelVariant.OLCRTC -> R.string.kernel_olcrtc
                        })
                    }
                    SettingsGroupItem(isTop = false, isBottom = true, containerColor = blockContainerColor) {
                        LabeledButtonGroup(
                            label = if (customKernelExists) stringResource(R.string.kernel_config_label) else stringResource(R.string.kernel_label),
                            supportingText = if (customKernelExists) stringResource(R.string.kernel_config_desc) else stringResource(R.string.client_variants_desc),
                            isModified = clientConfigSnapshot != null && kernelVariant != clientConfigSnapshot?.kernelVariant,
                            onHelpClick = { showKernelHelp.value = true }
                        ) {
                            variants.forEachIndexed { index, variant ->
                                configButtonGroupItem(
                                    selected = kernelVariant == variant,
                                    onSelect = {
                                        HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                        kernelVariant = variant
                                    },
                                    label = variantLabels[variant] ?: "",
                                    index = index,
                                    count = variants.size
                                )
                            }
                        }
                    }
                }
            }

            if (!isRawMode) {
                Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    when (kernelVariant) {
                        KernelVariant.TURNABLE -> {
                            if (turnableConfig.routes.isNotEmpty()) {
                                TurnableSettings(
                                    config = turnableConfig,
                                    onConfigChange = { turnableConfig = it },
                                    localPort = localPort,
                                    onLocalPortChange = { localPort = it },
                                    isLocalPortValid = isLocalPortValid,
                                    privacyMode = privacyMode,
                                    clientConfigSnapshot = clientConfigSnapshot,
                                    blockContainerColor = blockContainerColor,
                                    onShowRoutesDialog = { showRoutesDialog.value = true },
                                    onShowPortHelp = { showPortHelp.value = true }
                                )
                            } else {
                                TurnableMissingConfig(blockContainerColor)
                            }
                        }
                        
                        KernelVariant.OLCRTC -> {
                            OlcrtcSettings(
                                config = olcrtcConfig,
                                onConfigChange = { olcrtcConfig = it },
                                olcrtcSocksAddr = olcrtcSocksAddr,
                                onSocksAddrChange = { olcrtcSocksAddr = it },
                                isOlcrtcSocksValid = isOlcrtcSocksValid,
                                videoW = videoW,
                                onVideoWChange = { videoW = it },
                                videoH = videoH,
                                onVideoHChange = { videoH = it },
                                privacyMode = privacyMode,
                                clientConfigSnapshot = clientConfigSnapshot,
                                blockContainerColor = blockContainerColor,
                                onShowOlcrtcHelp = { showOlcrtcHelp.value = true },
                                onShowTransportDialog = { showTransportDialog.value = true }
                            )
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
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            LargeLeadingIcon {
                                Icon(
                                    painter = painterResource(if (customKernelExists) R.drawable.check_circle_24px else R.drawable.memory_24px),
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp),
                                    tint = if (customKernelExists) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LargeLeadingIcon {
                                Icon(
                                    painter = painterResource(R.drawable.error_24px),
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                            SupportingText(kernelError!!, color = MaterialTheme.colorScheme.error)
                        }
                    }
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
                val olcrtcParsed = OlcrtcConfig.parse(result)

                if (turnableParsed != null) {
                    kernelVariant = KernelVariant.TURNABLE
                    turnableConfig = turnableParsed
                    scope.launch { 
                        snackbarHostState.showExclusiveSnackbar(importSuccessMessage) 
                    }
                } else if (olcrtcParsed != null) {
                    kernelVariant = KernelVariant.OLCRTC
                    olcrtcConfig = olcrtcParsed
                    videoW = olcrtcParsed.videoW.let { if (it == 0) "" else it.toString() }
                    videoH = olcrtcParsed.videoH.let { if (it == 0) "" else it.toString() }
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

    if (showKernelHelp.value) {
        val appVersion = remember {
            try {
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                packageInfo.versionName ?: ""
            } catch (_: Exception) {
                ""
            }
        }
        val branch = if (appVersion.contains("unstable", ignoreCase = true)) "unstable" else "main"
        val turnableGuideUrl = "https://github.com/spkprsnts/WireTurn/blob/$branch/docs/guides/turnable.md"
        val olcrtcGuideUrl = "https://github.com/spkprsnts/WireTurn/blob/$branch/docs/guides/olcrtc.md"

        AlertDialog(
            onDismissRequest = { showKernelHelp.value = false },
            title = { Text(stringResource(R.string.kernel_help_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { uriHandler.openUri(turnableGuideUrl) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Start
                        ) {
                            Icon(painterResource(R.drawable.open_in_new_24px), contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.kernel_help_turnable_guide), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    TextButton(
                        onClick = { uriHandler.openUri(olcrtcGuideUrl) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Start
                        ) {
                            Icon(painterResource(R.drawable.open_in_new_24px), contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.kernel_help_olcrtc_guide), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showKernelHelp.value = false }) {
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
private fun TurnableSettings(
    config: TurnableConfig,
    onConfigChange: (TurnableConfig) -> Unit,
    localPort: String,
    onLocalPortChange: (String) -> Unit,
    isLocalPortValid: Boolean,
    privacyMode: Boolean,
    clientConfigSnapshot: ClientConfig?,
    blockContainerColor: Color,
    onShowRoutesDialog: () -> Unit,
    onShowPortHelp: () -> Unit
) {
    val context = LocalContext.current
    SettingsGroup(title = stringResource(R.string.route_title)) {
        SettingsGroupItem(
            isTop = true,
            isBottom = true,
            containerColor = blockContainerColor,
            onClick = {
                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                onShowRoutesDialog()
            }
        ) {
            RoutesBlock(
                config = config,
                isModified = clientConfigSnapshot != null && config.selectedRouteId != clientConfigSnapshot.turnableConfig.selectedRouteId
            )
        }
    }

    // 2. Детали подключения
    SettingsGroup(title = stringResource(R.string.connection_details)) {
        // Local Port
        SettingsGroupItem(
            isTop = true,
            isBottom = false,
            containerColor = blockContainerColor
        ) {
            TextFieldRow(
                label = stringResource(R.string.local_listen_address),
                value = localPort.redact(privacyMode),
                onValueChange = onLocalPortChange,
                placeholder = stringResource(R.string.local_listen_placeholder),
                isError = !isLocalPortValid || localPort.isBlank(),
                readOnly = privacyMode,
                isModified = clientConfigSnapshot != null && localPort.trim() != clientConfigSnapshot.localPort,
                onHelpClick = onShowPortHelp
            )
        }
        // Peers
        SettingsGroupItem(
            isTop = false,
            isBottom = false,
            containerColor = blockContainerColor
        ) {
            com.wireturn.app.ui.SliderRow(
                label = stringResource(R.string.peers_label),
                value = config.peers.toFloat(),
                onValueChange = {
                    onConfigChange(config.copy(peers = it.roundToInt()))
                },
                valueRange = 1f..32f,
                steps = 30,
                supportingText = stringResource(R.string.peers_desc),
                isModified = clientConfigSnapshot != null && config.peers != clientConfigSnapshot.turnableConfig.peers
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
                value = config.username.redact(privacyMode),
                onValueChange = {
                    if (!privacyMode) onConfigChange(config.copy(username = it))
                },
                isError = config.username.isBlank(),
                readOnly = privacyMode,
                supportingText = stringResource(R.string.username_desc),
                isModified = clientConfigSnapshot != null && config.username != clientConfigSnapshot.turnableConfig.username
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
                value = config.callId.redact(privacyMode),
                onValueChange = {
                    if (!privacyMode) onConfigChange(config.copy(callId = it))
                },
                isError = config.callId.isBlank(),
                readOnly = privacyMode,
                supportingText = stringResource(R.string.call_id_desc),
                isModified = clientConfigSnapshot != null && config.callId != clientConfigSnapshot.turnableConfig.callId
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
                value = (config.userUuid ?: "").redact(privacyMode),
                onValueChange = {
                    if (!privacyMode) onConfigChange(config.copy(userUuid = it))
                },
                isError = config.type == "relay" && config.userUuid.isNullOrBlank(),
                readOnly = privacyMode,
                supportingText = stringResource(R.string.user_uuid_desc),
                isModified = clientConfigSnapshot != null && config.userUuid != clientConfigSnapshot.turnableConfig.userUuid
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
                value = config.platformId.redact(privacyMode),
                onValueChange = {
                    if (!privacyMode) onConfigChange(config.copy(platformId = it))
                },
                isError = config.platformId.isBlank(),
                readOnly = privacyMode,
                supportingText = stringResource(R.string.platform_id_desc),
                isModified = clientConfigSnapshot != null && config.platformId != clientConfigSnapshot.turnableConfig.platformId
            )
        }
        // Type
        SettingsGroupItem(
            isTop = false,
            isBottom = false,
            containerColor = blockContainerColor
        ) {
            LabeledButtonGroup(
                label = stringResource(R.string.connection_type_label),
                supportingText = stringResource(R.string.connection_type_desc),
                isModified = clientConfigSnapshot != null && config.type != clientConfigSnapshot.turnableConfig.type
            ) {
                val types = listOf("relay", "direct")
                types.forEachIndexed { index, t ->
                    configButtonGroupItem(
                        selected = config.type == t,
                        onSelect = {
                            HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                            onConfigChange(config.copy(type = t))
                        },
                        label = t.replaceFirstChar { it.uppercase() },
                        index = index,
                        count = types.size
                    )
                }
            }
        }
        // Public Key
        SettingsGroupItem(
            isTop = false,
            isBottom = false,
            containerColor = blockContainerColor
        ) {
            TextFieldRow(
                label = stringResource(R.string.pub_key_label),
                value = (config.pubKey ?: "").redact(privacyMode),
                onValueChange = {
                    if (!privacyMode) onConfigChange(config.copy(pubKey = it))
                },
                isError = config.type == "relay" && config.pubKey.isNullOrBlank(),
                readOnly = privacyMode,
                supportingText = stringResource(R.string.pub_key_desc),
                isModified = clientConfigSnapshot != null && config.pubKey != clientConfigSnapshot.turnableConfig.pubKey
            )
        }
        // Encryption
        SettingsGroupItem(
            isTop = false,
            isBottom = false,
            containerColor = blockContainerColor
        ) {
            LabeledButtonGroup(
                label = stringResource(R.string.encryption_label),
                supportingText = stringResource(R.string.encryption_desc),
                isModified = clientConfigSnapshot != null && config.encryption != clientConfigSnapshot.turnableConfig.encryption
            ) {
                val options = listOf("handshake", "full")
                options.forEachIndexed { index, e ->
                    configButtonGroupItem(
                        selected = config.encryption == e,
                        onSelect = {
                            if (!privacyMode) {
                                HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                onConfigChange(config.copy(encryption = e))
                            }
                        },
                        label = e.replaceFirstChar { it.uppercase() },
                        enabled = !privacyMode,
                        index = index,
                        count = options.size
                    )
                }
            }
        }
        // Gateway
        SettingsGroupItem(
            isTop = false,
            isBottom = false,
            containerColor = blockContainerColor
        ) {
            TextFieldRow(
                label = stringResource(R.string.gateway_label),
                value = config.gateway.redact(privacyMode),
                onValueChange = {
                    if (!privacyMode) onConfigChange(config.copy(gateway = it))
                },
                isError = config.gateway.isBlank(),
                readOnly = privacyMode,
                supportingText = stringResource(R.string.gateway_desc),
                isModified = clientConfigSnapshot != null && config.gateway != clientConfigSnapshot.turnableConfig.gateway
            )
        }
        // Proto
        SettingsGroupItem(
            isTop = false,
            isBottom = false,
            containerColor = blockContainerColor
        ) {
            LabeledButtonGroup(
                label = stringResource(R.string.proto_label),
                supportingText = stringResource(R.string.proto_desc),
                isModified = clientConfigSnapshot != null && config.proto != clientConfigSnapshot.turnableConfig.proto
            ) {
                val options = listOf("dtls", "srtp", "none")
                val currentProto = config.proto ?: "none"
                options.forEachIndexed { index, p ->
                    configButtonGroupItem(
                        selected = currentProto == p,
                        onSelect = {
                            if (!privacyMode) {
                                HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                onConfigChange(config.copy(proto = if (p == "none") null else p))
                            }
                        },
                        label = p.uppercase(),
                        enabled = !privacyMode,
                        index = index,
                        count = options.size
                    )
                }
            }
        }
        // Force Turn
        SettingsGroupItem(
            isTop = false,
            isBottom = true,
            containerColor = blockContainerColor,
            onClick = {
                val next = !config.forceTurn
                HapticUtil.perform(context, if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                onConfigChange(config.copy(forceTurn = next))
            }
        ) {
            SwitchRow(
                label = stringResource(R.string.force_turn_label),
                supportingText = stringResource(R.string.force_turn_desc),
                checked = config.forceTurn,
                onCheckedChange = {
                    onConfigChange(config.copy(forceTurn = it))
                },
                isModified = clientConfigSnapshot != null && config.forceTurn != clientConfigSnapshot.turnableConfig.forceTurn
            )
        }
    }
}

@Composable
private fun TurnableMissingConfig(blockContainerColor: Color) {
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
                    tint = MaterialTheme.colorScheme.error
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.config_missing),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee()
                )
                val inlineContentId = "inline_icon"
                val annotatedString = buildAnnotatedString {
                    append(stringResource(R.string.config_import_hint_start))
                    append(" ")
                    appendInlineContent(inlineContentId, "[icon]")
                    append(" ")
                    append(stringResource(R.string.config_import_hint_end))
                }
                val inlineContent = mapOf(
                    inlineContentId to InlineTextContent(
                        Placeholder(
                            width = 24.sp,
                            height = 18.sp,
                            placeholderVerticalAlign = PlaceholderVerticalAlign.Bottom
                        )
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.note_add_24px),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .size(18.dp)
                        )
                    }
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = annotatedString,
                    inlineContent = inlineContent,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun OlcrtcSettings(
    config: OlcrtcConfig,
    onConfigChange: (OlcrtcConfig) -> Unit,
    olcrtcSocksAddr: String,
    onSocksAddrChange: (String) -> Unit,
    isOlcrtcSocksValid: Boolean,
    videoW: String,
    onVideoWChange: (String) -> Unit,
    videoH: String,
    onVideoHChange: (String) -> Unit,
    privacyMode: Boolean,
    clientConfigSnapshot: ClientConfig?,
    blockContainerColor: Color,
    onShowOlcrtcHelp: () -> Unit,
    onShowTransportDialog: () -> Unit
) {
    val context = LocalContext.current
    SettingsGroup(title = stringResource(R.string.connection_details)) {
        // SOCKS Proxy
        SettingsGroupItem(isTop = true, isBottom = false, containerColor = blockContainerColor) {
            TextFieldRow(
                label = stringResource(R.string.olcrtc_socks_proxy_label),
                value = olcrtcSocksAddr.redact(privacyMode),
                onValueChange = onSocksAddrChange,
                placeholder = stringResource(R.string.local_listen_placeholder),
                isError = !isOlcrtcSocksValid || olcrtcSocksAddr.isBlank(),
                readOnly = privacyMode,
                isModified = clientConfigSnapshot != null && olcrtcSocksAddr.trim() != "${clientConfigSnapshot.olcrtcConfig.socksHost}:${clientConfigSnapshot.olcrtcConfig.socksPort}",
                onHelpClick = onShowOlcrtcHelp
            )
        }

        SettingsGroupItem(
            isTop = false,
            isBottom = !config.isSocksAuthEnabled,
            containerColor = blockContainerColor,
            onClick = {
                val newValue = !config.isSocksAuthEnabled
                HapticUtil.perform(context, if (newValue) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                onConfigChange(config.copy(isSocksAuthEnabled = newValue))
            }
        ) {
            SwitchRow(
                label = stringResource(R.string.xray_proxy_auth),
                supportingText = stringResource(R.string.xray_proxy_auth_desc),
                checked = config.isSocksAuthEnabled,
                onCheckedChange = {
                    HapticUtil.perform(context, if (it) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                    onConfigChange(config.copy(isSocksAuthEnabled = it))
                },
                isModified = clientConfigSnapshot != null && config.isSocksAuthEnabled != clientConfigSnapshot.olcrtcConfig.isSocksAuthEnabled
            )
        }

        AnimatedVisibility(
            visible = config.isSocksAuthEnabled,
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
                        value = config.socksUser.redact(privacyMode),
                        onValueChange = { onConfigChange(config.copy(socksUser = it)) },
                        placeholder = "admin",
                        supportingText = stringResource(R.string.xray_proxy_auth_hint),
                        isError = config.isSocksAuthEnabled && config.socksUser.isNotEmpty() && !ValidatorUtils.isValidProxyUser(config.socksUser),
                        readOnly = privacyMode,
                        isModified = clientConfigSnapshot != null && config.socksUser != clientConfigSnapshot.olcrtcConfig.socksUser
                    )
                }
                SettingsGroupItem(
                    isTop = false,
                    isBottom = true,
                    containerColor = blockContainerColor
                ) {
                    TextFieldRow(
                        label = stringResource(R.string.xray_proxy_pass),
                        value = config.socksPass.redact(privacyMode),
                        onValueChange = { onConfigChange(config.copy(socksPass = it)) },
                        placeholder = "password",
                        supportingText = stringResource(R.string.xray_proxy_auth_hint),
                        isError = config.isSocksAuthEnabled && config.socksPass.isNotEmpty() && !ValidatorUtils.isValidProxyPass(config.socksPass),
                        readOnly = privacyMode,
                        isModified = clientConfigSnapshot != null && config.socksPass != clientConfigSnapshot.olcrtcConfig.socksPass
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Carrier
        SettingsGroupItem(isTop = true, isBottom = false, containerColor = blockContainerColor) {
            LabeledButtonGroup(
                label = stringResource(R.string.olcrtc_carrier_label),
                isModified = clientConfigSnapshot != null && config.carrier != clientConfigSnapshot.olcrtcConfig.carrier
            ) {
                val carriers = listOf("wbstream" to "WB Stream", "telemost" to "Telemost", "jazz" to "Jazz")
                carriers.forEachIndexed { index, (value, label) ->
                    configButtonGroupItem(
                        selected = config.carrier == value,
                        onSelect = {
                            HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                            onConfigChange(config.copy(carrier = value))
                        },
                        label = label,
                        index = index,
                        count = carriers.size
                    )
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
                onShowTransportDialog()
            }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LargeLeadingIcon {
                    Icon(
                        painter = painterResource(R.drawable.route_24px),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    ConfigRowLabel(stringResource(R.string.olcrtc_transport_label))
                    val currentLabel = when (config.transport) {
                        "datachannel" -> "DataChannel"
                        "vp8channel" -> "VP8Channel"
                        "seichannel" -> "SEIChannel"
                        "videochannel" -> "VideoChannel"
                        else -> config.transport
                    }
                    Spacer(Modifier.height(2.dp))
                    SupportingText(currentLabel)
                }
                com.wireturn.app.ui.InlineConfigIndicator(
                    clientConfigSnapshot != null && config.transport != clientConfigSnapshot.olcrtcConfig.transport
                )
            }
        }
        // ID
        SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
            TextFieldRow(
                label = stringResource(R.string.olcrtc_id_label),
                value = config.id.redact(privacyMode),
                onValueChange = { if (!privacyMode) onConfigChange(config.copy(id = it)) },
                isError = config.id.isBlank(),
                readOnly = privacyMode,
                isModified = clientConfigSnapshot != null && config.id != clientConfigSnapshot.olcrtcConfig.id
            )
        }
        // DNS
        SettingsGroupItem(isTop = false, isBottom = true, containerColor = blockContainerColor) {
            TextFieldRow(
                label = stringResource(R.string.olcrtc_dns_label),
                value = config.dns,
                onValueChange = { onConfigChange(config.copy(dns = it)) },
                isError = config.dns.isBlank(),
                isModified = clientConfigSnapshot != null && config.dns != clientConfigSnapshot.olcrtcConfig.dns
            )
        }
    }

    SettingsGroup(title = stringResource(R.string.server_settings_title)) {
        // Client ID
        SettingsGroupItem(isTop = true, isBottom = false, containerColor = blockContainerColor) {
            TextFieldRow(
                label = stringResource(R.string.olcrtc_client_id_label),
                value = config.clientId.redact(privacyMode),
                onValueChange = { if (!privacyMode) onConfigChange(config.copy(clientId = it)) },
                isError = config.clientId.isBlank(),
                readOnly = privacyMode,
                isModified = clientConfigSnapshot != null && config.clientId != clientConfigSnapshot.olcrtcConfig.clientId
            )
        }
        // Key
        SettingsGroupItem(isTop = false, isBottom = true, containerColor = blockContainerColor) {
            TextFieldRow(
                label = stringResource(R.string.olcrtc_key_label),
                value = config.key.redact(privacyMode),
                onValueChange = { if (!privacyMode) onConfigChange(config.copy(key = it)) },
                isError = config.key.isBlank(),
                readOnly = privacyMode,
                isModified = clientConfigSnapshot != null && config.key != clientConfigSnapshot.olcrtcConfig.key
            )
        }
    }

    when (config.transport) {
        "vp8channel" -> {
            SettingsGroup(title = stringResource(R.string.olcrtc_vp8_settings_title)) {
                SettingsGroupItem(isTop = true, isBottom = false, containerColor = blockContainerColor) {
                    com.wireturn.app.ui.SliderRow(
                        label = stringResource(R.string.olcrtc_vp8_fps),
                        value = config.vp8Fps.toFloat(),
                        onValueChange = { onConfigChange(config.copy(vp8Fps = it.roundToInt())) },
                        valueRange = 1f..60f,
                        steps = 59,
                        isModified = clientConfigSnapshot != null && config.vp8Fps != clientConfigSnapshot.olcrtcConfig.vp8Fps
                    )
                }
                SettingsGroupItem(isTop = false, isBottom = true, containerColor = blockContainerColor) {
                    com.wireturn.app.ui.SliderRow(
                        label = stringResource(R.string.olcrtc_vp8_batch),
                        value = config.vp8Batch.toFloat(),
                        onValueChange = { onConfigChange(config.copy(vp8Batch = it.roundToInt())) },
                        valueRange = 1f..100f,
                        steps = 99,
                        isModified = clientConfigSnapshot != null && config.vp8Batch != clientConfigSnapshot.olcrtcConfig.vp8Batch
                    )
                }
            }
        }
        "seichannel" -> {
            SettingsGroup(title = stringResource(R.string.olcrtc_sei_settings_title)) {
                SettingsGroupItem(isTop = true, isBottom = false, containerColor = blockContainerColor) {
                    com.wireturn.app.ui.SliderRow(
                        label = stringResource(R.string.olcrtc_sei_fps),
                        value = config.seiFps.toFloat(),
                        onValueChange = { onConfigChange(config.copy(seiFps = it.roundToInt())) },
                        valueRange = 1f..120f,
                        steps = 119,
                        isModified = clientConfigSnapshot != null && config.seiFps != clientConfigSnapshot.olcrtcConfig.seiFps
                    )
                }
                SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                    com.wireturn.app.ui.SliderRow(
                        label = stringResource(R.string.olcrtc_sei_batch),
                        value = config.seiBatch.toFloat(),
                        onValueChange = { onConfigChange(config.copy(seiBatch = it.roundToInt())) },
                        valueRange = 1f..256f,
                        steps = 255,
                        isModified = clientConfigSnapshot != null && config.seiBatch != clientConfigSnapshot.olcrtcConfig.seiBatch
                    )
                }
                SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                    com.wireturn.app.ui.SliderRow(
                        label = stringResource(R.string.olcrtc_sei_frag),
                        value = config.seiFrag.toFloat(),
                        onValueChange = { onConfigChange(config.copy(seiFrag = it.roundToInt())) },
                        valueRange = 100f..1500f,
                        steps = 140,
                        isModified = clientConfigSnapshot != null && config.seiFrag != clientConfigSnapshot.olcrtcConfig.seiFrag
                    )
                }
                SettingsGroupItem(isTop = false, isBottom = true, containerColor = blockContainerColor) {
                    com.wireturn.app.ui.SliderRow(
                        label = stringResource(R.string.olcrtc_sei_ack_ms),
                        value = config.seiAckMs.toFloat(),
                        onValueChange = { onConfigChange(config.copy(seiAckMs = it.roundToInt())) },
                        valueRange = 100f..5000f,
                        steps = 49,
                        isModified = clientConfigSnapshot != null && config.seiAckMs != clientConfigSnapshot.olcrtcConfig.seiAckMs
                    )
                }
            }
        }
        "videochannel" -> {
            SettingsGroup(title = stringResource(R.string.olcrtc_video_settings_title)) {
                SettingsGroupItem(isTop = true, isBottom = false, containerColor = blockContainerColor) {
                    TextFieldRow(
                        label = stringResource(R.string.olcrtc_video_codec),
                        value = config.videoCodec,
                        onValueChange = { onConfigChange(config.copy(videoCodec = it)) },
                        isError = config.videoCodec.isBlank(),
                        isModified = clientConfigSnapshot != null && config.videoCodec != clientConfigSnapshot.olcrtcConfig.videoCodec
                    )
                }
                SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TextFieldRow(
                            label = stringResource(R.string.olcrtc_video_width),
                            value = videoW,
                            onValueChange = onVideoWChange,
                            modifier = Modifier.weight(1f),
                            isError = videoW.isNotEmpty() && videoW.toIntOrNull() == null,
                            isModified = clientConfigSnapshot != null && videoW != clientConfigSnapshot.olcrtcConfig.videoW.let { if (it == 0) "" else it.toString() },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        TextFieldRow(
                            label = stringResource(R.string.olcrtc_video_height),
                            value = videoH,
                            onValueChange = onVideoHChange,
                            modifier = Modifier.weight(1f),
                            isError = videoH.isNotEmpty() && videoH.toIntOrNull() == null,
                            isModified = clientConfigSnapshot != null && videoH != clientConfigSnapshot.olcrtcConfig.videoH.let { if (it == 0) "" else it.toString() },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                }
                SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                    com.wireturn.app.ui.SliderRow(
                        label = stringResource(R.string.olcrtc_video_fps),
                        value = config.videoFps.toFloat(),
                        onValueChange = { onConfigChange(config.copy(videoFps = it.roundToInt())) },
                        valueRange = 1f..60f,
                        steps = 59,
                        isModified = clientConfigSnapshot != null && config.videoFps != clientConfigSnapshot.olcrtcConfig.videoFps
                    )
                }
                SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                    TextFieldRow(
                        label = stringResource(R.string.olcrtc_video_bitrate),
                        value = config.videoBitrate,
                        onValueChange = { onConfigChange(config.copy(videoBitrate = it)) },
                        isError = config.videoBitrate.isBlank(),
                        isModified = clientConfigSnapshot != null && config.videoBitrate != clientConfigSnapshot.olcrtcConfig.videoBitrate
                    )
                }
                SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                    TextFieldRow(
                        label = stringResource(R.string.olcrtc_video_hw),
                        value = config.videoHw,
                        onValueChange = { onConfigChange(config.copy(videoHw = it)) },
                        isError = config.videoHw.isBlank(),
                        isModified = clientConfigSnapshot != null && config.videoHw != clientConfigSnapshot.olcrtcConfig.videoHw
                    )
                }
                SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                    TextFieldRow(
                        label = stringResource(R.string.olcrtc_video_qr_recovery),
                        value = config.videoQrRecovery,
                        onValueChange = { onConfigChange(config.copy(videoQrRecovery = it)) },
                        isError = config.videoQrRecovery.isBlank(),
                        isModified = clientConfigSnapshot != null && config.videoQrRecovery != clientConfigSnapshot.olcrtcConfig.videoQrRecovery
                    )
                }
                SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                    com.wireturn.app.ui.SliderRow(
                        label = stringResource(R.string.olcrtc_video_qr_size),
                        value = config.videoQrSize.toFloat(),
                        onValueChange = { onConfigChange(config.copy(videoQrSize = it.roundToInt())) },
                        valueRange = 0f..1000f,
                        steps = 100,
                        isModified = clientConfigSnapshot != null && config.videoQrSize != clientConfigSnapshot.olcrtcConfig.videoQrSize
                    )
                }
                SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                    com.wireturn.app.ui.SliderRow(
                        label = stringResource(R.string.olcrtc_video_tile_module),
                        value = config.videoTileModule.toFloat(),
                        onValueChange = { onConfigChange(config.copy(videoTileModule = it.roundToInt())) },
                        valueRange = 1f..32f,
                        steps = 31,
                        isModified = clientConfigSnapshot != null && config.videoTileModule != clientConfigSnapshot.olcrtcConfig.videoTileModule
                    )
                }
                SettingsGroupItem(isTop = false, isBottom = true, containerColor = blockContainerColor) {
                    com.wireturn.app.ui.SliderRow(
                        label = stringResource(R.string.olcrtc_video_tile_rs),
                        value = config.videoTileRs.toFloat(),
                        onValueChange = { onConfigChange(config.copy(videoTileRs = it.roundToInt())) },
                        valueRange = 0f..100f,
                        steps = 100,
                        isModified = clientConfigSnapshot != null && config.videoTileRs != clientConfigSnapshot.olcrtcConfig.videoTileRs
                    )
                }
            }
        }
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
            LargeLeadingIcon {
                Icon(
                    painter = painterResource(R.drawable.route_24px),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
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
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
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
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
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
