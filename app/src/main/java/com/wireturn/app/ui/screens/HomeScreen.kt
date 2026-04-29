@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui.screens

import android.annotation.SuppressLint
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.wireturn.app.R
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import com.wireturn.app.data.DCType
import com.wireturn.app.data.KernelVariant
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Switch
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.wireturn.app.ui.InlineConfigIndicator
import com.wireturn.app.ui.redact
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.AppExclusionTooltip
import com.wireturn.app.ui.SettingsGroup
import com.wireturn.app.ui.SettingsGroupItem
import com.wireturn.app.viewmodel.MainViewModel
import com.wireturn.app.viewmodel.ProxyState
import androidx.core.net.toUri
import com.wireturn.app.ProxyServiceState
import com.wireturn.app.VpnServiceState
import com.wireturn.app.XrayServiceState
import com.wireturn.app.ui.CompactSettingsItem
import com.wireturn.app.ui.theme.extendedColorScheme
import com.wireturn.app.viewmodel.VpnState
import com.wireturn.app.viewmodel.XrayState
import kotlin.math.ln
import kotlin.math.pow

@SuppressLint("BatteryLife")
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onNavigateToExclusions: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    // --- State & Data ---
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val proxyState by viewModel.proxyState.collectAsStateWithLifecycle()
    val xrayState by XrayServiceState.state.collectAsStateWithLifecycle()
    val vpnServiceState by VpnServiceState.state.collectAsStateWithLifecycle()
    val clientConfig by viewModel.clientConfig.collectAsStateWithLifecycle()
    val xraySettings by viewModel.xraySettings.collectAsStateWithLifecycle()
    val globalVpnSettings by viewModel.globalVpnSettings.collectAsStateWithLifecycle()
    val xrayConfig by viewModel.xrayConfig.collectAsStateWithLifecycle()
    val batteryNotificationDismissed by viewModel.batteryNotificationDismissed.collectAsStateWithLifecycle()
    val appsExclusionHintShown by viewModel.appsExclusionHintShown.collectAsStateWithLifecycle()
    val customKernelExists by viewModel.customKernelExists.collectAsStateWithLifecycle()
    val vlessConfig by viewModel.vlessConfig.collectAsStateWithLifecycle()
    val runningConfig by ProxyServiceState.runningConfig.collectAsStateWithLifecycle()
    val runningWgConfig by XrayServiceState.runningWgConfig.collectAsStateWithLifecycle()
    val runningVlessConfig by XrayServiceState.runningVlessConfig.collectAsStateWithLifecycle()
    val runningXrayConfig by XrayServiceState.runningXrayConfig.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()

    val currentProfileId by viewModel.currentProfileId.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val runningProfileName by ProxyServiceState.runningProfileName.collectAsStateWithLifecycle()

    val currentProfileName = remember(currentProfileId, profiles) {
        profiles.find { it.id == currentProfileId }?.name ?: ""
    }

    val isArchitectureSupported = viewModel.isArchitectureSupported
    val deviceArchitecture = viewModel.deviceArchitecture
    val showArchWarning = rememberSaveable { mutableStateOf(!isArchitectureSupported) }

    if (showArchWarning.value) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showArchWarning.value = false },
            title = { Text(stringResource(R.string.warn_unsupported_arch_title)) },
            text = { Text(stringResource(R.string.warn_unsupported_arch_desc, deviceArchitecture)) },
            confirmButton = {
                TextButton(onClick = { showArchWarning.value = false }) {
                    Text(stringResource(R.string.btn_close))
                }
            }
        )
    }

    val profileChanged = remember(runningProfileName, currentProfileName) {
        runningProfileName != null && (currentProfileName != runningProfileName)
    }

    val proxyPing by viewModel.proxyPing.collectAsStateWithLifecycle()
    var lastSuccessPing by remember { mutableStateOf<MainViewModel.PingResult.Success?>(null) }
    var isControlPingScheduled by rememberSaveable { mutableStateOf(value = false) }
    
    // --- Effects & Lifecycle ---
    LaunchedEffect(proxyPing) {
        if (proxyPing is MainViewModel.PingResult.Success) {
            lastSuccessPing = proxyPing as MainViewModel.PingResult.Success
        }

        if (!isControlPingScheduled && (proxyPing is MainViewModel.PingResult.Success || proxyPing is MainViewModel.PingResult.Error)) {
            isControlPingScheduled = true
            delay(1000)
            viewModel.checkProxyPing()
        }
    }

    LaunchedEffect(runningXrayConfig) {
        lastSuccessPing = null
        isControlPingScheduled = false
    }

    val proxyTransfer by viewModel.proxyTransfer.collectAsStateWithLifecycle()
    val wgConfig by viewModel.wgConfig.collectAsStateWithLifecycle()

    val activeConfig = runningConfig ?: clientConfig
    val activeWgConfig = runningWgConfig ?: wgConfig
    val activeVlessConfig = runningVlessConfig ?: vlessConfig

    val configChanged = remember(clientConfig, runningConfig, wgConfig, runningWgConfig, vlessConfig, runningVlessConfig, xrayConfig, runningXrayConfig, profileChanged) {
        profileChanged ||
                (runningConfig != null && clientConfig != runningConfig) ||
                (runningWgConfig != null && wgConfig != runningWgConfig) ||
                (runningVlessConfig != null && vlessConfig != runningVlessConfig) ||
                (runningXrayConfig != null && xrayConfig != runningXrayConfig)
    }

    val mainConfigChanged = remember(clientConfig, runningConfig, profileChanged) {
        profileChanged || (runningConfig != null && clientConfig != runningConfig)
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
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        
        scope.launch(Dispatchers.IO) {
            val jsonFiles = mutableListOf<Pair<String?, String>>()
            uris.forEach { uri ->
                try {
                    val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (index != -1 && cursor.moveToFirst()) cursor.getString(index) else null
                    } ?: uri.lastPathSegment

                    if (fileName?.endsWith(".zip", ignoreCase = true) == true) {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            viewModel.importProfilesFromZip(stream)
                        }
                    } else {
                        val json = context.contentResolver.openInputStream(uri)?.use { stream ->
                            stream.bufferedReader().readText()
                        }
                        if (json != null) jsonFiles.add(fileName to json)
                    }
                } catch (_: Exception) {}
            }
            if (jsonFiles.isNotEmpty()) {
                viewModel.importProfiles(jsonFiles)
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

    val showVpnWarning = {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(warnVpnRequiresXray)
        }
    }

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

    val isDark = com.wireturn.app.ui.theme.LocalIsDark.current
    val screenBackgroundColor = if (isDark) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLow
    val blockContainerColor = if (isDark) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface

    // --- UI Layout ---
    Scaffold(
        modifier = modifier.fillMaxSize(),
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
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .padding(bottom = 64.dp)
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = screenBackgroundColor
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .fillMaxWidth()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 600.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))

            val hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, "android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED
            } else true

            LaunchedEffect(hasNotificationPermission, isIgnoringBatteryOptimizations) {
                if (hasNotificationPermission && isIgnoringBatteryOptimizations) {
                    viewModel.setBatteryNotificationDismissed(false)
                }
            }

            // 1. Update Banner
            UpdateBanner(
                state = updateState,
                onDownload = { viewModel.downloadUpdate() },
                onInstall = { viewModel.installUpdate() },
                onDetails = onNavigateToSettings,
                containerColor = blockContainerColor
            )

            // 2. Permission & Optimization Banner
            AnimatedVisibility(
                visible = (!isIgnoringBatteryOptimizations || !hasNotificationPermission) && !batteryNotificationDismissed,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    SettingsGroupItem(
                        isTop = true,
                        isBottom = true,
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
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
                                                notificationLauncher.launch("android.permission.POST_NOTIFICATIONS")
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
                    Spacer(Modifier.height(24.dp))
                }
            }

            // 3. Proxy Toggle Button
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
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

                Text(
                    text = when (proxyState) {
                        is ProxyState.Working -> stringResource(if (customKernelExists) R.string.proxy_running else R.string.proxy_active)
                        is ProxyState.Starting -> stringResource(R.string.starting)
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
            }

            Spacer(Modifier.height(20.dp))
            
            // 4. Ping & Transfer Stats
            AnimatedVisibility(
                visible = xrayState == XrayState.Running,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Stats Block
                        CompactSettingsItem(
                            containerColor = blockContainerColor,
                            modifier = Modifier.fillMaxHeight()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                val transfer = proxyTransfer
                                // Download
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(
                                        painter = painterResource(R.drawable.arrow_downward_24px),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Column(
                                        modifier = Modifier
                                            .offset(y = 1.dp)
                                            .widthIn(min = 60.dp)
                                    ) {
                                        Text(
                                            text = formatBytes(transfer?.rx ?: 0L),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = formatSpeed(transfer?.rxSpeed ?: 0L),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    }
                                }

                                // Upload
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(
                                        painter = painterResource(R.drawable.arrow_upward_24px),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Column(
                                        modifier = Modifier
                                            .offset(y = 1.dp)
                                            .widthIn(min = 60.dp)
                                    ) {
                                        Text(
                                            text = formatBytes(transfer?.tx ?: 0L),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.Bold
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

                        // Ping Block
                        CompactSettingsItem(
                            containerColor = blockContainerColor,
                            modifier = Modifier.fillMaxHeight(),
                            onClick = {
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                viewModel.checkProxyPing()
                            }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxHeight(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.wifi_24px),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Box(
                                    modifier = Modifier
                                        .widthIn(min = 52.dp)
                                        .offset(y = 1.dp),
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
                                                    style = MaterialTheme.typography.labelMedium,
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
                                                        .size(width = 36.dp, height = 12.dp)
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
                                                style = MaterialTheme.typography.labelMedium,
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
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }

                                        null -> {
                                            Text(
                                                text = stringResource(R.string.ping_unknown),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }

            Spacer(Modifier.height(8.dp))

            // 3. Profiles Block
            val showProfilesDialog = rememberSaveable { mutableStateOf(false) }
            SettingsGroupItem(
                isTop = true,
                isBottom = true,
                containerColor = blockContainerColor,
                onClick = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    showProfilesDialog.value = true
                }
            ) {
                ProfilesBlock(viewModel)
            }

            Spacer(Modifier.height(24.dp))

            if (showProfilesDialog.value) {
                ProfilesDialog(
                    viewModel = viewModel,
                    onImport = { profileImportLauncher.launch(arrayOf("application/json", "application/zip")) },
                    onDismiss = { showProfilesDialog.value = false }
                )
            }

            // 5. Xray & VPN Settings Card
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingsGroupItem(
                    isTop = true,
                    isBottom = false,
                    containerColor = blockContainerColor,
                    onClick = {
                        val next = !xraySettings.xrayEnabled
                        HapticUtil.perform(context, if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                        
                        if (!next && xraySettings.xrayVpnMode) {
                            showVpnWarning()
                        }
                        
                        viewModel.updateXraySettings(xraySettings.copy(xrayEnabled = next))
                    }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    )
                    {
                        when (xrayState) {
                            XrayState.Idle, XrayState.Running -> Icon(
                                painter = painterResource(
                                    R.drawable.ic_xray_24px
                                ),
                                contentDescription = null,
                                tint = if (xrayState == XrayState.Idle) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )

                            XrayState.Starting -> CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        }


                        val isSettingsValid =
                            if (xrayConfig.xrayConfiguration == com.wireturn.app.data.XrayConfiguration.VLESS) activeVlessConfig.isValid() else activeWgConfig.isValid()
                        val configValid = isSettingsValid || xrayState != XrayState.Idle

                        val xrayProtocol = when {
                            xrayState == XrayState.Running -> {
                                if (runningVlessConfig != null) stringResource(R.string.vless) else stringResource(
                                    R.string.wg_short
                                )
                            }

                            else -> if (xrayConfig.xrayConfiguration == com.wireturn.app.data.XrayConfiguration.VLESS) stringResource(
                                R.string.vless
                            ) else stringResource(R.string.wg_short)
                        }

                        LaunchedEffect(configValid, xraySettings.xrayEnabled, currentProfileId) {
                            delay(500)
                            if (!configValid && xraySettings.xrayEnabled) {
                                viewModel.updateXraySettings(
                                    viewModel.xraySettings.value.copy(
                                        xrayEnabled = false
                                    )
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = stringResource(R.string.xray_title) + " " + xrayProtocol,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = if (!configValid) {
                                    stringResource(R.string.xray_config_invalid)
                                } else {
                                    when (xrayState) {
                                        XrayState.Starting -> stringResource(R.string.starting)
                                        XrayState.Running -> stringResource(R.string.running)
                                        else -> stringResource(R.string.idle)
                                    }
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Switch(
                            checked = xraySettings.xrayEnabled,
                            onCheckedChange = { enabled ->
                                HapticUtil.perform(
                                    context,
                                    if (enabled) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                                )
                                
                                if (!enabled && xraySettings.xrayVpnMode) {
                                    showVpnWarning()
                                }

                                viewModel.updateXraySettings(
                                    viewModel.xraySettings.value.copy(
                                        xrayEnabled = enabled
                                    )
                                )
                            },
                            enabled = configValid
                        )
                    }
                }

                SettingsGroupItem(
                    isTop = false,
                    isBottom = false,
                    containerColor = blockContainerColor,
                    onClick = {
                        val next = !xraySettings.xrayVpnMode
                        HapticUtil.perform(context, if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                        
                        if (next && !xraySettings.xrayEnabled) {
                            showVpnWarning()
                        }

                        if (next) {
                            val intent = VpnService.prepare(context)
                            if (intent != null) {
                                vpnLauncher.launch(intent)
                            } else {
                                viewModel.updateXraySettings(xraySettings.copy(xrayVpnMode = true))
                            }
                        } else {
                            viewModel.updateXraySettings(xraySettings.copy(xrayVpnMode = false))
                        }
                    }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        when (vpnServiceState) {
                            VpnState.Idle, VpnState.Running, is VpnState.Error -> Icon(
                                painter = painterResource(R.drawable.vpn_key_24px),
                                contentDescription = null,
                                tint = if (vpnServiceState == VpnState.Running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                            VpnState.Starting -> CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    stringResource(R.string.vpn_mode),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                InlineConfigIndicator(runningWgConfig != null && xraySettings.xrayVpnMode != (vpnServiceState == VpnState.Running))
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = when (vpnServiceState) {
                                    VpnState.Starting -> stringResource(R.string.starting)
                                    VpnState.Running -> stringResource(R.string.running)
                                    is VpnState.Error -> (vpnServiceState as VpnState.Error).message
                                    else -> stringResource(R.string.idle)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        AppExclusionTooltip(
                            hintShown = appsExclusionHintShown,
                            onHintShown = { viewModel.setAppsExclusionHintShown(true) }
                        ) {
                            IconButton(onClick = {
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                if (!appsExclusionHintShown) {
                                    viewModel.setAppsExclusionHintShown(true)
                                }
                                onNavigateToExclusions()
                            }) {
                                Icon(
                                    painter = painterResource(R.drawable.apps_24px),
                                    contentDescription = stringResource(R.string.vpn_apps_exceptions),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                        alpha = if (globalVpnSettings.filteringEnabled) 1f else 0.38f
                                    )
                                )
                            }
                        }

                        Switch(checked = xraySettings.xrayVpnMode, onCheckedChange = { enabled ->
                            HapticUtil.perform(
                                context,
                                if (enabled) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)

                            if (enabled && !xraySettings.xrayEnabled) {
                                showVpnWarning()
                            }

                            if (enabled) {
                                val intent = VpnService.prepare(context)
                                if (intent != null) {
                                    vpnLauncher.launch(intent)
                                } else {
                                    viewModel.updateXraySettings(
                                        viewModel.xraySettings.value.copy(
                                            xrayVpnMode = true
                                        )
                                    )
                                }
                            } else {
                                viewModel.updateXraySettings(
                                    viewModel.xraySettings.value.copy(
                                        xrayVpnMode = false
                                    )
                                )
                            }
                        })
                    }
                }

                val clipboard = LocalClipboard.current
                val socks5Label = stringResource(R.string.clipboard_label_socks5)
                val httpLabel = stringResource(R.string.clipboard_label_http)

                var socksCopied by remember { mutableStateOf(false) }
                LaunchedEffect(socksCopied) {
                    if (socksCopied) {
                        delay(1500)
                        socksCopied = false
                    }
                }

                SettingsGroupItem(
                    isTop = false,
                    isBottom = xrayConfig.httpBindAddress.isBlank() && !(runningXrayConfig != null && xrayConfig.httpBindAddress != runningXrayConfig?.httpBindAddress),
                    containerColor = blockContainerColor,
                    onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        scope.launch {
                            clipboard.setClipEntry(ClipData.newPlainText(socks5Label, xrayConfig.socksBindAddress).toClipEntry())
                            socksCopied = true
                        }
                    }
                ) {
                    ProxyAddressRow(
                        label = stringResource(R.string.xray_socks5),
                        address = xrayConfig.socksBindAddress,
                        isModified = runningXrayConfig != null && xrayConfig.socksBindAddress != runningXrayConfig?.socksBindAddress,
                        isCopied = socksCopied
                    )
                }

                if (xrayConfig.httpBindAddress.isNotBlank() || (runningXrayConfig != null && xrayConfig.httpBindAddress != runningXrayConfig?.httpBindAddress)) {
                    var httpCopied by remember { mutableStateOf(false) }
                    LaunchedEffect(httpCopied) {
                        if (httpCopied) {
                            delay(1500)
                            httpCopied = false
                        }
                    }

                    SettingsGroupItem(
                        isTop = false,
                        isBottom = true,
                        containerColor = blockContainerColor,
                        onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            scope.launch {
                                clipboard.setClipEntry(ClipData.newPlainText(httpLabel, xrayConfig.httpBindAddress).toClipEntry())
                                httpCopied = true
                            }
                        }
                    ) {
                        ProxyAddressRow(
                            label = stringResource(R.string.xray_http),
                            address = xrayConfig.httpBindAddress,
                            isModified = runningXrayConfig != null && xrayConfig.httpBindAddress != runningXrayConfig?.httpBindAddress,
                            isCopied = httpCopied
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // 6. Current Connection Details
            if (activeConfig.isValid) {
                AnimatedVisibility(
                    visible = (proxyState is ProxyState.Working || proxyState is ProxyState.Running || proxyState is ProxyState.Starting || proxyState is ProxyState.CaptchaRequired) && configChanged,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column {
                        SettingsGroupItem(
                            isTop = true,
                            isBottom = true,
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.refresh_24px),
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(if (profileChanged) R.string.restart_required_profile else R.string.restart_required),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = when {
                                                profileChanged -> stringResource(R.string.restart_reason_profile)
                                                mainConfigChanged -> stringResource(R.string.restart_reason_client)
                                                else -> stringResource(R.string.restart_reason_xray)
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                        )
                                    }
                                }

                                Spacer(Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (!profileChanged) {
                                        Text(
                                            text = stringResource(R.string.reset),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier
                                                .clip(MaterialTheme.shapes.small)
                                                .clickable {
                                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                                    viewModel.revertToRunningConfigs()
                                                }
                                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(
                                        text = stringResource(R.string.btn_restart),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clip(MaterialTheme.shapes.small)
                                            .clickable {
                                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                                if (mainConfigChanged) {
                                                    viewModel.restartProxy()
                                                } else if (xrayConfigChanged) {
                                                    viewModel.restartXray()
                                                }
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }

                SettingsGroup(title = stringResource(R.string.current_client_settings)) {
                    SettingsGroupItem(
                        isTop = true,
                        isBottom = true,
                        containerColor = blockContainerColor
                    ) {
                        Column {
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
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface
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
            }

        }
    }

    if (showBottomSheet.value) {
        val sheetColor = MaterialTheme.colorScheme.surfaceContainer

        ModalBottomSheet(
            onDismissRequest = { 
                showBottomSheet.value = false
            },
            sheetState = bottomSheetState,
            containerColor = sheetColor,
            dragHandle = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                ) {
                    androidx.compose.material3.BottomSheetDefaults.DragHandle()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 24.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.repo_links),
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }
            }
        ) {
            RepoLinksContent(
                containerColor = sheetColor
            )
        }
    }
}

@Composable
private fun UpdateBanner(
    state: com.wireturn.app.viewmodel.UpdateState,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onDetails: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest
) {
    val context = LocalContext.current
    val isVisible = state is com.wireturn.app.viewmodel.UpdateState.Available || state is com.wireturn.app.viewmodel.UpdateState.ReadyToInstall || state is com.wireturn.app.viewmodel.UpdateState.Downloading

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            colors = CardDefaults.cardColors(
                containerColor = containerColor
            ),
            shape = MaterialTheme.shapes.medium,
            onClick = onDetails
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        painter = painterResource(R.drawable.refresh_24px),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = when (state) {
                            is com.wireturn.app.viewmodel.UpdateState.Available -> stringResource(R.string.update_available, state.version)
                            is com.wireturn.app.viewmodel.UpdateState.Downloading -> stringResource(R.string.update_downloading, state.progress)
                            is com.wireturn.app.viewmodel.UpdateState.ReadyToInstall -> stringResource(R.string.update_ready_desc_short)
                            else -> ""
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )

                    // Кнопки действий
                    when (state) {
                        is com.wireturn.app.viewmodel.UpdateState.Available -> {
                            TextButton(
                                onClick = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                    onDownload()
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text(stringResource(R.string.update_download), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        is com.wireturn.app.viewmodel.UpdateState.ReadyToInstall -> {
                            TextButton(
                                onClick = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                    onInstall()
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text(stringResource(R.string.update_install), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        else -> {}
                    }

                    Spacer(Modifier.width(4.dp))
                    Icon(
                        painter = painterResource(R.drawable.open_in_new_24px),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.size(16.dp)
                    )
                }

                if (state is com.wireturn.app.viewmodel.UpdateState.Downloading) {
                    androidx.compose.material3.LinearWavyProgressIndicator(
                        progress = { state.progress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 4.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

// Кнопка прокси
@Composable
private fun ProxyToggleButton(state: ProxyState, onClick: () -> Unit) {
    val containerColor by animateColorAsState(
        targetValue = when (state) {
            is ProxyState.Working -> MaterialTheme.colorScheme.primary
            is ProxyState.Running, is ProxyState.CaptchaRequired -> MaterialTheme.colorScheme.tertiary
            is ProxyState.Error -> MaterialTheme.colorScheme.errorContainer
            is ProxyState.Starting -> MaterialTheme.colorScheme.surfaceContainerHigh
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
            is ProxyState.Starting -> MaterialTheme.colorScheme.onSurfaceVariant
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
private fun RepoLinksContent(
    containerColor: Color
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val smartDismissNestedScroll = remember {
        object : NestedScrollConnection {
            private var hasScrolledDownInGesture = false

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source == NestedScrollSource.UserInput) {
                    if (consumed.y > 0) {
                        hasScrolledDownInGesture = true
                    }
                    if (available.y > 0 && hasScrolledDownInGesture) {
                        return available
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: androidx.compose.ui.unit.Velocity, available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                hasScrolledDownInGesture = false
                return super.onPostFling(consumed, available)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        LazyColumn(modifier = Modifier.nestedScroll(smartDismissNestedScroll)) {
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
        onClick = {
            onHaptic()
            onOpen(url)
        },
        modifier = Modifier.fillMaxWidth()
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
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun ProxyAddressRow(
    label: String,
    address: String,
    isModified: Boolean,
    isCopied: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                InlineConfigIndicator(isModified)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                address.ifBlank { stringResource(R.string.dash) },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (address.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.onSurface
            )
        }
        Icon(
            painter = painterResource(if (isCopied) R.drawable.check_circle_24px else R.drawable.content_copy_24px),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (isCopied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

