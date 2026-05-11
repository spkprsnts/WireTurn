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
import com.wireturn.app.data.XrayConfiguration
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.wireturn.app.ui.InlineConfigIndicator
import com.wireturn.app.ui.SwitchRow
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.AppExclusionTooltip
import com.wireturn.app.ui.VerticalAnimatedText
import com.wireturn.app.ui.SettingsGroupItem
import com.wireturn.app.ui.StandardLeadingIcon
import com.wireturn.app.ui.showExclusiveSnackbar
import com.wireturn.app.viewmodel.MainViewModel
import com.wireturn.app.viewmodel.ProxyState
import androidx.core.net.toUri
import com.wireturn.app.ProxyServiceState
import com.wireturn.app.VpnServiceState
import com.wireturn.app.XrayServiceState
import com.wireturn.app.ui.CompactSettingsItem
import com.wireturn.app.ui.ConfigRowLabel
import com.wireturn.app.ui.SupportingText
import com.wireturn.app.ui.UpdateBlock
import com.wireturn.app.viewmodel.UpdateState
import com.wireturn.app.viewmodel.VpnState
import com.wireturn.app.viewmodel.XrayState
import kotlin.math.ln
import kotlin.math.pow

@SuppressLint("BatteryLife")
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onNavigateToExclusions: () -> Unit,
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
    val clientConfigSnapshot by ProxyServiceState.clientConfigSnapshot.collectAsStateWithLifecycle()
    val wgConfigSnapshot by XrayServiceState.wgConfigSnapshot.collectAsStateWithLifecycle()
    val vlessConfigSnapshot by XrayServiceState.vlessConfigSnapshot.collectAsStateWithLifecycle()
    val xrayConfigSnapshot by XrayServiceState.xrayConfigSnapshot.collectAsStateWithLifecycle()
    val isRestarting by ProxyServiceState.isRestarting.collectAsStateWithLifecycle()
    val isChangingProfile by ProxyServiceState.isChangingProfile.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val updateProgress by viewModel.updateProgress.collectAsStateWithLifecycle()

    val currentProfileId by viewModel.currentProfileId.collectAsStateWithLifecycle()
    val autoLaunchSettings by viewModel.autoLaunchSettings.collectAsStateWithLifecycle()

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

    val proxyPing by viewModel.proxyPing.collectAsStateWithLifecycle()
    var lastSuccessPing by remember { mutableStateOf<MainViewModel.PingResult.Success?>(null) }
    var isControlPingScheduled by rememberSaveable { mutableStateOf(value = false) }
    
    val showAutoLaunchOverride = rememberSaveable { mutableStateOf(false) }
    val showMismatchDialog = rememberSaveable { mutableStateOf(false) }
    var mismatchMessage by rememberSaveable { mutableStateOf("") }
    var pendingProxyAction by remember { mutableStateOf<(() -> Unit)?>(null) }

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

    val lifecycleOwner = LocalLifecycleOwner.current

    val isBusy = isRestarting || isChangingProfile

    // Logic to prevent flickering "Tap to start" during hot restart gap
    var wasActiveBeforeRestart by remember { mutableStateOf(false) }
    LaunchedEffect(isBusy, proxyState, xrayState) {
        val isActive = proxyState !is ProxyState.Idle || xrayState == XrayState.Running || xrayState == XrayState.DirectRoute
        if (isBusy && isActive) {
            wasActiveBeforeRestart = true
        } else if (!isBusy) {
            wasActiveBeforeRestart = false
        }
    }

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

    // --- Effects & Lifecycle ---
    LaunchedEffect(proxyPing, proxyState) {
        if (proxyPing is MainViewModel.PingResult.Success) {
            lastSuccessPing = proxyPing as MainViewModel.PingResult.Success
        }

        val isProxyActive = proxyState is ProxyState.Connected || proxyState is ProxyState.Suppressed
        if (isProxyActive) {
            if (proxyPing is MainViewModel.PingResult.Success || proxyPing is MainViewModel.PingResult.Error) {
                if (!isControlPingScheduled) {
                    isControlPingScheduled = true
                    delay(1000)
                } else {
                    delay(15000)
                }

                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    viewModel.checkProxyPing()
                }
            } else if (proxyPing == null) {
                viewModel.checkProxyPing()
            }
        }
    }

    LaunchedEffect(xrayConfigSnapshot) {
        lastSuccessPing = null
    }

    val proxyTransfer by viewModel.proxyTransfer.collectAsStateWithLifecycle()
    val wgConfig by viewModel.wgConfig.collectAsStateWithLifecycle()

    val activeConfig = clientConfigSnapshot ?: clientConfig
    val activeXrayConfig = xrayConfigSnapshot ?: xrayConfig
    val activeWgConfig = wgConfigSnapshot ?: wgConfig
    val activeVlessConfig = vlessConfigSnapshot ?: vlessConfig

    val configChanged = remember(clientConfig, clientConfigSnapshot, wgConfig, wgConfigSnapshot, vlessConfig, vlessConfigSnapshot, xrayConfig, xrayConfigSnapshot, isBusy) {
        !isBusy && ((clientConfigSnapshot != null && clientConfig.fillDefaults() != clientConfigSnapshot) ||
                (wgConfigSnapshot != null && wgConfig.fillDefaults() != wgConfigSnapshot) ||
                (vlessConfigSnapshot != null && vlessConfig != vlessConfigSnapshot) ||
                (xrayConfigSnapshot != null && xrayConfig.fillDefaults() != xrayConfigSnapshot))
    }

    val mainConfigChanged = remember(clientConfig, clientConfigSnapshot, isBusy) {
        !isBusy && (clientConfigSnapshot != null && clientConfig.fillDefaults() != clientConfigSnapshot)
    }
    val xrayConfigChanged = remember(wgConfig, wgConfigSnapshot, vlessConfig, vlessConfigSnapshot, xrayConfig, xrayConfigSnapshot, clientConfig, clientConfigSnapshot, isBusy) {
        if (isBusy) return@remember false
        
        val baseChanged = (wgConfigSnapshot != null && wgConfig.fillDefaults() != wgConfigSnapshot) ||
                (vlessConfigSnapshot != null && vlessConfig != vlessConfigSnapshot) ||
                (xrayConfigSnapshot != null && xrayConfig.fillDefaults() != xrayConfigSnapshot)
        
        val connectionToProxyChanged = clientConfigSnapshot != null && (
                clientConfig.kernelVariant != clientConfigSnapshot?.kernelVariant ||
                clientConfig.localPort != clientConfigSnapshot?.localPort ||
                clientConfig.olcrtcConfig.socksHost != clientConfigSnapshot?.olcrtcConfig?.socksHost ||
                clientConfig.olcrtcConfig.socksPort != clientConfigSnapshot?.olcrtcConfig?.socksPort
        )
        
        baseChanged || connectionToProxyChanged
    }

    val snackbarHostState = remember { SnackbarHostState() }

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, "android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPermission = isGranted
    }

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
                    hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        ContextCompat.checkSelfPermission(context, "android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED
                    } else true
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
    val warnVpnRequiresXray = stringResource(R.string.warn_vpn_requires_xray)

    val showVpnWarning = {
        scope.launch {
            snackbarHostState.showExclusiveSnackbar(warnVpnRequiresXray)
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
                .padding(horizontal = 16.dp)
                .padding(bottom = 76.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(Modifier.height(8.dp))

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
                containerColor = if (isDark) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceContainerHigh
            )

            // --- Permissions & Optimization Banner ---
            AnimatedVisibility(
                visible = (!isIgnoringBatteryOptimizations || !hasNotificationPermission) && !batteryNotificationDismissed,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                SettingsGroupItem(
                    isTop = true,
                    isBottom = true,
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 24.dp)
                ) {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StandardLeadingIcon {
                                    Icon(
                                        painter = painterResource(R.drawable.error_24px),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                                Column {
                                    ConfigRowLabel(stringResource(R.string.permissions_title))
                                    Spacer(Modifier.height(2.dp))
                                    SupportingText(
                                        stringResource(R.string.permissions_desc),
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                            Spacer(Modifier.height(16.dp))
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
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                    modifier = Modifier.height(32.dp)
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
                }
            }

            // --- Proxy Toggle ---
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ProxyToggleButton(
                    proxyState = proxyState,
                    xrayState = xrayState,
                    xrayEnabled = xraySettings.xrayEnabled,
                    isRestarting = isBusy,
                    wasActiveBeforeRestart = wasActiveBeforeRestart,
                    isLocked = autoLaunchSettings.enabled,
                    onClick = {
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
                )

                val isActiveStatus = proxyState !is ProxyState.Idle || xrayState == XrayState.Running || xrayState == XrayState.DirectRoute
                val statusText = when {
                    (isBusy || wasActiveBeforeRestart) && (isActiveStatus || wasActiveBeforeRestart) -> stringResource(R.string.proxy_restarting)
                    proxyState is ProxyState.Connected -> {
                        if (xrayState == XrayState.DirectRoute) stringResource(R.string.vless_direct_active)
                        else stringResource(if (customKernelExists) R.string.proxy_running else R.string.proxy_active)
                    }
                    proxyState is ProxyState.Starting -> stringResource(R.string.starting)
                    proxyState is ProxyState.Connecting -> stringResource(R.string.connecting)
                    proxyState is ProxyState.Suppressed -> {
                        if (xrayState == XrayState.DirectRoute) stringResource(R.string.vless_direct_active)
                        else stringResource(R.string.connecting)
                    }
                    proxyState is ProxyState.CaptchaRequired -> stringResource(R.string.proxy_captcha_required)
                    proxyState is ProxyState.WaitingForNetwork -> stringResource(R.string.status_waiting_for_network)
                    proxyState is ProxyState.Error -> (proxyState as ProxyState.Error).message
                    else -> when {
                        autoLaunchSettings.enabled -> stringResource(R.string.proxy_auto_launch_active)
                        else -> stringResource(R.string.proxy_press_to_start)
                    }
                }

                val statusColor by animateColorAsState(
                    targetValue = when {
                        wasActiveBeforeRestart && proxyState is ProxyState.Idle -> MaterialTheme.colorScheme.tertiary
                        proxyState is ProxyState.Connected || proxyState is ProxyState.Suppressed -> MaterialTheme.colorScheme.primary
                        proxyState is ProxyState.Starting || proxyState is ProxyState.Connecting || proxyState is ProxyState.CaptchaRequired || proxyState is ProxyState.WaitingForNetwork -> MaterialTheme.colorScheme.tertiary
                        proxyState is ProxyState.Error -> MaterialTheme.colorScheme.error
                        isBusy && proxyState !is ProxyState.Idle -> MaterialTheme.colorScheme.tertiary
                        autoLaunchSettings.enabled -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
                    },
                    label = "status_color"
                )

                VerticalAnimatedText(
                    text = statusText,
                    style = MaterialTheme.typography.titleMedium,
                    color = statusColor,
                    textAlign = TextAlign.Center,
                    contentAlignment = Alignment.Center
                )
            }

            Spacer(Modifier.height(22.dp))

    // --- Ping & Transfer Stats ---
    AnimatedVisibility(
        visible = (xrayState == XrayState.Running || xrayState == XrayState.DirectRoute) && (proxyState is ProxyState.Connected || proxyState is ProxyState.Suppressed),
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
                                                VerticalAnimatedText(
                                                    text = stringResource(R.string.ping_ms, lastSuccessPing!!.ms),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = (if (lastSuccessPing!!.ms < 300) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error).copy(alpha = pulseAlpha),
                                                    fontWeight = FontWeight.Bold,
                                                    contentAlignment = Alignment.Center
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
                                            VerticalAnimatedText(
                                                text = stringResource(R.string.ping_ms,
                                                    currentPing.ms),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = if (currentPing.ms < 300) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                                                fontWeight = FontWeight.Bold,
                                                contentAlignment = Alignment.Center
                                            )
                                        }

                                        is MainViewModel.PingResult.Error -> {
                                            VerticalAnimatedText(
                                                text = stringResource(R.string.ping_error),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.error,
                                                contentAlignment = Alignment.Center
                                            )
                                        }

                                        null -> {
                                            VerticalAnimatedText(
                                                text = stringResource(R.string.ping_unknown),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.error,
                                                contentAlignment = Alignment.Center
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }

            Spacer(Modifier.height(8.dp))

            // --- Profiles Section ---
            val showProfilesDialog = rememberSaveable { mutableStateOf(false) }
            SettingsGroupItem(
                isTop = true,
                isBottom = !configChanged || proxyState is ProxyState.Idle,
                containerColor = blockContainerColor,
                onClick = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    showProfilesDialog.value = true
                }
            ) {
                ProfilesBlock(viewModel)
            }

            // --- Permissions & Optimization Banner ---
            AnimatedVisibility(
                visible = proxyState !is ProxyState.Idle && configChanged,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    Spacer(Modifier.height(2.dp))
                    SettingsGroupItem(
                        isTop = false,
                        isBottom = true,
                        containerColor = blockContainerColor
                    ) {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                StandardLeadingIcon {
                                    Icon(
                                        painter = painterResource(R.drawable.refresh_24px),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    ConfigRowLabel(stringResource(R.string.restart_required))
                                    Spacer(Modifier.height(2.dp))
                                    SupportingText(when {
                                        mainConfigChanged && xrayConfigChanged -> "${stringResource(R.string.restart_reason_client)} • ${stringResource(R.string.restart_reason_xray)}"
                                        mainConfigChanged -> stringResource(R.string.restart_reason_client)
                                        else -> stringResource(R.string.restart_reason_xray)
                                    })
                                }
                            }

                            Spacer(Modifier.height(16.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.reset),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clip(MaterialTheme.shapes.small)
                                        .clickable {
                                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                            viewModel.revertToSnapshotConfigs()
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                                Spacer(Modifier.width(8.dp))
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
                }
            }

            Spacer(Modifier.height(24.dp))

            if (showProfilesDialog.value) {
                ProfilesDialog(
                    viewModel = viewModel,
                    onImport = { profileImportLauncher.launch(arrayOf("application/json", "application/zip")) },
                    onDismiss = { showProfilesDialog.value = false }
                )
            }

            // --- Xray & VPN Settings ---
            val isSettingsValid = if (activeConfig.kernelVariant == KernelVariant.OLCRTC) {
                // For OLCRTC, link is only required if DualRoute is enabled
                if (activeXrayConfig.xrayConfiguration == XrayConfiguration.VLESS && activeVlessConfig.isDualRoute) {
                    activeVlessConfig.isValid()
                } else {
                    true // WG or VLESS solo mode just uses olcrtc SOCKS5
                }
            } else {
                if (activeXrayConfig.xrayConfiguration == XrayConfiguration.VLESS) activeVlessConfig.isValid() else activeWgConfig.isValid()
            }
            val configValid = isSettingsValid || xrayState != XrayState.Idle || isBusy

            val xrayProtocol = when {
                activeConfig.kernelVariant == KernelVariant.OLCRTC -> {
                    when (xrayState) {
                        XrayState.DirectRoute -> stringResource(R.string.vless)
                        XrayState.Running -> stringResource(R.string.xray_socks5)
                        else -> {
                            if (activeVlessConfig.isDualRoute) "${stringResource(R.string.xray_socks5)} / ${stringResource(R.string.vless)}"
                            else stringResource(R.string.xray_socks5)
                        }
                    }
                }
                xrayState == XrayState.Running || xrayState == XrayState.DirectRoute -> if (vlessConfigSnapshot != null) stringResource(R.string.vless) else stringResource(R.string.wg_short)
                else -> if (activeXrayConfig.xrayConfiguration == XrayConfiguration.VLESS) stringResource(R.string.vless) else stringResource(R.string.wg_short)
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingsGroupItem(
                    isTop = true,
                    isBottom = false,
                    containerColor = blockContainerColor,
                    enabled = configValid,
                    onClick = {
                        val next = !xraySettings.xrayEnabled
                        val action = {
                            HapticUtil.perform(context, if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)

                            if (!next && xraySettings.xrayVpnMode) {
                                showVpnWarning()
                            }

                            viewModel.updateXraySettings(xraySettings.copy(xrayEnabled = next))
                        }

                        if (next) {
                            checkMismatch(true, action)
                        } else {
                            action()
                        }
                    }
                ) {
                    LaunchedEffect(configValid, xraySettings.xrayEnabled, currentProfileId, autoLaunchSettings.enabled) {
                        delay(500)
                        if (!configValid && xraySettings.xrayEnabled && !autoLaunchSettings.enabled) {
                            viewModel.updateXraySettings(viewModel.xraySettings.value.copy(xrayEnabled = false))
                        }
                    }

                    SwitchRow(
                        label = stringResource(R.string.xray_title) + " " + xrayProtocol,
                        checked = xraySettings.xrayEnabled,
                        onCheckedChange = {}, // Обрабатывается родителем (SettingsGroupItem)
                        supportingText = if (!configValid) stringResource(R.string.xray_config_invalid) else {
                            when (xrayState) {
                                XrayState.Starting -> stringResource(R.string.starting)
                                XrayState.Connecting -> stringResource(R.string.connecting)
                                XrayState.Running -> stringResource(R.string.running)
                                XrayState.DirectRoute -> stringResource(R.string.vless_direct_active)
                                else -> stringResource(R.string.idle)
                            }
                        },
                        useLargeIcon = true,
                        leadingIcon = {
                            when (xrayState) {
                                XrayState.Idle, XrayState.Running, XrayState.DirectRoute -> Icon(
                                    painter = painterResource(R.drawable.ic_xray_24px),
                                    contentDescription = null,
                                    tint = if (xrayState == XrayState.Idle) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                                )
                                XrayState.Starting, XrayState.Connecting -> LoadingIndicator()
                            }
                        },
                        enabled = configValid
                    )
                }

                SettingsGroupItem(
                    isTop = false,
                    isBottom = true,
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
                    SwitchRow(
                        label = stringResource(R.string.vpn_mode),
                        checked = xraySettings.xrayVpnMode,
                        onCheckedChange = {}, // Обрабатывается родителем
                        isModified = wgConfigSnapshot != null && xraySettings.xrayVpnMode != (vpnServiceState == VpnState.Running),
                        supportingText = when (vpnServiceState) {
                            VpnState.Starting -> stringResource(R.string.starting)
                            VpnState.Running -> stringResource(R.string.running)
                            is VpnState.Error -> (vpnServiceState as VpnState.Error).message
                            else -> stringResource(R.string.idle)
                        },
                        useLargeIcon = true,
                        leadingIcon = {
                            when (vpnServiceState) {
                                VpnState.Idle, VpnState.Running, is VpnState.Error -> Icon(
                                    painter = painterResource(R.drawable.vpn_key_24px),
                                    contentDescription = null,
                                    tint = if (vpnServiceState == VpnState.Running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                VpnState.Starting -> LoadingIndicator()
                            }
                        },
                        trailingContent = {
                            AppExclusionTooltip(
                                hintShown = appsExclusionHintShown,
                                onHintShown = { viewModel.setAppsExclusionHintShown(true) }
                            ) {
                                IconButton(
                                    onClick = {
                                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                        if (!appsExclusionHintShown) {
                                            viewModel.setAppsExclusionHintShown(true)
                                        }
                                        onNavigateToExclusions()
                                    }
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.route_24px),
                                        contentDescription = stringResource(R.string.vpn_apps_exceptions),
                                        modifier = Modifier.size(32.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                            alpha = if (globalVpnSettings.filteringEnabled) 1f else 0.38f
                                        )
                                    )
                                }
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            val isOlcrtc = activeConfig.kernelVariant == KernelVariant.OLCRTC
            val showXray = xraySettings.xrayEnabled

            val displaySocksAddr = if (showXray) {
                activeXrayConfig.socksBindAddress
            } else if (isOlcrtc) {
                "${activeConfig.olcrtcConfig.socksHost}:${activeConfig.olcrtcConfig.socksPort}"
            } else {
                activeXrayConfig.socksBindAddress
            }

            val displayHttpAddr = if (showXray) activeXrayConfig.httpBindAddress else if (isOlcrtc) "" else activeXrayConfig.httpBindAddress

            val isSocksModified = if (showXray) {
                xrayConfigSnapshot != null && xrayConfig.socksBindAddress != xrayConfigSnapshot?.socksBindAddress
            } else if (isOlcrtc) {
                clientConfigSnapshot != null && (activeConfig.olcrtcConfig.socksHost != clientConfigSnapshot?.olcrtcConfig?.socksHost || activeConfig.olcrtcConfig.socksPort != clientConfigSnapshot?.olcrtcConfig?.socksPort)
            } else {
                xrayConfigSnapshot != null && xrayConfig.socksBindAddress != xrayConfigSnapshot?.socksBindAddress
            }

            val isHttpModified = if (showXray) {
                xrayConfigSnapshot != null && xrayConfig.httpBindAddress != xrayConfigSnapshot?.httpBindAddress
            } else false

            Column(
                modifier = Modifier.graphicsLayer { alpha = if (showXray || isOlcrtc) 1f else 0.38f },
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
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

                val showHttpBlock = displayHttpAddr.isNotBlank() || isHttpModified

                SettingsGroupItem(
                    isTop = true,
                    isBottom = !showHttpBlock,
                    containerColor = blockContainerColor,
                    enabled = showXray || isOlcrtc,
                    onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        scope.launch {
                            clipboard.setClipEntry(ClipData.newPlainText(socks5Label, displaySocksAddr).toClipEntry())
                            socksCopied = true
                        }
                    }
                ) {
                    ProxyAddressRow(
                        label = stringResource(R.string.xray_socks5),
                        address = displaySocksAddr,
                        isModified = isSocksModified,
                        isCopied = socksCopied,
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.lan_24px),
                                contentDescription = null,
                                tint = if (showXray || isOlcrtc) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                            )
                        }
                    )
                }

                AnimatedVisibility(
                    visible = showHttpBlock,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
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
                        enabled = showXray,
                        onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            scope.launch {
                                clipboard.setClipEntry(ClipData.newPlainText(httpLabel, displayHttpAddr).toClipEntry())
                                httpCopied = true
                            }
                        }
                    ) {
                        ProxyAddressRow(
                            label = stringResource(R.string.xray_http),
                            address = displayHttpAddr,
                            isModified = isHttpModified,
                            isCopied = httpCopied,
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.lan_24px),
                                    contentDescription = null,
                                    tint = if (showXray) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                )
                            }
                        )
                    }
                }
            }

            // --- Raw Mode Details ---
            if (activeConfig.isValid && activeConfig.isRawMode) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .graphicsLayer { alpha = 0.55f }
                ) {
                    androidx.compose.material3.HorizontalDivider(
                        modifier = Modifier.padding(top = 24.dp, bottom = 12.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                    )
                    
                    Text(
                        text = stringResource(R.string.raw_mode),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    val parts = activeConfig.rawCommand.split("\\s+".toRegex())
                        .filter { it.isNotBlank() }
                    val args = mutableListOf<String>()
                    var i = 0
                    while (i < parts.size) {
                        val part = parts[i]
                        if (part.startsWith("-") && i + 1 < parts.size && !parts[i + 1].startsWith("-")) {
                            args.add("$part ${parts[i + 1]}")
                            i += 2
                        } else {
                            args.add(part)
                            i += 1
                        }
                    }
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        args.forEach { arg ->
                            Text(
                                text = "• $arg",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
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

// --- Update Banner Component ---
@Composable
private fun UpdateBanner(
    state: UpdateState,
    progress: Int,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCheck: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest
) {
    val isVisible = state is UpdateState.Available || state is UpdateState.ReadyToInstall || state is UpdateState.Downloading || state is UpdateState.Error

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        SettingsGroupItem(
            isTop = true,
            isBottom = true,
            containerColor = containerColor,
            modifier = Modifier.padding(bottom = 24.dp)
        ) {
            UpdateBlock(
                state = state,
                progress = progress,
                showChangelog = false,
                onDownload = onDownload,
                onInstall = onInstall,
                onCheck = onCheck
            )
        }
    }
}

// --- Proxy Toggle Component ---
@Composable
private fun ProxyToggleButton(
    proxyState: ProxyState,
    xrayState: XrayState = XrayState.Idle,
    xrayEnabled: Boolean = true,
    isRestarting: Boolean = false,
    wasActiveBeforeRestart: Boolean = false,
    isLocked: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val isXrayWorking = xrayState == XrayState.Running || xrayState == XrayState.DirectRoute
    
    var xrayWasResetted by rememberSaveable(proxyState is ProxyState.Idle) { mutableStateOf(true) }
    if (!isXrayWorking || xrayState == XrayState.Idle) {
        xrayWasResetted = true
    }
    val xrayActuallyReady = isXrayWorking && xrayWasResetted

    var showXraySpinner by remember { mutableStateOf(false) }
    LaunchedEffect(xrayActuallyReady, proxyState) {
        val isProxyActive = proxyState is ProxyState.Connected || proxyState is ProxyState.Suppressed
        if (!xrayActuallyReady && isProxyActive && xrayEnabled) {
            delay(300)
            showXraySpinner = true
        } else {
            showXraySpinner = !xrayActuallyReady && xrayEnabled
        }
    }

    val toggleState = remember(proxyState, xrayState, isRestarting, showXraySpinner, wasActiveBeforeRestart) {
        val isActive = proxyState !is ProxyState.Idle || xrayState == XrayState.Running || xrayState == XrayState.DirectRoute
        val actuallyRestarting = isRestarting && (isActive || wasActiveBeforeRestart)
        val gapFilling = wasActiveBeforeRestart && proxyState is ProxyState.Idle
        when {
            actuallyRestarting || gapFilling || proxyState is ProxyState.Starting || proxyState is ProxyState.Connecting || proxyState is ProxyState.CaptchaRequired || proxyState is ProxyState.WaitingForNetwork -> "loading"
            proxyState is ProxyState.Connected || proxyState is ProxyState.Suppressed -> if (showXraySpinner) "loading" else "active"
            proxyState is ProxyState.Error -> "error"
            else -> "idle"
        }
    }

    val containerColor by animateColorAsState(
        targetValue = when (toggleState) {
            "active" -> if (xrayState == XrayState.DirectRoute) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
            "loading" -> MaterialTheme.colorScheme.tertiary
            "error" -> MaterialTheme.colorScheme.errorContainer
            else -> {
                val isActive = proxyState !is ProxyState.Idle || xrayState == XrayState.Running || xrayState == XrayState.DirectRoute
                if ((isRestarting && (isActive || wasActiveBeforeRestart)) || proxyState is ProxyState.Starting) 
                    MaterialTheme.colorScheme.surfaceContainerHigh 
                else MaterialTheme.colorScheme.surfaceVariant
            }
        },
        animationSpec = tween(600),
        label = "btn_bg"
    )

    val contentColor by animateColorAsState(
        targetValue = when (toggleState) {
            "active" -> if (xrayState == XrayState.DirectRoute) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onPrimary
            "loading" -> MaterialTheme.colorScheme.onTertiary
            "error" -> MaterialTheme.colorScheme.onErrorContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(600),
        label = "btn_fg"
    )

    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.84f
            toggleState == "loading" -> 0.90f
            toggleState == "active" || toggleState == "error" -> 0.95f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "btn_scale"
    )

    val elevation by animateDpAsState(
        targetValue = when {
            isPressed -> 0.dp
            toggleState == "loading" -> 2.dp
            toggleState == "active" || toggleState == "error" -> 8.dp
            else -> 20.dp
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "elevation"
    )

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(160.dp)) {
        Surface(
            onClick = onClick,
            interactionSource = interactionSource,
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
            Box(contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    when (toggleState) {
                        "loading" -> {
                            CircularWavyProgressIndicator(
                                modifier = Modifier.size(54.dp),
                                color = contentColor
                            )
                        }

                        "active" -> {
                            val icon = if (xrayState == XrayState.DirectRoute) R.drawable.ethernet_24px else R.drawable.check_circle_24px
                            Icon(
                                painterResource(icon),
                                stringResource(R.string.proxy_active_stop),
                                Modifier.size(66.dp),
                                tint = contentColor
                            )
                        }

                        "error" -> Icon(
                            painterResource(R.drawable.error_24px),
                            stringResource(R.string.proxy_error_restart),
                            Modifier.size(66.dp),
                            tint = contentColor
                        )

                        else -> Icon(
                            painterResource(R.drawable.power_24px),
                            stringResource(R.string.start_proxy),
                            Modifier.size(66.dp),
                            tint = contentColor
                        )
                    }
                }
            }
        }

        if (isLocked) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-12).dp, y = 12.dp)
                    .size(36.dp)
                    .shadow(elevation = 3.dp, shape = CircleShape),
                tonalElevation = 3.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.lock_24px),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }


}

// --- Repo Links Sheet Content ---
@Composable
private fun RepoLinksContent(
    containerColor: Color
) {
    // --- State & Data ---
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
                    title = stringResource(R.string.olcrtc_core),
                    subtitle = "openlibrecommunity/olcrtc",
                    url = "https://github.com/openlibrecommunity/olcrtc",
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
                    title = stringResource(R.string.hev_socks5_tunnel),
                    subtitle = "heiher/hev-socks5-tunnel",
                    url = "https://github.com/heiher/hev-socks5-tunnel",
                    containerColor = containerColor,
                    onHaptic = { HapticUtil.perform(context, HapticUtil.Pattern.SELECTION) },
                    onOpen = { uriHandler.openUri(it) }
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

// --- Repo Link Item Component ---
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
private fun ProxyAddressRow(
    label: String,
    address: String,
    isModified: Boolean,
    isCopied: Boolean,
    leadingIcon: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            StandardLeadingIcon(content = leadingIcon)
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                address.ifBlank { stringResource(R.string.dash) },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (address.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                InlineConfigIndicator(isModified)
            }
        }
        Icon(
            painter = painterResource(if (isCopied) R.drawable.check_circle_24px else R.drawable.content_copy_24px),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (isCopied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}


