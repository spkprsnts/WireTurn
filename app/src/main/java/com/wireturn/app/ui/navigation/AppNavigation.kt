package com.wireturn.app.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.screens.AppExceptionsScreen
import com.wireturn.app.ui.screens.CaptchaWebViewDialog
import com.wireturn.app.ui.screens.ClientConfigScreen
import com.wireturn.app.ui.screens.HomeScreen
import com.wireturn.app.ui.screens.LogsScreen
import com.wireturn.app.ui.screens.OnboardingScreen
import com.wireturn.app.ui.screens.SettingsScreen
import com.wireturn.app.ui.screens.XrayConfigScreen
import com.wireturn.app.viewmodel.ProxyState
import com.wireturn.app.viewmodel.MainViewModel

object Routes {
    const val ONBOARDING = "onboarding"
    const val XRAY_CONFIG = "xray_config"
    const val CLIENT_CONFIG = "client_config"
    const val HOME = "home"
    const val APP_SETTINGS = "app_settings?scrollToUpdate={scrollToUpdate}"
    fun appSettings(scrollToUpdate: Boolean = false) = "app_settings?scrollToUpdate=$scrollToUpdate"
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
    val currentProfileId by viewModel.currentProfileId.collectAsStateWithLifecycle()
    val finalStartDestination = remember {
        startDestination ?: if (viewModel.onboardingDone.value) Routes.HOME else Routes.ONBOARDING
    }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Определяем, видна ли клавиатура
    val isKeyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    
    // Проверяем, относится ли текущий маршрут к тем, где нужно показывать BottomBar
    // Используем startsWith, так как в маршрутах могут быть параметры (например, в настройках)
    val showBottomBar = !isKeyboardVisible && currentRoute != null && 
        BOTTOM_NAV_ROUTES.any { currentRoute.startsWith(it.split("?")[0]) }

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
                            viewModel = viewModel,
                            showFinishButton = false
                        )
                    }
                }

                composable(route = Routes.CLIENT_CONFIG) {
                    key(currentProfileId) {
                        ClientConfigScreen(
                            modifier = Modifier.statusBarsPadding(),
                            viewModel = viewModel,
                            showFinishButton = false
                        )
                    }
                }

                composable(Routes.HOME) {
                    HomeScreen(
                        modifier = Modifier.statusBarsPadding(),
                        viewModel = viewModel,
                        onNavigateToExclusions = { navController.navigate(Routes.APP_EXCLUSIONS) },
                        onNavigateToSettings = { 
                            navController.navigate(Routes.appSettings(scrollToUpdate = true)) {
                                popUpTo(Routes.HOME) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }

                composable(
                    route = Routes.APP_SETTINGS,
                    arguments = listOf(navArgument("scrollToUpdate") {
                        type = NavType.BoolType
                        defaultValue = false
                    })
                ) { backStackEntry ->
                    val scrollToUpdate = backStackEntry.arguments?.getBoolean("scrollToUpdate") ?: false
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
                    animationSpec = tween(durationMillis = 300, easing = LinearOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(durationMillis = 300)),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(durationMillis = 250, easing = FastOutLinearInEasing)
                ) + fadeOut(animationSpec = tween(durationMillis = 250)),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                AppNavigationBar(
                    currentRoute = currentRoute,
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
    NavItem(Routes.appSettings(), R.string.app_settings_title, R.drawable.baseline_app_settings_alt_24px, R.drawable.outline_app_settings_alt_24px),
    NavItem(Routes.LOGS, R.string.logs_title, R.drawable.terminal_24px, R.drawable.terminal_24px)
)

@Composable
private fun AppNavigationBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit
) {
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars),
            contentAlignment = Alignment.Center
        ) {
            NavigationBar(
                modifier = Modifier
                    .height(64.dp)
                    .widthIn(max = 600.dp),
                containerColor = Color.Transparent,
                tonalElevation = 0.dp,
                windowInsets = WindowInsets(0, 0, 0, 0)
            ) {
                navItems.forEach { item ->
                    // Сравниваем только базовую часть маршрута без параметров
                    val itemBaseRoute = item.route.split("?")[0]
                    val currentBaseRoute = currentRoute?.split("?")[0]
                    val selected = currentBaseRoute == itemBaseRoute

                    NavigationBarItem(
                        selected = selected,
                        label = {
                            Text(
                                text = stringResource(item.labelResId),
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        alwaysShowLabel = true,
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
