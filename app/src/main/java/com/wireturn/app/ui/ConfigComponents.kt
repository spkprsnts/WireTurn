package com.wireturn.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SingleChoiceSegmentedButtonRowScope
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.expandHorizontally
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import com.wireturn.app.R
import com.wireturn.app.data.ThemeMode
import com.wireturn.app.ui.theme.LocalThemeMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Inline indicator for rows, usually placed after a text label.
 */
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

/**
 * A helper to create a label row with an optional modification indicator.
 */
@Composable
fun ConfigLabelRow(isModified: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        content()
        InlineConfigIndicator(isModified)
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
 * A helper component to display supporting text with Material 3 hierarchy.
 */
@Composable
fun SupportingText(
    text: String?,
    secondaryText: String? = null,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    verticalSpacing: Dp = 2.dp
) {
    if (text.isNullOrBlank() && secondaryText.isNullOrBlank()) return

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(verticalSpacing)) {
        if (!text.isNullOrBlank()) {
            Text(
                text = text,
                style = style,
                color = color
            )
        }
        if (!secondaryText.isNullOrBlank()) {
            Text(
                text = secondaryText,
                style = MaterialTheme.typography.bodySmall,
                color = color.copy(alpha = 0.8f)
            )
        }
    }
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
fun SettingsGroupItem(
    isTop: Boolean,
    isBottom: Boolean,
    containerColor: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val cornerSize = 16.dp
    val smallCornerSize = 4.dp

    val topRadius by animateDpAsState(targetValue = if (isTop) cornerSize else smallCornerSize, label = "top_corner")
    val bottomRadius by animateDpAsState(targetValue = if (isBottom) cornerSize else smallCornerSize, label = "bottom_corner")

    val shape = RoundedCornerShape(
        topStart = topRadius,
        topEnd = topRadius,
        bottomStart = bottomRadius,
        bottomEnd = bottomRadius
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        onClick = onClick ?: {},
        enabled = onClick != null && enabled,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            disabledContainerColor = containerColor
        )
    ) {
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            content()
        }
    }
}

@Composable
fun CompactSettingsItem(
    containerColor: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        onClick = onClick ?: {},
        enabled = onClick != null && enabled,
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

@Composable
fun LabeledSegmentedButton(
    label: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    secondaryText: String? = null,
    isModified: Boolean = false,
    content: @Composable SingleChoiceSegmentedButtonRowScope.() -> Unit
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                InlineConfigIndicator(isModified)
            }
            SupportingText(text = supportingText, secondaryText = secondaryText)
        }
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
fun SwitchRow(
    label: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    secondaryText: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    isModified: Boolean = false,
    enabled: Boolean = true
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                InlineConfigIndicator(isModified)
            }
            SupportingText(text = supportingText, secondaryText = secondaryText)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.padding(start = 16.dp)
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
    secondaryText: String? = null,
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
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    InlineConfigIndicator(isModified)
                }
                
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
            SupportingText(text = supportingText, secondaryText = secondaryText)
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
            leadingIcon = leadingIcon,
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
    AnimatedVisibility(
        visible = history.isNotEmpty(),
        modifier = modifier,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
                        val text = historyItem.redact(privacyMode)
                        Text(
                            text = if (text.length > 30) {
                                text.take(21) + "..." + text.takeLast(6)
                            } else {
                                text
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
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
