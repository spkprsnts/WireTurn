@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.graphics.shapes.Morph
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.CoreServiceState
import com.wireturn.app.R
import com.wireturn.app.XrayServiceState
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.VerticalAnimatedText
import com.wireturn.app.viewmodel.MainViewModel
import com.wireturn.app.viewmodel.CoreState
import com.wireturn.app.viewmodel.XrayState
import kotlinx.coroutines.delay

@Composable
fun CoreToggleButton(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    size: Dp = 160.dp,
    shape: Shape? = null,
    isFloat: Boolean = false,
    isVisible: Boolean = true,
    statusAlignment: Alignment.Horizontal = Alignment.End,
    onClick: () -> Unit
) {
    val coreState by viewModel.coreState.collectAsStateWithLifecycle()
    val xrayState by XrayServiceState.state.collectAsStateWithLifecycle()
    val xrayConfig by viewModel.xrayConfig.collectAsStateWithLifecycle()
    val autoLaunchSettings by viewModel.autoLaunchSettings.collectAsStateWithLifecycle()

    val isRestarting by CoreServiceState.isRestarting.collectAsStateWithLifecycle()

    var wasActiveBeforeRestart by remember { mutableStateOf(false) }

    val isXrayWorking = xrayState == XrayState.Running || xrayState == XrayState.DirectRoute
    val isCoreActuallyConnected = coreState is CoreState.Connected || (coreState is CoreState.Suppressed && isXrayWorking)
    val actuallyRestarting = isRestarting || (wasActiveBeforeRestart && isCoreActuallyConnected)

    val toggleState = remember(coreState, xrayState, actuallyRestarting, xrayConfig.enabled) {
        when {
            actuallyRestarting || 
            coreState is CoreState.Starting ||
            coreState is CoreState.Connecting ||
            coreState is CoreState.Stopping ||
            coreState is CoreState.CaptchaRequired ||
            coreState is CoreState.WaitingForNetwork ||
            (coreState is CoreState.Suppressed && !isXrayWorking) -> "loading"
            
            coreState is CoreState.Error -> "error"
            
            (xrayConfig.enabled && !isXrayWorking && coreState !is CoreState.Idle) -> "loading"

            isCoreActuallyConnected -> "active"
            else -> "idle"
        }
    }

    LaunchedEffect(isRestarting, isCoreActuallyConnected) {
        if (isRestarting) {
            if (isCoreActuallyConnected || coreState is CoreState.Starting || coreState is CoreState.Connecting) {
                wasActiveBeforeRestart = true
            }
        } else if (wasActiveBeforeRestart) {
            if (!isCoreActuallyConnected) {
                wasActiveBeforeRestart = false
            } else {
                // Если мы все еще в состоянии Connected после завершения флага перезапуска,
                // ждем реального изменения состояния или сбрасываем по таймауту,
                // чтобы "Перезапуск" не висел вечно.
                delay(1000)
                wasActiveBeforeRestart = false
            }
        }
    }

    val context = LocalContext.current
    var isInitialComposition by remember { mutableStateOf(true) }
    LaunchedEffect(toggleState) {
        if (isInitialComposition) {
            isInitialComposition = false
            return@LaunchedEffect
        }
        when (toggleState) {
            "active" -> HapticUtil.perform(context, HapticUtil.Pattern.SUCCESS)
            "error" -> HapticUtil.perform(context, HapticUtil.Pattern.ERROR)
        }
    }

    val statusText = when {
        actuallyRestarting -> stringResource(R.string.core_restarting)
        coreState is CoreState.Connected -> {
            if (xrayState == XrayState.DirectRoute) stringResource(R.string.vless_direct_active)
            else stringResource(R.string.core_active)
        }
        coreState is CoreState.Starting -> stringResource(R.string.starting)
        coreState is CoreState.Stopping -> stringResource(R.string.stopping)
        coreState is CoreState.Connecting -> stringResource(R.string.connecting)
        coreState is CoreState.Suppressed -> {
            if (xrayState == XrayState.DirectRoute) stringResource(R.string.vless_direct_active)
            else stringResource(R.string.connecting)
        }
        coreState is CoreState.CaptchaRequired -> stringResource(R.string.core_captcha_required)
        coreState is CoreState.WaitingForNetwork -> stringResource(R.string.status_waiting_for_network)
        coreState is CoreState.Error -> (coreState as CoreState.Error).message
        else -> if (autoLaunchSettings.enabled) stringResource(R.string.core_auto_launch_active) else stringResource(R.string.core_press_to_start)
    }

    val statusColor by animateColorAsState(
        targetValue = when (toggleState) {
            "active" -> MaterialTheme.colorScheme.primary
            "loading" -> MaterialTheme.colorScheme.tertiary
            "error" -> MaterialTheme.colorScheme.error
            else -> if (autoLaunchSettings.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
        },
        label = "status_color"
    )

    var showTooltip by remember { mutableStateOf(false) }
    var previousToggleState by remember { mutableStateOf<String?>(null) }
    var lastActiveStatusText by remember { mutableStateOf(statusText) }

    LaunchedEffect(statusText) {
        if (toggleState != "idle") {
            lastActiveStatusText = statusText
        }
    }

    LaunchedEffect(toggleState, statusText, isVisible) {
        if (!isVisible || toggleState == "idle") {
            showTooltip = false
            previousToggleState = toggleState
            return@LaunchedEffect
        }

        if (isFloat && previousToggleState != null && (previousToggleState != toggleState || lastActiveStatusText != statusText)) {
            showTooltip = true
        }
        previousToggleState = toggleState

        if (showTooltip) {
            delay(5000)
            showTooltip = false
        }
    }

    if (isFloat) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom,
            modifier = modifier.width(size).graphicsLayer(clip = false)
        ) {
            val targetBias = if (statusAlignment == Alignment.Start) -1f else 1f
            val animatedBias by animateFloatAsState(targetBias, spring(stiffness = Spring.StiffnessLow))

            Box(
                modifier = Modifier.fillMaxWidth()
                    .wrapContentSize(unbounded = true, align = BiasAlignment(animatedBias, 0f))
                    .graphicsLayer(clip = false)
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = showTooltip && isVisible,
                    enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                    exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = CircleShape,
                        modifier = Modifier.padding(bottom = 8.dp).shadow(4.dp, CircleShape),
                        tonalElevation = 8.dp
                    ) {
                        VerticalAnimatedText(
                            text = if (showTooltip) statusText else lastActiveStatusText,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = statusColor,
                            maxLines = 1
                        )
                    }
                }
            }

            CoreToggleButtonInternal(
                toggleState = toggleState,
                xrayState = xrayState,
                isLocked = autoLaunchSettings.enabled,
                size = size,
                shape = shape,
                onClick = onClick
            )
        }
    } else {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = modifier
        ) {
            CoreToggleButtonInternal(
                toggleState = toggleState,
                xrayState = xrayState,
                isLocked = autoLaunchSettings.enabled,
                size = size,
                shape = shape,
                onClick = onClick
            )

            VerticalAnimatedText(
                text = statusText,
                style = MaterialTheme.typography.titleMedium,
                color = statusColor,
                textAlign = TextAlign.Center,
                contentAlignment = Alignment.Center
            )
        }
    }
}

@Composable
private fun CoreToggleButtonInternal(
    toggleState: String,
    xrayState: XrayState,
    modifier: Modifier = Modifier,
    isLocked: Boolean = false,
    size: Dp = 160.dp,
    shape: Shape? = null,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val targetPolygon = remember(toggleState, xrayState) {
        when (toggleState) {
            "active" -> if (xrayState == XrayState.DirectRoute) MaterialShapes.VerySunny else MaterialShapes.Sunny
            "loading" -> MaterialShapes.Pill
            "error"   -> MaterialShapes.Triangle
            else      -> MaterialShapes.Cookie7Sided
        }
    }
    var startPolygon by remember { mutableStateOf(targetPolygon) }
    var endPolygon   by remember { mutableStateOf(targetPolygon) }
    val morphAnim = remember { Animatable(1f) }
    LaunchedEffect(targetPolygon) {
        startPolygon = endPolygon
        endPolygon = targetPolygon
        morphAnim.snapTo(0f)
        morphAnim.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)
        )
    }
    val morph = remember(startPolygon, endPolygon) { Morph(startPolygon, endPolygon) }
    val morphProgress = morphAnim.value.coerceIn(0f, 1f)

    val morphPath = remember(morph, morphProgress) {
        android.graphics.Path().also { path ->
            var first = true
            morph.forEachCubic(morphProgress) { cubic ->
                if (first) {
                    path.moveTo(cubic.anchor0X, cubic.anchor0Y)
                    first = false
                }
                path.cubicTo(
                    cubic.control0X, cubic.control0Y,
                    cubic.control1X, cubic.control1Y,
                    cubic.anchor1X, cubic.anchor1Y
                )
            }
            path.close()
        }
    }
    val animatedShape: Shape = shape ?: remember(morphPath) {
        object : Shape {
            override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
                val scaledPath = android.graphics.Path(morphPath)
                val bounds = android.graphics.RectF()
                scaledPath.computeBounds(bounds, true)
                val matrix = android.graphics.Matrix()
                matrix.setRectToRect(
                    bounds,
                    android.graphics.RectF(0f, 0f, size.width, size.height),
                    android.graphics.Matrix.ScaleToFit.CENTER
                )
                scaledPath.transform(matrix)
                return Outline.Generic(scaledPath.asComposePath())
            }
        }
    }

    val containerColor by animateColorAsState(
        targetValue = when (toggleState) {
            "active" -> if (xrayState == XrayState.DirectRoute) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
            "loading" -> MaterialTheme.colorScheme.tertiary
            "error" -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = tween(600),
        label = "btn_bg"
    )

    val contentColor by animateColorAsState(
        targetValue = when (toggleState) {
            "active" -> if (xrayState == XrayState.DirectRoute) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onPrimary
            "loading" -> MaterialTheme.colorScheme.onTertiary
            "error" -> MaterialTheme.colorScheme.onErrorContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(600),
        label = "btn_fg"
    )

    val scale = remember { Animatable(1f) }
    LaunchedEffect(isPressed) {
        scale.animateTo(
            targetValue = if (isPressed) 0.9f else 1f,
            animationSpec = if (isPressed) {
                spring(stiffness = Spring.StiffnessHigh, dampingRatio = Spring.DampingRatioNoBouncy)
            } else {
                spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioLowBouncy)
            }
        )
    }

    Box(contentAlignment = Alignment.Center, modifier = modifier.size(size)) {
        val surfaceSize = size * (148f / 160f)
        val iconSize = size * (66f / 160f)
        val spinnerSize = size * (54f / 160f)
        val lockOffset = size * (12f / 160f)
        val lockSize = (size * (36f / 160f)).coerceAtLeast(20.dp)
        val lockIconSize = (size * (18f / 160f)).coerceAtLeast(12.dp)

        val elevation by animateDpAsState(
            targetValue = when {
                isPressed -> 0.dp
                toggleState == "loading" -> 4.dp
                toggleState == "active" || toggleState == "error" -> 12.dp
                else -> 22.dp
            } * (size / 160.dp),
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
            label = "elevation"
        )

        Surface(
            onClick = onClick,
            interactionSource = interactionSource,
            modifier = Modifier
                .size(surfaceSize)
                .scale(scale.value)
                .clip(animatedShape),
            shape = animatedShape,
            color = containerColor,
            shadowElevation = 0.dp,
            tonalElevation = elevation.coerceAtMost(16.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    when (toggleState) {
                        "loading" -> CircularWavyProgressIndicator(
                            modifier = Modifier.size(spinnerSize),
                            color = contentColor
                        )
                        "active" -> {
                            val icon = if (xrayState == XrayState.DirectRoute) R.drawable.ethernet_24px else R.drawable.check_24px
                            Icon(
                                painterResource(icon),
                                stringResource(R.string.core_active_stop),
                                Modifier.size(iconSize),
                                tint = contentColor
                            )
                        }
                        "error" -> Icon(
                            painterResource(R.drawable.error_24px),
                            stringResource(R.string.core_error_restart),
                            Modifier.size(iconSize),
                            tint = contentColor
                        )
                        else -> Icon(
                            painterResource(R.drawable.power_24px),
                            stringResource(R.string.start_core),
                            Modifier.size(iconSize),
                            tint = contentColor
                        )
                    }
                }
            }
        }

        if (isLocked) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = -lockOffset, y = lockOffset)
                    .size(lockSize)
                    .shadow(elevation = (3.dp * (size / 160.dp)).coerceAtLeast(1.dp), shape = CircleShape),
                tonalElevation = (3.dp * (size / 160.dp)).coerceAtLeast(1.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.lock_24px),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(lockIconSize)
                    )
                }
            }
        }
    }
}
