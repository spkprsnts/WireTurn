@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wireturn.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
    val kernelError by viewModel.kernelError.collectAsStateWithLifecycle()
    val privacyMode by viewModel.privacyMode.collectAsStateWithLifecycle()

    val vkLinkHistory by viewModel.vkLinkHistory.collectAsStateWithLifecycle()
    val telemostLinkHistory by viewModel.telemostLinkHistory.collectAsStateWithLifecycle()
    val serverAddressHistory by viewModel.serverAddressHistory.collectAsStateWithLifecycle()
    val jazzCredsHistory by viewModel.jazzCredsHistory.collectAsStateWithLifecycle()
    val runningConfig by com.wireturn.app.ProxyServiceState.runningConfig.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val kernelPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.setCustomKernel(it) } }

    var isRawMode by rememberSaveable(saved.isRawMode) { mutableStateOf(saved.isRawMode) }
    var rawCommand by rememberSaveable(saved.rawCommand) { mutableStateOf(saved.rawCommand) }
    var serverAddress by rememberSaveable(saved.serverAddress) { mutableStateOf(saved.serverAddress) }
    var vkLink       by rememberSaveable(saved.vkLink)         { mutableStateOf(saved.vkLink) }
    var telemostLink by rememberSaveable(saved.telemostLink)   { mutableStateOf(saved.telemostLink) }
    var threads      by rememberSaveable(saved.threads)        { mutableFloatStateOf(saved.threads.toFloat()) }
    var useUdp       by rememberSaveable(saved.useUdp)         { mutableStateOf(saved.useUdp) }
    var noDtls       by rememberSaveable(saved.noDtls)         { mutableStateOf(saved.noDtls) }
    var manualCaptcha by rememberSaveable(saved.manualCaptcha) { mutableStateOf(saved.manualCaptcha) }
    var localPort    by rememberSaveable(saved.localPort)      { mutableStateOf(saved.localPort) }
    var vlessMode    by rememberSaveable(saved.vlessMode)      { mutableStateOf(saved.vlessMode) }
    var dcMode       by rememberSaveable(saved.dcMode)         { mutableStateOf(saved.dcMode) }
    var forcePort443 by rememberSaveable(saved.forceTurnPort443) { mutableStateOf(saved.forceTurnPort443) }
    var isJazz       by rememberSaveable(saved.isJazz)         { mutableStateOf(saved.isJazz) }
    var jazzCreds    by rememberSaveable(saved.jazzCreds)      { mutableStateOf(saved.jazzCreds) }
    var lastSliderInt by rememberSaveable { mutableIntStateOf(saved.threads) }

    val isServerAddressValid = remember(serverAddress) {
        ValidatorUtils.isValidHostPort(serverAddress)
    }
    val isLocalPortValid = remember(localPort) {
        ValidatorUtils.isValidHostPort(localPort)
    }

    // Авто-сохранение с дебаунсом 200 мс на каждое изменение поля.
    LaunchedEffect(isRawMode, rawCommand, serverAddress, vkLink, telemostLink, threads, useUdp, noDtls,
        manualCaptcha, localPort, vlessMode, dcMode, forcePort443, isJazz, jazzCreds
    ) {
        delay(200)
        viewModel.saveClientConfig(
            viewModel.clientConfig.value.copy(
                isRawMode        = isRawMode,
                rawCommand       = rawCommand,
                serverAddress    = serverAddress.trim(),
                vkLink           = vkLink.trim(),
                telemostLink     = telemostLink.trim(),
                threads          = threads.roundToInt(),
                useUdp           = useUdp,
                noDtls           = noDtls,
                manualCaptcha    = manualCaptcha,
                localPort        = localPort.trim(),
                vlessMode        = vlessMode,
                dcMode           = dcMode,
                forceTurnPort443 = forcePort443,
                isJazz           = isJazz,
                jazzCreds        = jazzCreds.trim()
            )
        )
    }

    val contentAnimationSpec = tween<androidx.compose.ui.unit.IntSize>(300, easing = FastOutSlowInEasing)
    val visibilityAnimationSpec = tween<Float>(300, easing = FastOutSlowInEasing)

    Scaffold(
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
                                Text(stringResource(R.string.protocol), style = MaterialTheme.typography.bodyMedium)
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

                    var showServerHistoryMenu by remember { mutableStateOf(false) }

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
                                        Box {
                                            IconButton(onClick = { showServerHistoryMenu = true }) {
                                                Icon(
                                                    painter = painterResource(R.drawable.database_outlined_24px),
                                                    contentDescription = stringResource(R.string.history_label)
                                                )
                                            }
                                            DropdownMenu(
                                                expanded = showServerHistoryMenu,
                                                onDismissRequest = { showServerHistoryMenu = false }
                                            )
                                            {
                                                if (serverAddressHistory.isEmpty()) {
                                                    DropdownMenuItem(
                                                        text = { Text(stringResource(R.string.history_empty)) },
                                                        onClick = { showServerHistoryMenu = false },
                                                        enabled = false
                                                    )
                                                } else {
                                                    serverAddressHistory.forEach { historyItem ->
                                                        DropdownMenuItem(
                                                            modifier = Modifier.pointerInput(
                                                                historyItem
                                                            ) {
                                                                awaitEachGesture {
                                                                    awaitFirstDown(requireUnconsumed = false)
                                                                    var isLongPress = false
                                                                    val job = scope.launch {
                                                                        delay(1500)
                                                                        HapticUtil.perform(
                                                                            context,
                                                                            HapticUtil.Pattern.SELECTION
                                                                        )
                                                                        delay(1500)
                                                                        isLongPress = true
                                                                        HapticUtil.perform(
                                                                            context,
                                                                            HapticUtil.Pattern.ERROR
                                                                        )
                                                                        viewModel.removeServerAddressFromHistory(
                                                                            historyItem
                                                                        )
                                                                    }
                                                                    val up =
                                                                        waitForUpOrCancellation()
                                                                    job.cancel()
                                                                    if (isLongPress) {
                                                                        up?.consume()
                                                                    }
                                                                }
                                                            },
                                                            text = {
                                                                val text =
                                                                    historyItem.redact(privacyMode)
                                                                Text(
                                                                    text = if (text.length > 30) {
                                                                        text.take(21) + "..." + text.takeLast(
                                                                            6
                                                                        )
                                                                    } else {
                                                                        text
                                                                    },
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Visible
                                                                )
                                                            },
                                                            onClick = {
                                                                HapticUtil.perform(
                                                                    context,
                                                                    HapticUtil.Pattern.SELECTION
                                                                )
                                                                serverAddress = historyItem
                                                                showServerHistoryMenu = false
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                        }
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
                            SwitchRow(
                                label = stringResource(R.string.jazz_label),
                                description = stringResource(R.string.jazz_desc),
                                checked = isJazz,
                                onCheckedChange = {
                                    HapticUtil.perform(
                                        context,
                                        if (it) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                                    )
                                    isJazz = it
                                },
                                isModified = runningConfig != null && isJazz != runningConfig?.isJazz
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }

                    var showHistoryMenu by remember { mutableStateOf(false) }

                    AnimatedVisibility(
                        visible = isJazz && dcMode,
                        enter = fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing)) + 
                                expandVertically(animationSpec = tween(300, easing = FastOutSlowInEasing)),
                        exit = fadeOut(animationSpec = tween(300, easing = FastOutSlowInEasing)) + 
                               shrinkVertically(animationSpec = tween(300, easing = FastOutSlowInEasing))
                    ) {
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
                                    Box {
                                        IconButton(onClick = { showHistoryMenu = true }) {
                                            Icon(
                                                painter = painterResource(R.drawable.database_outlined_24px),
                                                contentDescription = stringResource(R.string.history_label)
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = showHistoryMenu,
                                            onDismissRequest = { showHistoryMenu = false }
                                        ) {
                                            if (jazzCredsHistory.isEmpty()) {
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.history_empty)) },
                                                    onClick = { showHistoryMenu = false },
                                                    enabled = false
                                                )
                                            } else {
                                                jazzCredsHistory.forEach { historyItem ->
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
                                                                    viewModel.removeJazzCredsFromHistory(historyItem)
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
                                                            jazzCreds = historyItem
                                                            showHistoryMenu = false
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    }

                    AnimatedVisibility(
                        visible = !isJazz && dcMode,
                        enter = fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing)) + 
                                expandVertically(animationSpec = tween(300, easing = FastOutSlowInEasing)),
                        exit = fadeOut(animationSpec = tween(300, easing = FastOutSlowInEasing)) + 
                               shrinkVertically(animationSpec = tween(300, easing = FastOutSlowInEasing))
                    ) {
                        OutlinedTextField(
                            value = telemostLink.redact(privacyMode),
                            onValueChange = { if (!privacyMode) telemostLink = it },
                            label = { Text(stringResource(R.string.telemost_link_label)) },
                            placeholder = { Text(stringResource(R.string.telemost_link_placeholder)) },
                            isError = !ValidatorUtils.isValidUrl(telemostLink) || telemostLink.isBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            readOnly = privacyMode,
                            supportingText = { Text(stringResource(R.string.telemost_link_support)) },
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ConfigFieldIndicator(runningConfig != null && (telemostLink.trim() != runningConfig?.telemostLink || runningConfig!!.isRawMode))
                                    Box {
                                        IconButton(onClick = { showHistoryMenu = true }) {
                                            Icon(
                                                painter = painterResource(R.drawable.database_outlined_24px),
                                                contentDescription = stringResource(R.string.history_label)
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = showHistoryMenu,
                                            onDismissRequest = { showHistoryMenu = false }
                                        ) {
                                            if (telemostLinkHistory.isEmpty()) {
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.history_empty)) },
                                                    onClick = { showHistoryMenu = false },
                                                    enabled = false
                                                )
                                            } else {
                                                telemostLinkHistory.forEach { historyItem ->
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
                                                                    viewModel.removeTelemostLinkFromHistory(historyItem)
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
                                                            telemostLink = historyItem
                                                            showHistoryMenu = false
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        )
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
                                    Box {
                                        IconButton(onClick = { showHistoryMenu = true }) {
                                            Icon(
                                                painter = painterResource(R.drawable.database_outlined_24px),
                                                contentDescription = stringResource(R.string.history_label)
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = showHistoryMenu,
                                            onDismissRequest = { showHistoryMenu = false }
                                        ) {
                                            if (vkLinkHistory.isEmpty()) {
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.history_empty)) },
                                                    onClick = { showHistoryMenu = false },
                                                    enabled = false
                                                )
                                            } else {
                                                vkLinkHistory.forEach { historyItem ->
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
                                                                    viewModel.removeVkLinkFromHistory(historyItem)
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
                                                            vkLink = historyItem
                                                            showHistoryMenu = false
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
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

                    AnimatedVisibility(
                        visible = !dcMode,
                        enter = fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing)) + 
                                expandVertically(animationSpec = tween(300, easing = FastOutSlowInEasing)),
                        exit = fadeOut(animationSpec = tween(300, easing = FastOutSlowInEasing)) + 
                               shrinkVertically(animationSpec = tween(300, easing = FastOutSlowInEasing))
                    ) {
                        Column {
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
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
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
                        Spacer(Modifier.height(12.dp))
                    }

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
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
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
                                if (customKernelExists) stringResource(R.string.loaded_from_memory)
                                else stringResource(R.string.from_apk),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
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
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
