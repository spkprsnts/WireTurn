@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui.screens

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
import com.wireturn.app.data.KernelVariant
import com.wireturn.app.data.XrayConfiguration
import com.wireturn.app.ui.AppExclusionTooltip
import com.wireturn.app.ui.AppSnackbar
import com.wireturn.app.ui.CompactItem
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.ItemPosition
import com.wireturn.app.ui.ModifiedIndicator
import com.wireturn.app.ui.RowLabel
import com.wireturn.app.ui.SectionGroup
import com.wireturn.app.ui.SectionItem
import com.wireturn.app.ui.StandardLeadingIcon
import com.wireturn.app.ui.SupportingText
import com.wireturn.app.ui.SwitchRow
import com.wireturn.app.ui.UpdateBlock
import com.wireturn.app.ui.VerticalAnimatedText
import com.wireturn.app.ui.components.CoreToggleButton
import com.wireturn.app.ui.privacySpoiler
import com.wireturn.app.ui.redact
import com.wireturn.app.ui.showExclusiveSnackbar
import com.wireturn.app.viewmodel.CoreState
import com.wireturn.app.viewmodel.MainViewModel
import com.wireturn.app.viewmodel.UpdateState
import com.wireturn.app.viewmodel.VpnState
import com.wireturn.app.viewmodel.XrayState
import com.wireturn.app.viewmodel.isImportant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ln
import kotlin.math.pow

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
    onCheckMismatch: (Boolean, () -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    // --- State & Data ---
    val context = LocalContext.current
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
    val xraySession by XrayServiceState.session.collectAsStateWithLifecycle()

    val batteryNotificationDismissed by viewModel.batteryNotificationDismissed.collectAsStateWithLifecycle()
    val vpnEnabled = vpnSettings.enabled
    val appsExclusionHintShown by viewModel.appsExclusionHintShown.collectAsStateWithLifecycle()
    val privacyMode by viewModel.privacyMode.collectAsStateWithLifecycle()
    val isRestarting by CoreServiceState.isRestarting.collectAsStateWithLifecycle()
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
    var lastSuccessPing by remember { mutableStateOf<MainViewModel.PingResult.Success?>(null) }
    var isControlPingScheduled by rememberSaveable { mutableStateOf(value = false) }

    val lifecycleOwner = LocalLifecycleOwner.current

    // --- Effects & Lifecycle ---
    LaunchedEffect(proxyPing, proxyState) {
        if (proxyPing is MainViewModel.PingResult.Success) {
            lastSuccessPing = proxyPing as MainViewModel.PingResult.Success
        }

        val isProxyActive =
            proxyState is CoreState.Connected || proxyState is CoreState.Suppressed
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

    val snackbarHostState = remember { SnackbarHostState() }
    val homeScrollState = rememberScrollState()

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
                ContextCompat.checkSelfPermission(
                    context,
                    "android.permission.POST_NOTIFICATIONS"
                ) == PackageManager.PERMISSION_GRANTED
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
                    val fileName =
                        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            val index =
                                cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
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
                } catch (_: Exception) {
                }
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
                    isIgnoringBatteryOptimizations =
                        pm.isIgnoringBatteryOptimizations(context.packageName)
                    hasNotificationPermission =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            ContextCompat.checkSelfPermission(
                                context,
                                "android.permission.POST_NOTIFICATIONS"
                            ) == PackageManager.PERMISSION_GRANTED
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

    // --- Business Logic Logic (Mismatches & Alerts) ---
    val warnVpnRequiresXray = stringResource(R.string.warn_vpn_requires_xray)

    val showVpnWarning = {
        scope.launch {
            snackbarHostState.showExclusiveSnackbar(
                message = warnVpnRequiresXray
            )
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
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState
            ) { data ->
                AppSnackbar(data)
            }
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
            AnimatedVisibility(
                visible = (!isIgnoringBatteryOptimizations || !hasNotificationPermission) && !batteryNotificationDismissed,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                SectionItem(
                    position = ItemPosition.Single,
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
                                    RowLabel(stringResource(R.string.permissions_title))
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
                                                HapticUtil.perform(
                                                    context,
                                                    HapticUtil.Pattern.CLICK
                                                )
                                                notificationLauncher.launch("android.permission.POST_NOTIFICATIONS")
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.onErrorContainer,
                                                contentColor = MaterialTheme.colorScheme.errorContainer
                                            ),
                                            contentPadding = PaddingValues(horizontal = 12.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Icon(
                                                painterResource(R.drawable.info_24px),
                                                null,
                                                Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                stringResource(R.string.permission_notifications),
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                        }
                                    }
                                    if (!hasNotificationPermission && !isIgnoringBatteryOptimizations) {
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    if (!isIgnoringBatteryOptimizations) {
                                        Button(
                                            onClick = {
                                                HapticUtil.perform(
                                                    context,
                                                    HapticUtil.Pattern.CLICK
                                                )
                                                batteryOptLauncher.launch(
                                                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                                        data =
                                                            "package:${context.packageName}".toUri()
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
                                            Icon(
                                                painterResource(R.drawable.battery_android_frame_5_24px),
                                                null,
                                                Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                stringResource(R.string.permission_battery),
                                                style = MaterialTheme.typography.labelMedium
                                            )
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
            val profiles by viewModel.profiles.collectAsStateWithLifecycle()
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
                                    com.wireturn.app.ui.activities.CreateProfileActivity::class.java
                                )
                            )
                        } else {
                            onToggleProxy()
                        }
                    }
                )
            }

            Spacer(Modifier.height(22.dp))

            // --- Ping & Transfer Stats ---
            AnimatedVisibility(
                visible = (xrayState == XrayState.Running || xrayState == XrayState.DirectRoute) && (proxyState is CoreState.Connected || proxyState is CoreState.Suppressed),
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
                        horizontalArrangement = Arrangement.spacedBy(
                            10.dp,
                            Alignment.CenterHorizontally
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Stats Block
                        CompactItem(
                            modifier = Modifier.fillMaxHeight()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                val transfer = proxyTransfer
                                // Download
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
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
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                alpha = 0.6f
                                            )
                                        )
                                    }
                                }

                                // Upload
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
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
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                alpha = 0.6f
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        // Ping Block
                        CompactItem(
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
                                    val infiniteTransition =
                                        rememberInfiniteTransition(label = "ping_pulse")
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
                                                    text = stringResource(
                                                        R.string.ping_ms,
                                                        lastSuccessPing!!.ms
                                                    ),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = (if (lastSuccessPing!!.ms < 1100) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error).copy(
                                                        alpha = pulseAlpha
                                                    ),
                                                    fontWeight = FontWeight.Bold,
                                                    contentAlignment = Alignment.Center
                                                )
                                            } else {
                                                Box(
                                                    modifier = Modifier
                                                        .size(width = 36.dp, height = 12.dp)
                                                        .graphicsLayer { alpha = pulseAlpha }
                                                        .background(
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                                alpha = 0.2f
                                                            ),
                                                            shape = CircleShape
                                                        )
                                                )
                                            }
                                        }

                                        is MainViewModel.PingResult.Success -> {
                                            VerticalAnimatedText(
                                                text = stringResource(
                                                    R.string.ping_ms,
                                                    currentPing.ms
                                                ),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = if (currentPing.ms < 1100) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
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
            SectionItem(
                position = ItemPosition.Single,
                onClick = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    showProfilesDialog.value = true
                }
            ) {
                ProfilesBlock(
                    viewModel = viewModel,
                    onImport = {
                        profileImportLauncher.launch(
                            arrayOf(
                                "application/json",
                                "application/zip"
                            )
                        )
                    }
                )
            }

            Spacer(Modifier.height(24.dp))

            if (showProfilesDialog.value) {
                ProfilesDialog(
                    viewModel = viewModel,
                    onImport = {
                        profileImportLauncher.launch(
                            arrayOf(
                                "application/json",
                                "application/zip"
                            )
                        )
                    },
                    onDismiss = { showProfilesDialog.value = false }
                )
            }

            val isSocks5Core = activeConfig.kernelVariant == KernelVariant.OLCRTC || activeConfig.kernelVariant == KernelVariant.WEBDAV

            // --- Xray & VPN Settings ---
            val isSettingsValid = if (isSocks5Core) {
                // For OLCRTC/WebDAV, link is only required if DualRoute is enabled
                if (activeXrayConfig.protocol == XrayConfiguration.VLESS && activeVlessConfig.isDualRoute) {
                    activeVlessConfig.isValid()
                } else {
                    true // WG or VLESS solo mode just uses SOCKS5 from core
                }
            } else {
                if (activeXrayConfig.protocol == XrayConfiguration.VLESS) activeVlessConfig.isValid() else activeWgConfig.isValid()
            }
            val configValid = isSettingsValid || xrayState != XrayState.Idle || isRestarting

            val xrayProtocol = when {
                isSocks5Core -> {
                    when (xrayState) {
                        XrayState.DirectRoute -> stringResource(R.string.vless)
                        XrayState.Running -> stringResource(R.string.socks5)
                        else -> {
                            if (activeVlessConfig.isDualRoute) "${stringResource(R.string.socks5)} / ${
                                stringResource(
                                    R.string.vless
                                )
                            }"
                            else stringResource(R.string.socks5)
                        }
                    }
                }

                xrayState == XrayState.Running || xrayState == XrayState.DirectRoute -> if (xraySession?.vless != null) stringResource(
                    R.string.vless
                ) else stringResource(R.string.wg_short)

                else -> if (activeXrayConfig.protocol == XrayConfiguration.VLESS) stringResource(R.string.vless) else stringResource(
                    R.string.wg_short
                )
            }

            val profilesExist = profiles.isNotEmpty()
            SectionGroup {
                SectionItem(
                    position = ItemPosition.Top,
                    onClick = {
                        if (profilesExist) {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            onNavigateToXrayConfig()
                        }
                    }
                ) {
                    LaunchedEffect(
                        configValid,
                        xrayConfig.enabled,
                        currentProfileId,
                        autoLaunchSettings.enabled
                    ) {
                        delay(300)
                        if (!configValid && xrayConfig.enabled && !autoLaunchSettings.enabled) {
                            viewModel.updateXrayConfig(viewModel.xrayConfig.value.copy(enabled = false))
                        }
                    }

                    SwitchRow(
                        label = stringResource(R.string.xray_title) + if (configValid && profilesExist) " $xrayProtocol" else "",
                        checked = xrayConfig.enabled,
                        onCheckedChange = { next ->
                            val action = {
                                HapticUtil.perform(
                                    context,
                                    if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                                )

                                if (!next && vpnEnabled) {
                                    showVpnWarning()
                                }

                                viewModel.updateXrayConfig(xrayConfig.copy(enabled = next))
                            }

                            if (next) {
                                onCheckMismatch(true, action)
                            } else {
                                action()
                            }
                        },
                        isSplit = true,
                        supportingText = if (!profilesExist) null else if (!configValid) stringResource(
                            R.string.xray_config_invalid
                        ) else {
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
                                    tint = if (xrayState == XrayState.Idle || !profilesExist) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                                )

                                XrayState.Starting, XrayState.Connecting -> LoadingIndicator()
                            }
                        },
                        enabled = configValid && profilesExist
                    )
                }

                val toggleVpnAction = { next: Boolean ->
                    HapticUtil.perform(
                        context,
                        if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                    )

                    if (next && !xrayConfig.enabled) {
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

                SectionItem(
                    position = ItemPosition.Bottom,
                    onClick = { toggleVpnAction(!vpnEnabled) }
                ) {
                    SwitchRow(
                        label = stringResource(R.string.vpn_mode),
                        checked = vpnEnabled,
                        onCheckedChange = toggleVpnAction,
                        isModified = xraySession?.wg != null && vpnEnabled != (vpnServiceState == VpnState.Running),
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
                                FilledTonalIconButton(
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
                                        modifier = Modifier.size(24.dp),
                                        tint = if (vpnSettings.filteringEnabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            val isOlcrtc = activeConfig.kernelVariant == KernelVariant.OLCRTC || activeConfig.kernelVariant == KernelVariant.WEBDAV
            val showXray = xrayConfig.enabled

            fun formatProxyAddr(
                addr: String,
                user: String,
                pass: String,
                authEnabled: Boolean
            ): String {
                if (addr.isBlank()) return ""
                return if (authEnabled && user.isNotBlank()) {
                    "$user:$pass@$addr"
                } else {
                    addr
                }
            }

            val displaySocksAddr = when {
                showXray -> activeXraySettings.socksBindAddress
                isOlcrtc -> activeConfig.socksAddr
                else -> activeXraySettings.socksBindAddress
            }

            val copySocksAddr = when {
                showXray -> formatProxyAddr(
                    activeXraySettings.socksBindAddress,
                    activeXraySettings.proxyUser,
                    activeXraySettings.proxyPass,
                    activeXraySettings.isProxyAuthEnabled
                )

                isOlcrtc -> formatProxyAddr(
                    activeConfig.socksAddr,
                    activeConfig.socksUser,
                    activeConfig.socksPass,
                    activeConfig.isSocksAuthEnabled
                )

                else -> formatProxyAddr(
                    activeXraySettings.socksBindAddress,
                    activeXraySettings.proxyUser,
                    activeXraySettings.proxyPass,
                    activeXraySettings.isProxyAuthEnabled
                )
            }

            val displayHttpAddr = when {
                showXray -> activeXraySettings.httpBindAddress
                isOlcrtc -> ""
                else -> activeXraySettings.httpBindAddress
            }

            val copyHttpAddr = when {
                showXray -> formatProxyAddr(
                    activeXraySettings.httpBindAddress,
                    activeXraySettings.proxyUser,
                    activeXraySettings.proxyPass,
                    activeXraySettings.isProxyAuthEnabled
                )

                isOlcrtc -> ""
                else -> formatProxyAddr(
                    activeXraySettings.httpBindAddress,
                    activeXraySettings.proxyUser,
                    activeXraySettings.proxyPass,
                    activeXraySettings.isProxyAuthEnabled
                )
            }

            val isSocksModified = when {
                showXray -> xraySession != null && activeXraySettings.socksBindAddress != xraySession?.settings?.socksBindAddress
                isOlcrtc -> proxySession != null && activeConfig.socksAddr != proxySession?.clientConfig?.socksAddr
                else -> xraySession != null && activeXraySettings.socksBindAddress != xraySession?.settings?.socksBindAddress
            }

            val isHttpModified = when {
                showXray -> xraySession != null && activeXraySettings.httpBindAddress != xraySession?.settings?.httpBindAddress
                else -> false
            }

            SectionGroup(
                modifier = Modifier.graphicsLayer {
                    alpha = if (showXray || isOlcrtc) 1f else 0.38f
                }
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

                SectionItem(
                    position = ItemPosition.Top,
                    enabled = showXray || isOlcrtc,
                    onClick = {
                        if (privacyMode) return@SectionItem
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipData.newPlainText(socks5Label, copySocksAddr).toClipEntry()
                            )
                            socksCopied = true
                        }
                    }
                ) {
                    ProxyAddressRow(
                        label = stringResource(R.string.socks5),
                        address = displaySocksAddr,
                        isModified = isSocksModified,
                        isCopied = socksCopied,
                        privacyMode = privacyMode,
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.lan_24px),
                                contentDescription = null,
                                tint = if (showXray || isOlcrtc) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                    alpha = 0.38f
                                )
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

                    SectionItem(
                        enabled = showXray,
                        onClick = {
                            if (privacyMode) return@SectionItem
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipData.newPlainText(
                                        httpLabel,
                                        copyHttpAddr
                                    ).toClipEntry()
                                )
                                httpCopied = true
                            }
                        }
                    ) {
                        ProxyAddressRow(
                            label = stringResource(R.string.xray_http),
                            address = displayHttpAddr,
                            isModified = isHttpModified,
                            isCopied = httpCopied,
                            privacyMode = privacyMode,
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.lan_24px),
                                    contentDescription = null,
                                    tint = if (showXray) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                        alpha = 0.38f
                                    )
                                )
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(2.dp))
            SectionItem(
                position = ItemPosition.Bottom,
                onClick = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    onNavigateToConnectionSettings()
                }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StandardLeadingIcon {
                        Icon(
                            painter = painterResource(R.drawable.settings_24px),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        RowLabel(stringResource(R.string.connection_settings_title))
                        SupportingText(stringResource(R.string.connection_settings_desc))
                    }
                    Icon(
                        painter = painterResource(R.drawable.arrow_forward_ios_24px),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(Modifier.navigationBarsPadding())
            Spacer(Modifier.height(16.dp))
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
    AnimatedVisibility(
        visible = state.isImportant,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        SectionItem(
            position = ItemPosition.Single,
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
    leadingIcon: @Composable (() -> Unit)? = null,
    privacyMode: Boolean = false
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            StandardLeadingIcon(content = leadingIcon)
        }

        Column(modifier = Modifier.weight(1f)) {
            val displayText = if (privacyMode) {
                if (address.isBlank()) " ".repeat(25) else address.redact(true)
            } else {
                address.ifBlank { stringResource(R.string.dash) }
            }
            Text(
                displayText,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (address.isBlank() && !privacyMode) MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = 0.5f
                )
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.privacySpoiler(privacyMode)
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ModifiedIndicator(isModified)
            }
        }
        Icon(
            painter = painterResource(if (isCopied) R.drawable.check_circle_24px else R.drawable.content_copy_24px),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (isCopied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                alpha = 0.6f
            )
        )
    }
}
