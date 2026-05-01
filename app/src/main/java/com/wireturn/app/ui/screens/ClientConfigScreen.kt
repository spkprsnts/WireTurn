@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wireturn.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.R
import com.wireturn.app.data.DCType
import com.wireturn.app.data.KernelVariant
import com.wireturn.app.ui.FieldTrailingIcons
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.InlineConfigIndicator
import com.wireturn.app.ui.LabeledSegmentedButton
import com.wireturn.app.ui.SettingsGroup
import com.wireturn.app.ui.SettingsGroupItem
import com.wireturn.app.ui.SupportingText
import com.wireturn.app.ui.SwitchRow
import com.wireturn.app.ui.TextFieldRow
import com.wireturn.app.ui.TurnableUrlEditorDialog
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
    val currentProfileId by viewModel.currentProfileId.collectAsStateWithLifecycle()
    val customKernelExists by viewModel.customKernelExists.collectAsStateWithLifecycle()
    val customKernelLastModified by viewModel.customKernelLastModified.collectAsStateWithLifecycle()
    val kernelError by viewModel.kernelError.collectAsStateWithLifecycle()
    val privacyMode by viewModel.privacyMode.collectAsStateWithLifecycle()

    val vkLinkHistory by viewModel.vkLinkHistory.collectAsStateWithLifecycle()
    val wbstreamUuidHistory by viewModel.wbstreamUuidHistory.collectAsStateWithLifecycle()
    val serverAddressHistory by viewModel.serverAddressHistory.collectAsStateWithLifecycle()
    val turnableUrlHistory by viewModel.turnableUrlHistory.collectAsStateWithLifecycle()
    val jazzCredsHistory by viewModel.jazzCredsHistory.collectAsStateWithLifecycle()
    val runningConfig by com.wireturn.app.ProxyServiceState.runningConfig.collectAsStateWithLifecycle()

    val context = LocalContext.current

    val kernelPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.setCustomKernel(it) } }

    var isRawMode by rememberSaveable(currentProfileId, saved.isRawMode) { mutableStateOf(saved.isRawMode) }
    var rawCommand by rememberSaveable(currentProfileId, saved.rawCommand) { mutableStateOf(saved.rawCommand) }
    var serverAddress by rememberSaveable(currentProfileId, saved.serverAddress) { mutableStateOf(saved.serverAddress) }
    var turnableUrl  by rememberSaveable(currentProfileId, saved.turnableUrl)    { mutableStateOf(saved.turnableUrl) }
    var vkLink       by rememberSaveable(currentProfileId, saved.vkLink)         { mutableStateOf(saved.vkLink) }
    var wbstreamUuid by rememberSaveable(currentProfileId, saved.wbstreamUuid)   { mutableStateOf(saved.wbstreamUuid) }
    var threads      by rememberSaveable(currentProfileId, saved.threads)        { mutableFloatStateOf(saved.threads.toFloat()) }
    var useUdp       by rememberSaveable(currentProfileId, saved.useUdp)         { mutableStateOf(saved.useUdp) }
    var noDtls       by rememberSaveable(currentProfileId, saved.noDtls)         { mutableStateOf(saved.noDtls) }
    var manualCaptcha by rememberSaveable(currentProfileId, saved.manualCaptcha) { mutableStateOf(saved.manualCaptcha) }
    var localPort    by rememberSaveable(currentProfileId, saved.localPort)      { mutableStateOf(saved.localPort) }
    var vlessMode    by rememberSaveable(currentProfileId, saved.vlessMode)      { mutableStateOf(saved.vlessMode) }
    var dcMode       by rememberSaveable(currentProfileId, saved.dcMode)         { mutableStateOf(saved.dcMode) }
    var forcePort443 by rememberSaveable(currentProfileId, saved.forceTurnPort443) { mutableStateOf(saved.forceTurnPort443) }
    var dcType       by rememberSaveable(currentProfileId, saved.dcType)         { mutableStateOf(saved.dcType) }
    var jazzCreds    by rememberSaveable(currentProfileId, saved.jazzCreds)      { mutableStateOf(saved.jazzCreds) }
    var kernelVariant by rememberSaveable(currentProfileId, saved.kernelVariant) { mutableStateOf(saved.kernelVariant) }
    var lastSliderInt by rememberSaveable { mutableIntStateOf(saved.threads) }
    var isUrlParsing by remember { mutableStateOf(false) }
    
    val showVkHelp = remember { mutableStateOf(false) }
    val showPortHelp = remember { mutableStateOf(false) }
    val showServerHelp = remember { mutableStateOf(false) }
    val showUrlEditor = remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Sync local state with saved config when it changes externally (e.g. profile switch)
    LaunchedEffect(saved) {
        isRawMode = saved.isRawMode
        rawCommand = saved.rawCommand
        serverAddress = saved.serverAddress
        turnableUrl = saved.turnableUrl
        vkLink = saved.vkLink
        wbstreamUuid = saved.wbstreamUuid
        threads = saved.threads.toFloat()
        useUdp = saved.useUdp
        noDtls = saved.noDtls
        manualCaptcha = saved.manualCaptcha
        localPort = saved.localPort
        vlessMode = saved.vlessMode
        dcMode = saved.dcMode
        forcePort443 = saved.forceTurnPort443
        dcType = saved.dcType
        jazzCreds = saved.jazzCreds
        kernelVariant = saved.kernelVariant
    }

    val isServerAddressValid = remember(serverAddress) { ValidatorUtils.isValidHostPort(serverAddress) }
    val isLocalPortValid = remember(localPort) { ValidatorUtils.isValidHostPort(localPort) }
    val isTurnableUrlValid = remember(turnableUrl) { ValidatorUtils.isValidTurnableUrl(turnableUrl) }

    LaunchedEffect(isRawMode, rawCommand, serverAddress, turnableUrl, vkLink, wbstreamUuid, threads, useUdp, noDtls,
        manualCaptcha, localPort, vlessMode, dcMode, forcePort443, dcType, jazzCreds, kernelVariant
    ) {
        delay(200)
        val current = viewModel.clientConfig.value
        val next = current.copy(
            isRawMode        = isRawMode,
            rawCommand       = rawCommand,
            serverAddress    = serverAddress.trim(),
            turnableUrl      = turnableUrl.trim(),
            vkLink           = vkLink.trim(),
            wbstreamUuid     = wbstreamUuid.trim(),
            threads          = threads.roundToInt(),
            useUdp           = useUdp,
            noDtls           = noDtls,
            manualCaptcha    = manualCaptcha,
            localPort        = localPort.trim(),
            vlessMode        = vlessMode,
            dcMode           = dcMode,
            forceTurnPort443 = forcePort443,
            dcType           = dcType,
            jazzCreds        = jazzCreds.trim(),
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
        topBar = { TopAppBar(title = { Text(stringResource(R.string.client_title)) }) },
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
                .padding(bottom = 80.dp),
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
                        isModified = runningConfig != null && isRawMode != runningConfig?.isRawMode
                    )
                }

                if (isRawMode) {
                    // Строка для команды в Raw Mode
                    SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                        TextFieldRow(
                            label = stringResource(R.string.raw_label),
                            value = rawCommand,
                            placeholder = stringResource(R.string.raw_placeholder),
                            onValueChange = { rawCommand = it },
                            isError = rawCommand.isBlank(),
                            singleLine = false,
                            minLines = 2,
                            maxLines = 4,
                            isModified = runningConfig != null && (rawCommand != runningConfig?.rawCommand || !runningConfig!!.isRawMode)
                        )
                    }
                    // Выбор ядра в Raw Mode
                    SettingsGroupItem(isTop = false, isBottom = true, containerColor = blockContainerColor) {
                        LabeledSegmentedButton(
                            label = if (customKernelExists) stringResource(R.string.kernel_config_label) else stringResource(R.string.kernel_label),
                            supportingText = if (customKernelExists) stringResource(R.string.kernel_config_desc) else stringResource(R.string.client_variants_desc),
                            isModified = runningConfig != null && kernelVariant != runningConfig?.kernelVariant
                        ) {
                            SegmentedButton(
                                selected = kernelVariant == KernelVariant.VK_TURN_PROXY,
                                onClick = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                    kernelVariant = KernelVariant.VK_TURN_PROXY
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                            ) { Text(stringResource(R.string.kernel_vk_turn_proxy)) }
                            SegmentedButton(
                                selected = kernelVariant == KernelVariant.TURNABLE,
                                onClick = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                    kernelVariant = KernelVariant.TURNABLE
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                            ) { Text(stringResource(R.string.kernel_turnable)) }
                        }
                    }
                } else {
                    // Обычный режим: Метод туннеля
                    SettingsGroupItem(isTop = false, isBottom = dcMode, containerColor = blockContainerColor) {
                        LabeledSegmentedButton(
                            label = stringResource(R.string.tunnel_label),
                            supportingText = stringResource(R.string.connection_method_desc),
                            isModified = runningConfig != null && dcMode != runningConfig?.dcMode
                        ) {
                            SegmentedButton(
                                selected = !dcMode,
                                onClick = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                    dcMode = false
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                            ) { Text(stringResource(R.string.turn_tunnel)) }
                            SegmentedButton(
                                selected = dcMode,
                                onClick = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                    dcMode = true
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                            ) { Text(stringResource(R.string.dc_tunnel)) }
                        }
                    }

                    if (!dcMode) {
                        // Обычный режим: Выбор ядра (если не DC)
                        SettingsGroupItem(isTop = false, isBottom = true, containerColor = blockContainerColor) {
                            LabeledSegmentedButton(
                                label = if (customKernelExists) stringResource(R.string.kernel_config_label) else stringResource(R.string.kernel_label),
                                supportingText = if (customKernelExists) stringResource(R.string.kernel_config_desc) else stringResource(R.string.client_variants_desc),
                                isModified = runningConfig != null && kernelVariant != runningConfig?.kernelVariant
                            ) {
                                SegmentedButton(
                                    selected = kernelVariant == KernelVariant.VK_TURN_PROXY,
                                    onClick = {
                                        HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                        kernelVariant = KernelVariant.VK_TURN_PROXY
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                                ) { Text(stringResource(R.string.kernel_vk_turn_proxy)) }
                                SegmentedButton(
                                    selected = kernelVariant == KernelVariant.TURNABLE,
                                    onClick = {
                                        HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                        kernelVariant = KernelVariant.TURNABLE
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                                ) { Text(stringResource(R.string.kernel_turnable)) }
                            }
                        }
                    }
                }
            }

            if (!isRawMode) {
                Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    // 2. Детали подключения
                    SettingsGroup(title = stringResource(R.string.connection_details)) {
                        if (dcMode) {
                            SettingsGroupItem(isTop = true, isBottom = false, containerColor = blockContainerColor) {
                                LabeledSegmentedButton(
                                    label = stringResource(R.string.dc_type_label),
                                    supportingText = stringResource(R.string.dc_type_desc),
                                    isModified = runningConfig != null && dcType != runningConfig?.dcType
                                ) {
                                    DCType.entries.forEachIndexed { index, type ->
                                        SegmentedButton(
                                            selected = dcType == type,
                                            onClick = {
                                                HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                                dcType = type
                                            },
                                            shape = SegmentedButtonDefaults.itemShape(index = index, count = DCType.entries.size)
                                        ) {
                                            Text(when (type) {
                                                DCType.SALUTE_JAZZ -> stringResource(R.string.jazz_label)
                                                DCType.WB_STREAM -> stringResource(R.string.wb_stream_label)
                                            })
                                        }
                                    }
                                }
                            }

                            SettingsGroupItem(isTop = false, isBottom = true, containerColor = blockContainerColor) {
                                when (dcType) {
                                    DCType.SALUTE_JAZZ -> {
                                        TextFieldRow(
                                            label = stringResource(R.string.jazz_creds_label),
                                            value = jazzCreds.redact(privacyMode),
                                            onValueChange = { if (!privacyMode) jazzCreds = it },
                                            placeholder = stringResource(R.string.jazz_creds_placeholder),
                                            isError = jazzCreds.isBlank() || !jazzCreds.contains(":"),
                                            readOnly = privacyMode,
                                            supportingText = stringResource(R.string.jazz_creds_support),
                                            isModified = runningConfig != null && (jazzCreds.trim() != runningConfig?.jazzCreds || runningConfig!!.isRawMode),
                                            trailingIcon = if (jazzCredsHistory.isNotEmpty()) {
                                                {
                                                    FieldTrailingIcons(
                                                        history = jazzCredsHistory,
                                                        onSelect = { jazzCreds = it },
                                                        onRemove = { viewModel.removeJazzCredsFromHistory(it) },
                                                        privacyMode = privacyMode
                                                    )
                                                }
                                            } else null
                                        )
                                    }
                                    DCType.WB_STREAM -> {
                                        TextFieldRow(
                                            label = stringResource(R.string.wbstream_uuid_label),
                                            value = wbstreamUuid.redact(privacyMode),
                                            onValueChange = { if (!privacyMode) wbstreamUuid = it },
                                            placeholder = stringResource(R.string.wbstream_uuid_placeholder),
                                            isError = wbstreamUuid.isBlank(),
                                            readOnly = privacyMode,
                                            supportingText = stringResource(R.string.wbstream_uuid_support),
                                            isModified = runningConfig != null && (wbstreamUuid.trim() != runningConfig?.wbstreamUuid || runningConfig!!.isRawMode),
                                            trailingIcon = if (wbstreamUuidHistory.isNotEmpty()) {
                                                {
                                                    FieldTrailingIcons(
                                                        history = wbstreamUuidHistory,
                                                        onSelect = { wbstreamUuid = it },
                                                        onRemove = { viewModel.removeWbstreamUuidFromHistory(it) },
                                                        privacyMode = privacyMode
                                                    )
                                                }
                                            } else null
                                        )
                                    }
                                }
                            }
                        } else {
                            if (kernelVariant == KernelVariant.VK_TURN_PROXY) {
                                SettingsGroupItem(isTop = true, isBottom = false, containerColor = blockContainerColor) {
                                    TextFieldRow(
                                        label = stringResource(R.string.server_address_label),
                                        value = serverAddress.redact(privacyMode),
                                        onValueChange = { if (!privacyMode) serverAddress = it },
                                        placeholder = stringResource(R.string.server_address_placeholder),
                                        isError = !isServerAddressValid || serverAddress.isBlank(),
                                        readOnly = privacyMode,
                                        supportingText = stringResource(R.string.server_address_support),
                                        isModified = runningConfig != null && (serverAddress.trim() != runningConfig?.serverAddress || runningConfig!!.isRawMode),
                                        onHelpClick = { showServerHelp.value = true },
                                        trailingIcon = if (serverAddressHistory.isNotEmpty()) {
                                            {
                                                FieldTrailingIcons(
                                                    history = serverAddressHistory,
                                                    onSelect = { serverAddress = it },
                                                    onRemove = { viewModel.removeServerAddressFromHistory(it) },
                                                    privacyMode = privacyMode
                                                )
                                            }
                                        } else null
                                    )
                                }
                                SettingsGroupItem(isTop = false, isBottom = true, containerColor = blockContainerColor) {
                                    TextFieldRow(
                                        label = stringResource(R.string.vk_link_label),
                                        value = vkLink.redact(privacyMode),
                                        onValueChange = { if (!privacyMode) vkLink = it },
                                        placeholder = stringResource(R.string.vk_link_placeholder),
                                        isError = !ValidatorUtils.isValidUrl(vkLink) || vkLink.isBlank(),
                                        readOnly = privacyMode,
                                        isModified = runningConfig != null && (vkLink.trim() != runningConfig?.vkLink || runningConfig!!.isRawMode),
                                        onHelpClick = { showVkHelp.value = true },
                                        trailingIcon = if (vkLinkHistory.isNotEmpty()) {
                                            {
                                                FieldTrailingIcons(
                                                    history = vkLinkHistory,
                                                    onSelect = { vkLink = it },
                                                    onRemove = { viewModel.removeVkLinkFromHistory(it) },
                                                    privacyMode = privacyMode
                                                )
                                            }
                                        } else null
                                    )
                                }
                            } else {
                                SettingsGroupItem(isTop = true, isBottom = true, containerColor = blockContainerColor) {
                                    TextFieldRow(
                                        label = stringResource(R.string.turnable_url_label),
                                        value = turnableUrl.redact(privacyMode),
                                        onValueChange = { if (!privacyMode) turnableUrl = it },
                                        placeholder = stringResource(R.string.turnable_url_placeholder),
                                        isError = !isTurnableUrlValid || turnableUrl.isBlank(),
                                        maxLines = 5,
                                        singleLine = false,
                                        readOnly = privacyMode,
                                        supportingText = stringResource(R.string.turnable_url_support),
                                        isModified = runningConfig != null && (turnableUrl.trim() != runningConfig?.turnableUrl || runningConfig!!.isRawMode),
                                        trailingIcon = {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                if (!privacyMode && turnableUrl.isNotBlank()) {
                                                    Box(
                                                        modifier = Modifier.size(40.dp),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        if (isUrlParsing) {
                                                            CircularWavyProgressIndicator(
                                                                modifier = Modifier.size(20.dp)
                                                            )
                                                        } else {
                                                            IconButton(
                                                                onClick = {
                                                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                                                    scope.launch {
                                                                        isUrlParsing = true
                                                                        delay(400)
                                                                        showUrlEditor.value = true
                                                                        isUrlParsing = false
                                                                    }
                                                                },
                                                                modifier = Modifier.size(40.dp)
                                                            ) {
                                                                Icon(
                                                                    painter = painterResource(R.drawable.edit_24px),
                                                                    contentDescription = null,
                                                                    modifier = Modifier.size(20.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                                FieldTrailingIcons(
                                                    history = turnableUrlHistory,
                                                    onSelect = { turnableUrl = it },
                                                    onRemove = { viewModel.removeTurnableUrlFromHistory(it) },
                                                    privacyMode = privacyMode,
                                                    iconSize = 20.dp
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // 3. Тонкая настройка
                    SettingsGroup(title = stringResource(R.string.parameters_title)) {
                        val showsThreads = !dcMode && kernelVariant == KernelVariant.VK_TURN_PROXY
                        val showsVless = dcMode || kernelVariant == KernelVariant.VK_TURN_PROXY
                        val showsTransport = !vlessMode && !dcMode && kernelVariant == KernelVariant.VK_TURN_PROXY
                        val showsNoDtls = showsTransport && useUdp
                        val showsCaptchaGroup = !dcMode && kernelVariant != KernelVariant.TURNABLE
                        
                        // 3.1 Local Port
                        SettingsGroupItem(
                            isTop = true, 
                            isBottom = !(showsThreads || showsVless || showsTransport || showsCaptchaGroup), 
                            containerColor = blockContainerColor
                        ) {
                            TextFieldRow(
                                label = stringResource(R.string.local_listen_address),
                                value = localPort.redact(privacyMode),
                                onValueChange = { if (!privacyMode) localPort = it },
                                placeholder = stringResource(R.string.local_listen_placeholder),
                                isError = !isLocalPortValid || localPort.isBlank(),
                                readOnly = privacyMode,
                                isModified = runningConfig != null && localPort.trim() != runningConfig?.localPort,
                                onHelpClick = { showPortHelp.value = true }
                            )
                        }

                        // 3.2 Threads
                        if (showsThreads) {
                            SettingsGroupItem(
                                isTop = false, 
                                isBottom = !(showsVless || showsTransport || showsCaptchaGroup), 
                                containerColor = blockContainerColor
                            ) {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = stringResource(R.string.threads_format, threads.roundToInt()),
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        InlineConfigIndicator(runningConfig != null && threads.roundToInt() != runningConfig?.threads)
                                    }
                                    Text(
                                        text = stringResource(R.string.threads_recommendation),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Slider(
                                        value = threads,
                                        onValueChange = {
                                            val newInt = it.roundToInt()
                                            if (newInt != lastSliderInt) {
                                                HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                                                lastSliderInt = newInt
                                            }
                                            threads = it
                                        },
                                        valueRange = 1f..8f,
                                        steps = 6,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }

                        // 3.3 VLESS Mode
                        if (showsVless) {
                            SettingsGroupItem(
                                isTop = false, 
                                isBottom = !(showsTransport || showsCaptchaGroup), 
                                containerColor = blockContainerColor,
                                onClick = {
                                    val next = !vlessMode
                                    HapticUtil.perform(context, if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                                    vlessMode = next
                                }
                            ) {
                                SwitchRow(
                                    label = stringResource(R.string.vless_mode),
                                    supportingText = stringResource(R.string.vless_mode_desc),
                                    checked = vlessMode,
                                    onCheckedChange = {
                                        HapticUtil.perform(context, if (it) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                                        vlessMode = it
                                    },
                                    isModified = runningConfig != null && vlessMode != runningConfig?.vlessMode
                                )
                            }
                        }

                        // 3.4 Transport Protocol
                        if (showsTransport) {
                            SettingsGroupItem(
                                isTop = false, 
                                isBottom = !(showsNoDtls || showsCaptchaGroup), 
                                containerColor = blockContainerColor
                            ) {
                                LabeledSegmentedButton(
                                    label = stringResource(R.string.transport_protocol),
                                    supportingText = stringResource(R.string.transport_protocol_desc),
                                    isModified = runningConfig != null && useUdp != runningConfig?.useUdp
                                ) {
                                    SegmentedButton(
                                        selected = !useUdp,
                                        onClick = {
                                            HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                            useUdp = false
                                            noDtls = false
                                        },
                                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                                    ) { Text(stringResource(R.string.tcp)) }
                                    SegmentedButton(
                                        selected = useUdp,
                                        onClick = {
                                            HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                            useUdp = true
                                        },
                                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                                    ) { Text(stringResource(R.string.udp)) }
                                }
                            }

                            if (useUdp) {
                                SettingsGroupItem(
                                    isTop = false, 
                                    isBottom = !showsCaptchaGroup, 
                                    containerColor = blockContainerColor,
                                    onClick = if (customKernelExists) {
                                        {
                                            val next = !noDtls
                                            HapticUtil.perform(context, if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                                            noDtls = next
                                        }
                                    } else null,
                                    enabled = customKernelExists
                                ) {
                                    SwitchRow(
                                        label = stringResource(R.string.no_dtls),
                                        supportingText = stringResource(R.string.no_dtls_desc),
                                        checked = noDtls,
                                        onCheckedChange = {
                                            HapticUtil.perform(context, if (it) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                                            noDtls = it
                                        },
                                        isModified = runningConfig != null && noDtls != runningConfig?.noDtls,
                                        enabled = customKernelExists
                                    )
                                }
                            }
                        }

                        // 3.5 Captcha & Port 443
                        if (showsCaptchaGroup) {
                            SettingsGroupItem(
                                isTop = false, 
                                isBottom = false, 
                                containerColor = blockContainerColor,
                                onClick = {
                                    val next = !manualCaptcha
                                    HapticUtil.perform(context, if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                                    manualCaptcha = next
                                }
                            ) {
                                SwitchRow(
                                    label = stringResource(R.string.manual_captcha),
                                    supportingText = stringResource(R.string.manual_captcha_desc),
                                    checked = manualCaptcha,
                                    onCheckedChange = {
                                        HapticUtil.perform(context, if (it) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                                        manualCaptcha = it
                                    },
                                    isModified = runningConfig != null && manualCaptcha != runningConfig?.manualCaptcha
                                )
                            }
                            SettingsGroupItem(
                                isTop = false, 
                                isBottom = true, 
                                containerColor = blockContainerColor,
                                onClick = {
                                    val next = !forcePort443
                                    HapticUtil.perform(context, if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                                    forcePort443 = next
                                }
                            ) {
                                SwitchRow(
                                    label = stringResource(R.string.force_turn_port_443),
                                    supportingText = stringResource(R.string.force_turn_port_443_desc),
                                    checked = forcePort443,
                                    onCheckedChange = {
                                        HapticUtil.perform(context, if (it) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                                        forcePort443 = it
                                    },
                                    isModified = runningConfig != null && forcePort443 != runningConfig?.forceTurnPort443
                                )
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
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                            Icon(
                                painter = painterResource(if (customKernelExists) R.drawable.check_circle_24px else R.drawable.memory_24px),
                                contentDescription = null,
                                tint = if (customKernelExists) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = if (customKernelExists) stringResource(R.string.custom_core) else stringResource(R.string.builtin_core),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (customKernelExists) {
                                        customKernelLastModified?.let {
                                            SimpleDateFormat("dd.MM.yyyy HH:mm", LocalLocale.current.platformLocale).format(Date(it))
                                        }?.let { stringResource(R.string.kernel_date_format, it) } ?: stringResource(R.string.loaded_from_memory)
                                    } else stringResource(R.string.from_apk),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
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
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(painter = painterResource(R.drawable.error_24px), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            Text(kernelError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            if (showFinishButton && onFinish != null) {
                val isValid = remember(isRawMode, rawCommand, dcMode, dcType, jazzCreds, wbstreamUuid, serverAddress, vkLink, turnableUrl, kernelVariant) {
                    com.wireturn.app.data.ClientConfig(
                        isRawMode = isRawMode,
                        rawCommand = rawCommand,
                        dcMode = dcMode,
                        dcType = dcType,
                        jazzCreds = jazzCreds,
                        wbstreamUuid = wbstreamUuid,
                        serverAddress = serverAddress,
                        vkLink = vkLink,
                        turnableUrl = turnableUrl,
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

    if (showUrlEditor.value) {
        TurnableUrlEditorDialog(
            url = turnableUrl,
            onDismiss = { showUrlEditor.value = false },
            onConfirm = {
                turnableUrl = it
                showUrlEditor.value = false
            }
        )
    }

    if (showServerHelp.value) {
        AlertDialog(
            onDismissRequest = { showServerHelp.value = false },
            title = { Text(stringResource(R.string.server_address_label)) },
            text = { 
                SupportingText(
                    text = stringResource(R.string.server_help_text),
                    secondaryText = stringResource(R.string.server_help_secondary),
                    verticalSpacing = 8.dp
                )
            },
            confirmButton = {
                TextButton(onClick = { showServerHelp.value = false }) {
                    Text(stringResource(R.string.btn_close))
                }
            }
        )
    }

    if (showVkHelp.value) {
        val uriHandler = LocalUriHandler.current
        AlertDialog(
            onDismissRequest = { showVkHelp.value = false },
            title = { Text(stringResource(R.string.vk_link_label)) },
            text = { 
                SupportingText(
                    text = stringResource(R.string.vk_help_text),
                    secondaryText = stringResource(R.string.vk_help_secondary),
                    verticalSpacing = 8.dp
                )
            },
            confirmButton = {
                TextButton(onClick = { 
                    uriHandler.openUri("https://www.google.com/search?q=https://vk.com/call/join/+")
                }) {
                    Text(stringResource(R.string.vk_help_search_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { showVkHelp.value = false }) {
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
                SupportingText(
                    text = stringResource(R.string.local_port_help_text),
                    secondaryText = stringResource(R.string.local_port_help_secondary),
                    verticalSpacing = 8.dp
                )
            },
            confirmButton = {
                TextButton(onClick = { showPortHelp.value = false }) {
                    Text(stringResource(R.string.btn_close))
                }
            }
        )
    }
}
