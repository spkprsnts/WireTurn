package com.wireturn.app

import android.content.Intent
import android.os.Bundle
import android.view.animation.AnticipateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.navigation.AppNavigation
import com.wireturn.app.ui.navigation.Routes
import com.wireturn.app.ui.screens.CaptchaWebViewDialog
import com.wireturn.app.ui.theme.WireturnTheme
import com.wireturn.app.viewmodel.AppLifecycleState
import com.wireturn.app.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Обработка перехода из плитки (QS Tile)
        val fromTile = intent?.action == "android.service.quicksettings.action.QS_TILE_PREFERENCES"

        // Удерживаем системный splash пока ViewModel не инициализируется
        splashScreen.setKeepOnScreenCondition { !viewModel.isInitialized.value }

        // Анимация выхода в стиле Material 3
        splashScreen.setOnExitAnimationListener { splashScreenView ->
            val iconView = splashScreenView.iconView

            // Анимация иконки: уменьшение и исчезновение
            iconView.animate()
                .scaleX(0.5f)
                .scaleY(0.5f)
                .alpha(0f)
                .setDuration(300L)
                .setInterpolator(AnticipateInterpolator())
                .start()

            // Анимация фона: плавное исчезновение
            splashScreenView.view.animate()
                .alpha(0f)
                .setDuration(300L)
                .withEndAction {
                    splashScreenView.remove()
                }
                .start()
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(object :
            DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                AppLifecycleState.isAppInForeground.value = true
            }
            override fun onStop(owner: LifecycleOwner) {
                AppLifecycleState.isAppInForeground.value = false
            }
        })

        HapticUtil.perform(this, HapticUtil.Pattern.LAUNCH)
        enableEdgeToEdge()
        setContent {
            val isInitialized by viewModel.isInitialized.collectAsStateWithLifecycle()
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val dynamicTheme by viewModel.dynamicTheme.collectAsStateWithLifecycle()

            val captchaUrl = intent?.getStringExtra("CAPTCHA_URL")

            WireturnTheme(themeMode = themeMode, dynamicColor = dynamicTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (isInitialized) {
                        AppNavigation(
                            viewModel = viewModel,
                            startDestination = if (fromTile) Routes.HOME else null
                        )
                    }

                    if (captchaUrl != null) {
                        CaptchaWebViewDialog(
                            captchaUrl = captchaUrl,
                            onDismiss = {
                                intent?.removeExtra("CAPTCHA_URL")
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        NotificationHelper.cancelCaptchaNotification(this)
    }

    override fun onResume() {
        super.onResume()
        NotificationHelper.cancelCaptchaNotification(this)
    }
}

