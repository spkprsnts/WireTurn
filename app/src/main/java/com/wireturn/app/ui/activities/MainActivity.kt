package com.wireturn.app.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.animation.AnticipateInterpolator
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.CoreTileService
import com.wireturn.app.NotificationHelper
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.navigation.AppNavigation
import com.wireturn.app.ui.screens.CaptchaWebViewDialog
import com.wireturn.app.ui.theme.WireturnTheme
import com.wireturn.app.viewmodel.AppLifecycleState
import com.wireturn.app.viewmodel.MainViewModel

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Обработка перехода из плитки (QS Tile)
        val fromTile = intent?.action == "android.service.quicksettings.action.QS_TILE_PREFERENCES"

        // Only handle on a fresh launch, not on a config-change recreation of the same intent -
        // onNewIntent() covers the case where the app is already running.
        if (savedInstanceState == null) {
            handleDeepLinkIntent(intent)
        }

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

        val appContext = applicationContext
        ProcessLifecycleOwner.get().lifecycle.addObserver(object :
            DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                AppLifecycleState.isAppInForeground.value = true
                // CoreTileService is an ACTIVE_TILE (see AndroidManifest) - the system never
                // auto-calls onStartListening() just because the user opens Quick Settings, so
                // this explicit push is the only thing keeping the tile in sync. Doing it here
                // (process coming to foreground) rather than only once in onCreate() matters
                // most right after an app update while the tunnel was running: the old process
                // is killed with no chance to push a final Idle state, and requestListeningState()
                // needs the app to actually be in the foreground to take effect, which isn't
                // guaranteed yet this early in onCreate().
                CoreTileService.requestUpdate(appContext)
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

            val onboardingDone by viewModel.onboardingDone.collectAsStateWithLifecycle()
            val captchaSession by com.wireturn.app.CoreServiceState.captchaSession.collectAsStateWithLifecycle()
            var lastHandledCaptchaSessionId by remember { mutableLongStateOf(-1L) }

            LaunchedEffect(captchaSession) {
                if (captchaSession != null && captchaSession?.sessionId != lastHandledCaptchaSessionId) {
                    lastHandledCaptchaSessionId = captchaSession?.sessionId ?: -1L
                    val intent = Intent(this@MainActivity, CaptchaActivity::class.java).apply {
                        putExtra("CAPTCHA_URL", captchaSession?.url)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    startActivity(intent)
                }
            }

            WireturnTheme(themeMode = themeMode, dynamicColor = dynamicTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (isInitialized) {
                        if (!onboardingDone && !fromTile) {
                            LaunchedEffect(Unit) {
                                startActivity(Intent(this@MainActivity, OnboardingActivity::class.java))
                                finish()
                            }
                        } else {
                            AppNavigation(viewModel = viewModel)
                        }
                    }

                    if (captchaUrl != null) {
                        CaptchaWebViewDialog(
                            viewModel = viewModel,
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
        handleDeepLinkIntent(intent)
        NotificationHelper.cancelCaptchaNotification(this)
    }

    override fun onResume() {
        super.onResume()
        NotificationHelper.cancelCaptchaNotification(this)
    }

    private fun handleDeepLinkIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        if (uri.scheme?.lowercase() !in setOf("wireturn", "wt")) return
        // Some browsers (e.g. Chrome) pass the referring page/app via getReferrer() when the user
        // taps a link that resolves to this intent-filter; only worth showing if it's an actual
        // website and not e.g. "android-app://com.android.chrome" (the browser itself).
        val referrerHost = referrer?.takeIf { it.scheme == "http" || it.scheme == "https" }?.host
        com.wireturn.app.ui.DeepLinkBus.submit(com.wireturn.app.ui.DeepLinkRequest(uri.toString(), referrerHost))
    }
}
