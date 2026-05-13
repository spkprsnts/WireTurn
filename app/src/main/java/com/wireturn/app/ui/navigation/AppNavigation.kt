@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wireturn.app.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import com.wireturn.app.data.XrayConfiguration
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.screens.AppExceptionsScreen
import com.wireturn.app.ui.screens.CaptchaWebViewDialog
import com.wireturn.app.ui.screens.ClientConfigScreen
import com.wireturn.app.ui.screens.HomeScreen
import com.wireturn.app.ui.screens.LogsScreen
import com.wireturn.app.ui.screens.OnboardingScreen
import com.wireturn.app.ui.components.ProxyToggleButton
import com.wireturn.app.ui.screens.SettingsScreen
import com.wireturn.app.ui.screens.XrayConfigScreen
import com.wireturn.app.viewmodel.ProxyState
import com.wireturn.app.viewmodel.MainViewModel
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.wireturn.app.data.KernelVariant

object Routes {
    const val ONBOARDING = "onboarding"
    const val XRAY_CONFIG = "xray_config"
    const val CLIENT_CONFIG = "client_config"
    const val HOME = "home"
    const val APP_SETTINGS = "app_settings"
    const val LOGS = "logs"
    const val APP_EXCLUSIONS = "app_exclusions"
}

// Нижнее меню видно только в основном потоке, не во время онбординга
private val BOTTOM_NAV_ROUTES = setOf(Routes.HOME, Routes.LOGS, Routes.CLIENT_CONFIG, Routes.XRAY_CONFIG, Routes.APP_SETTINGS)

@Composable
fun AppNavigation(
    viewModel: MainViewModel,
    startDestination: String? = null
) {
    val isInitialized by viewModel.isInitialized.collectAsStateWithLifecycle()

    // Не строим NavHost пока DataStore не загружен — иначе startDestination
    // захватит дефолтный onboardingDone=false и всегда покажет онбординг
    if (!isInitialized) return

    val proxyState by viewModel.proxyState.collectAsStateWithLifecycle()
    val xraySettings by viewModel.xraySettings.collectAsStateWithLifecycle()
    val currentProfileId by viewModel.currentProfileId.collectAsStateWithLifecycle()
    val isBottomBarVisibleByScroll by viewModel.isBottomBarVisible.collectAsStateWithLifecycle()
    val bottomBarOffset by viewModel.bottomBarOffset.collectAsStateWithLifecycle()
    val bottomBarHeight by viewModel.bottomBarHeight.collectAsStateWithLifecycle()
    val autoLaunchSettings by viewModel.autoLaunchSettings.collectAsStateWithLifecycle()
    val xrayConfig by viewModel.xrayConfig.collectAsStateWithLifecycle()
    val clientConfig by viewModel.clientConfig.collectAsStateWithLifecycle()

    val showAutoLaunchOverride = rememberSaveable { mutableStateOf(false) }
    val showMismatchDialog = rememberSaveable { mutableStateOf(false) }
    var mismatchMessage by rememberSaveable { mutableStateOf("") }
    var pendingProxyAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    
    // Состояние для свободного перемещения FAB
    val fabOffsetX = remember { Animatable(0f) }
    val fabOffsetY = remember { Animatable(0f) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current

    // Параметры для расчетов (в пикселях)
    val screenWidthPx = windowInfo.containerSize.width.toFloat()
    val screenHeightPx = windowInfo.containerSize.height.toFloat()
    val fabSizePx = with(density) { 72.dp.toPx() }
    val marginPx = with(density) { 16.dp.toPx() }
    val bottomNavPaddingPx = with(density) { 100.dp.toPx() } // Отступ снизу в Scaffold для FAB

    val vlessMismatch = stringResource(R.string.warn_proxy_vless_mismatch)
    val wgMismatch = stringResource(R.string.warn_proxy_wg_mismatch)

    val checkMismatch = { targetXrayEnabled: Boolean, onConfirmed: () -> Unit ->
        val selectedRoute = clientConfig.turnableConfig.routes.find { it.routeId == clientConfig.turnableConfig.selectedRouteId }
        val isTunnelVless = selectedRoute?.transport?.contains("KCP", ignoreCase = true) == true

        val isTurnable = clientConfig.kernelVariant == KernelVariant.TURNABLE
        val mismatch = isTurnable && targetXrayEnabled && (
                (isTunnelVless && xrayConfig.xrayConfiguration == XrayConfiguration.WIREGUARD) ||
                        (!isTunnelVless && xrayConfig.xrayConfiguration == XrayConfiguration.VLESS)
                )

        if (mismatch) {
            mismatchMessage = if (isTunnelVless) vlessMismatch else wgMismatch
            pendingProxyAction = onConfirmed
            showMismatchDialog.value = true
        } else {
            onConfirmed()
        }
    }

    if (showMismatchDialog.value) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showMismatchDialog.value = false },
            title = { Text(stringResource(R.string.mismatch_title)) },
            text = { Text(mismatchMessage) },
            confirmButton = {
                TextButton(onClick = {
                    showMismatchDialog.value = false
                    pendingProxyAction?.invoke()
                    pendingProxyAction = null
                }) {
                    Text(stringResource(R.string.btn_start))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showMismatchDialog.value = false
                    pendingProxyAction = null
                }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showAutoLaunchOverride.value) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showAutoLaunchOverride.value = false },
            title = { Text(stringResource(R.string.auto_launch_override_title)) },
            text = { Text(stringResource(R.string.auto_launch_override_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    showAutoLaunchOverride.value = false
                    viewModel.updateAutoLaunchSettings(autoLaunchSettings.copy(enabled = false))
                    pendingProxyAction?.invoke()
                    pendingProxyAction = null
                }) {
                    Text(stringResource(R.string.auto_launch_disable_and_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAutoLaunchOverride.value = false
                }) {
                    Text(stringResource(R.string.btn_close))
                }
            }
        )
    }
    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.updateXraySettings(xraySettings.copy(xrayVpnMode = true))
        }
    }
    val finalStartDestination = remember {
        startDestination ?: if (viewModel.onboardingDone.value) Routes.HOME else Routes.ONBOARDING
    }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Сбрасываем видимость бара при смене экрана
    LaunchedEffect(currentRoute) {
        viewModel.setBottomBarVisible(true)
    }

    // Определяем, видна ли клавиатура
    val isKeyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    
    // Сбрасываем смещение при закрытии клавиатуры, чтобы панель вернулась
    LaunchedEffect(isKeyboardVisible) {
        if (!isKeyboardVisible) {
            viewModel.setBottomBarVisible(true)
        }
    }
    // Используем startsWith, так как в маршрутах могут быть параметры (например, в настройках)
    val showBottomBar = !isKeyboardVisible && currentRoute != null && 
        isBottomBarVisibleByScroll &&
        BOTTOM_NAV_ROUTES.any { currentRoute.startsWith(it.split("?")[0]) }

    val triggerProxyAction = {
        val action = {
            when (proxyState) {
                is ProxyState.Idle, is ProxyState.Error -> {
                    checkMismatch(xraySettings.xrayEnabled) {
                        HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                        if (xraySettings.xrayVpnMode) {
                            val intent = VpnService.prepare(context)
                            if (intent != null) {
                                vpnLauncher.launch(intent)
                            } else {
                                viewModel.startProxy()
                            }
                        } else {
                            viewModel.startProxy()
                        }
                    }
                }
                else -> {
                    HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_OFF)
                    viewModel.stopProxy()
                }
            }
        }

        if (autoLaunchSettings.enabled) {
            pendingProxyAction = action
            showAutoLaunchOverride.value = true
        } else {
            action()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val navBarsPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            
            // Анимируем отступ контента, чтобы он не прыгал при появлении клавиатуры
            val bottomPadding by animateDpAsState(
                targetValue = if (!isKeyboardVisible) navBarsPadding else 0.dp,
                animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                label = "navBarPadding"
            )

            NavHost(
                navController = navController,
                startDestination = finalStartDestination,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = bottomPadding),
                enterTransition = { fadeIn(animationSpec = tween(100)) },
                exitTransition = { fadeOut(animationSpec = tween(100)) },
                popEnterTransition = { fadeIn(animationSpec = tween(100)) },
                popExitTransition = { fadeOut(animationSpec = tween(100)) }
            ) {

                // Онбординг-мастер (без нижнего меню)

                composable(route = Routes.ONBOARDING) {
                    OnboardingScreen(
                        modifier = Modifier.statusBarsPadding(),
                        onSkip = {
                            viewModel.setOnboardingDone()
                            navController.navigate(Routes.CLIENT_CONFIG) {
                                popUpTo(Routes.ONBOARDING) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    )
                }

                composable(Routes.XRAY_CONFIG) {
                    key(currentProfileId) {
                        XrayConfigScreen(
                            modifier = Modifier.statusBarsPadding(),
                            viewModel = viewModel
                        )
                    }
                }

                composable(route = Routes.CLIENT_CONFIG) {
                    key(currentProfileId) {
                        ClientConfigScreen(
                            modifier = Modifier.statusBarsPadding(),
                            viewModel = viewModel
                        )
                    }
                }

                composable(Routes.HOME) {
                    HomeScreen(
                        modifier = Modifier.statusBarsPadding(),
                        viewModel = viewModel,
                        onNavigateToExclusions = { navController.navigate(Routes.APP_EXCLUSIONS) },
                        onToggleProxy = { triggerProxyAction() },
                        onCheckMismatch = { target: Boolean, action: () -> Unit -> checkMismatch(target, action) }
                    )
                }

                composable(
                    route = Routes.APP_SETTINGS,
                    arguments = listOf(navArgument("scrollToUpdate") {
                        type = NavType.LongType
                        defaultValue = 0L
                    })
                ) { backStackEntry ->
                    val scrollToUpdate = backStackEntry.arguments?.getLong("scrollToUpdate") ?: 0L
                    SettingsScreen(
                        modifier = Modifier.statusBarsPadding(),
                        viewModel = viewModel,
                        scrollToUpdate = scrollToUpdate
                    )
                }

                composable(Routes.LOGS) {
                    LogsScreen(
                        modifier = Modifier.statusBarsPadding(),
                        viewModel = viewModel
                    )
                }

                composable(Routes.APP_EXCLUSIONS) {
                    AppExceptionsScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() }
                    )
                }
            }

            // Навигационная панель как оверлей с анимацией по гайдлайнам M3
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically(
                    initialOffsetY = { it }, 
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .graphicsLayer { translationY = bottomBarOffset }
            ) {
                AppNavigationBar(
                    currentRoute = currentRoute,
                    modifier = Modifier.onGloballyPositioned { 
                        viewModel.setBottomBarHeight(it.size.height.toFloat()) 
                    },
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(Routes.HOME) { saveState = true; inclusive = false }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }

            // Плавающая кнопка (FAB) которая просто выкатывается справа
            val showFloatingActionButton by viewModel.showFloatingActionButton.collectAsStateWithLifecycle()
            val showFab = !isKeyboardVisible && currentRoute != null && 
                currentRoute != Routes.HOME && 
                currentRoute != Routes.ONBOARDING &&
                currentRoute != Routes.APP_EXCLUSIONS &&
                showFloatingActionButton

            // Определяем положение для анимаций (слева или справа)
            val leftEdgeX = -(screenWidthPx - fabSizePx - marginPx * 2)
            val isFabOnLeft = fabOffsetX.value < leftEdgeX / 2
            val statusAlignment = if (isFabOnLeft) Alignment.Start else Alignment.End

            AnimatedVisibility(
                visible = showFab,
                enter = slideInHorizontally(
                    initialOffsetX = { if (isFabOnLeft) -it else it },
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ),
                exit = slideOutHorizontally(
                    targetOffsetX = { if (isFabOnLeft) -it else it },
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .graphicsLayer {
                        // Коэффициент влияния панели: 1.0 если кнопка внизу, 0.0 если поднята выше 150dp
                        val influenceThreshold = with(density) { 150.dp.toPx() }
                        val influence = (1f + (fabOffsetY.value / influenceThreshold)).coerceIn(0f, 1f)

                        val extraRise = if (bottomBarHeight > 0) {
                            (bottomBarOffset / bottomBarHeight) * 32.dp.toPx()
                        } else 0f

                        // Учитываем смещение от панели только если кнопка находится в её зоне (influence > 0)
                        translationX = fabOffsetX.value
                        translationY = (bottomBarOffset - extraRise) * influence + fabOffsetY.value
                    }
            ) {
                Box(
                    modifier = Modifier
                        .padding(end = 16.dp, bottom = 100.dp)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    scope.launch {
                                        fabOffsetX.snapTo(fabOffsetX.value + dragAmount.x)
                                        fabOffsetY.snapTo(fabOffsetY.value + dragAmount.y)
                                    }
                                },
                                onDragEnd = {
                                    scope.launch {
                                        // 1. Горизонтальное прилипание к ближайшему краю
                                        // Alignment.BottomEnd означает что x=0 это правый край.
                                        // Левый край это -(screenWidth - fabSize - margins)
                                        val targetX = if (fabOffsetX.value < leftEdgeX / 2) leftEdgeX else 0f
                                        
                                        // 2. Вертикальные ограничения
                                        // fabOffsetY.value = 0 это исходная позиция (над баром)
                                        // Ограничиваем сверху так, чтобы даже с кнопкой перезапуска (140dp) 
                                        // группа не заходила на TopBar (~160dp от верха экрана)
                                        val safeTopMarginPx = with(density) { 160.dp.toPx() }
                                        val maxGroupHeightPx = with(density) { (72 + 12 + 56).dp.toPx() }
                                        val topLimitY = -(screenHeightPx - bottomNavPaddingPx - safeTopMarginPx - maxGroupHeightPx)

                                        val targetY = fabOffsetY.value.coerceIn(topLimitY, 0f)

                                        // Запускаем анимации возврата к краям с более мягкими параметрами
                                        val snapSpec = spring<Float>(
                                            stiffness = Spring.StiffnessLow, 
                                            dampingRatio = Spring.DampingRatioLowBouncy
                                        )
                                        
                                        launch {
                                            fabOffsetX.animateTo(targetX, snapSpec)
                                        }
                                        launch {
                                            fabOffsetY.animateTo(targetY, snapSpec)
                                        }
                                    }
                                }
                            )
                        }
                ) {
                    val isConfigChanged by viewModel.isConfigChanged.collectAsStateWithLifecycle()
                    val isMainConfigChanged by viewModel.isMainConfigChanged.collectAsStateWithLifecycle()
                    val isXrayConfigChanged by viewModel.isXrayConfigChanged.collectAsStateWithLifecycle()

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Кнопка применения настроек (над основной кнопкой)
                        AnimatedVisibility(
                            visible = isConfigChanged && showFab,
                            enter = slideInHorizontally(
                                initialOffsetX = { 
                                    val totalOffset = (it + marginPx + (fabSizePx - it) / 2).toInt()
                                    if (isFabOnLeft) -totalOffset else totalOffset 
                                },
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            ),
                            exit = slideOutHorizontally(
                                targetOffsetX = { 
                                    val totalOffset = (it + marginPx + (fabSizePx - it) / 2).toInt()
                                    if (isFabOnLeft) -totalOffset else totalOffset 
                                },
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMedium
                                )
                            )
                        ) {
                            Surface(
                                onClick = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                    if (isMainConfigChanged) {
                                        viewModel.restartProxy()
                                    } else if (isXrayConfigChanged) {
                                        viewModel.restartXray()
                                    }
                                },
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier
                                    .size(56.dp)
                                    .shadow(elevation = 8.dp, shape = CircleShape),
                                tonalElevation = 8.dp
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        painter = painterResource(R.drawable.refresh_24px),
                                        contentDescription = stringResource(R.string.btn_restart),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }

                        ProxyToggleButton(
                            viewModel = viewModel,
                            size = 72.dp,
                            onClick = { triggerProxyAction() },
                            isFloat = true,
                            isVisible = showFab,
                            statusAlignment = statusAlignment
                        )
                    }
                }
            }
        }
    }

    // Диалог капчи поверх любого экрана. Оборачиваем в key(sessionId), чтобы для
    // каждой новой капча-сессии Compose пересоздавал диалог и WebView грузил URL заново
    // (бинарник цикличит креды и для каждой выдаёт новую капчу с тем же localhost-URL).
    val captchaState = proxyState as? ProxyState.CaptchaRequired
    val showCaptcha = remember(captchaState?.sessionId) {
        mutableStateOf(captchaState != null)
    }

    if (showCaptcha.value && captchaState != null) {
        key(captchaState.sessionId) {
            CaptchaWebViewDialog(
                viewModel = viewModel,
                captchaUrl = captchaState.url,
                onDismiss = { viewModel.dismissCaptcha() },
                onSuccess = { showCaptcha.value = false }
            )
        }
    }
}

private data class NavItem(
    val route: String,
    val labelResId: Int,
    val selectedIconRes: Int,
    val unselectedIconRes: Int
)

private val navItems = listOf(
    NavItem(Routes.HOME, R.string.nav_home, R.drawable.home_24px, R.drawable.home_outlined_24px),
    NavItem(Routes.CLIENT_CONFIG, R.string.client_title, R.drawable.mobile_24px, R.drawable.mobile_outlined_24px),
    NavItem(Routes.XRAY_CONFIG, R.string.xray_short, R.drawable.ic_xray_24px, R.drawable.ic_xray_24px),
    NavItem(Routes.APP_SETTINGS, R.string.app_settings_title, R.drawable.settings_24px, R.drawable.settings_outlined_24px),
    NavItem(Routes.LOGS, R.string.logs_title, R.drawable.terminal_24px, R.drawable.terminal_24px)
)


@Composable
private fun AppNavigationBar(
    currentRoute: String?,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit
) {
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars),
            contentAlignment = Alignment.Center
        ) {
            ShortNavigationBar(
                modifier = Modifier.widthIn(max = 600.dp),
                containerColor = Color.Transparent,
                windowInsets = WindowInsets(0, 0, 0, 0)
            ) {
                navItems.forEach { item ->
                    // Сравниваем только базовую часть маршрута без параметров
                    val itemBaseRoute = item.route.split("?")[0]
                    val currentBaseRoute = currentRoute?.split("?")[0]
                    val selected = currentBaseRoute == itemBaseRoute

                    ShortNavigationBarItem(
                        selected = selected,
                        label = {
                            Text(
                                text = stringResource(item.labelResId),
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        onClick = {
                            if (!selected) {
                                HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                                onNavigate(item.route)
                            }
                        },
                        icon = {
                            Icon(
                                painter = painterResource(
                                    if (selected) item.selectedIconRes else item.unselectedIconRes
                                ),
                                contentDescription = stringResource(item.labelResId)
                            )
                        }
                    )
                }
            }
        }
    }
}
