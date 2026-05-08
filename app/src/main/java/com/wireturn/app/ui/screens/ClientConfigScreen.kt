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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.R
import com.wireturn.app.data.KernelVariant
import com.wireturn.app.ui.ConfigRowLabel
import com.wireturn.app.ui.FieldTrailingIcons
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.LabeledSegmentedButton
import com.wireturn.app.ui.SettingsGroup
import com.wireturn.app.ui.SettingsGroupItem
import com.wireturn.app.ui.StandardLeadingIcon
import com.wireturn.app.ui.SupportingText
import com.wireturn.app.ui.SwitchRow
import com.wireturn.app.ui.TextFieldRow
import com.wireturn.app.ui.TurnableUrlEditorDialog
import com.wireturn.app.ui.ValidatorUtils
import com.wireturn.app.ui.redact
import com.wireturn.app.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
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

    val turnableUrlHistory by viewModel.turnableUrlHistory.collectAsStateWithLifecycle()
    val clientConfigSnapshot by com.wireturn.app.ProxyServiceState.clientConfigSnapshot.collectAsStateWithLifecycle()

    val context = LocalContext.current

    val kernelPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.setCustomKernel(it) } }

    var isRawMode by rememberSaveable(currentProfileId, saved.isRawMode) { mutableStateOf(saved.isRawMode) }
    var rawCommand by rememberSaveable(currentProfileId, saved.rawCommand) { mutableStateOf(saved.rawCommand) }
    var turnableUrl  by rememberSaveable(currentProfileId, saved.turnableUrl)    { mutableStateOf(saved.turnableUrl) }
    var localPort    by rememberSaveable(currentProfileId, saved.localPort)      { mutableStateOf(saved.localPort) }
    var kernelVariant by rememberSaveable(currentProfileId, saved.kernelVariant) { mutableStateOf(saved.kernelVariant) }
    var isUrlParsing by remember { mutableStateOf(false) }
    
    val showPortHelp = remember { mutableStateOf(false) }
    val showUrlEditor = remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Sync local state with saved config when it changes externally (e.g. profile switch)
    LaunchedEffect(saved) {
        isRawMode = saved.isRawMode
        rawCommand = saved.rawCommand
        turnableUrl = saved.turnableUrl
        localPort = saved.localPort
        kernelVariant = saved.kernelVariant
    }

    val isLocalPortValid = remember(localPort) { ValidatorUtils.isValidHostPort(localPort) }
    val isTurnableUrlValid = remember(turnableUrl) { ValidatorUtils.isValidTurnableUrl(turnableUrl) }

    LaunchedEffect(isRawMode, rawCommand, turnableUrl, localPort, kernelVariant) {
        delay(200)
        val current = viewModel.clientConfig.value
        val next = current.copy(
            isRawMode        = isRawMode,
            rawCommand       = rawCommand,
            turnableUrl      = turnableUrl.trim(),
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
                                SegmentedButton(
                                    selected = kernelVariant == KernelVariant.TURNABLE,
                                    onClick = {
                                        HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                        kernelVariant = KernelVariant.TURNABLE
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 1)
                                ) { Text(stringResource(R.string.kernel_turnable)) }
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
                            SegmentedButton(
                                selected = kernelVariant == KernelVariant.TURNABLE,
                                onClick = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                    kernelVariant = KernelVariant.TURNABLE
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 1)
                            ) { Text(stringResource(R.string.kernel_turnable)) }
                        }
                    }
                }
            }

            if (!isRawMode) {
                Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    // 2. Детали подключения
                    SettingsGroup(title = stringResource(R.string.connection_details)) {
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
                                isModified = clientConfigSnapshot != null && (turnableUrl.trim() != clientConfigSnapshot?.turnableUrl || clientConfigSnapshot!!.isRawMode),
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
                                            privacyMode = privacyMode
                                        )
                                    }
                                }
                            )
                        }
                    }

                    // 3. Тонкая настройка
                    SettingsGroup(title = stringResource(R.string.parameters_title)) {
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
                val isValid = remember(isRawMode, rawCommand, turnableUrl, kernelVariant) {
                    com.wireturn.app.data.ClientConfig(
                        isRawMode = isRawMode,
                        rawCommand = rawCommand,
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
