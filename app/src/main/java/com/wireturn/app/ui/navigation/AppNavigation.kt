@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
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
    
    // Состояние для скрытия FAB при скролле
    var isFabVisibleByScroll by remember { mutableStateOf(true) }
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Прячем при любом свайпе вниз, показываем при свайпе вверх
                if (available.y < -1f) {
                    isFabVisibleByScroll = false
                } else if (available.y > 1f) {
                    isFabVisibleByScroll = true
                }
                return Offset.Zero
            }
        }
    }

    val context = LocalContext.current
    val density = LocalDensity.current

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
                .nestedScroll(nestedScrollConnection)
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

            // Плавающая кнопка (FAB) которая просто выкатывается справа
            val showFloatingActionButton by viewModel.showFloatingActionButton.collectAsStateWithLifecycle()
            val showFab = !isKeyboardVisible && currentRoute != null && 
                currentRoute != Routes.HOME && 
                currentRoute != Routes.ONBOARDING &&
                currentRoute != Routes.APP_EXCLUSIONS &&
                showFloatingActionButton &&
                isFabVisibleByScroll

            // Определяем положение для анимаций (всегда справа по M3)
            val statusAlignment = Alignment.End
            
            val navBarHeightPx = with(density) { WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().toPx() }

            AnimatedVisibility(
                visible = showFab,
                enter = slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ),
                exit = slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .graphicsLayer {
                        // Если нижняя панель навигации (меню) видима, FAB должен быть над ней.
                        // bottomBarOffset вьюмодели = 0 когда панель полностью видна,
                        // и = bottomBarHeight когда панель полностью скрыта.
                        
                        // Высота контента панели без учета системных инсетов
                        val bottomBarContentHeight = (bottomBarHeight - navBarHeightPx).coerceAtLeast(0f)
                        
                        // На сколько панель сейчас "выглядывает" над системным баром
                        val currentVisibleContent = (bottomBarContentHeight - bottomBarOffset).coerceAtLeast(0f)
                        
                        translationY = -currentVisibleContent
                        clip = false // Позволяем теням выходить за границы анимации
                    }
            ) {
                Box(
                    modifier = Modifier.graphicsLayer(clip = false)
                ) {
                    val isConfigChanged by viewModel.isConfigChanged.collectAsStateWithLifecycle()
                    val isMainConfigChanged by viewModel.isMainConfigChanged.collectAsStateWithLifecycle()
                    val isXrayConfigChanged by viewModel.isXrayConfigChanged.collectAsStateWithLifecycle()

                    Column(
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier.graphicsLayer(clip = false)
                    ) {
                        // Кнопка применения настроек (над основной кнопкой)
                        // Используем прямое указание на топовую функцию, чтобы избежать конфликта с ColumnScope
                        androidx.compose.animation.AnimatedVisibility(
                            visible = isConfigChanged && showFab,
                            enter = scaleIn(
                                initialScale = 0.66f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            ) + fadeIn(animationSpec = tween(200)),
                            exit = scaleOut(
                                targetScale = 0.66f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            ) + fadeOut(animationSpec = tween(200)),
                            modifier = Modifier.graphicsLayer(clip = false)
                        ) {
                            // padding(start = 16.dp, end = 16.dp) дает место для тени и заменяет внешний паддинг.
                            // padding(bottom = 12.dp) заменяет spacedBy.
                            Box(
                                modifier = Modifier
                                    .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)
                                    .graphicsLayer(clip = false)
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
                                        .shadow(
                                            elevation = 4.dp,
                                            shape = CircleShape, 
                                            clip = false
                                        ),
                                    shadowElevation = 0.dp,
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
                        }

                        ProxyToggleButton(
                            viewModel = viewModel,
                            size = 86.dp,
                            shape = RoundedCornerShape(20.dp),
                            onClick = { triggerProxyAction() },
                            isFloat = true,
                            isVisible = showFab,
                            statusAlignment = statusAlignment,
                            modifier = Modifier
                                .padding(start = 16.dp, end = 12.dp, bottom = 12.dp)
                                .graphicsLayer(clip = false)
                        )
                    }
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
