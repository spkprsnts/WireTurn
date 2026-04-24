@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wireturn.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wireturn.app.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.ui.ConfigFieldIndicator
import com.wireturn.app.ui.InlineConfigIndicator
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.ValidatorUtils
import com.wireturn.app.viewmodel.MainViewModel
import com.wireturn.app.data.DCType
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
    val jazzCredsHistory by viewModel.jazzCredsHistory.collectAsStateWithLifecycle()
    val vlessLinkHistory by viewModel.vlessLinkHistory.collectAsStateWithLifecycle()
    val runningConfig by com.wireturn.app.ProxyServiceState.runningConfig.collectAsStateWithLifecycle()
    val runningVlessConfig by com.wireturn.app.XrayServiceState.runningVlessConfig.collectAsStateWithLifecycle()
    val xrayConfig by viewModel.xrayConfig.collectAsStateWithLifecycle()
    val vlessSaved by viewModel.vlessConfig.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val importSuccessMessage = stringResource(R.string.wg_import_success)
    val importErrorMessage = stringResource(R.string.wg_import_error)

    val kernelPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.setCustomKernel(it) } }

    var isRawMode by rememberSaveable(saved.isRawMode) { mutableStateOf(saved.isRawMode) }
    var rawCommand by rememberSaveable(saved.rawCommand) { mutableStateOf(saved.rawCommand) }
    var serverAddress by rememberSaveable(saved.serverAddress) { mutableStateOf(saved.serverAddress) }
    var vkLink       by rememberSaveable(saved.vkLink)         { mutableStateOf(saved.vkLink) }
    var wbstreamUuid by rememberSaveable(saved.wbstreamUuid)   { mutableStateOf(saved.wbstreamUuid) }
    var threads      by rememberSaveable(saved.threads)        { mutableFloatStateOf(saved.threads.toFloat()) }
    var useUdp       by rememberSaveable(saved.useUdp)         { mutableStateOf(saved.useUdp) }
    var noDtls       by rememberSaveable(saved.noDtls)         { mutableStateOf(saved.noDtls) }
    var manualCaptcha by rememberSaveable(saved.manualCaptcha) { mutableStateOf(saved.manualCaptcha) }
    var localPort    by rememberSaveable(saved.localPort)      { mutableStateOf(saved.localPort) }
    var vlessMode    by rememberSaveable(saved.vlessMode)      { mutableStateOf(saved.vlessMode) }
    var vlessLink    by rememberSaveable(vlessSaved.vlessLink)      { mutableStateOf(vlessSaved.vlessLink) }
    var vlessUseLocalAddress by rememberSaveable(vlessSaved.vlessUseLocalAddress)      { mutableStateOf(vlessSaved.vlessUseLocalAddress) }
    var dcMode       by rememberSaveable(saved.dcMode)         { mutableStateOf(saved.dcMode) }
    var forcePort443 by rememberSaveable(saved.forceTurnPort443) { mutableStateOf(saved.forceTurnPort443) }
    var dcType       by rememberSaveable(saved.dcType)         { mutableStateOf(saved.dcType) }
    var jazzCreds    by rememberSaveable(saved.jazzCreds)      { mutableStateOf(saved.jazzCreds) }
    var lastSliderInt by rememberSaveable { mutableIntStateOf(saved.threads) }

    val snackbarHostState = remember { SnackbarHostState() }
    val showVlessQrScanner = remember { mutableStateOf(false) }

    val isServerAddressValid = remember(serverAddress) {
        ValidatorUtils.isValidHostPort(serverAddress)
    }
    val isLocalPortValid = remember(localPort) {
        ValidatorUtils.isValidHostPort(localPort)
    }

    // Авто-сохранение с дебаунсом 200 мс на каждое изменение поля.
    LaunchedEffect(isRawMode, rawCommand, serverAddress, vkLink, wbstreamUuid, threads, useUdp, noDtls,
        manualCaptcha, localPort, vlessMode, dcMode, forcePort443, dcType, jazzCreds
    ) {
        delay(200)
        viewModel.saveClientConfig(
            viewModel.clientConfig.value.copy(
                isRawMode        = isRawMode,
                rawCommand       = rawCommand,
                serverAddress    = serverAddress.trim(),
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
                jazzCreds        = jazzCreds.trim()
            )
        )
    }

    LaunchedEffect(vlessLink, vlessUseLocalAddress) {
        delay(200)
        viewModel.updateVlessConfig(
            com.wireturn.app.data.VlessConfig(
                vlessLink = vlessLink,
                vlessUseLocalAddress = vlessUseLocalAddress
            )
        )
    }

    val contentAnimationSpec = tween<androidx.compose.ui.unit.IntSize>(300, easing = FastOutSlowInEasing)
    val visibilityAnimationSpec = tween<Float>(300, easing = FastOutSlowInEasing)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.client_title)) })
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
                .animateContentSize(animationSpec = contentAnimationSpec)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(20.dp))

            // Подключение
            Text(stringResource(R.string.connection_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))

            AnimatedVisibility(
                visible = isRawMode,
                enter = fadeIn(animationSpec = visibilityAnimationSpec) + expandVertically(animationSpec = contentAnimationSpec),
                exit = fadeOut(animationSpec = visibilityAnimationSpec) + shrinkVertically(animationSpec = contentAnimationSpec)
            ) {
                OutlinedTextField(
                    value = rawCommand,
                    onValueChange = { rawCommand = it },
                    isError = rawCommand.isBlank(),
                    label = { Text(stringResource(R.string.raw_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        ConfigFieldIndicator(runningConfig != null && (rawCommand != runningConfig?.rawCommand || !runningConfig!!.isRawMode))
                    }
                )
            }
            AnimatedVisibility(
                visible = !isRawMode,
                enter = fadeIn(animationSpec = visibilityAnimationSpec) + expandVertically(animationSpec = contentAnimationSpec),
                exit = fadeOut(animationSpec = visibilityAnimationSpec) + shrinkVertically(animationSpec = contentAnimationSpec)
            ) {
                Column {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.tunnel), style = MaterialTheme.typography.bodyMedium)
                                InlineConfigIndicator(runningConfig != null && dcMode != runningConfig?.dcMode)
                            }
                        }
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = !dcMode,
                                onClick = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                    dcMode = false
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                            ) { Text(stringResource(R.string.turn_protocol)) }
                            SegmentedButton(
                                selected = dcMode,
                                onClick = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                    dcMode = true
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                            ) { Text(stringResource(R.string.dc_protocol)) }
                        }
                    }

                    ScreenSectionDivider()

                    AnimatedVisibility(
                        visible = !dcMode,
                        enter = fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing)) + 
                                expandVertically(animationSpec = tween(300, easing = FastOutSlowInEasing)),
                        exit = fadeOut(animationSpec = tween(300, easing = FastOutSlowInEasing)) + 
                               shrinkVertically(animationSpec = tween(300, easing = FastOutSlowInEasing))
                    ) {
                        Column {
                            OutlinedTextField(
                                value = serverAddress.redact(privacyMode),
                                onValueChange = { if (!privacyMode) serverAddress = it },
                                label = { Text(stringResource(R.string.server_address_label)) },
                                placeholder = { Text(stringResource(R.string.server_address_placeholder)) },
                                isError = !isServerAddressValid || serverAddress.isBlank(),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                readOnly = privacyMode,
                                supportingText = { Text(stringResource(R.string.server_address_support)) },
                                trailingIcon = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        ConfigFieldIndicator(runningConfig != null && (serverAddress.trim() != runningConfig?.serverAddress || runningConfig!!.isRawMode))
                                        HistoryIconButton(
                                            history = serverAddressHistory,
                                            onSelect = { serverAddress = it },
                                            onRemove = { viewModel.removeServerAddressFromHistory(it) },
                                            privacyMode = privacyMode
                                        )
                                    }
                                }
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                    }

                    AnimatedVisibility(
                        visible = dcMode,
                        enter = fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing)) + 
                                expandVertically(animationSpec = tween(300, easing = FastOutSlowInEasing)),
                        exit = fadeOut(animationSpec = tween(300, easing = FastOutSlowInEasing)) + 
                               shrinkVertically(animationSpec = tween(300, easing = FastOutSlowInEasing))
                    ) {
                        Column {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(stringResource(R.string.dc_type_label), style = MaterialTheme.typography.bodyMedium)
                                        InlineConfigIndicator(runningConfig != null && dcType != runningConfig?.dcType)
                                    }
                                }
                                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                    DCType.entries.forEachIndexed { index, type ->
                                        SegmentedButton(
                                            selected = dcType == type,
                                            onClick = {
                                                HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                                dcType = type
                                            },
                                            shape = SegmentedButtonDefaults.itemShape(index = index, count = DCType.entries.size)
                                        ) {
                                            Text(when(type) {
                                                DCType.SALUTE_JAZZ -> stringResource(R.string.jazz_label)
                                                DCType.WB_STREAM -> stringResource(R.string.wb_stream_label)
                                            })
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(16.dp))

                            AnimatedContent(
                                targetState = dcType,
                                transitionSpec = {
                                    if (targetState.ordinal > initialState.ordinal) {
                                        (slideInHorizontally { it } + fadeIn()).togetherWith(
                                            slideOutHorizontally { -it } + fadeOut())
                                    } else {
                                        (slideInHorizontally { -it } + fadeIn()).togetherWith(
                                            slideOutHorizontally { it } + fadeOut())
                                    }
                                },
                                label = "dc_type_fields"
                            ) { targetDcType ->
                                when (targetDcType) {
                                    DCType.SALUTE_JAZZ -> {
                                        OutlinedTextField(
                                            value = jazzCreds.redact(privacyMode),
                                            onValueChange = { if (!privacyMode) jazzCreds = it },
                                            label = { Text(stringResource(R.string.jazz_creds_label)) },
                                            placeholder = { Text(stringResource(R.string.jazz_creds_placeholder)) },
                                            isError = jazzCreds.isBlank() || !jazzCreds.contains(":"),
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                            readOnly = privacyMode,
                                            supportingText = { Text(stringResource(R.string.jazz_creds_support)) },
                                            trailingIcon = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    ConfigFieldIndicator(runningConfig != null && (jazzCreds.trim() != runningConfig?.jazzCreds || runningConfig!!.isRawMode))
                                                    HistoryIconButton(
                                                        history = jazzCredsHistory,
                                                        onSelect = { jazzCreds = it },
                                                        onRemove = { viewModel.removeJazzCredsFromHistory(it) },
                                                        privacyMode = privacyMode
                                                    )
                                                }
                                            }
                                        )
                                    }
                                    DCType.WB_STREAM -> {
                                        OutlinedTextField(
                                            value = wbstreamUuid.redact(privacyMode),
                                            onValueChange = { if (!privacyMode) wbstreamUuid = it },
                                            label = { Text(stringResource(R.string.wbstream_uuid_label)) },
                                            placeholder = { Text(stringResource(R.string.wbstream_uuid_placeholder)) },
                                            isError = wbstreamUuid.isBlank(),
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                            readOnly = privacyMode,
                                            supportingText = { Text(stringResource(R.string.wbstream_uuid_support)) },
                                            trailingIcon = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    ConfigFieldIndicator(runningConfig != null && (wbstreamUuid.trim() != runningConfig?.wbstreamUuid || runningConfig!!.isRawMode))
                                                    HistoryIconButton(
                                                        history = wbstreamUuidHistory,
                                                        onSelect = { wbstreamUuid = it },
                                                        onRemove = { viewModel.removeWbstreamUuidFromHistory(it) },
                                                        privacyMode = privacyMode
                                                    )
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = !dcMode,
                        enter = fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing)) + 
                                expandVertically(animationSpec = tween(300, easing = FastOutSlowInEasing)),
                        exit = fadeOut(animationSpec = tween(300, easing = FastOutSlowInEasing)) + 
                               shrinkVertically(animationSpec = tween(300, easing = FastOutSlowInEasing))
                    ) {
                        OutlinedTextField(
                            value = vkLink.redact(privacyMode),
                            onValueChange = { if (!privacyMode) vkLink = it },
                            label = { Text(stringResource(R.string.vk_link_label)) },
                            placeholder = { Text(stringResource(R.string.vk_link_placeholder)) },
                            isError = !ValidatorUtils.isValidUrl(vkLink) || vkLink.isBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            readOnly = privacyMode,
                            supportingText = { Text(stringResource(R.string.vk_link_support)) },
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ConfigFieldIndicator(runningConfig != null && (vkLink.trim() != runningConfig?.vkLink || runningConfig!!.isRawMode))
                                    HistoryIconButton(
                                        history = vkLinkHistory,
                                        onSelect = { vkLink = it },
                                        onRemove = { viewModel.removeVkLinkFromHistory(it) },
                                        privacyMode = privacyMode
                                    )
                                }
                            }
                        )
                    }


                    ScreenSectionDivider()

                    OutlinedTextField(
                        value = localPort.redact(privacyMode),
                        onValueChange = { if (!privacyMode) localPort = it },
                        label = { Text(stringResource(R.string.local_listen_address)) },
                        placeholder = { Text(stringResource(R.string.local_listen_placeholder)) },
                        isError = !isLocalPortValid || localPort.isBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        readOnly = privacyMode,
                        supportingText = { Text(stringResource(R.string.local_listen_support)) },
                        trailingIcon = {
                            ConfigFieldIndicator(runningConfig != null && localPort.trim() != runningConfig?.localPort)
                        }
                    )

                    ScreenSectionDivider()

                    // Параметры
                    Text(stringResource(R.string.parameters_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(16.dp))

                    SwitchRow(
                        label = stringResource(R.string.vless_mode),
                        description = stringResource(R.string.vless_mode_desc),
                        checked = vlessMode,
                        onCheckedChange = {
                            HapticUtil.perform(
                                context,
                                if (it) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                            )
                            vlessMode = it
                        },
                        isModified = runningConfig != null && vlessMode != runningConfig?.vlessMode
                    )

                    AnimatedVisibility(
                        visible = vlessMode,
                        enter = fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing)) +
                                expandVertically(animationSpec = tween(300, easing = FastOutSlowInEasing)),
                        exit = fadeOut(animationSpec = tween(300, easing = FastOutSlowInEasing)) +
                                shrinkVertically(animationSpec = tween(300, easing = FastOutSlowInEasing))
                    ) {
                        Column {
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = vlessLink.redact(privacyMode),
                                onValueChange = { if (!privacyMode) vlessLink = it },
                                label = { Text(stringResource(R.string.vless_link_label)) },
                                placeholder = { Text(stringResource(R.string.vless_link_placeholder)) },
                                isError = !ValidatorUtils.isValidVlessLink(vlessLink) || (vlessLink.isBlank() && xrayConfig.xrayEnabled),
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 4,
                                readOnly = privacyMode,
                                supportingText = { Text(stringResource(R.string.vless_link_config_desc)) },
                                leadingIcon = {
                                    IconButton(onClick = { showVlessQrScanner.value = true }) {
                                        Icon(
                                            painter = painterResource(R.drawable.qr_code_24px),
                                            contentDescription = stringResource(R.string.wg_import_qr)
                                        )
                                    }
                                },
                                trailingIcon = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        ConfigFieldIndicator(runningVlessConfig != null && vlessLink.trim() != runningVlessConfig?.vlessLink)
                                        HistoryIconButton(
                                            history = vlessLinkHistory,
                                            onSelect = { vlessLink = it },
                                            onRemove = { viewModel.removeVlessLinkFromHistory(it) },
                                            privacyMode = privacyMode
                                        )
                                    }
                                }
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = vlessMode,
                        enter = fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing)) +
                                expandVertically(animationSpec = tween(300, easing = FastOutSlowInEasing)),
                        exit = fadeOut(animationSpec = tween(300, easing = FastOutSlowInEasing)) +
                                shrinkVertically(animationSpec = tween(300, easing = FastOutSlowInEasing))
                    ) {
                        Column {
                            Spacer(Modifier.height(12.dp))
                            SwitchRow(
                                label = stringResource(R.string.use_local_listen_address),
                                description = stringResource(R.string.use_local_listen_desc),
                                checked = vlessUseLocalAddress,
                                onCheckedChange = {
                                    HapticUtil.perform(
                                        context,
                                        if (it) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                                    )
                                    vlessUseLocalAddress = it
                                },
                                isModified = runningVlessConfig != null && vlessUseLocalAddress != runningVlessConfig?.vlessUseLocalAddress
                            )
                            if (!dcMode) ScreenSectionDivider()
                        }
                    }

                    AnimatedVisibility(
                        visible = !dcMode,
                        enter = fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing)) + 
                                expandVertically(animationSpec = tween(300, easing = FastOutSlowInEasing)),
                        exit = fadeOut(animationSpec = tween(300, easing = FastOutSlowInEasing)) + 
                               shrinkVertically(animationSpec = tween(300, easing = FastOutSlowInEasing))
                    ) {
                        Column {
                            if(!vlessMode) Spacer(Modifier.height(12.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(stringResource(R.string.threads_format, threads.roundToInt()), style = MaterialTheme.typography.bodyMedium)
                                        InlineConfigIndicator(runningConfig != null && threads.roundToInt() != runningConfig?.threads)
                                    }
                                    Text(
                                        stringResource(R.string.threads_recommendation),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
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

                    AnimatedVisibility(
                        visible = !vlessMode && !dcMode,
                        enter = fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing)) + 
                                expandVertically(animationSpec = tween(300, easing = FastOutSlowInEasing)),
                        exit = fadeOut(animationSpec = tween(300, easing = FastOutSlowInEasing)) + 
                               shrinkVertically(animationSpec = tween(300, easing = FastOutSlowInEasing))
                    ) {
                        Column {
                            Spacer(Modifier.height(12.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(stringResource(R.string.transport_protocol), style = MaterialTheme.typography.bodyMedium)
                                        InlineConfigIndicator(runningConfig != null && useUdp != runningConfig?.useUdp)
                                    }
                                    Text(
                                        stringResource(R.string.transport_protocol_desc),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
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

                            AnimatedVisibility(
                                visible = useUdp,
                                enter = fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing)) + 
                                        expandVertically(animationSpec = tween(300, easing = FastOutSlowInEasing)),
                                exit = fadeOut(animationSpec = tween(300, easing = FastOutSlowInEasing)) + 
                                       shrinkVertically(animationSpec = tween(300, easing = FastOutSlowInEasing))
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
                        visible = !dcMode,
                        enter = fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing)) + 
                                expandVertically(animationSpec = tween(300, easing = FastOutSlowInEasing)),
                        exit = fadeOut(animationSpec = tween(300, easing = FastOutSlowInEasing)) + 
                               shrinkVertically(animationSpec = tween(300, easing = FastOutSlowInEasing))
                    ) {
                        Column {
                            Spacer(Modifier.height(12.dp))
                            SwitchRow(
                                label = stringResource(R.string.manual_captcha),
                                description = stringResource(R.string.manual_captcha_desc),
                                checked = manualCaptcha,
                                onCheckedChange = {
                                    HapticUtil.perform(
                                        context,
                                        if (it) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                                    )
                                    manualCaptcha = it
                                },
                                isModified = runningConfig != null && manualCaptcha != runningConfig?.manualCaptcha
                            )

                            Spacer(Modifier.height(12.dp))
                            SwitchRow(
                                label = stringResource(R.string.force_turn_port_443),
                                description = stringResource(R.string.force_turn_port_443_desc),
                                checked = forcePort443,
                                onCheckedChange = {
                                    HapticUtil.perform(
                                        context,
                                        if (it) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                                    )
                                    forcePort443 = it
                                },
                                isModified = runningConfig != null && forcePort443 != runningConfig?.forceTurnPort443
                            )
                        }
                    }
                }
            }

            ScreenSectionDivider()

            SwitchRow(
                label = stringResource(R.string.raw_mode),
                description = stringResource(R.string.raw_mode_desc),
                checked = isRawMode,
                onCheckedChange = { isRawMode = it },
                isModified = runningConfig != null && isRawMode != runningConfig?.isRawMode
            )

            ScreenSectionDivider()

            // Ядро
            Text(stringResource(R.string.core_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (customKernelExists)
                        MaterialTheme.colorScheme.secondaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            painter = painterResource(
                                if (customKernelExists) R.drawable.check_circle_24px
                                else R.drawable.memory_24px
                            ),
                            contentDescription = null,
                            tint = if (customKernelExists)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                if (customKernelExists) stringResource(R.string.custom_core)
                                else stringResource(R.string.builtin_core),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                if (customKernelExists) {
                                    val dateStr = customKernelLastModified?.let {
                                        val currentLocale = LocalLocale.current.platformLocale
                                        SimpleDateFormat("dd.MM.yyyy HH:mm", currentLocale).format(Date(it))
                                    }
                                    if (dateStr != null) {
                                        stringResource(R.string.kernel_date_format, dateStr)
                                    } else {
                                        stringResource(R.string.loaded_from_memory)
                                    }
                                } else stringResource(R.string.from_apk),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (customKernelExists) {
                        IconButton(onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            viewModel.clearCustomKernel()
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.delete_24px),
                                contentDescription = stringResource(R.string.reset),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                        FilledTonalButton(onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            viewModel.clearKernelError()
                            kernelPickerLauncher.launch(arrayOf("*/*"))
                        }) {
                            Text(stringResource(R.string.btn_load))
                        }
                    }
                }
            }

            if (kernelError != null) {
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.error_24px),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        kernelError!!,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // Кнопка «Завершить» — только в онбординг-флоу
            if (showFinishButton && onFinish != null) {
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onFinish,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = serverAddress.isNotBlank() && vkLink.isNotBlank(),
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(stringResource(R.string.finish_setup), style = MaterialTheme.typography.labelLarge)
                }
            }

            Spacer(Modifier.height(28.dp))
        }

    }

    if (showVlessQrScanner.value) {
        QrScannerDialog(
            title = stringResource(R.string.vless_import_qr),
            message = stringResource(R.string.vless_qr_scan_desc),
            onDismiss = { showVlessQrScanner.value = false },
            onResult = { result ->
                if (ValidatorUtils.isValidVlessLink(result)) {
                    vlessLink = result
                    scope.launch {
                        snackbarHostState.showSnackbar(importSuccessMessage)
                    }
                } else {
                    scope.launch {
                        snackbarHostState.showSnackbar(importErrorMessage)
                    }
                }
            }
        )
    }
}

@Composable
private fun HistoryIconButton(
    history: List<String>,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    privacyMode: Boolean
) {
    if (history.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painter = painterResource(R.drawable.database_outlined_24px),
                contentDescription = stringResource(R.string.history_label)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            history.forEach { historyItem ->
                DropdownMenuItem(
                    modifier = Modifier.pointerInput(historyItem) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var isLongPress = false
                            val job = scope.launch {
                                delay(1500)
                                HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                                delay(1500)
                                isLongPress = true
                                HapticUtil.perform(context, HapticUtil.Pattern.ERROR)
                                onRemove(historyItem)
                            }
                            val up = waitForUpOrCancellation()
                            job.cancel()
                            if (isLongPress) {
                                up?.consume()
                            }
                        }
                    },
                    text = {
                        val text = historyItem.redact(privacyMode)
                        Text(
                            text = if (text.length > 30) {
                                text.take(21) + "..." + text.takeLast(6)
                            } else {
                                text
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Visible
                        )
                    },
                    onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                        onSelect(historyItem)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ScreenSectionDivider() {
    Spacer(Modifier.height(16.dp))
    HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    isModified: Boolean = false,
    enabled: Boolean = true
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                InlineConfigIndicator(isModified)
            }
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
