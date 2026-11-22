package com.wireturn.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wireturn.app.R
import com.wireturn.app.ui.theme.ContentAlpha
import com.wireturn.app.ui.theme.GroupCardCorners

/**
 * Local provider for interaction source to coordinate ripples between parent blocks and children.
 */
val LocalSettingsInteractionSource = compositionLocalOf<MutableInteractionSource?> { null }

/**
 * Provided by a clickable [SectionItem]: a [SwitchRow] inside, whose taps the item handles, reports
 * its state through it. The item is that row's one accessibility node (its clickable merges the
 * label), and a merged child's role is dropped - so the item itself announces "switch, on/off".
 * null outside of a clickable item.
 */
val LocalSectionToggle = compositionLocalOf<((Boolean?) -> Unit)?> { null }

// The role and state a clickable item announces for the switch row it holds, if any.
private fun Modifier.switchSemantics(checked: Boolean?): Modifier =
    if (checked == null) this
    else semantics {
        role = Role.Switch
        toggleableState = ToggleableState(checked)
    }

// Card's DisabledAlpha (internal in material3) - the content color a disabled card gives.
private const val DISABLED_CARD_CONTENT_ALPHA = 0.38f

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
    isModified: Boolean = false,
    marquee: Boolean = false,
    prefix: String? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        // prefix stays fixed in place even when the main text scrolls via marquee - e.g. "Xray"
        // never moves while a long protocol name like "SOCKS5 / VLESS" scrolls next to it.
        if (!prefix.isNullOrEmpty()) {
            Text(
                text = prefix,
                style = style,
                color = color.takeOrElse { MaterialTheme.colorScheme.onSurface },
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip
            )
            if (text.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
            }
        }
        if (text.isNotEmpty()) {
            VerticalAnimatedText(
                text = text,
                style = style,
                color = color.takeOrElse { MaterialTheme.colorScheme.onSurface },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // fill = false: only clamp to the leftover space (so marquee kicks in exactly
                // when it stops fitting there) without force-stretching short text to fill it.
                modifier = if (marquee) Modifier.weight(1f, fill = false) else Modifier,
                textModifier = if (marquee) {
                    Modifier.basicMarquee(velocity = 20.dp, initialDelayMillis = 2_500)
                } else {
                    Modifier
                }
            )
        }
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
    privacyMode: Boolean = false,
    textModifier: Modifier = Modifier
) {
    val transitionState = remember { MutableTransitionState(text) }
    transitionState.targetState = text
    val transition = rememberTransition(transitionState, label = "vertical_animated_text")
    val fadeSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()

    transition.AnimatedContent(
        transitionSpec = {
            if (transitionState.currentState == transitionState.targetState) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                (fadeIn(animationSpec = fadeSpec, initialAlpha = 0.2f) +
                        slideInVertically(initialOffsetY = { h -> h / 2 }))
                    .togetherWith(
                        fadeOut(animationSpec = fadeSpec) +
                            slideOutVertically(targetOffsetY = { h -> -h / 2 })
                    )
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
            modifier = textModifier.privacySpoiler(privacyMode)
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
    contentAlignment: Alignment = Alignment.CenterStart,
    singleLine: Boolean = false
) {
    if (text.isNullOrBlank()) return

    VerticalAnimatedText(
        text = text,
        style = style,
        color = color,
        modifier = modifier,
        textAlign = textAlign,
        contentAlignment = contentAlignment,
        maxLines = if (singleLine) 1 else Int.MAX_VALUE,
        softWrap = !singleLine,
        overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip
    )
}

/**
 * Shared with call sites that build their own label+supporting-text layout instead of using
 * [LabelGroup] directly (e.g. composite supporting content, inverted label/indicator placement),
 * so the gap between the two lines stays consistent across the app either way.
 */
object LabelGroupDefaults {
    val SupportingGap = 2.5.dp
}

/**
 * A combined component for a label and its supporting text.
 */
@Composable
fun LabelGroup(
    label: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    isModified: Boolean = false,
    labelStyle: TextStyle = MaterialTheme.typography.titleMedium,
    supportingStyle: TextStyle = MaterialTheme.typography.bodyMedium.copy(lineHeight = 18.sp),
    labelColor: Color = Color.Unspecified,
    supportingColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    labelMarquee: Boolean = false,
    labelPrefix: String? = null,
    supportingSingleLine: Boolean = false
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.Center) {
        RowLabel(
            text = label,
            isModified = isModified,
            style = labelStyle,
            color = labelColor,
            marquee = labelMarquee,
            prefix = labelPrefix
        )
        if (!supportingText.isNullOrBlank()) {
            Spacer(Modifier.height(LabelGroupDefaults.SupportingGap))
            SupportingText(
                text = supportingText,
                style = supportingStyle,
                color = supportingColor,
                singleLine = supportingSingleLine
            )
        }
    }
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
        enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) + expandVertically(
            MaterialTheme.motionScheme.defaultSpatialSpec()
        ),
        exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()) + shrinkVertically(
            MaterialTheme.motionScheme.defaultSpatialSpec()
        ),
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

    val topRadius by animateDpAsState(
        targetValue = if (isTop) GroupCardCorners.outer else GroupCardCorners.inner,
        label = "top_corner"
    )
    val bottomRadius by animateDpAsState(
        targetValue = if (isBottom) GroupCardCorners.outer else GroupCardCorners.inner,
        label = "bottom_corner"
    )

    val shape = RoundedCornerShape(
        topStart = topRadius,
        topEnd = topRadius,
        bottomStart = bottomRadius,
        bottomEnd = bottomRadius
    )

    val internalInteractionSource = interactionSource ?: LocalSettingsInteractionSource.current
    ?: remember { MutableInteractionSource() }

    val body: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .let { if (!enabled) it.alpha(ContentAlpha.disabled) else it },
            contentAlignment = Alignment.CenterStart
        ) {
            content()
        }
    }
    val cardModifier = modifier
        .fillMaxWidth()
        .heightIn(min = 72.dp)

    CompositionLocalProvider(LocalSettingsInteractionSource provides internalInteractionSource) {
        if (onClick != null) {
            var switchChecked by remember { mutableStateOf<Boolean?>(null) }
            val reportSwitch = remember { { checked: Boolean? -> switchChecked = checked } }
            CompositionLocalProvider(LocalSectionToggle provides reportSwitch) {
                Card(
                    modifier = cardModifier.switchSemantics(switchChecked),
                    shape = shape,
                    onClick = onClick,
                    enabled = enabled,
                    interactionSource = internalInteractionSource,
                    colors = CardDefaults.cardColors(
                        containerColor = containerColor,
                        disabledContainerColor = containerColor.copy(alpha = 0.5f)
                    ),
                    content = { body() }
                )
            }
        } else {
            // Not clickable: a plain card, not a disabled clickable one - that one told
            // accessibility services every such row was disabled. The colors stay what the
            // disabled card drew, content color included.
            CompositionLocalProvider(LocalSectionToggle provides null) {
                Card(
                    modifier = cardModifier,
                    shape = shape,
                    colors = CardDefaults.cardColors(
                        containerColor = if (enabled) containerColor else containerColor.copy(alpha = 0.5f),
                        contentColor = contentColorFor(containerColor).copy(alpha = DISABLED_CARD_CONTENT_ALPHA)
                    ),
                    content = { body() }
                )
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
            // The card is the switch - the one below takes no input of its own.
            modifier = modifier.fillMaxWidth().switchSemantics(checked),
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
                LabelGroup(
                    label = label,
                    modifier = Modifier.padding(start = 12.dp),
                    labelColor = contentColor,
                    supportingText = supportingText,
                    supportingColor = contentColor.copy(alpha = ContentAlpha.secondary)
                )

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
    val internalInteractionSource = interactionSource ?: LocalSettingsInteractionSource.current
    ?: remember { MutableInteractionSource() }

    val body: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .let { if (!enabled) it.alpha(ContentAlpha.disabled) else it },
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }

    CompositionLocalProvider(LocalSettingsInteractionSource provides internalInteractionSource) {
        if (onClick != null) {
            Card(
                modifier = modifier,
                shape = CircleShape,
                onClick = onClick,
                enabled = enabled,
                interactionSource = internalInteractionSource,
                colors = CardDefaults.cardColors(
                    containerColor = containerColor,
                    disabledContainerColor = containerColor.copy(alpha = 0.5f)
                ),
                content = { body() }
            )
        } else {
            // A plain card, as in SectionItem.
            Card(
                modifier = modifier,
                shape = CircleShape,
                colors = CardDefaults.cardColors(
                    containerColor = if (enabled) containerColor else containerColor.copy(alpha = 0.5f),
                    contentColor = contentColorFor(containerColor).copy(alpha = DISABLED_CARD_CONTENT_ALPHA)
                ),
                content = { body() }
            )
        }
    }
}

// Forward-arrow + vertical divider hinting that a row is itself tappable to navigate somewhere -
// shared by SwitchRow's isSplit/onRowClick (Xray/VPN mode rows) and HomeScreen's profile card.
@Composable
fun TappableRowIndicator(modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(12.dp))
        Icon(
            painter = painterResource(R.drawable.arrow_forward_ios_24px),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        VerticalDivider(
            modifier = Modifier.height(40.dp),
            thickness = 1.1.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f)
        )
        Spacer(Modifier.width(12.dp))
    }
}
