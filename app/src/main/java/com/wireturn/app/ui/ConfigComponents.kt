@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonGroupScope
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.TwoRowsTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.ui.graphics.takeOrElse
import com.wireturn.app.ui.theme.LocalIsDark
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.wireturn.app.R
import com.wireturn.app.viewmodel.UpdateState
import kotlin.math.roundToInt
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
        VerticalAnimatedText(
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
    softWrap: Boolean = true,
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
            softWrap = softWrap,
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
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    paddingStart: Dp = 8.dp,
    paddingEnd: Dp = 20.dp,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .padding(start = paddingStart, end = paddingEnd)
            .size(size),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun LargeLeadingIcon(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    StandardLeadingIcon(
        modifier = modifier,
        size = 40.dp,
        paddingStart = 0.dp,
        paddingEnd = 12.dp,
        content = content
    )
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
    // LargeIncreased = 20.dp (for outer group boundaries)
    // ExtraSmall = 4.dp (for internal joints)
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
                disabledContainerColor = if (enabled) containerColor else containerColor.copy(alpha = 0.5f)
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .let { if (!enabled) it.alpha(0.38f) else it },
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
                disabledContainerColor = if (enabled) containerColor else containerColor.copy(alpha = 0.5f)
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .let { if (!enabled) it.alpha(0.38f) else it },
                contentAlignment = Alignment.Center
            ) {
                content()
            }
        }
    }
}

@Composable
fun SliderRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    supportingText: String? = null,
    isModified: Boolean = false,
    valueDisplay: @Composable (Float) -> Unit = {
        Text(
            text = it.roundToInt().toString(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
) {
    val showDialog = remember { mutableStateOf(false) }
    val context = LocalContext.current

    if (showDialog.value) {
        var textValue by remember { mutableStateOf(value.roundToInt().toString()) }
        AlertDialog(
            onDismissRequest = { showDialog.value = false },
            title = { Text(label) },
            text = {
                TextField(
                    value = textValue,
                    onValueChange = { newValue ->
                        if (newValue.isEmpty() || newValue.all { it.isDigit() }) {
                            textValue = newValue
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val parsed = textValue.toFloatOrNull()
                    if (parsed != null) {
                        onValueChange(parsed.coerceIn(valueRange))
                    }
                    showDialog.value = false
                }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog.value = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ConfigRowLabel(text = label, isModified = isModified, modifier = Modifier.weight(1f))
            Surface(
                onClick = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    showDialog.value = true
                },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    valueDisplay(value)
                    Icon(
                        painter = painterResource(R.drawable.edit_24px),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    )
                }
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        )
        SupportingText(text = supportingText)
    }
}

@Composable
fun LabeledButtonGroup(
    modifier: Modifier = Modifier,
    label: String? = null,
    supportingText: String? = null,
    isModified: Boolean = false,
    onHelpClick: (() -> Unit)? = null,
    content: ButtonGroupScope.() -> Unit
) {
    val context = LocalContext.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!label.isNullOrBlank() || onHelpClick != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (!label.isNullOrBlank()) {
                    ConfigRowLabel(
                        text = label,
                        isModified = isModified,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }

                if (onHelpClick != null) {
                    IconButton(
                        onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            onHelpClick()
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.info_24px),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
        ButtonGroup(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            expandedRatio = 0f,
            overflowIndicator = { menuState ->
                ButtonGroupDefaults.OverflowIndicator(menuState)
            },
            content = content
        )
        SupportingText(text = supportingText)
    }
}

/**
 * A toggleable item for [LabeledButtonGroup] with expressive shapes and fixed text wrapping.
 */
fun ButtonGroupScope.configButtonGroupItem(
    selected: Boolean,
    onSelect: () -> Unit,
    label: String,
    index: Int,
    count: Int,
    enabled: Boolean = true,
    iconRes: Int? = null,
    weight: Float = 1f
) {
    customItem(
        buttonGroupContent = {
            val interactionSource = remember { MutableInteractionSource() }

            val shapes = when {
                count == 1 -> ToggleButtonShapes(shape = CircleShape, pressedShape = CircleShape, checkedShape = CircleShape)
                index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                index == count - 1 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
            }

            ToggleButton(
                checked = selected,
                onCheckedChange = { if (!selected) onSelect() },
                enabled = enabled,
                modifier = Modifier
                    .weight(weight),
                interactionSource = interactionSource,
                shapes = shapes
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (iconRes != null) {
                        Icon(
                            painter = painterResource(iconRes),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = label,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center
                    )
                }
            }
        },
        menuContent = { state ->
            DropdownMenuItem(
                text = { Text(label) },
                onClick = {
                    onSelect()
                    state.dismiss()
                },
                enabled = enabled
            )
        }
    )
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
    leadingIconSize: Dp = 24.dp,
    useLargeIcon: Boolean = false,
    trailingContent: @Composable (RowScope.() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
    clickable: Boolean = true,
    onRowClick: (() -> Unit)? = null,
    isSplit: Boolean = false
) {
    val parentInteractionSource = LocalSettingsInteractionSource.current
    val internalInteractionSource = interactionSource ?: parentInteractionSource ?: remember { MutableInteractionSource() }
    
    // Decouple switch interaction from parent if in split mode to avoid whole block ripple on toggle
    val switchInteractionSource = if (onRowClick != null || isSplit) remember { MutableInteractionSource() } else internalInteractionSource

    // Auto-disable internal clickable if we are inside a SettingsGroupItem that handles the interaction source,
    // or if we have an explicit onRowClick.
    val actualClickable = if (onRowClick != null || isSplit) false else if (parentInteractionSource != null && interactionSource == null) false else clickable

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
    ) {
        val leftPartModifier = Modifier
            .weight(1f)
            .let { m ->
                if (onRowClick != null) {
                    m.clickable(
                        enabled = enabled,
                        onClick = onRowClick
                    )
                } else if (actualClickable) {
                    m.clickable(
                        interactionSource = internalInteractionSource,
                        indication = null,
                        enabled = enabled,
                        onClick = { onCheckedChange(!checked) }
                    )
                } else m
            }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = leftPartModifier
        ) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                if (leadingIcon != null) {
                    if (useLargeIcon) {
                        LargeLeadingIcon(content = leadingIcon)
                    } else {
                        StandardLeadingIcon(size = leadingIconSize, content = leadingIcon)
                    }
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
        }

        if (onRowClick != null || isSplit) {
            Spacer(Modifier.width(12.dp))
            // Forward Arrow
            Icon(
                painter = painterResource(R.drawable.arrow_forward_ios_24px),
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(11.dp))
            // Vertical Divider
            VerticalDivider(
                modifier = Modifier.height(39.dp),
                thickness = 1.5.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f)
            )
            Spacer(Modifier.width(12.dp))
        } else {
            Spacer(Modifier.width(16.dp))
        }

        ConfigSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            interactionSource = switchInteractionSource
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
    leadingIconSize: Dp = 24.dp,
    useLargeIcon: Boolean = false,
    trailingIcon: @Composable (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    isModified: Boolean = false,
    onHelpClick: (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    val context = LocalContext.current
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
                    onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        onHelpClick()
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.info_24px),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            readOnly = readOnly,
            isError = isError,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            placeholder = { Text(placeholder) },
            leadingIcon = leadingIcon?.let {
                {
                    if (useLargeIcon) {
                        LargeLeadingIcon(content = it)
                    } else {
                        StandardLeadingIcon(size = leadingIconSize, content = it)
                    }
                }
            },
            trailingIcon = trailingIcon,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            shape = RoundedCornerShape(8.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                errorContainerColor = Color.Transparent,
                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant,
                disabledIndicatorColor = Color.Transparent,
                errorIndicatorColor = MaterialTheme.colorScheme.error,
                unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        )
        if (supportingText?.isNotEmpty() ?: false) {
            Spacer(Modifier.height(2.dp))
            SupportingText(text = supportingText)
        }
    }
}

@Composable
fun ConfigDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 4.dp,
        tonalElevation = 4.dp,
        shape = MaterialTheme.shapes.medium,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
        modifier = modifier
    ) {
        if (!title.isNullOrBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
        content()
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
        HistoryDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            history = history,
            onSelect = onSelect,
            onRemove = onRemove,
            privacyMode = privacyMode
        )
    }
}

@Composable
fun HistoryDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    history: List<String>,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    privacyMode: Boolean,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.history_label)
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    ConfigDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        title = title,
        modifier = modifier
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
                    onDismissRequest()
                }
            )
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
            LargeLeadingIcon {
                val targetContentColor = MaterialTheme.colorScheme.onSurface

                when (state) {
                    is UpdateState.Checking, is UpdateState.Downloading -> {
                        androidx.compose.material3.LoadingIndicator(
                            modifier = Modifier.size(40.dp),
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
                            modifier = Modifier.size(32.dp),
                            tint = if (state is UpdateState.Error) MaterialTheme.colorScheme.error
                                   else targetContentColor
                        )
                    }
                }
            }

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

@Composable
fun ConfigTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable (RowScope.() -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    expandedHeight: Dp = if (subtitle != null) 254.dp else 188.dp,
    collapsedHeight: Dp = TopAppBarDefaults.LargeAppBarCollapsedHeight,
    containerColor: Color = Color.Unspecified,
    startCollapsed: Boolean = true,
) {
    if (startCollapsed && scrollBehavior != null) {
        LaunchedEffect(scrollBehavior.state.heightOffsetLimit) {
            if (scrollBehavior.state.heightOffsetLimit != 0f) {
                scrollBehavior.state.heightOffset = scrollBehavior.state.heightOffsetLimit
            }
        }
    }

    val screenBackgroundColor = containerColor.takeOrElse {
        if (LocalIsDark.current) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLow
    }

    val annotatedTitle = remember(title) {

        buildAnnotatedString {
            val parts = title.split("**")
            if (parts.size == 3) {
                append(parts[0])
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(parts[1])
                }
                append(parts[2])
            } else {
                append(title)
            }
        }
    }

    val collapsedTitle = remember(title) {
        title.replace("**", "")
    }

    TwoRowsTopAppBar(
        expandedHeight = expandedHeight,
        collapsedHeight = collapsedHeight,
        title = { isExpanded ->
            Text(
                text = if (isExpanded) annotatedTitle else buildAnnotatedString { append(collapsedTitle) },
                style = if (isExpanded) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .padding(bottom = if (isExpanded) 28.dp else 0.dp)
                    .padding(top = if (isExpanded) 28.dp else 0.dp)
            )
        },
        subtitle = { isExpanded ->
            if (isExpanded && subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .padding(bottom = 28.dp)
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
        navigationIcon = {
            if (onBack != null) {
                FilledTonalIconButton(
                    onClick = onBack,
                    modifier = Modifier.padding(start = 15.dp, end = 3.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Icon(
                        painterResource(R.drawable.arrow_back_24px),
                        contentDescription = null
                    )
                }
            }
        },
        actions = { actions?.invoke(this) },
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = screenBackgroundColor,
            scrolledContainerColor = screenBackgroundColor,
            subtitleContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}

@Composable
fun <T> SelectionDialog(
    title: String,
    items: List<T>,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    isSelected: (T) -> Boolean,
    itemContent: @Composable (T, Boolean) -> Unit
) {
    val context = LocalContext.current

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.fillMaxWidth(0.9f)
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .padding(vertical = 24.dp, horizontal = 12.dp)
                    .heightIn(max = 600.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = if (description != null) 4.dp else 16.dp, start = 12.dp)
                )
                if (description != null) {
                    SupportingText(
                        text = description,
                        modifier = Modifier.padding(bottom = 16.dp, start = 12.dp)
                    )
                }
                items.forEachIndexed { index, item ->
                    val selected = isSelected(item)
                    val shape = when {
                        items.size == 1 -> MaterialTheme.shapes.medium
                        index == 0 -> RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
                        index == items.size - 1 -> RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
                        else -> RoundedCornerShape(4.dp)
                    }

                    Surface(
                        onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            onSelect(item)
                            onDismiss()
                        },
                        shape = shape,
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                    ) {
                        itemContent(item, selected)
                    }
                }
            }
        }
    }
}
