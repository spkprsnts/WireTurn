@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonGroupScope
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.set
import com.wireturn.app.R
import com.wireturn.app.ui.theme.ContentAlpha
import kotlin.math.roundToInt

/**
 * Inline indicator for rows, usually placed after a text label.
 */
@Composable
fun IconSwitch(
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
                Crossfade(targetState = checked, animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(), label = "switch_icon") { isChecked ->
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
            RowLabel(text = label, isModified = isModified, modifier = Modifier.weight(1f))
            Surface(
                onClick = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    showDialog.value = true
                },
                shape = MaterialTheme.shapes.small,
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
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = ContentAlpha.secondary)
                    )
                }
            }
        }
        // SliderState isn't keyed on `value` itself (only steps/valueRange) since it's meant to
        // own the value once created - re-set it every recomposition so this stays a fully
        // controlled component, same as the old value/onValueChange overload did internally.
        val sliderState = remember(steps, valueRange) { SliderState(value, steps, valueRange) }
        sliderState.value = value
        Slider(
            state = sliderState,
            onValueChange = onValueChange,
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
                    RowLabel(
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
            expandedRatio = 0.1f,
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
fun ButtonGroupScope.selectableButtonItem(
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
                    .weight(weight)
                    .animateWidth(interactionSource),
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
    isSplit: Boolean = false,
    labelMarquee: Boolean = false,
    labelPrefix: String? = null,
    supportingSingleLine: Boolean = false
) {
    val parentInteractionSource = LocalSettingsInteractionSource.current
    val internalInteractionSource = interactionSource ?: parentInteractionSource ?: remember { MutableInteractionSource() }
    
    // Decouple switch interaction from parent if in split mode to avoid whole block ripple on toggle
    val switchInteractionSource = if (onRowClick != null || isSplit) remember { MutableInteractionSource() } else internalInteractionSource

    // Auto-disable internal clickable if we are inside a SettingsGroupItem that handles the interaction source,
    // or if we have an explicit onRowClick.
    val actualClickable = onRowClick == null && !isSplit &&
        (parentInteractionSource == null || interactionSource != null) && clickable

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

                LabelGroup(
                    label = label,
                    modifier = Modifier.weight(1f),
                    supportingText = supportingText,
                    isModified = isModified,
                    labelMarquee = labelMarquee,
                    labelPrefix = labelPrefix,
                    supportingSingleLine = supportingSingleLine
                )
            }
        }

        if (onRowClick != null || isSplit) {
            TappableRowIndicator()
        } else {
            Spacer(Modifier.width(16.dp))
        }

        if (trailingContent != null) {
            trailingContent()
            Spacer(Modifier.width(16.dp))
        }

        IconSwitch(
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
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    privacyMode: Boolean = false,
    // Owns its own reveal/hide toggle (eye icon + PasswordVisualTransformation) instead of the
    // caller wiring trailingIcon/visualTransformation by hand for the same thing - mutually
    // exclusive with passing a custom trailingIcon/visualTransformation, which this ignores.
    isSecret: Boolean = false
) {
    val context = LocalContext.current
    var secretRevealed by rememberSaveable { mutableStateOf(false) }
    val effectiveTrailingIcon: (@Composable () -> Unit)? = if (isSecret) {
        {
            if (!privacyMode) {
                IconButton(onClick = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    secretRevealed = !secretRevealed
                }) {
                    Icon(
                        painter = painterResource(
                            if (secretRevealed) R.drawable.visibility_24px else R.drawable.visibility_off_24px
                        ),
                        contentDescription = null
                    )
                }
            }
        }
    } else trailingIcon
    val effectiveVisualTransformation = if (isSecret && !secretRevealed) {
        PasswordVisualTransformation()
    } else {
        visualTransformation
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            RowLabel(
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
                .padding(vertical = 4.dp)
                .privacySpoiler(privacyMode),
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
            trailingIcon = effectiveTrailingIcon,
            visualTransformation = if (privacyMode) PasswordVisualTransformation() else effectiveVisualTransformation,
            keyboardOptions = keyboardOptions,
            shape = MaterialTheme.shapes.small,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                errorContainerColor = Color.Transparent,
                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant,
                disabledIndicatorColor = Color.Transparent,
                errorIndicatorColor = MaterialTheme.colorScheme.error,
                unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ContentAlpha.secondary),
                focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ContentAlpha.secondary)
            )
        )
        if (supportingText?.isNotEmpty() ?: false) {
            Spacer(Modifier.height(2.dp))
            SupportingText(text = supportingText)
        }
    }
}
