package com.wireturn.app.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.ime
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
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.screens.CaptchaWebViewDialog
import com.wireturn.app.ui.screens.ClientSetupScreen
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
    const val CLIENT_SETUP = "client_setup"
    const val HOME = "home"
    const val APP_SETTINGS = "app_settings"
    const val LOGS = "logs"
}

// Нижнее меню видно только в основном потоке, не во время онбординга
private val BOTTOM_NAV_ROUTES = setOf(Routes.HOME, Routes.LOGS, Routes.CLIENT_SETUP, Routes.XRAY_CONFIG, Routes.APP_SETTINGS)

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
    val finalStartDestination = remember {
        startDestination ?: if (viewModel.onboardingDone.value) Routes.HOME else Routes.ONBOARDING
    }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Определяем, видна ли клавиатура
    val isKeyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val showBottomBar = currentRoute in BOTTOM_NAV_ROUTES && !isKeyboardVisible

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
            val bottomPadding = if (!isKeyboardVisible) navBarsPadding else 0.dp

            NavHost(
                navController = navController,
                startDestination = finalStartDestination,
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(bottom = bottomPadding),
                enterTransition = { fadeIn(animationSpec = tween(100)) },
                exitTransition = { fadeOut(animationSpec = tween(100)) },
                popEnterTransition = { fadeIn(animationSpec = tween(100)) },
                popExitTransition = { fadeOut(animationSpec = tween(100)) }
            ) {

                // Онбординг-мастер (без нижнего меню)

                composable(route = Routes.ONBOARDING) {
                    OnboardingScreen(
                        onSkip = {
                            viewModel.setOnboardingDone()
                            navController.navigate(Routes.CLIENT_SETUP) {
                                popUpTo(Routes.ONBOARDING) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    )
                }

                composable(Routes.XRAY_CONFIG) {
                    XrayConfigScreen(
                        viewModel = viewModel,
                        showFinishButton = false
                    )
                }

                composable(route = Routes.CLIENT_SETUP) {
                    ClientSetupScreen(
                        viewModel = viewModel,
                        showFinishButton = false
                    )
                }

                composable(Routes.HOME) {
                    HomeScreen(
                        viewModel = viewModel
                    )
                }

                composable(Routes.APP_SETTINGS) {
                    SettingsScreen(viewModel = viewModel)
                }

                composable(Routes.LOGS) {
                    LogsScreen(viewModel = viewModel)
                }
            }

            // Навигационная панель как оверлей
            AnimatedVisibility(
                visible = showBottomBar,
                enter = fadeIn(animationSpec = tween(150)),
                exit = fadeOut(animationSpec = tween(150)),
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
    NavItem(Routes.CLIENT_SETUP, R.string.client_title, R.drawable.mobile_24px, R.drawable.mobile_outlined_24px),
    NavItem(Routes.XRAY_CONFIG, R.string.xray_short, R.drawable.ic_xray_24px, R.drawable.ic_xray_24px),
    NavItem(Routes.APP_SETTINGS, R.string.app_settings_title, R.drawable.baseline_app_settings_alt_24px, R.drawable.outline_app_settings_alt_24px),
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
                tonalElevation = 0.dp
            ) {
                navItems.forEach { item ->
                    val selected = currentRoute == item.route
                    NavigationBarItem(
                        selected = selected,
                        label = {
                            Text(
                                text = stringResource(item.labelResId),
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
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

