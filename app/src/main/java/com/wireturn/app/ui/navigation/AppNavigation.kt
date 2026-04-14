package com.wireturn.app.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import com.wireturn.app.ui.screens.WireproxyConfigScreen
import com.wireturn.app.viewmodel.ProxyState
import com.wireturn.app.viewmodel.MainViewModel

object Routes {
    const val ONBOARDING = "onboarding"
    const val WIREPROXY_CONFIG = "wireproxy_config"
    const val CLIENT_SETUP = "client_setup"
    const val HOME = "home"
    const val LOGS = "logs"
}

// Нижнее меню видно только в основном потоке, не во время онбординга
private val BOTTOM_NAV_ROUTES = setOf(Routes.HOME, Routes.LOGS, Routes.CLIENT_SETUP, Routes.WIREPROXY_CONFIG)

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
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = fadeIn(animationSpec = tween(150)),
                exit = fadeOut(animationSpec = tween(150))
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
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            NavHost(
                navController = navController, 
                startDestination = finalStartDestination,
                modifier = Modifier.statusBarsPadding(),
                enterTransition = { fadeIn(animationSpec = tween(200)) },
                exitTransition = { fadeOut(animationSpec = tween(200)) },
                popEnterTransition = { fadeIn(animationSpec = tween(200)) },
                popExitTransition = { fadeOut(animationSpec = tween(200)) }
            ) {

                // Онбординг-мастер (без нижнего меню)

                composable(Routes.ONBOARDING) {
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

                // Основной поток (с нижним меню)

                composable(Routes.WIREPROXY_CONFIG) {
                    Box(Modifier.padding(bottom = innerPadding.calculateBottomPadding())) {
                        WireproxyConfigScreen(
                            viewModel = viewModel,
                            showFinishButton = false
                        )
                    }
                }

                composable(Routes.CLIENT_SETUP) {
                    Box(Modifier.padding(bottom = innerPadding.calculateBottomPadding())) {
                        ClientSetupScreen(
                            viewModel = viewModel,
                            showFinishButton = false
                        )
                    }
                }

                composable(Routes.HOME) {
                    Box(Modifier.padding(bottom = innerPadding.calculateBottomPadding())) {
                        HomeScreen(
                            viewModel = viewModel
                        )
                    }
                }

                composable(Routes.LOGS) {
                    Box(Modifier.padding(bottom = innerPadding.calculateBottomPadding())) {
                        LogsScreen(viewModel = viewModel)
                    }
                }
            }
        }
    }

    // Диалог капчи поверх любого экрана. Оборачиваем в key(sessionId), чтобы для
    // каждой новой капча-сессии Compose пересоздавал диалог и WebView грузил URL заново
    // (бинарник цикличит креды и для каждой выдаёт новую капчу с тем же localhost-URL).
    val captchaState = proxyState as? ProxyState.CaptchaRequired
    if (captchaState != null) {
        androidx.compose.runtime.key(captchaState.sessionId) {
            CaptchaWebViewDialog(
                captchaUrl = captchaState.url,
                onDismiss = { viewModel.dismissCaptcha() }
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
    NavItem(Routes.WIREPROXY_CONFIG, R.string.wireproxy_title, R.drawable.wifi_24px, R.drawable.wifi_24px),
    NavItem(Routes.LOGS, R.string.logs_title, R.drawable.terminal_24px, R.drawable.terminal_24px)
)

@Composable
private fun AppNavigationBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit
) {
    val context = LocalContext.current
    NavigationBar {
        navItems.forEach { item ->
            val selected = currentRoute == item.route
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (!selected) HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                    onNavigate(item.route)
                },
                icon = {
                    Crossfade(targetState = selected, label = "nav_icon_${item.route}") { isSelected ->
                        Icon(
                            painter = painterResource(
                                if (isSelected) item.selectedIconRes else item.unselectedIconRes
                            ),
                            contentDescription = stringResource(item.labelResId)
                        )
                    }
                },
                label = { Text(stringResource(item.labelResId)) }
            )
        }
    }
}

