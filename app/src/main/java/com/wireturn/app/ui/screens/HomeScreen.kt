@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.wireturn.app.R
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import com.wireturn.app.data.ThemeMode
import com.wireturn.app.data.DCType
import com.wireturn.app.data.KernelVariant
import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Switch
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import android.content.ClipData
import android.net.VpnService
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.wireturn.app.ui.InlineConfigIndicator
import com.wireturn.app.ui.redact
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.viewmodel.MainViewModel
import com.wireturn.app.viewmodel.ProxyState
import com.wireturn.app.viewmodel.UpdateState
import androidx.core.net.toUri
import com.wireturn.app.ProxyServiceState
import com.wireturn.app.VpnServiceState
import com.wireturn.app.XrayServiceState
import com.wireturn.app.ui.theme.extendedColorScheme
import com.wireturn.app.viewmodel.VpnState
import com.wireturn.app.viewmodel.XrayState
import kotlin.math.ln
import kotlin.math.pow

@SuppressLint("BatteryLife")
@Composable
fun HomeScreen(
    viewModel: MainViewModel
) {
    // --- State & Data ---
    val context = LocalContext.current
    val proxyState by viewModel.proxyState.collectAsStateWithLifecycle()
    val xrayState by XrayServiceState.state.collectAsStateWithLifecycle()
    val vpnServiceState by VpnServiceState.state.collectAsStateWithLifecycle()
    val clientConfig by viewModel.clientConfig.collectAsStateWithLifecycle()
    val xraySettings by viewModel.xraySettings.collectAsStateWithLifecycle()
    val xrayConfig by viewModel.xrayConfig.collectAsStateWithLifecycle()
    val batteryNotificationDismissed by viewModel.batteryNotificationDismissed.collectAsStateWithLifecycle()
    val customKernelExists by viewModel.customKernelExists.collectAsStateWithLifecycle()
    val vlessConfig by viewModel.vlessConfig.collectAsStateWithLifecycle()
    val runningConfig by ProxyServiceState.runningConfig.collectAsStateWithLifecycle()
    val runningWgConfig by XrayServiceState.runningWgConfig.collectAsStateWithLifecycle()
    val runningVlessConfig by XrayServiceState.runningVlessConfig.collectAsStateWithLifecycle()
    val runningXrayConfig by XrayServiceState.runningXrayConfig.collectAsStateWithLifecycle()

    val proxyPing by viewModel.proxyPing.collectAsStateWithLifecycle()
    var lastSuccessPing by remember { mutableStateOf<MainViewModel.PingResult.Success?>(null) }
    
    // --- Effects & Lifecycle ---
    LaunchedEffect(proxyPing) {
        if (proxyPing is MainViewModel.PingResult.Success) {
            lastSuccessPing = proxyPing as MainViewModel.PingResult.Success
        }
    }
    LaunchedEffect(runningXrayConfig) {
        lastSuccessPing = null
    }

    val proxyTransfer by viewModel.proxyTransfer.collectAsStateWithLifecycle()
    val wgConfig by viewModel.wgConfig.collectAsStateWithLifecycle()

    val activeConfig = runningConfig ?: clientConfig
    val activeWgConfig = runningWgConfig ?: wgConfig
    val activeVlessConfig = runningVlessConfig ?: vlessConfig

    val configChanged = remember(clientConfig, runningConfig, wgConfig, runningWgConfig, vlessConfig, runningVlessConfig, xrayConfig, runningXrayConfig) {
        (runningConfig != null && clientConfig != runningConfig) ||
                (runningWgConfig != null && wgConfig != runningWgConfig) ||
                (runningVlessConfig != null && vlessConfig != runningVlessConfig) ||
                (runningXrayConfig != null && xrayConfig != runningXrayConfig)
    }

    val mainConfigChanged = remember(clientConfig, runningConfig) {
        runningConfig != null && clientConfig != runningConfig
    }
    val xrayConfigChanged = remember(wgConfig, runningWgConfig, vlessConfig, runningVlessConfig, xrayConfig, runningXrayConfig) {
        (runningWgConfig != null && wgConfig != runningWgConfig) ||
                (runningVlessConfig != null && vlessConfig != runningVlessConfig) ||
                (runningXrayConfig != null && xrayConfig != runningXrayConfig)
    }

    val snackbarHostState = remember { SnackbarHostState() }

    val lifecycleOwner = LocalLifecycleOwner.current
    
    // --- Launchers ---
    val batteryOptLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* пользователь закрыл диалог батареи — результат нас не интересует */ }

    val pm = remember { context.getSystemService(PowerManager::class.java) }
    var isIgnoringBatteryOptimizations by remember {
        mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName))
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    val profileImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            context.contentResolver.openInputStream(it)?.use { stream ->
                val json = stream.bufferedReader().readText()
                viewModel.importProfile(json)
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.setHomeScreenActive(true)
                    isIgnoringBatteryOptimizations = pm.isIgnoringBatteryOptimizations(context.packageName)
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

    val privacyMode by viewModel.privacyMode.collectAsStateWithLifecycle()
    val showBottomSheet = rememberSaveable { mutableStateOf(false) }
    val showAppsDialog = rememberSaveable { mutableStateOf(false) }
    var isAppsLoading by remember { mutableStateOf(false) }
    var loadedAppList by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    val bottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.updateXraySettings(viewModel.xraySettings.value.copy(xrayVpnMode = true))
        }
    }

    // --- Business Logic Logic (Mismatches & Alerts) ---
    val warnVlessMismatch = stringResource(R.string.warn_proxy_vless_mismatch)
    val warnWgMismatch = stringResource(R.string.warn_proxy_wg_mismatch)
    val warnVpnRequiresXray = stringResource(R.string.warn_vpn_requires_xray)

    val scope = rememberCoroutineScope()
    var hasShownMismatchForCurrentRun by remember { mutableStateOf(xrayState == XrayState.Running) }
    LaunchedEffect(xrayState) {
        if (xrayState != XrayState.Running) {
            hasShownMismatchForCurrentRun = false
        }
    }

    LaunchedEffect(xrayState, runningConfig, runningVlessConfig, runningWgConfig) {
        if (xrayState != XrayState.Running || hasShownMismatchForCurrentRun) return@LaunchedEffect
        val config = runningConfig ?: return@LaunchedEffect
        if (config.isRawMode) return@LaunchedEffect
        val supportsMismatchCheck = config.dcMode || config.kernelVariant == KernelVariant.VK_TURN_PROXY
        if (!supportsMismatchCheck) return@LaunchedEffect

        val xrayIsVlessRunning = runningVlessConfig != null
        val xrayShouldBeVless = config.vlessMode
        if (xrayIsVlessRunning != xrayShouldBeVless) {
            hasShownMismatchForCurrentRun = true
            snackbarHostState.showSnackbar(
                message = if (xrayShouldBeVless) warnVlessMismatch else warnWgMismatch,
                duration = androidx.compose.material3.SnackbarDuration.Long
            )
        }
    }

    // --- UI Layout ---
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.turn_proxy_title)) },
                actions = {
                    IconButton(onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        showBottomSheet.value = true
                    }) {
                        Icon(painterResource(R.drawable.info_24px), contentDescription = stringResource(R.string.info_desc))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .fillMaxWidth()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 600.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true

            LaunchedEffect(hasNotificationPermission, isIgnoringBatteryOptimizations) {
                if (hasNotificationPermission && isIgnoringBatteryOptimizations) {
                    viewModel.setBatteryNotificationDismissed(false)
                }
            }

            // 1. Permission & Optimization Banner
            AnimatedVisibility(
                visible = (!isIgnoringBatteryOptimizations || !hasNotificationPermission) && !batteryNotificationDismissed,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                    ),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(R.drawable.error_24px),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.permissions_title),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.permissions_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(modifier = Modifier.weight(1f)) {
                                if (!hasNotificationPermission) {
                                    Button(
                                        onClick = {
                                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.onErrorContainer,
                                            contentColor = MaterialTheme.colorScheme.errorContainer
                                        ),
                                        contentPadding = PaddingValues(horizontal = 12.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Icon(painterResource(R.drawable.info_24px), null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.permission_notifications), style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                                if (!hasNotificationPermission && !isIgnoringBatteryOptimizations) {
                                    Spacer(Modifier.width(8.dp))
                                }
                                if (!isIgnoringBatteryOptimizations) {
                                    Button(
                                        onClick = {
                                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                            batteryOptLauncher.launch(
                                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                                    data = "package:${context.packageName}".toUri()
                                                }
                                            )
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.onErrorContainer,
                                            contentColor = MaterialTheme.colorScheme.errorContainer
                                        ),
                                        contentPadding = PaddingValues(horizontal = 12.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Icon(painterResource(R.drawable.power_24px), null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.permission_battery), style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                            
                            TextButton(
                                onClick = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                    viewModel.setBatteryNotificationDismissed(true)
                                }
                            ) {
                                Text(
                                    stringResource(R.string.btn_close),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            // 2. Proxy Toggle Button
            ProxyToggleButton(
                state = proxyState,
                onClick = {
                    when (proxyState) {
                        is ProxyState.Idle, is ProxyState.Error -> {
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
                        is ProxyState.Running, is ProxyState.Working, is ProxyState.CaptchaRequired -> {
                            HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_OFF)
                            viewModel.stopProxy()
                        }
                        else -> {}
                    }
                }
            )

            Spacer(Modifier.height(20.dp))

            Text(
                text = when (proxyState) {
                    is ProxyState.Working -> stringResource(if (customKernelExists) R.string.proxy_running else R.string.proxy_active)
                    is ProxyState.Starting -> stringResource(R.string.proxy_starting)
                    is ProxyState.Running -> stringResource(R.string.proxy_connecting)
                    is ProxyState.Error -> (proxyState as ProxyState.Error).message
                    is ProxyState.CaptchaRequired -> stringResource(R.string.proxy_captcha_required)
                    else -> stringResource(R.string.proxy_press_to_start)
                },
                style = MaterialTheme.typography.titleMedium,
                color = when (proxyState) {
                    is ProxyState.Working -> MaterialTheme.colorScheme.primary
                    is ProxyState.Running, is ProxyState.CaptchaRequired -> MaterialTheme.colorScheme.tertiary
                    is ProxyState.Error -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
                },
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(10.dp))
            
            // 4. Ping & Transfer Stats
            AnimatedVisibility(
                visible = xrayState == XrayState.Running,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column (
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Surface(
                            onClick = {
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                viewModel.checkProxyPing()
                            },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = null
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.wifi_24px),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .offset(y = (-0.5).dp)
                                )
                                Box(
                                    modifier = Modifier.widthIn(min = 48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val currentPing = proxyPing
                                    val infiniteTransition = rememberInfiniteTransition(label = "ping_pulse")
                                    val pulseAlpha by infiniteTransition.animateFloat(
                                        initialValue = 0.3f,
                                        targetValue = 0.9f,
                                        animationSpec = infiniteRepeatable(
                                            animation = tween(1000, easing = LinearEasing),
                                            repeatMode = RepeatMode.Reverse
                                        ),
                                        label = "pulse_alpha"
                                    )

                                    when (currentPing) {
                                        is MainViewModel.PingResult.Loading -> {
                                            if (lastSuccessPing != null) {
                                                Text(
                                                    text = stringResource(R.string.ping_ms, lastSuccessPing!!.ms),
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = when {
                                                        lastSuccessPing!!.ms < 150 -> MaterialTheme.extendedColorScheme.success
                                                        lastSuccessPing!!.ms < 300 -> MaterialTheme.extendedColorScheme.warning
                                                        else -> MaterialTheme.colorScheme.error
                                                    }.copy(alpha = pulseAlpha),
                                                    fontWeight = FontWeight.Bold
                                                )
                                            } else {
                                                Box(
                                                    modifier = Modifier
                                                        .size(width = 42.dp, height = 14.dp)
                                                        .graphicsLayer { alpha = pulseAlpha }
                                                        .background(
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                                                            shape = CircleShape
                                                        )
                                                )
                                            }
                                        }

                                        is MainViewModel.PingResult.Success -> {
                                            Text(
                                                text = stringResource(R.string.ping_ms, currentPing.ms),
                                                style = MaterialTheme.typography.labelLarge,
                                                color = when {
                                                    currentPing.ms < 150 -> MaterialTheme.extendedColorScheme.success
                                                    currentPing.ms < 300 -> MaterialTheme.extendedColorScheme.warning
                                                    else -> MaterialTheme.colorScheme.error
                                                },
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        is MainViewModel.PingResult.Error -> {
                                            Text(
                                                text = stringResource(R.string.ping_error),
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }

                                        null -> {
                                            Text(
                                                text = stringResource(R.string.ping_unknown),
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // peer receive/sent
                    Spacer(Modifier.height(5.dp))
                    Row (
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val transfer = proxyTransfer
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.arrow_downward_24px),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                modifier = Modifier.size(14.dp)
                            )
                            Column(horizontalAlignment = Alignment.Start) {
                                Text(
                                    text = formatBytes(transfer?.rx ?: 0L),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = formatSpeed(transfer?.rxSpeed ?: 0L),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.arrow_upward_24px),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                modifier = Modifier.size(14.dp)
                            )
                            Column(horizontalAlignment = Alignment.Start) {
                                Text(
                                    text = formatBytes(transfer?.tx ?: 0L),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = formatSpeed(transfer?.txSpeed ?: 0L),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // 3. Profiles Block
            val showProfilesDialog = rememberSaveable { mutableStateOf(false) }
            ProfilesBlock(viewModel) {
                showProfilesDialog.value = true
            }

            if (showProfilesDialog.value) {
                ProfilesDialog(
                    viewModel = viewModel,
                    onImport = { profileImportLauncher.launch(arrayOf("application/json")) },
                    onDismiss = { showProfilesDialog.value = false }
                )
            }

            Spacer(Modifier.height(16.dp))

            // 5. Xray & VPN Settings Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp, horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    )
                    {
                        when(xrayState) {
                            XrayState.Idle -> Icon(
                                painter = painterResource(
                                    R.drawable.stop_24px
                                ),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(36.dp)
                            )
                            XrayState.Starting -> CircularWavyProgressIndicator(modifier = Modifier.size(36.dp))
                            XrayState.Running -> Icon(
                                painter = painterResource(
                                    R.drawable.check_circle_24px
                                ),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                        }



                        val isSettingsValid = if (xrayConfig.xrayConfiguration == com.wireturn.app.data.XrayConfiguration.VLESS) activeVlessConfig.isValid() else activeWgConfig.isValid()
                        val configValid = isSettingsValid || xrayState != XrayState.Idle

                        val xrayProtocol = when {
                            xrayState != XrayState.Idle -> {
                                if (runningVlessConfig != null) stringResource(R.string.vless) else stringResource(R.string.wg_short)
                            }
                            else -> if (xrayConfig.xrayConfiguration == com.wireturn.app.data.XrayConfiguration.VLESS) stringResource(R.string.vless) else stringResource(R.string.wg_short)
                        }

                        LaunchedEffect(configValid, xraySettings.xrayEnabled) {
                            if(!configValid && xraySettings.xrayEnabled) {
                                viewModel.updateXraySettings(viewModel.xraySettings.value.copy(xrayEnabled = false))
                            }
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = stringResource(R.string.xray_title) + " " + xrayProtocol,
                                style = MaterialTheme.typography.titleMedium
                            )
                            AnimatedVisibility(
                                visible = !configValid || xrayState != XrayState.Idle,
                                enter = fadeIn() + expandVertically(),
                                exit = fadeOut() + shrinkVertically()
                            ) {
                                Text(
                                    text = if (!configValid) {
                                        stringResource(R.string.xray_config_invalid)
                                    } else {
                                        when (xrayState) {
                                            XrayState.Starting -> stringResource(R.string.xray_starting_state)
                                            XrayState.Running -> stringResource(R.string.xray_running_state)
                                            else -> ""
                                        }
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Switch(
                            checked = xraySettings.xrayEnabled,
                            onCheckedChange = { enabled ->
                                HapticUtil.perform(
                                    context,
                                    if (enabled) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                                )
                                viewModel.updateXraySettings(viewModel.xraySettings.value.copy(xrayEnabled = enabled))
                            },
                            enabled = configValid
                        )
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp, horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                            Row(
                                modifier = Modifier
                                    .padding(start = 6.dp)
                                    .weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(18.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.vpn_key_24px),
                                    contentDescription = null,
                                    tint = when (vpnServiceState) {
                                        VpnState.Running -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier
                                        .size(24.dp)
                                        .offset(y = (-1).dp)
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        stringResource(R.string.vpn_mode),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    InlineConfigIndicator(runningWgConfig != null && xraySettings.xrayVpnMode != (vpnServiceState == VpnState.Running))
                                }
                            }

                        IconButton(onClick = {
                            if (isAppsLoading) return@IconButton
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            if (loadedAppList.isNotEmpty()) {
                                showAppsDialog.value = true
                            } else {
                                isAppsLoading = true
                                scope.launch(Dispatchers.IO) {
                                    val pm = context.packageManager
                                    val apps = pm.getInstalledApplications(0)
                                    val result = apps.map { app ->
                                        AppInfo(
                                            packageName = app.packageName,
                                            name = pm.getApplicationLabel(app).toString(),
                                            isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                                        )
                                    }.filter { it.name.isNotBlank() && it.packageName != context.packageName }

                                    withContext(Dispatchers.Main) {
                                        loadedAppList = result
                                        isAppsLoading = false
                                        showAppsDialog.value = true
                                    }
                                }
                            }
                        }) {
                            if (isAppsLoading) {
                                CircularWavyProgressIndicator(
                                    modifier = Modifier.size(20.dp)
                                )
                            } else {
                                Icon(
                                    painter = painterResource(R.drawable.apps_24px),
                                    contentDescription = stringResource(R.string.vpn_apps_exceptions),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Switch(checked = xraySettings.xrayVpnMode, onCheckedChange = { enabled ->
                            HapticUtil.perform(
                                context,
                                if (enabled) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                            )
                            if (enabled) {
                                if (!xraySettings.xrayEnabled) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            message = warnVpnRequiresXray,
                                            duration = androidx.compose.material3.SnackbarDuration.Short
                                        )
                                    }
                                }
                                val intent = VpnService.prepare(context)
                                if (intent != null) {
                                    vpnLauncher.launch(intent)
                                } else {
                                    viewModel.updateXraySettings(viewModel.xraySettings.value.copy(xrayVpnMode = true))
                                }
                            } else {
                                viewModel.updateXraySettings(viewModel.xraySettings.value.copy(xrayVpnMode = false))
                            }
                        })
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    Column {
                        Column {
                            val clipboard = LocalClipboard.current

                            val socks5Label = stringResource(R.string.clipboard_label_socks5)
                            val httpLabel = stringResource(R.string.clipboard_label_http)

                            ProxyAddressRow(
                                label = stringResource(R.string.xray_socks5),
                                address = xrayConfig.socksBindAddress,
                                isModified = runningXrayConfig != null && xrayConfig.socksBindAddress != runningXrayConfig?.socksBindAddress,
                                onCopy = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                    scope.launch {
                                        clipboard.setClipEntry(ClipData.newPlainText(socks5Label, it).toClipEntry())
                                    }
                                }
                            )

                            if (xrayConfig.httpBindAddress.isNotBlank() || (runningXrayConfig != null && xrayConfig.httpBindAddress != runningXrayConfig?.httpBindAddress)) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                )

                                ProxyAddressRow(
                                    label = stringResource(R.string.xray_http),
                                    address = xrayConfig.httpBindAddress,
                                    isModified = runningXrayConfig != null && xrayConfig.httpBindAddress != runningXrayConfig?.httpBindAddress,
                                    onCopy = {
                                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                        scope.launch {
                                            clipboard.setClipEntry(
                                                ClipData.newPlainText(
                                                    httpLabel,
                                                    it
                                                ).toClipEntry()
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 6. Current Connection Details
            if (activeConfig.isValid) {
                Spacer(Modifier.height(16.dp))

                AnimatedVisibility(
                    visible = (proxyState is ProxyState.Working || proxyState is ProxyState.Running || proxyState is ProxyState.Starting || proxyState is ProxyState.CaptchaRequired) && configChanged,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Surface(
                        onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            if (mainConfigChanged) {
                                viewModel.restartProxy()
                            } else if (xrayConfigChanged) {
                                viewModel.restartXray()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .padding(bottom = 12.dp),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        tonalElevation = 2.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.refresh_24px),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.restart_required),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (mainConfigChanged) stringResource(R.string.restart_reason_client) else stringResource(R.string.restart_reason_xray),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                )
                            }
                            Text(
                                text = stringResource(R.string.btn_restart),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.current_client_settings), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(12.dp))
                        if (activeConfig.isRawMode) {
                            Text(
                                stringResource(R.string.raw_mode),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(4.dp))

                            // Разбираем rawCommand с группировкой параметров и значений
                            val parts = activeConfig.rawCommand.split("\\s+".toRegex())
                                .filter { it.isNotBlank() }
                            val args = mutableListOf<String>()
                            var i = 0
                            while (i < parts.size) {
                                val part = parts[i]
                                if (part.startsWith("-") && i + 1 < parts.size && !parts[i + 1].startsWith(
                                        "-"
                                    )
                                ) {
                                    // Параметр со значением: объединяем в одну строку
                                    args.add("$part ${parts[i + 1]}")
                                    i += 2
                                } else {
                                    // Флаг без значения или одиночный элемент
                                    args.add(part)
                                    i += 1
                                }
                            }
                            Column {
                                args.forEach { arg ->
                                    Text(
                                        arg,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(4.dp))  // Небольшой отступ между аргументами
                                }
                            }
                        } else {
                            if (activeConfig.dcMode) {
                                ConfigRow(
                                    stringResource(R.string.tunnel_label),
                                    stringResource(R.string.dc_tunnel)
                                )
                                ConfigRow(
                                    stringResource(R.string.dc_type_label),
                                    stringResource(if (activeConfig.dcType == DCType.SALUTE_JAZZ) R.string.jazz_label else R.string.wb_stream_label)
                                )
                                if (activeConfig.dcType == DCType.SALUTE_JAZZ) {
                                    ConfigRow(
                                        stringResource(R.string.jazz_room),
                                        activeConfig.jazzCreds.redact(privacyMode)
                                    )
                                } else {
                                    val wbstreamUuid = activeConfig.wbstreamUuid.redact(privacyMode)
                                    if (activeConfig.wbstreamUuid.isNotBlank()) {
                                        ConfigRow(
                                            stringResource(R.string.wbstream_uuid_label),
                                            if (wbstreamUuid.length > 30) {
                                                wbstreamUuid.take(8) + "..." + wbstreamUuid.takeLast(6)
                                            } else {
                                                wbstreamUuid.ifBlank { stringResource(R.string.not_set) }
                                            }
                                        )
                                    }
                                }
                                if (activeConfig.vlessMode) {
                                    ConfigRow(
                                        stringResource(R.string.transport_protocol),
                                        stringResource(R.string.vless)
                                    )
                                }
                            } else {
                                ConfigRow(
                                    stringResource(R.string.tunnel_label),
                                    stringResource(R.string.turn_tunnel)
                                )
                                when (activeConfig.kernelVariant) {
                                    KernelVariant.VK_TURN_PROXY -> {
                                        ConfigRow(
                                            stringResource(R.string.server),
                                            activeConfig.serverAddress.redact(privacyMode)
                                        )
                                        val vkLink = activeConfig.vkLink.redact(privacyMode)
                                        if (activeConfig.vkLink.isNotBlank()) {
                                            ConfigRow(
                                                stringResource(R.string.vk_link_label),
                                                if (vkLink.length > 30) {
                                                    vkLink.take(21) + "..." + vkLink.takeLast(6)
                                                } else {
                                                    vkLink.ifBlank { stringResource(R.string.not_set) }
                                                }
                                            )
                                        }
                                        ConfigRow(
                                            stringResource(R.string.threads),
                                            "${activeConfig.threads}"
                                        )
                                        ConfigRow(
                                            stringResource(R.string.transport_protocol),
                                            stringResource(if (activeConfig.vlessMode) R.string.vless else {
                                                if (activeConfig.useUdp) R.string.udp else R.string.tcp
                                            })
                                        )
                                        if (activeConfig.noDtls && activeConfig.useUdp) {
                                            ConfigRow(stringResource(R.string.no_dtls), stringResource(R.string.check_mark))
                                        }
                                        if (activeConfig.manualCaptcha) {
                                            ConfigRow(stringResource(R.string.manual_captcha), stringResource(R.string.check_mark))
                                        }
                                        if (activeConfig.forceTurnPort443) {
                                            ConfigRow(stringResource(R.string.force_turn_port_443), stringResource(R.string.check_mark))
                                        }
                                    }

                                    KernelVariant.TURNABLE -> {
                                        val turnableUrl = activeConfig.turnableUrl.redact(privacyMode)
                                        if (activeConfig.turnableUrl.isNotBlank()) {
                                            ConfigRow(
                                                stringResource(R.string.turnable_url_label),
                                                if (turnableUrl.length > 30) {
                                                    turnableUrl.take(21) + "..." + turnableUrl.takeLast(6)
                                                } else {
                                                    turnableUrl.ifBlank { stringResource(R.string.not_set) }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            ConfigRow(
                                stringResource(R.string.local_port),
                                activeConfig.localPort.redact(privacyMode)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }

    if (showBottomSheet.value) {
        val sheetColor = MaterialTheme.colorScheme.surfaceContainerLow
        val showRepoLinks = rememberSaveable { mutableStateOf(false) }

        ModalBottomSheet(
            onDismissRequest = { 
                showBottomSheet.value = false
                showRepoLinks.value = false
            },
            sheetState = bottomSheetState,
            containerColor = sheetColor
        ) {
            when {
                showRepoLinks.value -> {
                    RepoLinksContent(
                        containerColor = sheetColor,
                        onBack = { showRepoLinks.value = false }
                    )
                }
                else -> {
                    InfoBottomSheet(
                        viewModel = viewModel,
                        containerColor = sheetColor,
                        privacyMode = privacyMode,
                        onPrivacyModeChange = { viewModel.setPrivacyMode(it) },
                        onOpenRepoLinks = { showRepoLinks.value = true }
                    )
                }
            }
        }
    }

    if (showAppsDialog.value) {
        AppExceptionsDialog(
            viewModel = viewModel,
            appList = loadedAppList,
            onDismiss = { showAppsDialog.value = false }
        )
    }

    UpdateDialogs(viewModel)
}

// --- Dialogs & Sheets ---

@Composable
private fun AppExceptionsDialog(
    viewModel: MainViewModel,
    appList: List<AppInfo>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val xraySettings by viewModel.xraySettings.collectAsStateWithLifecycle()
    
    // Используем mutableStateOf для хранения списка, чтобы иметь возможность его обновить (пересортировать)
    var initialExcludedApps by remember { mutableStateOf(xraySettings.excludedApps) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val sortedAppList = remember(initialExcludedApps, appList) {
        appList.sortedWith(
                compareByDescending<AppInfo> { initialExcludedApps.contains(it.packageName) }
                    .thenBy { it.name.lowercase() }
            )
    }

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboard.current
    val listState = rememberLazyListState()
    
    var newlyAddedPackages by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(newlyAddedPackages) {
        if (newlyAddedPackages.isNotEmpty()) {
            kotlinx.coroutines.delay(1500)
            newlyAddedPackages = emptySet()
        }
    }

    val handleDismiss = {
        onDismiss()
    }

    val filteredApps = remember(searchQuery, sortedAppList) {
        if (searchQuery.isBlank()) sortedAppList
        else sortedAppList.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = handleDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .widthIn(max = 600.dp)
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        title = {
            Text(stringResource(R.string.vpn_apps_exceptions))
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.search_apps)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = CircleShape,
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.search_24px),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    painter = painterResource(R.drawable.close_24px),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                )

                Box(modifier = Modifier.height(400.dp)) {
                    if (filteredApps.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.no_apps_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(filteredApps, key = { it.packageName }) { app ->
                                val isExcluded = xraySettings.excludedApps.contains(app.packageName)
                                val isNewlyAdded = newlyAddedPackages.contains(app.packageName)
                                val itemShape = MaterialTheme.shapes.medium

                                val backgroundColor by animateColorAsState(
                                    targetValue = when {
                                        isNewlyAdded -> MaterialTheme.colorScheme.primaryContainer
                                        isExcluded -> MaterialTheme.colorScheme.surfaceContainerHigh
                                        else -> Color.Transparent
                                    },
                                    label = "item_bg_color"
                                )
                                
                                Surface(
                                    onClick = {
                                        HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                                        viewModel.toggleAppExclusion(app.packageName)
                                    },
                                    shape = itemShape,
                                    color = backgroundColor,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .animateItem()
                                ) {
                                    ListItem(
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                        headlineContent = { 
                                            Text(
                                                app.name, 
                                                maxLines = 1, 
                                                overflow = TextOverflow.Ellipsis,
                                                fontWeight = if (isExcluded || isNewlyAdded) FontWeight.SemiBold else FontWeight.Normal
                                            ) 
                                        },
                                        supportingContent = { 
                                            Text(
                                                app.packageName, 
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                            ) 
                                        },
                                        leadingContent = {
                                            val icon = remember(app.packageName) {
                                                try {
                                                    context.packageManager.getApplicationIcon(app.packageName)
                                                        .toBitmap().asImageBitmap()
                                                } catch (_: Exception) { null }
                                            }
                                            if (icon != null) {
                                                Image(
                                                    bitmap = icon,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(32.dp)
                                                )
                                            } else {
                                                Icon(
                                                    painterResource(R.drawable.mobile_24px),
                                                    null,
                                                    modifier = Modifier.size(32.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                                )
                                            }
                                        },
                                        trailingContent = {
                                            Checkbox(
                                                checked = isExcluded,
                                                onCheckedChange = null // Обработка в Surface.onClick
                                            )
                                        },
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val importSuccessMsg = stringResource(R.string.apps_imported_count)
                val importFailMsg = stringResource(R.string.no_apps_imported)

                TextButton(onClick = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    scope.launch {
                        val clipData = clipboard.getClipEntry()?.clipData
                        if (clipData != null && clipData.itemCount > 0) {
                            val text = clipData.getItemAt(0).text?.toString() ?: ""
                            val packagesToImport = text.split(Regex("[\\s,;|]+"))
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                            
                            if (packagesToImport.isNotEmpty()) {
                                val allPackages = sortedAppList.map { it.packageName }.toSet()
                                val validPackages = packagesToImport.filter { allPackages.contains(it) }.toSet()
                                
                                if (validPackages.isNotEmpty()) {
                                    val currentExcluded = xraySettings.excludedApps
                                    val newExcluded = currentExcluded + validPackages
                                    if (newExcluded != currentExcluded) {
                                        viewModel.updateXraySettings(xraySettings.copy(excludedApps = newExcluded))
                                        newlyAddedPackages = validPackages
                                        initialExcludedApps = newExcluded // Trigger re-sort
                                        listState.animateScrollToItem(0)
                                    }
                                    snackbarHostState.showSnackbar(importSuccessMsg.format(validPackages.size))
                                } else {
                                    snackbarHostState.showSnackbar(importFailMsg)
                                }
                            }
                        }
                    }
                }) {
                    Text(stringResource(R.string.import_from_clipboard))
                }
                
                TextButton(onClick = handleDismiss) {
                    Text(stringResource(R.string.btn_close))
                }
            }
        }
    )
}

data class AppInfo(
    val packageName: String,
    val name: String,
    val isSystem: Boolean
)

@Composable
private fun UpdateDialogs(viewModel: MainViewModel) {
    val context = LocalContext.current
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()

    when (val state = updateState) {
        is UpdateState.Available -> {
            AlertDialog(
                onDismissRequest = { viewModel.resetUpdateState() },
                title = { Text(stringResource(R.string.update_available_title)) },
                text = {
                    Column {
                        Text(stringResource(R.string.update_available, state.version))
                        if (state.changelog.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Column(
                                modifier = Modifier
                                    .heightIn(max = 200.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    state.changelog,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        viewModel.downloadUpdate()
                    }) { Text(stringResource(R.string.update_download)) }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.resetUpdateState() }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        is UpdateState.Downloading -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.update_downloading_title)) },
                text = {
                    Column {
                        Text(stringResource(R.string.update_downloading, state.progress))
                        Spacer(Modifier.height(12.dp))
                        LinearWavyProgressIndicator(
                            progress = { state.progress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {}
            )
        }

        is UpdateState.ReadyToInstall -> {
            AlertDialog(
                onDismissRequest = { viewModel.resetUpdateState() },
                title = { Text(stringResource(R.string.update_ready_title)) },
                text = { Text(stringResource(R.string.update_ready_desc)) },
                confirmButton = {
                    TextButton(onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        viewModel.installUpdate()
                    }) { Text(stringResource(R.string.update_install)) }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.resetUpdateState() }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        else -> {}
    }
}

// --- UI Components ---

// Кнопка прокси
@Composable
private fun ProxyToggleButton(state: ProxyState, onClick: () -> Unit) {
    val containerColor by animateColorAsState(
        targetValue = when (state) {
            is ProxyState.Working -> MaterialTheme.colorScheme.primary
            is ProxyState.Running, is ProxyState.CaptchaRequired -> MaterialTheme.colorScheme.tertiary
            is ProxyState.Error -> MaterialTheme.colorScheme.errorContainer
            is ProxyState.Starting -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = tween(600),
        label = "btn_bg"
    )
    val contentColor by animateColorAsState(
        targetValue = when (state) {
            is ProxyState.Working -> MaterialTheme.colorScheme.onPrimary
            is ProxyState.Running, is ProxyState.CaptchaRequired -> MaterialTheme.colorScheme.onTertiary
            is ProxyState.Error -> MaterialTheme.colorScheme.onErrorContainer
            is ProxyState.Starting -> MaterialTheme.colorScheme.onSecondaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(600),
        label = "btn_fg"
    )
    val scale by animateFloatAsState(
        targetValue = when (state) {
            is ProxyState.Idle, is ProxyState.Error -> 1f
            is ProxyState.Starting, is ProxyState.Running, is ProxyState.CaptchaRequired -> 0.92f
            else -> 0.96f
        },
        animationSpec = spring(
            dampingRatio = 0.3f, // Значения меньше 0.5 дают очень сильный резонанс
            stiffness = Spring.StiffnessLow
        ),
        label = "btn_scale"
    )

    // Вычисляем размер тени отдельно для анимации (опционально)
    val elevation by animateDpAsState(
        targetValue = when (state) {
            is ProxyState.Idle, is ProxyState.Error -> 16.dp
            is ProxyState.Starting, is ProxyState.Running, is ProxyState.CaptchaRequired -> 2.dp
            else -> 6.dp
        },
        label = "elevation"
    )

    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(148.dp)
            .scale(scale)
            .shadow(
                elevation = elevation,
                shape = CircleShape,
                ambientColor = Color.Black.copy(alpha = 0.8f),
                spotColor = Color.Black.copy(alpha = 0.2f)
            )
            .clip(CircleShape),
        shape = CircleShape,
        color = containerColor,
        shadowElevation = 0.dp,
        tonalElevation = elevation
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (state) {
                is ProxyState.Starting, is ProxyState.Running, is ProxyState.CaptchaRequired -> CircularWavyProgressIndicator(color = contentColor)
                is ProxyState.Working -> Icon(
                    painterResource(R.drawable.check_circle_24px), stringResource(R.string.proxy_active_stop),
                    Modifier.size(66.dp), tint = contentColor
                )
                is ProxyState.Error -> Icon(
                    painterResource(R.drawable.error_24px), stringResource(R.string.proxy_error_restart),
                    Modifier.size(66.dp), tint = contentColor
                )
                else -> Icon(
                    painterResource(R.drawable.power_24px), stringResource(R.string.start_proxy),
                    Modifier.size(66.dp), tint = contentColor
                )
            }
        }
    }
}

// Bottom sheet

@Composable
private fun InfoBottomSheet(
    viewModel: MainViewModel,
    containerColor: Color,
    privacyMode: Boolean,
    onPrivacyModeChange: (Boolean) -> Unit,
    onOpenRepoLinks: () -> Unit
) {
    val context = LocalContext.current
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val dynamicTheme by viewModel.dynamicTheme.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val showResetDialog = rememberSaveable { mutableStateOf(false) }

    val dash = stringResource(R.string.dash)
    val appVersion = remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: dash }
        catch (_: Exception) { dash }
    }

    val listColors = ListItemDefaults.colors(containerColor = containerColor)

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        // Ссылки на репозитории
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.repo_links)) },
                supportingContent = { Text(stringResource(R.string.repo_links_desc)) },
                leadingContent = {
                    Icon(
                        painter = painterResource(R.drawable.open_in_new_24px),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                colors = listColors,
                modifier = Modifier.clickable {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    onOpenRepoLinks()
                }
            )
        }

        item { HorizontalDivider() }

        // Настройки интерфейса
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.theme_title)) },
                supportingContent = {
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        ThemeMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = themeMode == mode,
                                onClick = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                    viewModel.setThemeMode(mode)
                                },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = ThemeMode.entries.size
                                ),
                                label = {
                                    Text(
                                        when (mode) {
                                            ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                                            ThemeMode.DARK -> stringResource(R.string.theme_dark)
                                            ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                                        }
                                    )
                                }
                            )
                        }
                    }
                },
                colors = listColors
            )
        }

        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.dynamic_theme_title)) },
                supportingContent = { Text(stringResource(R.string.dynamic_theme_desc)) },
                colors = listColors,
                trailingContent = {
                    Switch(
                        checked = dynamicTheme,
                        onCheckedChange = { viewModel.setDynamicTheme(it) }
                    )
                }
            )
        }

        item { HorizontalDivider() }

        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.privacy_mode_title)) },
                supportingContent = { Text(stringResource(R.string.privacy_mode_desc)) },
                colors = listColors,
                trailingContent = {
                    Switch(
                        checked = privacyMode,
                        onCheckedChange = onPrivacyModeChange
                    )
                }
            )
        }

        item { HorizontalDivider() }

        // Обновление
        item {
            UpdateListItem(
                state = updateState,
                colors = listColors,
                onCheck = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    viewModel.checkForUpdate()
                },
                onDownload = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    viewModel.downloadUpdate()
                },
                onInstall = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    viewModel.installUpdate()
                }
            )
        }

        item { HorizontalDivider() }

        // Сброс
        item {
            ListItem(
                headlineContent = {
                    Text(
                        stringResource(R.string.reset_settings),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                },
                colors = listColors,
                trailingContent = {
                    Icon(
                        painterResource(R.drawable.delete_24px),
                        contentDescription = stringResource(R.string.reset),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                modifier = Modifier.clickable {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    showResetDialog.value = true
                }
            )
        }

        // Версия
        item {
            Text(
                text = stringResource(R.string.version_format, appVersion),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showResetDialog.value) {
        AlertDialog(
            onDismissRequest = { showResetDialog.value = false },
            title = { Text(stringResource(R.string.reset_all_settings_title)) },
            text = { Text(stringResource(R.string.reset_all_settings_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog.value = false
                        viewModel.resetAllSettings(context)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(stringResource(R.string.reset)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog.value = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun RepoLinksContent(
    containerColor: Color,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back_24px),
                    contentDescription = stringResource(R.string.cancel)
                )
            }
            Text(
                text = stringResource(R.string.repo_links),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        LazyColumn {
            item {
                RepoLinkItem(
                    title = stringResource(R.string.android_client),
                    subtitle = "spkprsnts/WireTurn",
                    url = "https://github.com/spkprsnts/WireTurn",
                    containerColor = containerColor,
                    onHaptic = { HapticUtil.perform(context, HapticUtil.Pattern.SELECTION) },
                    onOpen = { uriHandler.openUri(it) }
                )
            }

            item {
                RepoLinkItem(
                    title = stringResource(R.string.android_client_original),
                    subtitle = "samosvalishe/turn-proxy-android",
                    url = "https://github.com/samosvalishe/turn-proxy-android",
                    containerColor = containerColor,
                    onHaptic = { HapticUtil.perform(context, HapticUtil.Pattern.SELECTION) },
                    onOpen = { uriHandler.openUri(it) }
                )
            }

            item {
                RepoLinkItem(
                    title = stringResource(R.string.proxy_core_current),
                    subtitle = "spkprsnts/vk-turn-proxy (branch: dc)",
                    url = "https://github.com/spkprsnts/vk-turn-proxy/tree/dc",
                    containerColor = containerColor,
                    onHaptic = { HapticUtil.perform(context, HapticUtil.Pattern.SELECTION) },
                    onOpen = { uriHandler.openUri(it) }
                )
            }

            item {
                RepoLinkItem(
                    title = stringResource(R.string.proxy_core_use),
                    subtitle = "alxmcp/vk-turn-proxy",
                    url = "https://github.com/alxmcp/vk-turn-proxy",
                    containerColor = containerColor,
                    onHaptic = { HapticUtil.perform(context, HapticUtil.Pattern.SELECTION) },
                    onOpen = { uriHandler.openUri(it) }
                )
            }

            item {
                RepoLinkItem(
                    title = stringResource(R.string.proxy_core),
                    subtitle = "cacggghp/vk-turn-proxy",
                    url = "https://github.com/cacggghp/vk-turn-proxy",
                    containerColor = containerColor,
                    onHaptic = { HapticUtil.perform(context, HapticUtil.Pattern.SELECTION) },
                    onOpen = { uriHandler.openUri(it) }
                )
            }

            item {
                RepoLinkItem(
                    title = stringResource(R.string.turnable_core),
                    subtitle = "TheAirBlow/Turnable",
                    url = "https://github.com/TheAirBlow/Turnable",
                    containerColor = containerColor,
                    onHaptic = { HapticUtil.perform(context, HapticUtil.Pattern.SELECTION) },
                    onOpen = { uriHandler.openUri(it) }
                )
            }

            item {
                RepoLinkItem(
                    title = stringResource(R.string.xray_core_name),
                    subtitle = "spkprsnts/vless-client",
                    url = "https://github.com/spkprsnts/vless-client",
                    containerColor = containerColor,
                    onHaptic = { HapticUtil.perform(context, HapticUtil.Pattern.SELECTION) },
                    onOpen = { uriHandler.openUri(it) }
                )
            }

            item {
                RepoLinkItem(
                    title = stringResource(R.string.xray_core_original),
                    subtitle = "XTLS/Xray-core",
                    url = "https://github.com/XTLS/Xray-core",
                    containerColor = containerColor,
                    onHaptic = { HapticUtil.perform(context, HapticUtil.Pattern.SELECTION) },
                    onOpen = { uriHandler.openUri(it) }
                )
            }

            item {
                RepoLinkItem(
                    title = stringResource(R.string.tun2socks),
                    subtitle = "xjasonlyu/libtun2socks.so",
                    url = "https://github.com/xjasonlyu/tun2socks",
                    containerColor = containerColor,
                    onHaptic = { HapticUtil.perform(context, HapticUtil.Pattern.SELECTION) },
                    onOpen = { uriHandler.openUri(it) }
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

// Пункт обновления в bottom sheet

@Composable
private fun UpdateListItem(
    state: UpdateState,
    colors: ListItemColors,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit
) {
    val supportingText = when (state) {
        is UpdateState.Idle -> stringResource(R.string.update_tap_to_check)
        is UpdateState.Checking -> stringResource(R.string.update_checking)
        is UpdateState.Available -> stringResource(R.string.update_available, state.version)
        is UpdateState.Downloading -> stringResource(R.string.update_downloading, state.progress)
        is UpdateState.ReadyToInstall -> stringResource(R.string.update_ready_desc_short)
        is UpdateState.NoUpdate -> stringResource(R.string.update_no_update)
        is UpdateState.Error -> stringResource(R.string.update_error, state.message)
    }

    ListItem(
        headlineContent = {
            Text(stringResource(R.string.update_title), style = MaterialTheme.typography.titleSmall)
        },
        supportingContent = {
            Column {
                Text(
                    supportingText,
                    color = if (state is UpdateState.Error) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state is UpdateState.Downloading) {
                    LinearWavyProgressIndicator(
                        progress = { state.progress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                }
            }
        },
        trailingContent = {
            when (state) {
                is UpdateState.Available -> TextButton(onClick = onDownload) {
                    Text(stringResource(R.string.update_download))
                }
                is UpdateState.ReadyToInstall -> TextButton(onClick = onInstall) {
                    Text(stringResource(R.string.update_install))
                }
                is UpdateState.Idle, is UpdateState.NoUpdate, is UpdateState.Error ->
                    TextButton(onClick = onCheck) {
                        Text(stringResource(R.string.update_check))
                    }
                else -> {}
            }
        },
        colors = colors
    )
}

// Общие компоненты

@Composable
private fun RepoLinkItem(
    title: String,
    subtitle: String,
    url: String,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    onHaptic: () -> Unit,
    onOpen: (String) -> Unit
) {
    Surface(
        color = containerColor,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                onHaptic()
                onOpen(url)
            }
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Icon(
                painter = painterResource(R.drawable.open_in_new_24px),
                contentDescription = stringResource(R.string.btn_open),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}


// --- Utils ---

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    if (bytes < 1024) return "$bytes B"
    val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt()
    val pre = "KMGTPE"[exp - 1]
    return String.format(java.util.Locale.US, "%.1f %siB", bytes / 1024.0.pow(exp.toDouble()), pre)
}

private fun formatSpeed(bytesPerSecond: Long): String {
    return formatBytes(bytesPerSecond) + "/s"
}



@Composable
private fun ConfigRow(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun ProxyAddressRow(
    label: String,
    address: String,
    isModified: Boolean,
    onCopy: (String) -> Unit
) {
    var isCopied by remember { mutableStateOf(false) }
    LaunchedEffect(isCopied) {
        if (isCopied) {
            kotlinx.coroutines.delay(1500)
            isCopied = false
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp, horizontal = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                InlineConfigIndicator(isModified)
            }
            Text(
                address.ifBlank { stringResource(R.string.dash) },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (address.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.onSurface
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (address.isNotBlank()) {
                IconButton(
                    onClick = {
                        isCopied = true
                        onCopy(address)
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        painter = painterResource(if (isCopied) R.drawable.check_circle_24px else R.drawable.content_copy_24px),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (isCopied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

