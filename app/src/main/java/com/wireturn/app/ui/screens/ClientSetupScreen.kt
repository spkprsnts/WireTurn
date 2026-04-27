@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wireturn.app.ui.screens

import android.annotation.SuppressLint
import androidx.core.net.toUri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.R
import com.wireturn.app.data.DCType
import com.wireturn.app.data.KernelVariant
import com.wireturn.app.ui.ConfigLabelRow
import com.wireturn.app.ui.FieldTrailingIcons
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.InlineConfigIndicator
import com.wireturn.app.ui.LabeledSegmentedButton
import com.wireturn.app.ui.SectionHeader
import com.wireturn.app.ui.SwitchRow
import com.wireturn.app.ui.ValidatorUtils
import com.wireturn.app.ui.redact
import com.wireturn.app.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ClientSetupScreen(
    viewModel: MainViewModel,
    showFinishButton: Boolean = false,
    onFinish: (() -> Unit)? = null
) {
    val saved by viewModel.clientConfig.collectAsStateWithLifecycle()
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

    var isRawMode by rememberSaveable(saved.isRawMode) { mutableStateOf(saved.isRawMode) }
    var rawCommand by rememberSaveable(saved.rawCommand) { mutableStateOf(saved.rawCommand) }
    var serverAddress by rememberSaveable(saved.serverAddress) { mutableStateOf(saved.serverAddress) }
    var turnableUrl  by rememberSaveable(saved.turnableUrl)    { mutableStateOf(saved.turnableUrl) }
    var vkLink       by rememberSaveable(saved.vkLink)         { mutableStateOf(saved.vkLink) }
    var wbstreamUuid by rememberSaveable(saved.wbstreamUuid)   { mutableStateOf(saved.wbstreamUuid) }
    var threads      by rememberSaveable(saved.threads)        { mutableFloatStateOf(saved.threads.toFloat()) }
    var useUdp       by rememberSaveable(saved.useUdp)         { mutableStateOf(saved.useUdp) }
    var noDtls       by rememberSaveable(saved.noDtls)         { mutableStateOf(saved.noDtls) }
    var manualCaptcha by rememberSaveable(saved.manualCaptcha) { mutableStateOf(saved.manualCaptcha) }
    var localPort    by rememberSaveable(saved.localPort)      { mutableStateOf(saved.localPort) }
    var vlessMode    by rememberSaveable(saved.vlessMode)      { mutableStateOf(saved.vlessMode) }
    var dcMode       by rememberSaveable(saved.dcMode)         { mutableStateOf(saved.dcMode) }
    var forcePort443 by rememberSaveable(saved.forceTurnPort443) { mutableStateOf(saved.forceTurnPort443) }
    var dcType       by rememberSaveable(saved.dcType)         { mutableStateOf(saved.dcType) }
    var jazzCreds    by rememberSaveable(saved.jazzCreds)      { mutableStateOf(saved.jazzCreds) }
    var kernelVariant by rememberSaveable(saved.kernelVariant) { mutableStateOf(saved.kernelVariant) }
    var lastSliderInt by rememberSaveable { mutableIntStateOf(saved.threads) }
    var showUrlEditor by remember { mutableStateOf(false) }
    var isUrlParsing by remember { mutableStateOf(false) }

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

    val visibilityAnimationSpec = tween<Float>(300, easing = FastOutSlowInEasing)
    val expandCollapseSpec = tween<androidx.compose.ui.unit.IntSize>(300, easing = FastOutSlowInEasing)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = { Text(stringResource(R.string.client_title)) }) },
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(Modifier.height(8.dp))

            // 1. Конфигурация режима и способа подключения
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionHeader(stringResource(R.string.connection_title))

                    AnimatedVisibility(
                        visible = !isRawMode,
                        enter = expandVertically(animationSpec = expandCollapseSpec) + fadeIn(animationSpec = visibilityAnimationSpec),
                        exit = shrinkVertically(animationSpec = expandCollapseSpec) + fadeOut(animationSpec = visibilityAnimationSpec)
                    ) {
                        Column {
                            Spacer(Modifier.height(16.dp))
                            LabeledSegmentedButton(
                                label = stringResource(R.string.tunnel_label),
                                subLabel = stringResource(R.string.connection_method_desc),
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
                    }

                    AnimatedVisibility(
                        visible = !isRawMode && !dcMode,
                        enter = expandVertically(animationSpec = expandCollapseSpec) + fadeIn(animationSpec = visibilityAnimationSpec),
                        exit = shrinkVertically(animationSpec = expandCollapseSpec) + fadeOut(animationSpec = visibilityAnimationSpec)
                    ) {
                        Column {
                            Spacer(Modifier.height(16.dp))
                            HorizontalDivider(thickness = 0.5.dp)
                        }
                    }

                    AnimatedVisibility(
                        visible = isRawMode || !dcMode,
                        enter = expandVertically(animationSpec = expandCollapseSpec) + fadeIn(animationSpec = visibilityAnimationSpec),
                        exit = shrinkVertically(animationSpec = expandCollapseSpec) + fadeOut(animationSpec = visibilityAnimationSpec)
                    ) {
                        Column {
                            Spacer(Modifier.height(16.dp))
                            LabeledSegmentedButton(
                                label = if (customKernelExists) stringResource(R.string.kernel_config_label) else stringResource(R.string.kernel_label),
                                subLabel = if (customKernelExists) stringResource(R.string.kernel_config_desc) else stringResource(R.string.client_variants_desc),
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

            Spacer(Modifier.height(16.dp))

            // 1.1 Raw режим
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionHeader(stringResource(R.string.raw_label))
                    Spacer(Modifier.height(16.dp))

                    SwitchRow(
                        label = stringResource(R.string.raw_mode),
                        description = stringResource(R.string.raw_mode_desc),
                        checked = isRawMode,
                        onCheckedChange = { isRawMode = it },
                        isModified = runningConfig != null && isRawMode != runningConfig?.isRawMode
                    )

                    AnimatedVisibility(
                        visible = isRawMode,
                        enter = expandVertically(animationSpec = expandCollapseSpec) + fadeIn(animationSpec = visibilityAnimationSpec),
                        exit = shrinkVertically(animationSpec = expandCollapseSpec) + fadeOut(animationSpec = visibilityAnimationSpec)
                    ) {
                        Column {
                            Spacer(Modifier.height(16.dp))
                            HorizontalDivider(thickness = 0.5.dp)
                            Spacer(Modifier.height(16.dp))

                            OutlinedTextField(
                                value = rawCommand,
                                placeholder = { Text(stringResource(R.string.raw_placeholder)) },
                                onValueChange = { rawCommand = it },
                                isError = rawCommand.isBlank(),
                                label = { 
                                    ConfigLabelRow(runningConfig != null && (rawCommand != runningConfig?.rawCommand || !runningConfig!!.isRawMode)) {
                                        Text(stringResource(R.string.raw_label))
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // 2. Детали подключения (адреса, ссылки, UUID)
            AnimatedVisibility(
                visible = !isRawMode,
                enter = expandVertically(animationSpec = expandCollapseSpec) + fadeIn(animationSpec = visibilityAnimationSpec),
                exit = shrinkVertically(animationSpec = expandCollapseSpec) + fadeOut(animationSpec = visibilityAnimationSpec)
            ) {
                Column {
                    Spacer(Modifier.height(16.dp))
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            SectionHeader(stringResource(R.string.connection_details))
                            Spacer(Modifier.height(16.dp))

                            AnimatedContent(
                                targetState = dcMode,
                                label = "tunnel_mode_fields",
                                transitionSpec = {
                                    val direction = if (targetState) 1 else -1
                                    (slideInHorizontally(animationSpec = tween(300)) { it * direction } + fadeIn(
                                        animationSpec = tween(300)
                                    )).togetherWith(slideOutHorizontally(animationSpec = tween(300)) { -it * direction } + fadeOut(
                                        animationSpec = tween(300)
                                    ))
                                }
                            ) { targetDcMode ->
                                Column {
                                    if (targetDcMode) {
                                        LabeledSegmentedButton(
                                            label = stringResource(R.string.dc_type_label),
                                            subLabel = stringResource(R.string.dc_type_desc),
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

                                        AnimatedContent(
                                            targetState = dcType,
                                            label = "dc_fields",
                                            transitionSpec = {
                                                val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                                                (slideInHorizontally(animationSpec = tween(300)) { it * direction } + fadeIn(
                                                    animationSpec = tween(300)
                                                )).togetherWith(slideOutHorizontally(animationSpec = tween(300)) { -it * direction } + fadeOut(
                                                    animationSpec = tween(300)
                                                ))
                                            }
                                        ) { targetDcType ->
                                            Column {
                                                Spacer(Modifier.height(16.dp))
                                                when (targetDcType) {
                                                    DCType.SALUTE_JAZZ -> {
                                                        OutlinedTextField(
                                                            value = jazzCreds.redact(privacyMode),
                                                            onValueChange = { if (!privacyMode) jazzCreds = it },
                                                        label = { 
                                                            ConfigLabelRow(runningConfig != null && (jazzCreds.trim() != runningConfig?.jazzCreds || runningConfig!!.isRawMode)) {
                                                                Text(stringResource(R.string.jazz_creds_label))
                                                            }
                                                        },
                                                            placeholder = { Text(stringResource(R.string.jazz_creds_placeholder)) },
                                                            isError = jazzCreds.isBlank() || !jazzCreds.contains(":"),
                                                            modifier = Modifier.fillMaxWidth(),
                                                            singleLine = true,
                                                            readOnly = privacyMode,
                                                            supportingText = { Text(stringResource(R.string.jazz_creds_support)) },
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
                                                        OutlinedTextField(
                                                            value = wbstreamUuid.redact(privacyMode),
                                                            onValueChange = { if (!privacyMode) wbstreamUuid = it },
                                                        label = { 
                                                            ConfigLabelRow(runningConfig != null && (wbstreamUuid.trim() != runningConfig?.wbstreamUuid || runningConfig!!.isRawMode)) {
                                                                Text(stringResource(R.string.wbstream_uuid_label))
                                                            }
                                                        },
                                                            placeholder = { Text(stringResource(R.string.wbstream_uuid_placeholder)) },
                                                            isError = wbstreamUuid.isBlank(),
                                                            modifier = Modifier.fillMaxWidth(),
                                                            singleLine = true,
                                                            readOnly = privacyMode,
                                                            supportingText = { Text(stringResource(R.string.wbstream_uuid_support)) },
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
                                        }
                                    } else {
                                        AnimatedContent(
                                            targetState = kernelVariant,
                                            label = "kernel_fields",
                                            transitionSpec = {
                                                val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                                                (slideInHorizontally(animationSpec = tween(300)) { it * direction } + fadeIn(
                                                    animationSpec = tween(300)
                                                )).togetherWith(slideOutHorizontally(animationSpec = tween(300)) { -it * direction } + fadeOut(
                                                    animationSpec = tween(300)
                                                ))
                                            }
                                        ) { targetKernel ->
                                            Column {
                                                if (targetKernel == KernelVariant.VK_TURN_PROXY) {
                                                    OutlinedTextField(
                                                        value = serverAddress.redact(privacyMode),
                                                        onValueChange = { if (!privacyMode) serverAddress = it },
                                                        label = { 
                                                            ConfigLabelRow(runningConfig != null && (serverAddress.trim() != runningConfig?.serverAddress || runningConfig!!.isRawMode)) {
                                                                Text(stringResource(R.string.server_address_label))
                                                            }
                                                        },
                                                        placeholder = { Text(stringResource(R.string.server_address_placeholder)) },
                                                        isError = !isServerAddressValid || serverAddress.isBlank(),
                                                        modifier = Modifier.fillMaxWidth(),
                                                        singleLine = true,
                                                        readOnly = privacyMode,
                                                        supportingText = { Text(stringResource(R.string.server_address_support)) },
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
                                                    Spacer(Modifier.height(16.dp))
                                                    OutlinedTextField(
                                                        value = vkLink.redact(privacyMode),
                                                        onValueChange = { if (!privacyMode) vkLink = it },
                                                        label = { 
                                                            ConfigLabelRow(runningConfig != null && (vkLink.trim() != runningConfig?.vkLink || runningConfig!!.isRawMode)) {
                                                                Text(stringResource(R.string.vk_link_label))
                                                            }
                                                        },
                                                        placeholder = { Text(stringResource(R.string.vk_link_placeholder)) },
                                                        isError = !ValidatorUtils.isValidUrl(vkLink) || vkLink.isBlank(),
                                                        modifier = Modifier.fillMaxWidth(),
                                                        singleLine = true,
                                                        readOnly = privacyMode,
                                                        supportingText = { Text(stringResource(R.string.vk_link_support)) },
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
                                                } else {
                                                    OutlinedTextField(
                                                        value = turnableUrl.redact(privacyMode),
                                                        onValueChange = { if (!privacyMode) turnableUrl = it },
                                                        label = { 
                                                            ConfigLabelRow(runningConfig != null && (turnableUrl.trim() != runningConfig?.turnableUrl || runningConfig!!.isRawMode)) {
                                                                Text(stringResource(R.string.turnable_url_label))
                                                            }
                                                        },
                                                        placeholder = { Text(stringResource(R.string.turnable_url_placeholder)) },
                                                        isError = !isTurnableUrlValid || turnableUrl.isBlank(),
                                                        modifier = Modifier.fillMaxWidth(),
                                                        maxLines = 5,
                                                        readOnly = privacyMode,
                                                        supportingText = { Text(stringResource(R.string.turnable_url_support)) },
                                                        trailingIcon = if ((!privacyMode && turnableUrl.isNotBlank()) || turnableUrlHistory.isNotEmpty()) {
                                                            {
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
                                                                                IconButton(onClick = {
                                                                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                                                                    scope.launch {
                                                                                        isUrlParsing = true
                                                                                        delay(400)
                                                                                        showUrlEditor = true
                                                                                        isUrlParsing = false
                                                                                    }
                                                                                }) {
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
                                                        } else null
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. Тонкая настройка
            AnimatedVisibility(
                visible = !isRawMode,
                enter = expandVertically(animationSpec = expandCollapseSpec) + fadeIn(animationSpec = visibilityAnimationSpec),
                exit = shrinkVertically(animationSpec = expandCollapseSpec) + fadeOut(animationSpec = visibilityAnimationSpec)
            ) {
                Column {
                    Spacer(Modifier.height(16.dp))
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            SectionHeader(stringResource(R.string.parameters_title))
                            Spacer(Modifier.height(16.dp))

                            OutlinedTextField(
                                value = localPort.redact(privacyMode),
                                onValueChange = { if (!privacyMode) localPort = it },
                                label = { 
                                    ConfigLabelRow(runningConfig != null && localPort.trim() != runningConfig?.localPort) {
                                        Text(stringResource(R.string.local_listen_address))
                                    }
                                },
                                placeholder = { Text(stringResource(R.string.local_listen_placeholder)) },
                                isError = !isLocalPortValid || localPort.isBlank(),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                readOnly = privacyMode,
                                supportingText = { Text(stringResource(R.string.local_listen_support)) }
                            )

                            AnimatedVisibility(
                                visible = !dcMode && kernelVariant == KernelVariant.VK_TURN_PROXY,
                                enter = expandVertically(animationSpec = expandCollapseSpec) + fadeIn(animationSpec = visibilityAnimationSpec),
                                exit = shrinkVertically(animationSpec = expandCollapseSpec) + fadeOut(animationSpec = visibilityAnimationSpec)
                            ) {
                                Column {
                                    Spacer(Modifier.height(16.dp))
                                    HorizontalDivider(thickness = 0.5.dp)
                                    Spacer(Modifier.height(16.dp))

                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(stringResource(R.string.threads_format, threads.roundToInt()), style = MaterialTheme.typography.bodyMedium)
                                            InlineConfigIndicator(runningConfig != null && threads.roundToInt() != runningConfig?.threads)
                                        }
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
                                        Spacer(Modifier.height(8.dp))
                                        Text(stringResource(R.string.threads_recommendation), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }

                            AnimatedVisibility(
                                visible = dcMode || kernelVariant == KernelVariant.VK_TURN_PROXY,
                                enter = expandVertically(animationSpec = expandCollapseSpec) + fadeIn(animationSpec = visibilityAnimationSpec),
                                exit = shrinkVertically(animationSpec = expandCollapseSpec) + fadeOut(animationSpec = visibilityAnimationSpec)
                            ) {
                                Column {
                                    Spacer(Modifier.height(16.dp))
                                    HorizontalDivider(thickness = 0.5.dp)
                                    Spacer(Modifier.height(16.dp))

                                    SwitchRow(
                                        label = stringResource(R.string.vless_mode),
                                        description = stringResource(R.string.vless_mode_desc),
                                        checked = vlessMode,
                                        onCheckedChange = {
                                            HapticUtil.perform(context, if (it) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                                            vlessMode = it
                                        },
                                        isModified = runningConfig != null && vlessMode != runningConfig?.vlessMode
                                    )
                                }
                            }

                            AnimatedVisibility(
                                visible = !vlessMode && !dcMode && kernelVariant == KernelVariant.VK_TURN_PROXY,
                                enter = expandVertically(animationSpec = expandCollapseSpec) + fadeIn(animationSpec = visibilityAnimationSpec),
                                exit = shrinkVertically(animationSpec = expandCollapseSpec) + fadeOut(animationSpec = visibilityAnimationSpec)
                            ) {
                                Column {
                                    Spacer(Modifier.height(16.dp))
                                    LabeledSegmentedButton(
                                        label = stringResource(R.string.transport_protocol),
                                        subLabel = stringResource(R.string.transport_protocol_desc),
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

                                    AnimatedVisibility(
                                        visible = useUdp,
                                        enter = expandVertically(animationSpec = expandCollapseSpec) + fadeIn(animationSpec = visibilityAnimationSpec),
                                        exit = shrinkVertically(animationSpec = expandCollapseSpec) + fadeOut(animationSpec = visibilityAnimationSpec)
                                    ) {
                                        Column {
                                            Spacer(Modifier.height(12.dp))
                                            SwitchRow(
                                                label = stringResource(R.string.no_dtls),
                                                description = stringResource(R.string.no_dtls_desc),
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
                            }

                            AnimatedVisibility(
                                visible = !dcMode && kernelVariant != KernelVariant.TURNABLE,
                                enter = expandVertically(animationSpec = expandCollapseSpec) + fadeIn(animationSpec = visibilityAnimationSpec),
                                exit = shrinkVertically(animationSpec = expandCollapseSpec) + fadeOut(animationSpec = visibilityAnimationSpec)
                            ) {
                                Column {
                                    Spacer(Modifier.height(16.dp))
                                    SwitchRow(
                                        label = stringResource(R.string.manual_captcha),
                                        description = stringResource(R.string.manual_captcha_desc),
                                        checked = manualCaptcha,
                                        onCheckedChange = {
                                            HapticUtil.perform(context, if (it) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                                            manualCaptcha = it
                                        },
                                        isModified = runningConfig != null && manualCaptcha != runningConfig?.manualCaptcha
                                    )

                                    Spacer(Modifier.height(16.dp))
                                    SwitchRow(
                                        label = stringResource(R.string.force_turn_port_443),
                                        description = stringResource(R.string.force_turn_port_443_desc),
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
            }

            Spacer(Modifier.height(16.dp))

            // 5. Ядро (Kernel file management)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader(stringResource(R.string.core_title))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    AnimatedContent(
                        targetState = customKernelExists,
                        label = "kernel_status_transition",
                        transitionSpec = {
                            (fadeIn(animationSpec = visibilityAnimationSpec)).togetherWith(fadeOut(animationSpec = visibilityAnimationSpec))
                                .using(SizeTransform(clip = false))
                        }
                    ) { isCustom ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                                Icon(
                                    painter = painterResource(if (isCustom) R.drawable.check_circle_24px else R.drawable.memory_24px),
                                    contentDescription = null,
                                    tint = if (isCustom) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(if (isCustom) stringResource(R.string.custom_core) else stringResource(R.string.builtin_core), style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = if (isCustom) {
                                            customKernelLastModified?.let {
                                                SimpleDateFormat("dd.MM.yyyy HH:mm", LocalLocale.current.platformLocale).format(Date(it))
                                            }?.let { stringResource(R.string.kernel_date_format, it) } ?: stringResource(R.string.loaded_from_memory)
                                        } else stringResource(R.string.from_apk),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (isCustom) {
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
                }
                if (kernelError != null) {
                    Row(modifier = Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(painter = painterResource(R.drawable.error_24px), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                        Text(kernelError!!, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (showUrlEditor) {
                TurnableUrlEditorDialog(
                    url = turnableUrl,
                    onDismiss = { showUrlEditor = false },
                    onConfirm = {
                        turnableUrl = it
                        showUrlEditor = false
                    }
                )
            }

            if (showFinishButton && onFinish != null) {
                Spacer(Modifier.height(16.dp))
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
            Spacer(Modifier.height(28.dp))
        }
    }
}

@SuppressLint("AuthLeak")
@Composable
private fun TurnableUrlEditorDialog(
    url: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val initialData = remember(url) {
        try {
            val uri = url.toUri()
            val userInfo = uri.encodedUserInfo?.let { "$it@" } ?: ""
            val scheme = uri.scheme ?: "turnable"
            val host = uri.host ?: ""
            val path = uri.path ?: ""
            val baseUrl = "$scheme://$userInfo$host$path"
            val params = uri.queryParameterNames.map { it to (uri.getQueryParameter(it) ?: "") }
            baseUrl to params
        } catch (_: Exception) {
            url to emptyList()
        }
    }

    var baseUrl by remember { mutableStateOf(initialData.first) }
    var params by remember { mutableStateOf(initialData.second) }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .widthIn(max = 840.dp)
            .fillMaxWidth(0.95f)
            .padding(vertical = 24.dp),
        title = { Text(stringResource(R.string.edit_url_params)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.url_base)) },
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("scheme://user:pass@host/path") }
                )

                HorizontalDivider(thickness = 0.5.dp)
                Text(stringResource(R.string.edit_url_params), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

                params.forEachIndexed { index, pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = pair.first,
                            onValueChange = { newKey ->
                                params = params.toMutableList().apply { this[index] = newKey to pair.second }
                            },
                            label = { Text(stringResource(R.string.url_param_key)) },
                            modifier = Modifier.weight(0.8f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = pair.second,
                            onValueChange = { newValue ->
                                params = params.toMutableList().apply { this[index] = pair.first to newValue }
                            },
                            label = { Text(stringResource(R.string.url_param_value)) },
                            modifier = Modifier.weight(2f),
                            singleLine = true,
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        params = params.toMutableList().apply { removeAt(index) }
                                    }
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.delete_24px),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        )
                    }
                }

                FilledTonalButton(
                    onClick = { params = params + ("" to "") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.add_parameter))
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                try {
                    val builder = baseUrl.toUri().buildUpon()
                    builder.clearQuery()
                    params.forEach { (key, value) ->
                        if (key.isNotBlank()) {
                            builder.appendQueryParameter(key, value)
                        }
                    }
                    onConfirm(builder.build().toString())
                } catch (_: Exception) {
                    onConfirm(baseUrl)
                }
            }) {
                Text(stringResource(R.string.btn_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
