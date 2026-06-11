@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonGroupScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Slider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.TwoRowsTopAppBar
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.core.net.toUri
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.wireturn.app.R
import com.wireturn.app.viewmodel.UpdateState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/**
 * Local provider for interaction source to coordinate ripples between parent blocks and children.
 */
val LocalSettingsInteractionSource = compositionLocalOf<MutableInteractionSource?> { null }

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
fun ModifiedIndicator(isModified: Boolean, modifier: Modifier = Modifier) {
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
    if (!enabled) return this
    return " ".repeat(this.length.coerceAtLeast(12))
}

/**
 * Telegram-style spoiler effect with magic sparks.
 */
@Composable
fun Modifier.privacySpoiler(
    enabled: Boolean,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
    sparkColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
): Modifier {
    if (!enabled) return this

    val infiniteTransition = rememberInfiniteTransition(label = "privacySpoiler")
    val t by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "time"
    )

    return this.drawWithContent {
        if (enabled) {
            // Background mask
            drawRect(color = containerColor)
            
            // Стабильный seed на основе размера, чтобы не было мерцания при микро-изменениях size
            val seed = (size.width.toInt() shl 16) xor size.height.toInt()
            val random = Random(seed.toLong() + 42L)
            
            // Оптимизированная плотность: 1 искра на ~40 пикселей (по площади)
            // Этого достаточно для создания густого эффекта, но гораздо легче для GPU
            val count = (size.width * size.height / 40).toInt().coerceIn(60, 500)
            
            clipRect {
                repeat(count) {
                    val x = random.nextFloat() * size.width
                    val y = random.nextFloat() * size.height
                    
                    val individualPhase = random.nextFloat()
                    // Используем только целые множители скорости (1 или 2), 
                    // чтобы анимация была идеально бесшовной при t=0 и t=1
                    val speedScale = if (random.nextBoolean()) 1f else 2f
                    val particleT = (t * speedScale + individualPhase) % 1f
                    
                    // Плавное мерцание (синус от 0 до PI всегда возвращается в 0)
                    val alphaScale = kotlin.math.sin(particleT * Math.PI).toFloat()
                    
                    val radiusX = 1.5.dp.toPx() + random.nextFloat() * 2.5.dp.toPx()
                    val radiusY = 1.5.dp.toPx() + random.nextFloat() * 2.5.dp.toPx()
                    
                    // Рандомизируем направление вращения и тип траектории
                    val dirX = if (random.nextBoolean()) 1f else -1f
                    val dirY = if (random.nextBoolean()) 1f else -1f
                    val movementType = random.nextInt(3)
                    
                    val (offsetX, offsetY) = when (movementType) {
                        0 -> { // Эллипс
                            kotlin.math.sin(particleT * 2 * Math.PI).toFloat() * radiusX * dirX to
                            kotlin.math.cos(particleT * 2 * Math.PI).toFloat() * radiusY * dirY
                        }
                        1 -> { // Восьмерка
                            kotlin.math.sin(particleT * 2 * Math.PI).toFloat() * radiusX * dirX to
                            kotlin.math.sin(particleT * 4 * Math.PI).toFloat() * radiusY * dirY
                        }
                        else -> { // Колебание по диагонали
                            val angle = individualPhase * 2 * Math.PI
                            val dist = kotlin.math.sin(particleT * 2 * Math.PI).toFloat() * radiusX
                            kotlin.math.cos(angle).toFloat() * dist * dirX to kotlin.math.sin(angle).toFloat() * dist * dirY
                        }
                    }
                    
                    drawCircle(
                        color = sparkColor.copy(alpha = alphaScale * sparkColor.alpha),
                        radius = (random.nextFloat() * 1.2.dp.toPx()).coerceAtLeast(0.7.dp.toPx()),
                        center = Offset(x + offsetX, y + offsetY)
                    )
                }
            }
        } else {
            drawContent()
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, isModified: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(bottom = 12.dp, top = 8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
        ModifiedIndicator(isModified)
    }
}

/**
 * Shared label for settings rows.
 */
@Composable
fun RowLabel(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    color: Color = Color.Unspecified,
    isModified: Boolean = false
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        VerticalAnimatedText(
            text = text,
            style = style,
            color = color.takeOrElse { MaterialTheme.colorScheme.onSurface },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        ModifiedIndicator(isModified)
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
    contentAlignment: Alignment = Alignment.CenterStart,
    privacyMode: Boolean = false
) {
    val transitionState = remember { MutableTransitionState(text) }
    transitionState.targetState = text
    val transition = rememberTransition(transitionState, label = "vertical_animated_text")

    transition.AnimatedContent(
        transitionSpec = {
            if (transitionState.currentState == transitionState.targetState) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                (fadeIn(animationSpec = tween(220), initialAlpha = 0.2f) +
                        slideInVertically(initialOffsetY = { h -> h / 2 }))
                    .togetherWith(fadeOut(animationSpec = tween(160)) +
                            slideOutVertically(targetOffsetY = { h -> -h / 2 }))
            }
        },
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
            maxLines = maxLines,
            modifier = Modifier.privacySpoiler(privacyMode)
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
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign: TextAlign? = null,
    contentAlignment: Alignment = Alignment.CenterStart
) {
    if (text.isNullOrBlank()) return

    VerticalAnimatedText(
        text = text,
        style = style,
        color = color,
        modifier = modifier,
        textAlign = textAlign,
        contentAlignment = contentAlignment
    )
}

/**
 * A reusable container for sections that expand and collapse with a smooth transition.
 * Uses MutableTransitionState to avoid initial flicker on screen start.
 */
@Composable
fun ExpandableSection(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val visibleState = remember { MutableTransitionState(visible) }
    visibleState.targetState = visible

    AnimatedVisibility(
        visibleState = visibleState,
        enter = fadeIn(tween(300)) + expandVertically(tween(300)),
        exit = fadeOut(tween(300)) + shrinkVertically(tween(300)),
        modifier = modifier
    ) {
        content()
    }
}

@Composable
fun SectionGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    isModified: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier) {
        if (!title.isNullOrBlank()) {
            Box(modifier = Modifier.padding(start = 8.dp)) {
                SectionHeader(title = title, isModified = isModified)
            }
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

enum class ItemPosition {
    Top, Middle, Bottom, Single
}

@Composable
fun SectionItem(
    modifier: Modifier = Modifier,
    position: ItemPosition = ItemPosition.Middle,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit
) {
    val isTop = position == ItemPosition.Top || position == ItemPosition.Single
    val isBottom = position == ItemPosition.Bottom || position == ItemPosition.Single

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
fun MainSwitchItem(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
) {
    val containerColor = MaterialTheme.colorScheme.primaryContainer
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer

    val interactionSource = remember { MutableInteractionSource() }

    CompositionLocalProvider(LocalSettingsInteractionSource provides interactionSource) {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = CircleShape,
            onClick = { onCheckedChange(!checked) },
            enabled = enabled,
            interactionSource = interactionSource,
            colors = CardDefaults.cardColors(
                containerColor = containerColor,
                contentColor = contentColor
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    RowLabel(
                        text = label,
                        modifier = Modifier.padding(start = 12.dp),
                        color = contentColor
                    )
                    if (supportingText != null) {
                        SupportingText(
                            text = supportingText,
                            color = contentColor.copy(alpha = 0.7f),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }

                IconSwitch(
                    checked = checked,
                    onCheckedChange = null,
                    enabled = enabled
                )
            }
        }
    }
}


@Composable
fun CompactItem(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
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
            RowLabel(text = label, isModified = isModified, modifier = Modifier.weight(1f))
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
                    RowLabel(text = label, isModified = isModified)
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
    privacyMode: Boolean = false
) {
    val context = LocalContext.current
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
            trailingIcon = trailingIcon,
            visualTransformation = if (privacyMode) PasswordVisualTransformation() else visualTransformation,
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
fun AppDropdownMenu(
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

    AppDropdownMenu(
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
                            delay(1500.milliseconds)
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
                RowLabel(text = titleText)
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
                        FilledTonalIconButton(
                            onClick = { onCheck.invoke() }
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.refresh_24px),
                                contentDescription = stringResource(R.string.update_tap_to_check)
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
fun TopAppBarScrollBehavior.noFlingExpandConnection(): NestedScrollConnection {
    val inner = nestedScrollConnection
    return remember(inner) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (source == NestedScrollSource.SideEffect && available.y > 0f) Offset.Zero
                else inner.onPreScroll(available, source)

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (source == NestedScrollSource.SideEffect && available.y > 0f) Offset.Zero
                else inner.onPostScroll(consumed, available, source)

            override suspend fun onPreFling(available: Velocity): Velocity = inner.onPreFling(available)
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = inner.onPostFling(consumed, available)
        }
    }
}

@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
fun AppTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable (RowScope.() -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    expandedHeight: Dp = Dp.Unspecified,
    collapsedHeight: Dp = TopAppBarDefaults.LargeAppBarCollapsedHeight - 8.dp,
    containerColor: Color = Color.Unspecified,
    startCollapsed: Boolean = true,
) {
    val screenBackgroundColor = containerColor.takeOrElse {
        MaterialTheme.colorScheme.background
    }

    val annotatedTitle = remember(title) {
        buildAnnotatedString {
            append(title)
        }
    }

    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    val configuration = LocalConfiguration.current
    val textMeasurer = rememberTextMeasurer()
    val titleStyle = MaterialTheme.typography.headlineLarge

    val isMultiLine = remember(annotatedTitle, windowInfo.containerSize.width, configuration.screenWidthDp) {
        val width = windowInfo.containerSize.width.takeIf { it > 0 }
            ?: with(density) { configuration.screenWidthDp.dp.toPx() }.toInt()
        val horizontalPadding = with(density) { 32.dp.toPx() }.toInt()
        val availableWidth = width - horizontalPadding
        val measuredText = textMeasurer.measure(
            text = annotatedTitle,
            style = titleStyle,
            constraints = Constraints(maxWidth = availableWidth)
        )
        measuredText.lineCount > 1
    }

    val finalExpandedHeight = remember(isMultiLine, subtitle, expandedHeight) {
        if (expandedHeight != Dp.Unspecified) return@remember expandedHeight
        
        when {
            subtitle != null -> if (isMultiLine) 262.dp else 222.dp
            else -> if (isMultiLine) 212.dp else 179.dp
        }
    }

    if (startCollapsed && scrollBehavior != null) {
        LaunchedEffect(scrollBehavior.state.heightOffsetLimit, finalExpandedHeight) {
            if (scrollBehavior.state.heightOffsetLimit != 0f) {
                scrollBehavior.state.heightOffset = scrollBehavior.state.heightOffsetLimit
            }
        }
    }

    TwoRowsTopAppBar(
        expandedHeight = finalExpandedHeight,
        collapsedHeight = collapsedHeight,
        title = { isExpanded ->
            Text(
                text = annotatedTitle,
                style = if (isExpanded) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleLarge,
                maxLines = if (isExpanded) Int.MAX_VALUE else 1,
                overflow = if (isExpanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .padding(top = if (isExpanded) 28.dp else 0.dp)
                    .padding(bottom = if (isExpanded) 24.dp else 0.dp)
            )
        },
        subtitle = { isExpanded ->
            if (isExpanded && subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .padding(bottom = 24.dp)
                )
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        // Перехватываем события на начальной фазе (до того, как их увидит AppBar)
                        val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        
                        event.changes.forEach { change ->
                            // Если палец движется (драг), поглощаем это движение.
                            // Мы не трогаем Down и Up, чтобы клики по кнопкам продолжали работать.
                            val dragAmount = change.position - change.previousPosition
                            if (dragAmount.getDistanceSquared() > 0.1f) {
                                change.consume()
                            }
                        }
                    }
                }
            },
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
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface,
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
    dismissOnSelect: Boolean = true,
    isSelected: (T) -> Boolean,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    itemContent: @Composable (T, Boolean) -> Unit
) {
    val context = LocalContext.current

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.fillMaxWidth(0.9f)
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(
                modifier = Modifier
                    .padding(vertical = 24.dp, horizontal = 12.dp)
                    .heightIn(max = 600.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = if (description != null) 8.dp else 16.dp)
                )
                if (description != null) {
                    SupportingText(
                        text = description,
                        textAlign = TextAlign.Center,
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                            .padding(horizontal = 12.dp)
                    )
                }
                items.forEach { item ->
                    val selected = isSelected(item)
                    val shape = MaterialTheme.shapes.extraLarge

                    Surface(
                        onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            onSelect(item)
                            if (dismissOnSelect) onDismiss()
                        },
                        shape = shape,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                    ) {
                        CompositionLocalProvider(
                            LocalContentColor provides if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        ) {
                            ProvideTextStyle(value = MaterialTheme.typography.titleMedium) {
                                itemContent(item, selected)
                            }
                        }
                    }
                }
                footer?.invoke(this)
            }
        }
    }
}

@Composable
fun AppSnackbar(
    data: SnackbarData,
    modifier: Modifier = Modifier
) {
    Snackbar(
        modifier = modifier
            .padding(12.dp)
            .padding(bottom = 16.dp),
        action = data.visuals.actionLabel?.let { label ->
            {
                TextButton(onClick = { data.performAction() }) {
                    Text(label)
                }
            }
        },
        dismissAction = {
            IconButton(onClick = { data.dismiss() }) {
                Icon(painterResource(R.drawable.close_24px), contentDescription = null)
            }
        }
    ) {
        Text(data.visuals.message)
    }
}

@Composable
fun ShareDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    textToShare: String,
    onShowQr: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    AppDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.show_qr_code)) },
            onClick = {
                onDismissRequest()
                onShowQr()
            },
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.qr_code_24px),
                    contentDescription = null
                )
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.share_profile)) },
            onClick = {
                onDismissRequest()
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, textToShare)
                }
                context.startActivity(Intent.createChooser(intent, null))
            },
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.share_24px),
                    contentDescription = null
                )
            }
        )
    }
}

@Composable
fun QrCodeDialog(
    text: String,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.qr_import)
) {
    val bitmap = remember(text) { generateQrCode(text) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_close))
            }
        },
        title = {
            Text(
                text = title,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (bitmap != null) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color.White,
                        modifier = Modifier
                            .size(260.dp)
                            .padding(8.dp)
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    )
}

private fun generateQrCode(text: String, size: Int = 512): Bitmap? {
    return try {
        val bitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bitmap = createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap[x, y] =
                    if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
        }
        bitmap
    } catch (_: Exception) {
        null
    }
}
