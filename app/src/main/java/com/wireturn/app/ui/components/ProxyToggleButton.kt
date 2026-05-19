package com.wireturn.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.DefaultShadowColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.ProxyServiceState
import com.wireturn.app.R
import com.wireturn.app.XrayServiceState
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.VerticalAnimatedText
import com.wireturn.app.viewmodel.MainViewModel
import com.wireturn.app.viewmodel.ProxyState
import com.wireturn.app.viewmodel.XrayState
import kotlinx.coroutines.delay

@Composable
fun ProxyToggleButton(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    size: Dp = 160.dp,
    shape: androidx.compose.ui.graphics.Shape? = null,
    isFloat: Boolean = false,
    isVisible: Boolean = true,
    statusAlignment: Alignment.Horizontal = Alignment.End,
    onClick: () -> Unit
) {
    val proxyState by viewModel.proxyState.collectAsStateWithLifecycle()
    val xrayState by XrayServiceState.state.collectAsStateWithLifecycle()
    val xrayConfig by viewModel.xrayConfig.collectAsStateWithLifecycle()
    val autoLaunchSettings by viewModel.autoLaunchSettings.collectAsStateWithLifecycle()

    val isRestarting by ProxyServiceState.isRestarting.collectAsStateWithLifecycle()
    val isChangingProfile by ProxyServiceState.isChangingProfile.collectAsStateWithLifecycle()
    val isBusy = isRestarting || isChangingProfile

    var wasActiveBeforeRestart by remember { mutableStateOf(false) }
    LaunchedEffect(isBusy, proxyState, xrayState) {
        val isActive = proxyState !is ProxyState.Idle || xrayState == XrayState.Running || xrayState == XrayState.DirectRoute
        if (isBusy && isActive) {
            wasActiveBeforeRestart = true
        } else if (!isBusy) {
            wasActiveBeforeRestart = false
        }
    }

    val isXrayWorking = xrayState == XrayState.Running || xrayState == XrayState.DirectRoute
    val toggleState = remember(proxyState, xrayState, isRestarting, wasActiveBeforeRestart, xrayConfig.enabled) {
        val isActive = proxyState !is ProxyState.Idle || isXrayWorking
        val actuallyRestarting = isRestarting && (isActive || wasActiveBeforeRestart)
        val gapFilling = wasActiveBeforeRestart && proxyState is ProxyState.Idle
        
        when {
            actuallyRestarting || gapFilling || 
            proxyState is ProxyState.Starting || 
            proxyState is ProxyState.Connecting || 
            proxyState is ProxyState.CaptchaRequired || 
            proxyState is ProxyState.WaitingForNetwork ||
            (xrayConfig.enabled && !isXrayWorking && proxyState !is ProxyState.Idle) -> "loading"
            
            proxyState is ProxyState.Connected || proxyState is ProxyState.Suppressed -> "active"
            proxyState is ProxyState.Error -> "error"
            else -> "idle"
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
        toggleState == "loading" && (isBusy || wasActiveBeforeRestart) -> stringResource(R.string.proxy_restarting)
        proxyState is ProxyState.Connected -> {
            if (xrayState == XrayState.DirectRoute) stringResource(R.string.vless_direct_active)
            else stringResource(R.string.proxy_active)
        }
        proxyState is ProxyState.Starting -> stringResource(R.string.starting)
        proxyState is ProxyState.Connecting -> stringResource(R.string.connecting)
        proxyState is ProxyState.Suppressed -> {
            if (xrayState == XrayState.DirectRoute) stringResource(R.string.vless_direct_active)
            else stringResource(R.string.connecting)
        }
        proxyState is ProxyState.CaptchaRequired -> stringResource(R.string.proxy_captcha_required)
        proxyState is ProxyState.WaitingForNetwork -> stringResource(R.string.status_waiting_for_network)
        proxyState is ProxyState.Error -> (proxyState as ProxyState.Error).message
        else -> if (autoLaunchSettings.enabled) stringResource(R.string.proxy_auto_launch_active) else stringResource(R.string.proxy_press_to_start)
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

            ProxyToggleButtonInternal(
                toggleState = toggleState,
                xrayState = xrayState,
                isFloat = true,
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
            ProxyToggleButtonInternal(
                toggleState = toggleState,
                xrayState = xrayState,
                isFloat = false,
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
private fun ProxyToggleButtonInternal(
    toggleState: String,
    xrayState: XrayState,
    modifier: Modifier = Modifier,
    isFloat: Boolean = false,
    isLocked: Boolean = false,
    size: Dp = 160.dp,
    shape: androidx.compose.ui.graphics.Shape? = null,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val cornerPercent by animateIntAsState(
        targetValue = when (toggleState) {
            "active" -> 28 // Более выраженный Squircle
            "loading" -> 38
            else -> 50 // Circle
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "shape_morph"
    )
    val animatedShape = shape ?: RoundedCornerShape(cornerPercent)

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

    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.84f
            toggleState == "loading" -> 0.90f
            toggleState == "active" || toggleState == "error" -> 0.95f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "btn_scale"
    )

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
                .scale(scale)
                .shadow(
                    elevation = elevation,
                    shape = animatedShape,
                    ambientColor = if (isFloat) DefaultShadowColor else containerColor.copy(alpha = 0.4f),
                    spotColor = if (isFloat) DefaultShadowColor else containerColor
                )
                .clip(animatedShape),
            shape = animatedShape,
            color = containerColor,
            shadowElevation = 0.dp,
            tonalElevation = (elevation * 0.4f).coerceAtMost(8.dp) // Более адекватный тональный эффект
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
                                stringResource(R.string.proxy_active_stop),
                                Modifier.size(iconSize),
                                tint = contentColor
                            )
                        }
                        "error" -> Icon(
                            painterResource(R.drawable.error_24px),
                            stringResource(R.string.proxy_error_restart),
                            Modifier.size(iconSize),
                            tint = contentColor
                        )
                        else -> Icon(
                            painterResource(R.drawable.power_24px),
                            stringResource(R.string.start_proxy),
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
