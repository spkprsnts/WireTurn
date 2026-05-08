package com.wireturn.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SingleChoiceSegmentedButtonRowScope
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import com.wireturn.app.R
import com.wireturn.app.data.ThemeMode
import com.wireturn.app.ui.theme.LocalThemeMode
import com.wireturn.app.viewmodel.UpdateState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Local provider for interaction source to coordinate ripples between parent blocks and children.
 */
val LocalSettingsInteractionSource = compositionLocalOf<MutableInteractionSource?> { null }

/**
 * Inline indicator for rows, usually placed after a text label.
 */
@Composable
fun ConfigSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null
) {
    val internalInteractionSource = interactionSource ?: LocalSettingsInteractionSource.current ?: remember { MutableInteractionSource() }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = modifier,
            interactionSource = internalInteractionSource,
            thumbContent = {
                Crossfade(targetState = checked, animationSpec = tween(200), label = "switch_icon") { isChecked ->
                    if (isChecked) {
                        Icon(
                            painter = painterResource(R.drawable.check_24px),
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.close_24px),
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize),
                        )
                    }
                }
            }
        )
    }
}

@Composable
fun InlineConfigIndicator(isModified: Boolean, modifier: Modifier = Modifier) {
    if (isModified) {
        Box(
            modifier = modifier
                .padding(start = 6.dp, end = 4.dp)
                .size(6.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        )
    }
}

@Composable
fun String.redact(enabled: Boolean): String {
    return if (enabled) stringResource(R.string.redacted_value) else this
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(bottom = 12.dp, top = 8.dp)
    )
}

/**
 * Shared label for settings rows.
 */
@Composable
fun ConfigRowLabel(
    text: String,
    modifier: Modifier = Modifier,
    isModified: Boolean = false
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        InlineConfigIndicator(isModified)
    }
}

/**
 * A reusable component for animated text transitions.
 */
@Composable
fun VerticalAnimatedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    maxLines: Int = Int.MAX_VALUE,
    contentAlignment: Alignment = Alignment.CenterStart
) {
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (fadeIn(animationSpec = tween(220, delayMillis = 40), initialAlpha = 0.2f) +
                    slideInVertically(initialOffsetY = { h -> h / 2 }))
                .togetherWith(fadeOut(animationSpec = tween(160)) +
                        slideOutVertically(targetOffsetY = { h -> -h / 2 }))
        },
        label = "vertical_animated_text",
        modifier = modifier,
        contentAlignment = contentAlignment
    ) { targetText ->
        Text(
            text = targetText,
            style = style,
            color = color,
            fontWeight = fontWeight,
            textAlign = textAlign,
            overflow = overflow,
            maxLines = maxLines
        )
    }
}

/**
 * A helper component to display supporting text with Material 3 hierarchy.
 */
@Composable
fun SupportingText(
    text: String?,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    if (text.isNullOrBlank()) return

    VerticalAnimatedText(
        text = text,
        style = style,
        color = color,
        modifier = modifier
    )
}

@Composable
fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier) {
        Box(modifier = Modifier.padding(start = 8.dp)) {
            SectionHeader(title = title)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            content()
        }
    }
}

@Composable
fun StandardLeadingIcon(
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .padding(start = 8.dp)
            .size(24.dp),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun SettingsGroupItem(
    isTop: Boolean,
    isBottom: Boolean,
    containerColor: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit
) {
    val cornerSize = 20.dp
    val smallCornerSize = 4.dp

    val topRadius by animateDpAsState(targetValue = if (isTop) cornerSize else smallCornerSize, label = "top_corner")
    val bottomRadius by animateDpAsState(targetValue = if (isBottom) cornerSize else smallCornerSize, label = "bottom_corner")

    val shape = RoundedCornerShape(
        topStart = topRadius,
        topEnd = topRadius,
        bottomStart = bottomRadius,
        bottomEnd = bottomRadius
    )

    val internalInteractionSource = interactionSource ?: LocalSettingsInteractionSource.current ?: remember { MutableInteractionSource() }

    CompositionLocalProvider(LocalSettingsInteractionSource provides internalInteractionSource) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp),
            shape = shape,
            onClick = onClick ?: {},
            enabled = onClick != null && enabled,
            interactionSource = internalInteractionSource,
            colors = CardDefaults.cardColors(
                containerColor = containerColor,
                disabledContainerColor = containerColor
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                content()
            }
        }
    }
}

@Composable
fun CompactSettingsItem(
    containerColor: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit
) {
    val internalInteractionSource = interactionSource ?: LocalSettingsInteractionSource.current ?: remember { MutableInteractionSource() }

    CompositionLocalProvider(LocalSettingsInteractionSource provides internalInteractionSource) {
        Card(
            modifier = modifier,
            shape = RoundedCornerShape(24.dp),
            onClick = onClick ?: {},
            enabled = onClick != null && enabled,
            interactionSource = internalInteractionSource,
            colors = CardDefaults.cardColors(
                containerColor = containerColor,
                disabledContainerColor = containerColor
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                content()
            }
        }
    }
}

@Composable
fun LabeledSegmentedButton(
    label: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    isModified: Boolean = false,
    content: @Composable SingleChoiceSegmentedButtonRowScope.() -> Unit
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ConfigRowLabel(text = label, isModified = isModified)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth(), content = content)
        SupportingText(text = supportingText)
    }
}

@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    isModified: Boolean = false,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (RowScope.() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
    clickable: Boolean = true
) {
    val parentInteractionSource = LocalSettingsInteractionSource.current
    val internalInteractionSource = interactionSource ?: parentInteractionSource ?: remember { MutableInteractionSource() }
    
    // Auto-disable internal clickable if we are inside a SettingsGroupItem that handles the interaction source,
    // unless clickable is explicitly set to true and we want nested clicks (rare).
    // If the user passed interactionSource explicitly, they likely want to coordinate.
    val actualClickable = if (parentInteractionSource != null && interactionSource == null) false else clickable

    val rowModifier = if (actualClickable) {
        modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = internalInteractionSource,
                indication = null,
                enabled = enabled,
                onClick = { onCheckedChange(!checked) }
            )
    } else {
        modifier.fillMaxWidth()
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = rowModifier
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            if (leadingIcon != null) {
                StandardLeadingIcon(content = leadingIcon)
                Spacer(Modifier.width(20.dp))
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                ConfigRowLabel(text = label, isModified = isModified)
                Spacer(Modifier.height(2.dp))
                SupportingText(text = supportingText)
            }

            if (trailingContent != null) {
                Spacer(Modifier.width(16.dp))
                trailingContent()
            }
        }

        Spacer(Modifier.width(16.dp))

        ConfigSwitch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            interactionSource = internalInteractionSource
        )
    }
}

@Composable
fun TextFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    supportingText: String? = null,
    isError: Boolean = false,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = 1,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    isModified: Boolean = false,
    onHelpClick: (() -> Unit)? = null
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ConfigRowLabel(
                text = label,
                isModified = isModified,
                modifier = Modifier.weight(1f, fill = false)
            )

            if (onHelpClick != null) {
                IconButton(
                    onClick = onHelpClick,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.info_24px),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            readOnly = readOnly,
            isError = isError,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            placeholder = { Text(placeholder) },
            leadingIcon = leadingIcon?.let {
                {
                    StandardLeadingIcon(content = it)
                }
            },
            trailingIcon = trailingIcon,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                errorContainerColor = Color.Transparent,
                unfocusedIndicatorColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                focusedIndicatorColor = MaterialTheme.colorScheme.primary
            )
        )
        Spacer(Modifier.height(2.dp))
        SupportingText(text = supportingText)
    }
}

@Composable
fun FieldTrailingIcons(
    history: List<String>,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    privacyMode: Boolean,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp
) {
    if (history.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
            HistoryIconButton(
                history = history,
                onSelect = onSelect,
                onRemove = onRemove,
                privacyMode = privacyMode,
                iconSize = iconSize
            )
        }
    }
}

@Composable
fun HistoryIconButton(
    history: List<String>,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    privacyMode: Boolean,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp
) {
    if (history.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Box(modifier = modifier) {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(if (iconSize < 24.dp) 40.dp else 48.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.history_24px),
                contentDescription = stringResource(R.string.history_label),
                modifier = Modifier.size(iconSize)
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
                        val displayText = if (!privacyMode) truncateUrlParameters(historyItem) else historyItem.redact(true)
                        Text(
                            text = displayText,
                            maxLines = 1,
                            modifier = Modifier.basicMarquee(velocity = 60.dp, initialDelayMillis = 2_500)
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

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("AuthLeak")
@Composable
fun TurnableUrlEditorDialog(
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val view = LocalView.current
        val themeMode = LocalThemeMode.current
        val systemDarkTheme = isSystemInDarkTheme()
        val isDark = when (themeMode) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM -> systemDarkTheme
        }

        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window ?: (view.context as? Activity)?.window
            window?.let {
                val controller = WindowCompat.getInsetsController(it, view)
                controller.isAppearanceLightStatusBars = !isDark
                controller.isAppearanceLightNavigationBars = !isDark
            }
        }

        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.edit_url_params)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                painter = painterResource(R.drawable.close_24px),
                                contentDescription = stringResource(R.string.cancel)
                            )
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = {
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
                            }
                        ) {
                            Text(stringResource(R.string.btn_save))
                        }
                    },
                    scrollBehavior = scrollBehavior
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
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.url_base)) },
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("scheme://user:pass@host/path") }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                
                Text(
                    text = stringResource(R.string.edit_url_params), 
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )

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
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = pair.second,
                            onValueChange = { newValue ->
                                params = params.toMutableList().apply { this[index] = pair.first to newValue }
                            },
                            label = { Text(stringResource(R.string.url_param_value)) },
                            modifier = Modifier.weight(1.5f),
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
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(painterResource(R.drawable.add_24px), null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.add_parameter))
                }
                
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

/**
 * A shared component to display application update status.
 */
@Composable
fun UpdateBlock(
    state: UpdateState,
    progress: Int,
    modifier: Modifier = Modifier,
    showChangelog: Boolean = true,
    onDownload: (() -> Unit)? = null,
    onInstall: (() -> Unit)? = null,
    onCheck: (() -> Unit)? = null
) {
    val titleText = stringResource(R.string.update_title)

    val supportingText = when (state) {
        is UpdateState.Idle -> stringResource(R.string.update_tap_to_check)
        is UpdateState.Checking -> stringResource(R.string.update_checking)
        is UpdateState.Available -> stringResource(R.string.update_available, state.version)
        is UpdateState.Downloading -> stringResource(R.string.update_downloading_short)
        is UpdateState.ReadyToInstall -> stringResource(R.string.update_ready_desc_short)
        is UpdateState.NoUpdate -> stringResource(R.string.update_no_update)
        is UpdateState.Error -> stringResource(R.string.update_error, state.message)
    }

    // Сохраняем описание обновы, чтобы оно не пропадало во время загрузки или когда всё готово
    var currentChangelog by remember { mutableStateOf("") }
    LaunchedEffect(state) {
        if (state is UpdateState.Available) {
            currentChangelog = state.changelog
        } else if (state is UpdateState.Idle || state is UpdateState.NoUpdate) {
            currentChangelog = ""
        }
    }

    val contentColor = MaterialTheme.colorScheme.onSurface

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StandardLeadingIcon {
                val targetContentColor = MaterialTheme.colorScheme.onSurface

                when (state) {
                    is UpdateState.Checking, is UpdateState.Downloading -> {
                        androidx.compose.material3.CircularWavyProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = targetContentColor
                        )
                    }
                    else -> {
                        Icon(
                            painter = painterResource(
                                when (state) {
                                    is UpdateState.Error -> R.drawable.error_24px
                                    is UpdateState.Available, is UpdateState.ReadyToInstall -> R.drawable.info_24px
                                    else -> R.drawable.sync_24px
                                }
                            ),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = if (state is UpdateState.Error) MaterialTheme.colorScheme.error
                            else targetContentColor
                        )
                    }
                }
            }

            Spacer(Modifier.width(20.dp))

            Column(modifier = Modifier.weight(1f)) {
                ConfigRowLabel(text = titleText)
                Spacer(Modifier.height(2.dp))
                SupportingText(
                    text = supportingText,
                    color = if (state is UpdateState.Error) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface
                )
            }

            when (state) {
                is UpdateState.Available -> {
                    if (onDownload != null) {
                        Spacer(Modifier.width(12.dp))
                        TextButton(
                            onClick = { onDownload.invoke() },
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.update_download),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                is UpdateState.Downloading -> {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "$progress%",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp),
                        textAlign = TextAlign.End
                    )
                }
                is UpdateState.ReadyToInstall -> {
                    if (onInstall != null) {
                        Spacer(Modifier.width(12.dp))
                        TextButton(
                            onClick = { onInstall.invoke() },
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.update_install),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                is UpdateState.Idle, is UpdateState.NoUpdate, is UpdateState.Error -> {
                    if (onCheck != null) {
                        Spacer(Modifier.width(12.dp))
                        IconButton(
                            onClick = { onCheck.invoke() }
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.refresh_24px),
                                contentDescription = stringResource(R.string.update_tap_to_check),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                else -> {}
            }
        }

        // Линейный прогресс-бар при скачивании
        AnimatedVisibility(
            visible = state is UpdateState.Downloading,
            enter = expandVertically(animationSpec = tween(300)) + fadeIn(tween(300)),
            exit = shrinkVertically(animationSpec = tween(300)) + fadeOut(tween(300))
        ) {
            androidx.compose.material3.LinearWavyProgressIndicator(
                progress = { progress.div(100f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                color = MaterialTheme.colorScheme.primary
            )
        }

        AnimatedVisibility(
            visible = showChangelog && currentChangelog.isNotBlank(),
            enter = expandVertically(animationSpec = tween(300)) + fadeIn(tween(300)),
            exit = shrinkVertically(animationSpec = tween(300)) + fadeOut(tween(300))
        ) {
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

private fun truncateUrlParameters(url: String): String {
    return try {
        val limit = 50
        val uri = url.toUri()
        if (uri.query.isNullOrBlank()) return url
        val builder = uri.buildUpon()
        builder.clearQuery()
        uri.queryParameterNames.forEach { key ->
            val values = uri.getQueryParameters(key)
            values.forEach { value ->
                val truncated = if (value.length > limit) value.take(limit) + "..." else value
                builder.appendQueryParameter(key, truncated)
            }
        }
        builder.build().toString()
    } catch (_: Exception) {
        url
    }
}
