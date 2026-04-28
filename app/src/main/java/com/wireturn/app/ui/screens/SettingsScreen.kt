@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui.screens

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.R
import com.wireturn.app.data.ThemeMode
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.MarkdownUtils
import com.wireturn.app.ui.SectionHeader
import com.wireturn.app.ui.SwitchRow
import com.wireturn.app.viewmodel.MainViewModel
import com.wireturn.app.viewmodel.UpdateState

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    scrollToUpdate: Boolean = false
) {
    val context = LocalContext.current
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val dynamicTheme by viewModel.dynamicTheme.collectAsStateWithLifecycle()
    val privacyMode by viewModel.privacyMode.collectAsStateWithLifecycle()
    val allowUnstableUpdates by viewModel.allowUnstableUpdates.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val showResetDialog = rememberSaveable { mutableStateOf(false) }

    val scrollState = rememberScrollState()
    var updateBlockOffset by remember { mutableStateOf(0f) }

    LaunchedEffect(scrollToUpdate, updateBlockOffset) {
        if (scrollToUpdate && updateBlockOffset > 0f) {
            scrollState.animateScrollTo(updateBlockOffset.toInt())
        }
    }

    val supportsDynamicColor = remember { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }
    val supportsSystemTheme = remember { Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q }

    LaunchedEffect(supportsDynamicColor) {
        if (!supportsDynamicColor && dynamicTheme) {
            viewModel.setDynamicTheme(false)
        }
    }

    LaunchedEffect(supportsSystemTheme) {
        if (!supportsSystemTheme && themeMode == ThemeMode.SYSTEM) {
            viewModel.setThemeMode(ThemeMode.DARK)
        }
    }

    val dash = stringResource(R.string.dash)
    val appVersion = remember {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: dash
        } catch (_: Exception) {
            dash
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_settings_title)) }) },
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
                .verticalScroll(scrollState)
                .padding(bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // 1. Оформление
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionHeader(stringResource(R.string.theme_title))
                    Spacer(Modifier.height(16.dp))

                    val themeModes = remember(supportsSystemTheme) {
                        if (supportsSystemTheme) ThemeMode.entries else ThemeMode.entries.filter { it != ThemeMode.SYSTEM }
                    }

                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        themeModes.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = themeMode == mode,
                                onClick = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                    viewModel.setThemeMode(mode)
                                },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = themeModes.size
                                ),
                                label = {
                                    Text(
                                        when (mode) {
                                            ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                                            ThemeMode.DARK -> stringResource(R.string.theme_dark)
                                            ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                                        }
                                    )
                                }
                            )
                        }
                    }

                    if (supportsDynamicColor) {
                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider(thickness = 0.5.dp)
                        Spacer(Modifier.height(16.dp))

                        SwitchRow(
                            label = stringResource(R.string.dynamic_theme_title),
                            description = stringResource(R.string.dynamic_theme_desc),
                            checked = dynamicTheme,
                            onCheckedChange = { viewModel.setDynamicTheme(it) }
                        )
                    }
                }
            }

            // 2. Приватность
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionHeader(stringResource(R.string.privacy_mode_title))
                    Spacer(Modifier.height(16.dp))

                    SwitchRow(
                        label = stringResource(R.string.privacy_mode_title),
                        description = stringResource(R.string.privacy_mode_desc),
                        checked = privacyMode,
                        onCheckedChange = { viewModel.setPrivacyMode(it) }
                    )
                }
            }

            // 3. Обновление
            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        updateBlockOffset = coordinates.positionInParent().y
                    }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionHeader(stringResource(R.string.update_title))
                    Spacer(Modifier.height(16.dp))

                    UpdateBlock(
                        state = updateState,
                        onCheck = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            viewModel.checkForUpdate()
                        },
                        onDownload = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            viewModel.downloadUpdate()
                        },
                        onInstall = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            viewModel.installUpdate()
                        }
                    )

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(thickness = 0.5.dp)
                    Spacer(Modifier.height(16.dp))

                    SwitchRow(
                        label = stringResource(R.string.unstable_updates_title),
                        description = stringResource(R.string.unstable_updates_desc),
                        checked = allowUnstableUpdates,
                        onCheckedChange = { viewModel.setAllowUnstableUpdates(it) }
                    )
                }
            }

            // 4. Сброс настроек
            Surface(
                onClick = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    showResetDialog.value = true
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = Color.Transparent
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        painterResource(R.drawable.delete_24px),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        stringResource(R.string.reset_settings),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // Версия
            Text(
                text = stringResource(R.string.version_format, appVersion),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

        }
    }

    if (showResetDialog.value) {
        AlertDialog(
            onDismissRequest = { showResetDialog.value = false },
            title = { Text(stringResource(R.string.reset_all_settings_title)) },
            text = { Text(stringResource(R.string.reset_all_settings_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog.value = false
                        viewModel.resetAllSettings(context)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(stringResource(R.string.reset)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog.value = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun UpdateBlock(
    state: UpdateState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit
) {
    val context = LocalContext.current
    val titleText = stringResource(R.string.update_title)
    val supportingText = when (state) {
        is UpdateState.Idle -> stringResource(R.string.update_tap_to_check)
        is UpdateState.Checking -> stringResource(R.string.update_checking)
        is UpdateState.Available -> stringResource(R.string.update_available, state.version)
        is UpdateState.Downloading -> stringResource(R.string.update_downloading, state.progress)
        is UpdateState.ReadyToInstall -> stringResource(R.string.update_ready_desc_short)
        is UpdateState.NoUpdate -> stringResource(R.string.update_no_update)
        is UpdateState.Error -> stringResource(R.string.update_error, state.message)
    }

    // Сохраняем описание обновы, чтобы оно не пропадало во время загрузки или когда всё готово
    var currentChangelog by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(state) {
        if (state is UpdateState.Available) {
            currentChangelog = state.changelog
        } else if (state is UpdateState.Idle || state is UpdateState.NoUpdate) {
            currentChangelog = ""
        }
    }

    val canInteract = state !is UpdateState.Checking && state !is UpdateState.Downloading
    val isImportantState = state is UpdateState.Available || state is UpdateState.Downloading || state is UpdateState.ReadyToInstall
    val containerColor = if (isImportantState) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    val contentColor = if (isImportantState) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface

    Surface(
        onClick = {
            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
            when (state) {
                is UpdateState.Available -> onDownload()
                is UpdateState.ReadyToInstall -> onInstall()
                else -> onCheck()
            }
        },
        enabled = canInteract,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        tonalElevation = if (isImportantState) 2.dp else 0.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.heightIn(min = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    AnimatedContent(
                        targetState = state,
                        label = "update_indicator",
                        transitionSpec = {
                            fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                        },
                        contentKey = { it::class }
                    ) { targetState ->
                        val targetIsImportant = targetState is UpdateState.Available || targetState is UpdateState.Downloading || targetState is UpdateState.ReadyToInstall
                        val targetContentColor = if (targetIsImportant) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface

                        when (targetState) {
                            is UpdateState.Checking, is UpdateState.Downloading -> {
                                CircularWavyProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = targetContentColor
                                )
                            }
                            else -> {
                                Icon(
                                    painter = painterResource(
                                        if (targetState is UpdateState.Error) R.drawable.error_24px
                                        else R.drawable.refresh_24px
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    tint = if (targetState is UpdateState.Error) MaterialTheme.colorScheme.error
                                    else targetContentColor
                                )
                            }
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.labelLarge,
                        color = contentColor,
                        fontWeight = FontWeight.Bold
                    )
                    AnimatedContent(
                        targetState = state,
                        label = "update_supporting_text",
                        transitionSpec = {
                            fadeIn(tween(200)) togetherWith fadeOut(tween(200)) using SizeTransform(clip = false)
                        },
                        contentKey = { if (it is UpdateState.Downloading) "downloading" else it }
                    ) { targetState ->
                        val targetIsImportant = targetState is UpdateState.Available || targetState is UpdateState.Downloading || targetState is UpdateState.ReadyToInstall
                        val targetContentColor = if (targetIsImportant) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface

                        val targetSupportingText = when (targetState) {
                            is UpdateState.Idle -> stringResource(R.string.update_tap_to_check)
                            is UpdateState.Checking -> stringResource(R.string.update_checking)
                            is UpdateState.Available -> stringResource(R.string.update_available, targetState.version)
                            is UpdateState.Downloading -> supportingText // Use outer supportingText for real-time progress
                            is UpdateState.ReadyToInstall -> stringResource(R.string.update_ready_desc_short)
                            is UpdateState.NoUpdate -> stringResource(R.string.update_no_update)
                            is UpdateState.Error -> stringResource(R.string.update_error, targetState.message)
                        }

                        Text(
                            text = targetSupportingText,
                            style = MaterialTheme.typography.bodySmall,
                            color = (if (targetState is UpdateState.Error) MaterialTheme.colorScheme.error
                                    else targetContentColor).copy(alpha = 0.7f)
                        )
                    }
                }

                AnimatedContent(
                    targetState = when(state) {
                        is UpdateState.Available -> "download"
                        is UpdateState.ReadyToInstall -> "install"
                        else -> "none"
                    },
                    label = "update_actions",
                    transitionSpec = {
                        fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(200))
                    }
                ) { targetKey ->
                    val actionText = when (targetKey) {
                        "download" -> stringResource(R.string.update_download)
                        "install" -> stringResource(R.string.update_install)
                        else -> null
                    }
                    if (actionText != null) {
                        Text(
                            text = actionText,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }
            }

            // Линейный прогресс-бар при скачивании
            AnimatedVisibility(
                visible = state is UpdateState.Downloading,
                enter = expandVertically(animationSpec = tween(300)) + fadeIn(tween(300)),
                exit = shrinkVertically(animationSpec = tween(300)) + fadeOut(tween(300))
            ) {
                androidx.compose.material3.LinearWavyProgressIndicator(
                    progress = { (state as? UpdateState.Downloading)?.progress?.div(100f) ?: 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (currentChangelog.isNotBlank()) {
                Text(
                    text = MarkdownUtils.parseMarkdown(
                        text = currentChangelog,
                        linkStyle = SpanStyle(
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline
                        )
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }
}
