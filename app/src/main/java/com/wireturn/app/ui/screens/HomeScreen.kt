package com.wireturn.app.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.CoreServiceState
import com.wireturn.app.R
import com.wireturn.app.VpnServiceState
import com.wireturn.app.XrayServiceState
import com.wireturn.app.data.XrayConfiguration
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.ValidatorUtils
import com.wireturn.app.ui.components.CoreToggleButton
import com.wireturn.app.ui.showExclusiveToast
import com.wireturn.app.ui.trackGestureStartedAtBoundary
import com.wireturn.app.viewmodel.CoreState
import com.wireturn.app.viewmodel.MainViewModel
import com.wireturn.app.viewmodel.VpnState
import com.wireturn.app.viewmodel.XrayState
import com.wireturn.app.viewmodel.isImportant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@SuppressLint("BatteryLife")
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onNavigateToExclusions: () -> Unit,
    onNavigateToXrayConfig: () -> Unit,
    onNavigateToConnectionSettings: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToLogs: () -> Unit,
    onToggleProxy: () -> Unit,
    modifier: Modifier = Modifier
) {
    // --- State & Data ---
    val context = LocalContext.current
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val proxyState by viewModel.coreState.collectAsStateWithLifecycle()
    val xrayState by XrayServiceState.state.collectAsStateWithLifecycle()
    val vpnServiceState by VpnServiceState.state.collectAsStateWithLifecycle()

    val clientConfig by viewModel.clientConfig.collectAsStateWithLifecycle()
    val xrayConfig by viewModel.xrayConfig.collectAsStateWithLifecycle()
    val xraySettings by viewModel.xraySettings.collectAsStateWithLifecycle()
    val vlessConfig by viewModel.vlessConfig.collectAsStateWithLifecycle()
    val wgConfig by viewModel.wgConfig.collectAsStateWithLifecycle()
    val vpnSettings by viewModel.vpnSettings.collectAsStateWithLifecycle()

    val proxySession by CoreServiceState.session.collectAsStateWithLifecycle()
    val captchaSession by CoreServiceState.captchaSession.collectAsStateWithLifecycle()
    val partialCaptchaAlerts by viewModel.partialCaptchaAlerts.collectAsStateWithLifecycle()
    val xraySession by XrayServiceState.session.collectAsStateWithLifecycle()

    val batteryNotificationDismissed by viewModel.batteryNotificationDismissed.collectAsStateWithLifecycle()
    val vpnEnabled = vpnSettings.enabled
    val appsExclusionHintShown by viewModel.appsExclusionHintShown.collectAsStateWithLifecycle()
    val privacyMode by viewModel.privacyMode.collectAsStateWithLifecycle()
    val restartAttempt by CoreServiceState.restartAttempt.collectAsStateWithLifecycle()
    val isRestarting = restartAttempt != null
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val updateProgress by viewModel.updateProgress.collectAsStateWithLifecycle()

    val currentProfileId by viewModel.currentProfileId.collectAsStateWithLifecycle()
    val autoLaunchSettings by viewModel.autoLaunchSettings.collectAsStateWithLifecycle()

    val activeConfig = proxySession?.clientConfig ?: clientConfig
    val activeXrayConfig = xraySession?.xray ?: xrayConfig
    val activeXraySettings = xraySession?.settings ?: xraySettings
    val activeWgConfig = xraySession?.wg ?: wgConfig
    val activeVlessConfig = xraySession?.vless ?: vlessConfig

    val isArchitectureSupported = viewModel.isArchitectureSupported
    val deviceArchitecture = viewModel.deviceArchitecture
    val showArchWarning = rememberSaveable { mutableStateOf(!isArchitectureSupported) }

    if (showArchWarning.value) {
        AlertDialog(
            onDismissRequest = { showArchWarning.value = false },
            title = { Text(stringResource(R.string.warn_unsupported_arch_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.warn_unsupported_arch_desc,
                        deviceArchitecture
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { showArchWarning.value = false }) {
                    Text(stringResource(R.string.btn_close))
                }
            }
        )
    }

    val proxyPing by viewModel.proxyPing.collectAsStateWithLifecycle()
    val isNativeTun by viewModel.isNativeTun.collectAsStateWithLifecycle()
    var lastSuccessPing by remember { mutableStateOf<MainViewModel.PingResult.Success?>(null) }
    var isControlPingScheduled by rememberSaveable { mutableStateOf(value = false) }

    val lifecycleOwner = LocalLifecycleOwner.current

    // --- Effects & Lifecycle ---
    LaunchedEffect(proxyPing, proxyState, isNativeTun) {
        if (proxyPing is MainViewModel.PingResult.Success) {
            lastSuccessPing = proxyPing as MainViewModel.PingResult.Success
        }

        val isProxyActive = !isNativeTun &&
                (proxyState is CoreState.Connected || proxyState is CoreState.Suppressed)
        if (isProxyActive) {
            if (proxyPing is MainViewModel.PingResult.Success || proxyPing is MainViewModel.PingResult.Error) {
                if (!isControlPingScheduled) {
                    isControlPingScheduled = true
                    delay(1_000.milliseconds)
                } else {
                    delay(15_000.milliseconds)
                }

                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    viewModel.checkProxyPing()
                }
            } else if (proxyPing == null) {
                viewModel.checkProxyPing(delayFirst = true)
            }
        } else {
            isControlPingScheduled = false
        }
    }

    LaunchedEffect(xraySession?.xray) {
        lastSuccessPing = null
    }

    val proxyTransfer by viewModel.proxyTransfer.collectAsStateWithLifecycle()
    val profilesGestureEnabled by viewModel.profilesGestureEnabled.collectAsStateWithLifecycle()
    val homeScrollState = rememberScrollState()
    val showProfilesDialog = rememberSaveable { mutableStateOf(false) }

    // --- Swipe-up-to-open profiles shade ---
    // The real ModalBottomSheet lives in its own Android Window, so a single continuous touch
    // starting on this screen can never be handed off to drive it directly - there's no public
    // API to feed it raw drag deltas either. So this is a threshold trigger, not a live drag:
    // once an upward swipe past the point where home content can't scroll any further crosses
    // profilesRevealThresholdPx (or is a fast enough flick), it opens the sheet with its normal
    // entrance animation - the same as tapping the profile row.
    //
    // Gated to gestures that already started with nothing left to scroll - otherwise a single
    // swipe that merely scrolls long content down to its end would also fire the shade in the
    // same motion. Reaching the bottom mid-gesture doesn't count; only the *next* swipe does.
    var homeGestureStartedAtBottom by remember { mutableStateOf(false) }
    var profilesRevealDragPx by remember { mutableFloatStateOf(0f) }
    var profilesRevealTriggered by remember { mutableStateOf(false) }
    val profilesRevealThresholdPx = with(LocalDensity.current) { 72.dp.toPx() }
    val profilesRevealConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (!profilesGestureEnabled ||
                    source != NestedScrollSource.UserInput ||
                    !homeGestureStartedAtBottom ||
                    profilesRevealTriggered ||
                    showProfilesDialog.value
                ) {
                    return Offset.Zero
                }
                if (available.y >= 0f || homeScrollState.canScrollForward) {
                    profilesRevealDragPx = 0f
                    return Offset.Zero
                }
                profilesRevealDragPx -= available.y
                if (profilesRevealDragPx > profilesRevealThresholdPx) {
                    profilesRevealTriggered = true
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    showProfilesDialog.value = true
                }
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                profilesRevealDragPx = 0f
                if (profilesGestureEnabled &&
                    homeGestureStartedAtBottom &&
                    !profilesRevealTriggered &&
                    !showProfilesDialog.value &&
                    available.y < -2500f
                ) {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    showProfilesDialog.value = true
                }
                profilesRevealTriggered = false
                return Velocity.Zero
            }
        }
    }

    // --- Launchers ---
    val batteryOptLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* пользователь закрыл диалог батареи — результат нас не интересует */ }

    val pm = remember { context.getSystemService(PowerManager::class.java) }
    var isIgnoringBatteryOptimizations by remember {
        mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName))
    }
    var hasNotificationPermission by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(
                        context,
                        "android.permission.POST_NOTIFICATIONS"
                    ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPermission = isGranted
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.setHomeScreenActive(true)
                    isIgnoringBatteryOptimizations =
                        pm.isIgnoringBatteryOptimizations(context.packageName)
                    hasNotificationPermission =
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(
                            context,
                            "android.permission.POST_NOTIFICATIONS"
                        ) == PackageManager.PERMISSION_GRANTED
                    if (XrayServiceState.state.value == XrayState.Running) {
                        viewModel.checkProxyPing()
                    }
                }

                Lifecycle.Event.ON_PAUSE -> {
                    viewModel.setHomeScreenActive(false)
                }

                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            viewModel.setHomeScreenActive(false)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // --- Business Logic Logic (Mismatches & Alerts) ---
    val warnVpnRequiresXray = stringResource(R.string.warn_vpn_requires_xray)

    val showVpnWarning = {
        if (profiles.isNotEmpty()) {
            context.showExclusiveToast(warnVpnRequiresXray)
        }
    }


    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.setVpnEnabled(true)
        }
    }

    // --- UI Layout ---
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.turn_proxy_title),
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                ),
                actions = {
                    IconButton(
                        onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            onNavigateToLogs()
                        }
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.terminal_24px),
                            contentDescription = stringResource(R.string.logs_title)
                        )
                    }
                    IconButton(
                        onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            onNavigateToSettings()
                        }
                    ) {
                        BadgedBox(
                            badge = {
                                if (updateState.isImportant) {
                                    Badge()
                                }
                            }
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.settings_24px),
                                contentDescription = stringResource(R.string.app_settings_title)
                            )
                        }
                    }
                },
                expandedHeight = 56.dp
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .fillMaxWidth()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 600.dp)
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .trackGestureStartedAtBoundary(
                    atBoundary = { !homeScrollState.canScrollForward },
                    onGestureStart = { homeGestureStartedAtBottom = it }
                )
                .nestedScroll(profilesRevealConnection)
                .verticalScroll(homeScrollState)
                .padding(top = 8.dp)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            LaunchedEffect(hasNotificationPermission, isIgnoringBatteryOptimizations) {
                if (hasNotificationPermission && isIgnoringBatteryOptimizations) {
                    viewModel.setBatteryNotificationDismissed(false)
                }
            }

            // --- Update Banner ---
            UpdateBanner(
                state = updateState,
                progress = updateProgress,
                onDownload = { viewModel.downloadUpdate() },
                onInstall = { viewModel.installUpdate() },
                onCheck = { viewModel.checkForUpdate() },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )

            // --- Permissions & Optimization Banner ---
            PermissionsBanner(
                visible = (!isIgnoringBatteryOptimizations || !hasNotificationPermission) && !batteryNotificationDismissed,
                needsNotificationPermission = !hasNotificationPermission,
                needsBatteryExemption = !isIgnoringBatteryOptimizations,
                onRequestNotifications = { notificationLauncher.launch("android.permission.POST_NOTIFICATIONS") },
                onRequestBatteryExemption = {
                    batteryOptLauncher.launch(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data =
                                "package:${context.packageName}".toUri()
                        }
                    )
                },
                onDismiss = { viewModel.setBatteryNotificationDismissed(true) }
            )

            // --- Proxy Toggle ---
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CoreToggleButton(
                    viewModel = viewModel,
                    onClick = {
                        if (profiles.isEmpty()) {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            context.startActivity(
                                Intent(
                                    context,
                                    com.wireturn.app.ui.activities.AddProfileActivity::class.java
                                )
                            )
                        } else {
                            onToggleProxy()
                        }
                    }
                )
            }

            // --- Pending Captcha ---
            // The way back to a captcha nobody auto-opens: a partial one (the tunnel still runs on
            // its other streams), or any one whose dialog got closed before it was solved.
            CaptchaCard(
                // A partial one only when the user wants to hear about those (app settings).
                session = captchaSession?.takeIf { !it.partial || partialCaptchaAlerts },
                onSolve = { url ->
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    context.startActivity(
                        Intent(
                            context,
                            com.wireturn.app.ui.activities.CaptchaActivity::class.java
                        ).apply {
                            putExtra("CAPTCHA_URL", url)
                            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                    )
                }
            )

            Spacer(Modifier.height(22.dp))

            // --- Ping & Transfer Stats ---
            TunnelStats(
                // Only once the tunnel is really up ("Tunnel active" / "Direct route"): a running
                // Xray or VPN alone isn't enough while the core is still connecting, and Suppressed
                // counts only after Xray has actually switched over to the direct route.
                visible = (proxyState is CoreState.Connected &&
                        (xrayState == XrayState.Running || vpnServiceState == VpnState.Running)) ||
                        (proxyState is CoreState.Suppressed && xrayState == XrayState.DirectRoute),
                transfer = proxyTransfer,
                showPing = !isNativeTun,
                ping = proxyPing,
                lastSuccessPing = lastSuccessPing,
                onPingClick = { viewModel.checkProxyPing() }
            )

            Spacer(Modifier.height(8.dp))

            // --- Profiles Section ---
            ProfilesBlock(
                viewModel = viewModel,
                onClick = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    showProfilesDialog.value = true
                }
            )

            Spacer(Modifier.height(24.dp))

            if (showProfilesDialog.value) {
                ProfilesDialog(
                    viewModel = viewModel,
                    onDismiss = { showProfilesDialog.value = false }
                )
            }

            // --- wireturn:// / wt:// deep link import ---
            DeepLinkImportDialogs(viewModel) { scrollTargetId ->
                showProfilesDialog.value = true
                if (scrollTargetId != null) {
                    scope.launch {
                        delay(300.milliseconds) // let ProfilesDialog compose and its scroll-request collector attach
                        com.wireturn.app.ui.UIEventBus.requestScroll(scrollTargetId)
                    }
                }
            }

            val isSocks5Native = activeConfig.kernelVariant.isSocks5Native
            // The profile is Xray itself (XrayKernel) - there's no overlay to set up or switch.
            val isXrayProfile =
                activeConfig.kernelVariant == com.wireturn.app.data.KernelVariant.XRAY
            val xrayOn = xrayConfig.enabled || isXrayProfile

            // --- Xray & VPN Settings ---
            val isSettingsValid = if (isSocks5Native) {
                // For OLCRTC/WebDAV/qWDTT/OpenFlux, link is only required if DualRoute or
                // Socks5Chain is enabled - matches XrayService's own isConfigValid check.
                if (activeXrayConfig.protocol == XrayConfiguration.VLESS &&
                    (activeVlessConfig.isDualRoute || activeVlessConfig.isSocks5Chain)
                ) {
                    activeVlessConfig.isValid()
                } else {
                    true // WG or VLESS solo mode just uses SOCKS5 from core
                }
            } else {
                if (activeXrayConfig.protocol == XrayConfiguration.VLESS) activeVlessConfig.isValid() else activeWgConfig.isValid()
            }
            val configValid = isSettingsValid || xrayState != XrayState.Idle || isRestarting

            val activeVlessProtocolLabel =
                stringResource(ValidatorUtils.uriProtocolStringRes(activeVlessConfig.vlessLink))

            val xrayProtocol = when {
                isSocks5Native -> {
                    when (xrayState) {
                        XrayState.DirectRoute -> activeVlessProtocolLabel
                        else -> stringResource(R.string.socks5)
                    }
                }

                xrayState == XrayState.Running || xrayState == XrayState.DirectRoute -> xraySession?.vless?.vlessLink?.let {
                    stringResource(ValidatorUtils.uriProtocolStringRes(it))
                } ?: stringResource(R.string.wg_short)

                else -> if (activeXrayConfig.protocol == XrayConfiguration.VLESS) activeVlessProtocolLabel else stringResource(
                    R.string.wg_short
                )
            }

            val profilesExist = profiles.isNotEmpty()
            val currentProfile =
                profiles.find { it.id == currentProfileId } ?: profiles.firstOrNull()
            // Turns Xray off once its config stops being valid (a profile switch, say) - unless
            // auto-launch is about to bring it back. Only with an Xray switch to turn off.
            if (!isXrayProfile) LaunchedEffect(
                configValid,
                xrayConfig.enabled,
                currentProfileId,
                autoLaunchSettings.enabled
            ) {
                delay(300.milliseconds)
                if (!configValid && xrayConfig.enabled && !autoLaunchSettings.enabled) {
                    viewModel.updateXrayConfig(viewModel.xrayConfig.value.copy(enabled = false))
                }
            }

            val toggleVpnAction = { next: Boolean ->
                HapticUtil.perform(
                    context,
                    if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                )

                if (next && !xrayOn && !isSocks5Native) {
                    showVpnWarning()
                }

                if (next) {
                    val intent = VpnService.prepare(context)
                    if (intent != null) {
                        vpnLauncher.launch(intent)
                    } else {
                        viewModel.setVpnEnabled(true)
                    }
                } else {
                    viewModel.setVpnEnabled(false)
                }
            }

            XrayVpnSection(
                isXrayProfile = isXrayProfile,
                profilesExist = profilesExist,
                configValid = configValid,
                xrayProtocol = xrayProtocol,
                xrayEnabled = xrayConfig.enabled,
                xrayState = xrayState,
                currentProfile = currentProfile,
                vpnEnabled = vpnEnabled,
                vpnNeedsXray = vpnEnabled && !xrayOn && !isSocks5Native,
                vpnServiceState = vpnServiceState,
                isVpnModified = xraySession?.wg != null && vpnEnabled != (vpnServiceState == VpnState.Running),
                vpnFilteringEnabled = vpnSettings.filteringEnabled,
                appsExclusionHintShown = appsExclusionHintShown,
                onXrayClick = {
                    if (profilesExist) {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        onNavigateToXrayConfig()
                    }
                },
                onXrayCheckedChange = { next ->
                    HapticUtil.perform(
                        context,
                        if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                    )

                    // OLCRTC/WEBDAV run their own socks5, so VPN mode keeps working
                    // without Xray for those - only warn when it truly can't.
                    if (!next && vpnEnabled && !isSocks5Native) {
                        showVpnWarning()
                    }

                    viewModel.updateXrayConfig(xrayConfig.copy(enabled = next))
                },
                onVpnCheckedChange = toggleVpnAction,
                onAppsExclusionHintShown = { viewModel.setAppsExclusionHintShown(true) },
                onAppsExclusionsClick = onNavigateToExclusions
            )

            Spacer(Modifier.height(12.dp))

            ProxyAddressesSection(
                addresses = proxyAddresses(
                    xrayOn,
                    isSocks5Native,
                    activeXraySettings,
                    activeConfig,
                    xraySession,
                    proxySession
                ),
                socksEnabled = xrayOn || isSocks5Native,
                httpEnabled = xrayOn,
                privacyMode = privacyMode
            )

            Spacer(Modifier.height(2.dp))
            ConnectionSettingsItem(onClick = onNavigateToConnectionSettings)

            Spacer(Modifier.navigationBarsPadding())
            Spacer(Modifier.height(16.dp))
        }
    }
}
