package com.wireturn.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
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
    shape: androidx.compose.ui.graphics.Shape = CircleShape,
    isFloat: Boolean = false,
    isVisible: Boolean = true,
    statusAlignment: Alignment.Horizontal = Alignment.End,
    onClick: () -> Unit
) {
    val proxyState by viewModel.proxyState.collectAsStateWithLifecycle()
    val xrayState by XrayServiceState.state.collectAsStateWithLifecycle()
    val xraySettings by viewModel.xraySettings.collectAsStateWithLifecycle()
    val autoLaunchSettings by viewModel.autoLaunchSettings.collectAsStateWithLifecycle()
    val customKernelExists by viewModel.customKernelExists.collectAsStateWithLifecycle()

    val isRestarting by ProxyServiceState.isRestarting.collectAsStateWithLifecycle()
    val isChangingProfile by ProxyServiceState.isChangingProfile.collectAsStateWithLifecycle()
    val isBusy = isRestarting || isChangingProfile

    // Логика предотвращения мерцания "Нажмите для старта" во время горячего перезапуска
    var wasActiveBeforeRestart by remember { mutableStateOf(false) }
    LaunchedEffect(isBusy, proxyState, xrayState) {
        val isActive = proxyState !is ProxyState.Idle || xrayState == XrayState.Running || xrayState == XrayState.DirectRoute
        if (isBusy && isActive) {
            wasActiveBeforeRestart = true
        } else if (!isBusy) {
            wasActiveBeforeRestart = false
        }
    }

    val isActiveStatus = proxyState !is ProxyState.Idle || xrayState == XrayState.Running || xrayState == XrayState.DirectRoute
    val statusText = when {
        (isBusy || wasActiveBeforeRestart) && (isActiveStatus || wasActiveBeforeRestart) -> stringResource(R.string.proxy_restarting)
        proxyState is ProxyState.Connected -> {
            if (xrayState == XrayState.DirectRoute) stringResource(R.string.vless_direct_active)
            else stringResource(if (customKernelExists) R.string.proxy_running else R.string.proxy_active)
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
        else -> when {
            autoLaunchSettings.enabled -> stringResource(R.string.proxy_auto_launch_active)
            else -> stringResource(R.string.proxy_press_to_start)
        }
    }

    val statusColor by animateColorAsState(
        targetValue = when {
            wasActiveBeforeRestart && proxyState is ProxyState.Idle -> MaterialTheme.colorScheme.tertiary
            proxyState is ProxyState.Connected || proxyState is ProxyState.Suppressed -> MaterialTheme.colorScheme.primary
            proxyState is ProxyState.Starting || proxyState is ProxyState.Connecting || proxyState is ProxyState.CaptchaRequired || proxyState is ProxyState.WaitingForNetwork -> MaterialTheme.colorScheme.tertiary
            proxyState is ProxyState.Error -> MaterialTheme.colorScheme.error
            isBusy && proxyState !is ProxyState.Idle -> MaterialTheme.colorScheme.tertiary
            autoLaunchSettings.enabled -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
        },
        label = "status_color"
    )

    // Логика временного тултипа для плавающей кнопки
    var showTooltip by remember { mutableStateOf(false) }
    var previousState by remember { mutableStateOf<Triple<ProxyState, XrayState, Boolean>?>(null) }

    // Запоминаем текст, чтобы он не менялся на "Нажмите для старта" во время анимации исчезновения
    var lastActiveStatusText by remember { mutableStateOf(statusText) }
    LaunchedEffect(statusText) {
        if (proxyState !is ProxyState.Idle || isBusy) {
            lastActiveStatusText = statusText
        }
    }

    LaunchedEffect(proxyState, xrayState, isBusy, isVisible, statusText) {
        val currentState = Triple(proxyState, xrayState, isBusy)
        val isIdle = proxyState is ProxyState.Idle && xrayState == XrayState.Idle && !isBusy

        if (!isVisible || isIdle) {
            showTooltip = false
            previousState = currentState
            return@LaunchedEffect
        }

        // Показываем тултип только если это НЕ первая композиция и состояние или текст реально изменились
        if (isFloat && previousState != null && (previousState != currentState || lastActiveStatusText != statusText)) {
            showTooltip = true
        }
        
        previousState = currentState

        // Если тултип включен, запускаем таймер. При перезапуске эффекта (новое состояние) таймер обнулится.
        if (showTooltip) {
            delay(5000)
            showTooltip = false
        }
    }

    if (isFloat) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom,
            modifier = modifier
                .width(size) // Фиксируем ширину по кнопке
                .graphicsLayer(clip = false)
        ) {
            val targetBias = if (statusAlignment == Alignment.Start) -1f else 1f
            val animatedBias by animateFloatAsState(
                targetValue = targetBias,
                animationSpec = spring(stiffness = Spring.StiffnessLow),
                label = "tooltip_bias"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentSize(unbounded = true, align = BiasAlignment(animatedBias, 0f))
                    .graphicsLayer(clip = false)
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = showTooltip && isVisible,
                    enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                    exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
                    modifier = Modifier.graphicsLayer(clip = false)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = CircleShape,
                        modifier = Modifier
                            .padding(bottom = 8.dp)
                            .shadow(
                                elevation = 4.dp, // Увеличено для лучшей читаемости
                                shape = CircleShape,
                                clip = false
                            ),
                        shadowElevation = 0.dp,
                        tonalElevation = 8.dp // Больше акцента
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

            ProxyToggleButton(
                proxyState = proxyState,
                xrayState = xrayState,
                xrayEnabled = xraySettings.xrayEnabled,
                isRestarting = isBusy,
                wasActiveBeforeRestart = wasActiveBeforeRestart,
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
            ProxyToggleButton(
                proxyState = proxyState,
                xrayState = xrayState,
                xrayEnabled = xraySettings.xrayEnabled,
                isRestarting = isBusy,
                wasActiveBeforeRestart = wasActiveBeforeRestart,
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
fun ProxyToggleButton(
    proxyState: ProxyState,
    modifier: Modifier = Modifier,
    xrayState: XrayState = XrayState.Idle,
    xrayEnabled: Boolean = true,
    isRestarting: Boolean = false,
    wasActiveBeforeRestart: Boolean = false,
    isLocked: Boolean = false,
    size: Dp = 160.dp,
    shape: androidx.compose.ui.graphics.Shape = CircleShape,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val isXrayWorking = xrayState == XrayState.Running || xrayState == XrayState.DirectRoute
    
    val toggleState = remember(proxyState, xrayState, isRestarting, wasActiveBeforeRestart, xrayEnabled) {
        val isActive = proxyState !is ProxyState.Idle || isXrayWorking
        val actuallyRestarting = isRestarting && (isActive || wasActiveBeforeRestart)
        val gapFilling = wasActiveBeforeRestart && proxyState is ProxyState.Idle
        when {
            actuallyRestarting || gapFilling || proxyState is ProxyState.Starting || proxyState is ProxyState.Connecting || proxyState is ProxyState.CaptchaRequired || proxyState is ProxyState.WaitingForNetwork -> "loading"
            proxyState is ProxyState.Connected || proxyState is ProxyState.Suppressed -> {
                // Если Xray включен, но еще не запущен, продолжаем показывать загрузку, избегая мерцания галочки
                if (xrayEnabled && !isXrayWorking) "loading" else "active"
            }
            proxyState is ProxyState.Error -> "error"
            else -> "idle"
        }
    }

    val containerColor by animateColorAsState(
        targetValue = when (toggleState) {
            "active" -> if (xrayState == XrayState.DirectRoute) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
            "loading" -> MaterialTheme.colorScheme.tertiary
            "error" -> MaterialTheme.colorScheme.errorContainer
            else -> {
                val isActive = proxyState !is ProxyState.Idle || xrayState == XrayState.Running || xrayState == XrayState.DirectRoute
                if ((isRestarting && (isActive || wasActiveBeforeRestart)) || proxyState is ProxyState.Starting) 
                    MaterialTheme.colorScheme.surfaceContainerHigh 
                else MaterialTheme.colorScheme.surfaceVariant
            }
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
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
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
                toggleState == "active" || toggleState == "error" -> 12.dp // Больше глубины
                else -> 22.dp // Максимально высокая тень для Idle
            } * (size / 160.dp),
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessLow
            ),
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
                    shape = shape
                )
                .clip(shape),
            shape = shape,
            color = containerColor,
            shadowElevation = 0.dp,
            tonalElevation = elevation * 0.6f // Добавляем тональный высветляющий эффект
        ) {
            Box(contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    when (toggleState) {
                        "loading" -> {
                            CircularWavyProgressIndicator(
                                modifier = Modifier.size(spinnerSize),
                                color = contentColor
                            )
                        }

                        "active" -> {
                            val icon = if (xrayState == XrayState.DirectRoute) R.drawable.ethernet_24px else R.drawable.check_circle_24px
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
