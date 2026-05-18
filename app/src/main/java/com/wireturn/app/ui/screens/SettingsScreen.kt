@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui.screens

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.R
import com.wireturn.app.ProxyServiceState
import com.wireturn.app.XrayServiceState
import com.wireturn.app.data.ThemeMode
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.XrayConfig
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.LabeledButtonGroup
import com.wireturn.app.ui.SettingsGroup
import com.wireturn.app.ui.SettingsGroupItem
import com.wireturn.app.ui.SwitchRow
import com.wireturn.app.ui.TextFieldRow
import com.wireturn.app.ui.UpdateBlock
import com.wireturn.app.ui.ValidatorUtils
import com.wireturn.app.ui.configButtonGroupItem
import com.wireturn.app.ui.redact
import com.wireturn.app.ui.trackScrollDelta
import com.wireturn.app.viewmodel.MainViewModel
import com.wireturn.app.viewmodel.UpdateState
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    scrollToUpdate: Long = 0L
) {
    val context = LocalContext.current
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val dynamicTheme by viewModel.dynamicTheme.collectAsStateWithLifecycle()
    val privacyMode by viewModel.privacyMode.collectAsStateWithLifecycle()
    val allowUnstableUpdates by viewModel.allowUnstableUpdates.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val updateProgress by viewModel.updateProgress.collectAsStateWithLifecycle()

    val clientSnapshot by ProxyServiceState.clientConfigSnapshot.collectAsStateWithLifecycle()
    val xraySnapshot by XrayServiceState.xrayConfigSnapshot.collectAsStateWithLifecycle()

    val showResetDialog = rememberSaveable { mutableStateOf(false) }
    val showListenHelp = remember { mutableStateOf(false) }
    val showSocksHelp = remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    var updateBlockOffset by remember { mutableFloatStateOf(0f) }
    var hasScrolled by remember(scrollToUpdate) { mutableStateOf(false) }

    LaunchedEffect(scrollToUpdate) {
        if (scrollToUpdate > 0L && !hasScrolled) {
            // Ждем пока координаты блока станут доступны
            while (updateBlockOffset <= 0f) {
                delay(16)
            }
            // Небольшая задержка, чтобы анимация была плавной и после отрисовки
            delay(100)
            scrollState.animateScrollTo(updateBlockOffset.toInt())
            hasScrolled = true
        }
    }

    val supportsDynamicColor = remember { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }
    val supportsSystemTheme = remember { Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q }

    LaunchedEffect(supportsDynamicColor) {
        if (!supportsDynamicColor && dynamicTheme) {
            viewModel.setDynamicTheme(false)
        }
    }

    LaunchedEffect(supportsSystemTheme) {
        if (!supportsSystemTheme && themeMode == ThemeMode.SYSTEM) {
            viewModel.setThemeMode(ThemeMode.DARK)
        }
    }

    val initialOffset = with(LocalDensity.current) { -48.dp.toPx() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        remember {
            androidx.compose.material3.TopAppBarState(
                initialHeightOffsetLimit = initialOffset,
                initialHeightOffset = initialOffset,
                initialContentOffset = 0f
            )
        }
    )

    val dash = stringResource(R.string.dash)
    val appVersion = remember {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: dash
        } catch (_: Exception) {
            dash
        }
    }

    val isDark = com.wireturn.app.ui.theme.LocalIsDark.current
    val blockContainerColor = if (isDark) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        MaterialTheme.colorScheme.surface
    }

    val showBottomSheet = rememberSaveable { mutableStateOf(false) }
    val bottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumTopAppBar(
                title = {
                    val collapsedFraction = scrollBehavior.state.collapsedFraction
                    Text(
                        text = stringResource(R.string.app_settings_title),
                        modifier = Modifier.padding(
                            bottom = 24.dp * (1f - collapsedFraction)
                        )
                    )
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                ),
                actions = {
                    IconButton(onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        showBottomSheet.value = true
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.info_24px), 
                            contentDescription = stringResource(R.string.info_desc)
                        )
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .fillMaxWidth()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 840.dp)
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .padding(horizontal = 16.dp)
                .trackScrollDelta(
                    onScrollDelta = { viewModel.onBottomBarScroll(it) },
                    onSettle = { viewModel.settleBottomBar(it) }
                )
                .verticalScroll(scrollState)
                .padding(bottom = 76.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // 0. Proxy
            val clientConfig by viewModel.clientConfig.collectAsStateWithLifecycle()
            val xrayConfig by viewModel.xrayConfig.collectAsStateWithLifecycle()

            // Turnable
            SettingsGroup(title = stringResource(R.string.settings_group_turnable)) {
                var listenAddr by remember(clientConfig.listenAddr) { mutableStateOf(clientConfig.listenAddr) }
                
                LaunchedEffect(listenAddr) {
                    delay(300)
                    if (listenAddr != clientConfig.listenAddr && ValidatorUtils.isValidHostPort(listenAddr)) {
                        viewModel.saveClientConfig(clientConfig.copy(listenAddr = listenAddr))
                    }
                }
                
                SettingsGroupItem(isTop = true, isBottom = true, containerColor = blockContainerColor) {
                    TextFieldRow(
                        label = stringResource(R.string.local_listen_address),
                        value = listenAddr.redact(privacyMode),
                        onValueChange = { listenAddr = it },
                        placeholder = ClientConfig.DEFAULT_LISTEN_ADDR,
                        isError = !ValidatorUtils.isValidHostPort(listenAddr),
                        readOnly = privacyMode,
                        isModified = clientSnapshot?.let { it.listenAddr != listenAddr } ?: false,
                        onHelpClick = { showListenHelp.value = true }
                    )
                }
            }

            // olcRTC
            val olcrtcSocksAddr by viewModel.olcrtcSocksAddr.collectAsStateWithLifecycle()
            val olcrtcSocksAuthEnabled by viewModel.olcrtcSocksAuthEnabled.collectAsStateWithLifecycle()
            val olcrtcSocksUser by viewModel.olcrtcSocksUser.collectAsStateWithLifecycle()
            val olcrtcSocksPass by viewModel.olcrtcSocksPass.collectAsStateWithLifecycle()

            SettingsGroup(title = stringResource(R.string.settings_group_olcrtc)) {
                var olSocks by remember(olcrtcSocksAddr) { mutableStateOf(olcrtcSocksAddr) }
                var olAuth by remember(olcrtcSocksAuthEnabled) { mutableStateOf(olcrtcSocksAuthEnabled) }
                var olUser by remember(olcrtcSocksUser) { mutableStateOf(olcrtcSocksUser) }
                var olPass by remember(olcrtcSocksPass) { mutableStateOf(olcrtcSocksPass) }
                var olPassVisible by rememberSaveable { mutableStateOf(false) }

                LaunchedEffect(olSocks, olAuth, olUser, olPass) {
                    delay(300)
                    if (olSocks != olcrtcSocksAddr || 
                        olAuth != olcrtcSocksAuthEnabled || 
                        olUser != olcrtcSocksUser || 
                        olPass != olcrtcSocksPass) {
                        viewModel.saveOlcrtcSocks(olSocks, olAuth, olUser, olPass)
                    }
                }

                SettingsGroupItem(isTop = true, isBottom = false, containerColor = blockContainerColor) {
                    TextFieldRow(
                        label = stringResource(R.string.socks5),
                        value = olSocks.redact(privacyMode),
                        onValueChange = { olSocks = it },
                        placeholder = "127.0.0.1:9001",
                        isError = olSocks.isNotEmpty() && !ValidatorUtils.isValidHostPort(olSocks),
                        readOnly = privacyMode,
                        isModified = clientSnapshot?.let { it.socksAddr != olSocks } ?: false,
                        onHelpClick = { showSocksHelp.value = true }
                    )
                }
                
                SettingsGroupItem(
                    isTop = false,
                    isBottom = !olAuth,
                    containerColor = blockContainerColor,
                    onClick = {
                        olAuth = !olAuth
                        HapticUtil.perform(
                            context, 
                            if (olAuth) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                        )
                    }
                ) {
                    SwitchRow(
                        label = stringResource(R.string.xray_proxy_auth),
                        supportingText = stringResource(R.string.xray_proxy_auth_desc),
                        checked = olAuth,
                        onCheckedChange = { olAuth = it },
                        isModified = clientSnapshot?.let { it.isSocksAuthEnabled != olAuth } ?: false
                    )
                }
                
                AnimatedVisibility(visible = olAuth) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                            TextFieldRow(
                                label = stringResource(R.string.xray_proxy_user),
                                value = olUser.redact(privacyMode),
                                onValueChange = { olUser = it },
                                placeholder = "admin",
                                readOnly = privacyMode,
                                isModified = clientSnapshot?.let { it.socksUser != olUser } ?: false
                            )
                        }
                        SettingsGroupItem(isTop = false, isBottom = true, containerColor = blockContainerColor) {
                            TextFieldRow(
                                label = stringResource(R.string.xray_proxy_pass),
                                value = olPass.redact(privacyMode),
                                onValueChange = { olPass = it },
                                placeholder = "password",
                                readOnly = privacyMode,
                                isModified = clientSnapshot?.let { it.socksPass != olPass } ?: false,
                                trailingIcon = {
                                    IconButton(onClick = { olPassVisible = !olPassVisible }) {
                                        Icon(
                                            painter = painterResource(
                                                if (olPassVisible) R.drawable.visibility_24px
                                                else R.drawable.visibility_off_24px
                                            ),
                                            contentDescription = null
                                        )
                                    }
                                },
                                visualTransformation = if (olPassVisible) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                }
                            )
                        }
                    }
                }
            }

            // Xray
            SettingsGroup(title = stringResource(R.string.settings_group_xray)) {
                var xraySocks by remember(xrayConfig.socksBindAddress) { mutableStateOf(xrayConfig.socksBindAddress) }
                var xrayHttp by remember(xrayConfig.httpBindAddress) { mutableStateOf(xrayConfig.httpBindAddress) }
                var xrayPassVisible by rememberSaveable { mutableStateOf(false) }
                
                LaunchedEffect(xraySocks, xrayHttp) {
                    delay(300)
                    val next = xrayConfig.copy(socksBindAddress = xraySocks, httpBindAddress = xrayHttp)
                    if (next != xrayConfig) viewModel.updateXrayConfig(next)
                }
                
                SettingsGroupItem(isTop = true, isBottom = false, containerColor = blockContainerColor) {
                    TextFieldRow(
                        label = stringResource(R.string.socks5),
                        value = xraySocks.redact(privacyMode),
                        onValueChange = { xraySocks = it },
                        placeholder = XrayConfig.DEFAULT_SOCKS_BIND_ADDRESS,
                        isError = !ValidatorUtils.isValidHostPort(xraySocks),
                        readOnly = privacyMode,
                        isModified = xraySnapshot?.let { it.socksBindAddress != xraySocks } ?: false
                    )
                }
                
                SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                    TextFieldRow(
                        label = stringResource(R.string.xray_http),
                        value = xrayHttp.redact(privacyMode),
                        onValueChange = { xrayHttp = it },
                        placeholder = "127.0.0.1:1081",
                        isError = xrayHttp.isNotEmpty() && !ValidatorUtils.isValidHostPort(xrayHttp),
                        readOnly = privacyMode,
                        isModified = xraySnapshot?.let { it.httpBindAddress != xrayHttp } ?: false
                    )
                }

                var xrayAuth by remember(xrayConfig.isProxyAuthEnabled) { mutableStateOf(xrayConfig.isProxyAuthEnabled) }
                var xrayUser by remember(xrayConfig.proxyUser) { mutableStateOf(xrayConfig.proxyUser) }
                var xrayPass by remember(xrayConfig.proxyPass) { mutableStateOf(xrayConfig.proxyPass) }
                
                LaunchedEffect(xrayAuth, xrayUser, xrayPass) {
                    delay(300)
                    val next = xrayConfig.copy(isProxyAuthEnabled = xrayAuth, proxyUser = xrayUser, proxyPass = xrayPass)
                    if (next != xrayConfig) viewModel.updateXrayConfig(next)
                }

                SettingsGroupItem(
                    isTop = false,
                    isBottom = !xrayAuth,
                    containerColor = blockContainerColor,
                    onClick = {
                        xrayAuth = !xrayAuth
                        HapticUtil.perform(
                            context, 
                            if (xrayAuth) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                        )
                    }
                ) {
                    SwitchRow(
                        label = stringResource(R.string.xray_proxy_auth),
                        supportingText = stringResource(R.string.xray_proxy_auth_desc),
                        checked = xrayAuth,
                        onCheckedChange = { xrayAuth = it },
                        isModified = xraySnapshot?.let { it.isProxyAuthEnabled != xrayAuth } ?: false
                    )
                }
                
                AnimatedVisibility(visible = xrayAuth) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                            TextFieldRow(
                                label = stringResource(R.string.xray_proxy_user),
                                value = xrayUser.redact(privacyMode),
                                onValueChange = { xrayUser = it },
                                placeholder = "admin",
                                readOnly = privacyMode,
                                isModified = xraySnapshot?.let { it.proxyUser != xrayUser } ?: false
                            )
                        }
                        SettingsGroupItem(isTop = false, isBottom = true, containerColor = blockContainerColor) {
                            TextFieldRow(
                                label = stringResource(R.string.xray_proxy_pass),
                                value = xrayPass.redact(privacyMode),
                                onValueChange = { xrayPass = it },
                                placeholder = "password",
                                readOnly = privacyMode,
                                isModified = xraySnapshot?.let { it.proxyPass != xrayPass } ?: false,
                                trailingIcon = {
                                    IconButton(onClick = { xrayPassVisible = !xrayPassVisible }) {
                                        Icon(
                                            painter = painterResource(
                                                if (xrayPassVisible) R.drawable.visibility_24px
                                                else R.drawable.visibility_off_24px
                                            ),
                                            contentDescription = null
                                        )
                                    }
                                },
                                visualTransformation = if (xrayPassVisible) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                }
                            )
                        }
                    }
                }
            }


            // 1. Оформление
            val themeModes = remember(supportsSystemTheme) {
                if (supportsSystemTheme) {
                    ThemeMode.entries
                } else {
                    ThemeMode.entries.filter { it != ThemeMode.SYSTEM }
                }
            }
            val themeModeLabels = themeModes.associateWith { mode ->
                stringResource(when (mode) {
                    ThemeMode.LIGHT -> R.string.theme_light
                    ThemeMode.DARK -> R.string.theme_dark
                    ThemeMode.SYSTEM -> R.string.theme_system
                })
            }

            SettingsGroup(title = stringResource(R.string.app_appearance)) {
                val showFloatingActionButton by viewModel.showFloatingActionButton.collectAsStateWithLifecycle()

                SettingsGroupItem(
                    isTop = true, 
                    isBottom = false, 
                    containerColor = blockContainerColor
                ) {
                    LabeledButtonGroup(label = stringResource(R.string.theme_title)) {
                        themeModes.forEachIndexed { index, mode ->
                            configButtonGroupItem(
                                selected = themeMode == mode,
                                onSelect = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                    viewModel.setThemeMode(mode)
                                },
                                label = themeModeLabels[mode] ?: "",
                                index = index,
                                count = themeModes.size
                            )
                        }
                    }
                }

                if (supportsDynamicColor) {
                    SettingsGroupItem(
                        isTop = false, 
                        isBottom = false, 
                        containerColor = blockContainerColor,
                        onClick = {
                            val next = !dynamicTheme
                            HapticUtil.perform(
                                context, 
                                if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                            )
                            viewModel.setDynamicTheme(next)
                        }
                    ) {
                        SwitchRow(
                            label = stringResource(R.string.dynamic_theme_title),
                            supportingText = stringResource(R.string.dynamic_theme_desc),
                            checked = dynamicTheme,
                            onCheckedChange = { viewModel.setDynamicTheme(it) }
                        )
                    }
                }

                SettingsGroupItem(
                    isTop = false,
                    isBottom = true,
                    containerColor = blockContainerColor,
                    onClick = {
                        val next = !showFloatingActionButton
                        HapticUtil.perform(
                            context, 
                            if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                        )
                        viewModel.setShowFloatingActionButton(next)
                    }
                ) {
                    SwitchRow(
                        label = stringResource(R.string.settings_show_fab_title),
                        supportingText = stringResource(R.string.settings_show_fab_desc),
                        checked = showFloatingActionButton,
                        onCheckedChange = { viewModel.setShowFloatingActionButton(it) }
                    )
                }

                Spacer(Modifier.height(12.dp))

                SettingsGroupItem(
                    isTop = true,
                    isBottom = true,
                    containerColor = blockContainerColor,
                    onClick = {
                        val next = !privacyMode
                        HapticUtil.perform(
                            context, 
                            if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                        )
                        viewModel.setPrivacyMode(next)
                    }
                ) {
                    SwitchRow(
                        label = stringResource(R.string.privacy_mode_title),
                        supportingText = stringResource(R.string.privacy_mode_desc),
                        checked = privacyMode,
                        onCheckedChange = { viewModel.setPrivacyMode(it) }
                    )
                }
            }

            // 2.1 Сеть
            val waitForNetwork by viewModel.waitForNetwork.collectAsStateWithLifecycle()
            val restartOnNetworkChange by viewModel.restartOnNetworkChange.collectAsStateWithLifecycle()
            val autoLaunchSettings by viewModel.autoLaunchSettings.collectAsStateWithLifecycle()

            SettingsGroup(title = stringResource(R.string.network_settings_title)) {
                SettingsGroupItem(
                    isTop = true,
                    isBottom = false,
                    containerColor = blockContainerColor,
                    onClick = {
                        val next = !waitForNetwork
                        HapticUtil.perform(
                            context, 
                            if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                        )
                        viewModel.setWaitForNetwork(next)
                    }
                ) {
                    SwitchRow(
                        label = stringResource(R.string.wait_for_network_title),
                        supportingText = stringResource(R.string.wait_for_network_desc),
                        checked = waitForNetwork,
                        onCheckedChange = { viewModel.setWaitForNetwork(it) }
                    )
                }

                SettingsGroupItem(
                    isTop = false,
                    isBottom = true,
                    containerColor = blockContainerColor,
                    onClick = {
                        val next = !restartOnNetworkChange
                        HapticUtil.perform(
                            context, 
                            if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                        )
                        viewModel.setRestartOnNetworkChange(next)
                    }
                ) {
                    SwitchRow(
                        label = stringResource(R.string.restart_on_network_change_title),
                        supportingText = stringResource(R.string.restart_on_network_change_desc),
                        checked = restartOnNetworkChange,
                        onCheckedChange = { viewModel.setRestartOnNetworkChange(it) }
                    )
                }

                Spacer(Modifier.height(12.dp))

                var localUrl by remember { mutableStateOf(autoLaunchSettings.checkUrl) }
                var localInterval by remember { mutableStateOf(autoLaunchSettings.intervalMinutes.toString()) }

                val defaultUrl = "https://www.google.com"
                val defaultInterval = 15

                val isUrlValid = remember(localUrl) {
                    localUrl.isNotBlank() && (localUrl.startsWith("http://") || localUrl.startsWith("https://"))
                }
                val minutesInt = localInterval.toIntOrNull()
                val isIntervalValid = minutesInt != null && minutesInt >= 1
                val canEnable = isUrlValid && isIntervalValid

                LaunchedEffect(localUrl) {
                    delay(300)
                    val valueToSave = if (isUrlValid) localUrl else defaultUrl
                    if (valueToSave != autoLaunchSettings.checkUrl) {
                        viewModel.updateAutoLaunchSettings(autoLaunchSettings.copy(checkUrl = valueToSave))
                    }
                }

                LaunchedEffect(localInterval) {
                    delay(300)
                    val valueToSave = if (isIntervalValid) minutesInt else defaultInterval
                    if (valueToSave != autoLaunchSettings.intervalMinutes) {
                        viewModel.updateAutoLaunchSettings(autoLaunchSettings.copy(intervalMinutes = valueToSave))
                    }
                }

                LaunchedEffect(canEnable) {
                    if (!canEnable && autoLaunchSettings.enabled) {
                        viewModel.updateAutoLaunchSettings(autoLaunchSettings.copy(enabled = false))
                    }
                }

                SettingsGroupItem(
                    isTop = true,
                    isBottom = false,
                    containerColor = blockContainerColor,
                    enabled = canEnable,
                    onClick = {
                        if (!canEnable) return@SettingsGroupItem
                        val next = !autoLaunchSettings.enabled
                        HapticUtil.perform(
                            context, 
                            if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                        )
                        viewModel.updateAutoLaunchSettings(autoLaunchSettings.copy(enabled = next))
                    }
                ) {
                    SwitchRow(
                        label = stringResource(R.string.settings_auto_launch_title),
                        supportingText = stringResource(R.string.settings_auto_launch_desc),
                        checked = autoLaunchSettings.enabled,
                        enabled = canEnable,
                        onCheckedChange = { 
                            viewModel.updateAutoLaunchSettings(autoLaunchSettings.copy(enabled = it)) 
                        }
                    )
                }

                SettingsGroupItem(
                    isTop = false,
                    isBottom = false,
                    containerColor = blockContainerColor
                ) {
                    TextFieldRow(
                        label = stringResource(R.string.settings_auto_launch_url),
                        value = localUrl,
                        onValueChange = { localUrl = it },
                        isError = !isUrlValid && localUrl.isNotBlank(),
                        placeholder = defaultUrl
                    )
                }

                SettingsGroupItem(
                    isTop = false,
                    isBottom = true,
                    containerColor = blockContainerColor
                ) {
                    TextFieldRow(
                        label = stringResource(R.string.settings_auto_launch_interval),
                        value = localInterval,
                        onValueChange = { localInterval = it },
                        isError = !isIntervalValid && localInterval.isNotBlank(),
                        placeholder = defaultInterval.toString(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            }

            // 2.1 Капча
            val captchaStyleMod by viewModel.captchaStyleMod.collectAsStateWithLifecycle()
            val captchaForceTint by viewModel.captchaForceTint.collectAsStateWithLifecycle()

            SettingsGroup(title = stringResource(R.string.captcha_settings_title)) {
                SettingsGroupItem(
                    isTop = true,
                    isBottom = !captchaStyleMod,
                    containerColor = blockContainerColor,
                    onClick = {
                        val next = !captchaStyleMod
                        HapticUtil.perform(
                            context, 
                            if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                        )
                        viewModel.setCaptchaStyleMod(next)
                    }
                ) {
                    SwitchRow(
                        label = stringResource(R.string.captcha_style_mod_title),
                        supportingText = stringResource(R.string.captcha_style_mod_desc),
                        checked = captchaStyleMod,
                        onCheckedChange = { viewModel.setCaptchaStyleMod(it) }
                    )
                }

                if (captchaStyleMod) {
                    val originalLabel = stringResource(R.string.captcha_color_original)
                    val primaryLabel = stringResource(R.string.captcha_color_primary)
                    SettingsGroupItem(
                        isTop = false,
                        isBottom = true,
                        containerColor = blockContainerColor
                    ) {
                        LabeledButtonGroup(
                            label = stringResource(R.string.captcha_force_tint_title),
                            supportingText = stringResource(R.string.captcha_force_tint_desc)
                        ) {
                            configButtonGroupItem(
                                selected = !captchaForceTint,
                                onSelect = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                    viewModel.setCaptchaForceTint(false)
                                },
                                label = originalLabel,
                                index = 0,
                                count = 2
                            )
                            configButtonGroupItem(
                                selected = captchaForceTint,
                                onSelect = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                    viewModel.setCaptchaForceTint(true)
                                },
                                label = primaryLabel,
                                index = 1,
                                count = 2
                            )
                        }
                    }
                }
            }

            // 3. Обновление
            val isImportantState = updateState is UpdateState.Available || 
                    updateState is UpdateState.Downloading || 
                    updateState is UpdateState.ReadyToInstall
            
            val updateContainerColor = if (isImportantState) {
                if (isDark) {
                    MaterialTheme.colorScheme.surfaceVariant 
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                }
            } else {
                blockContainerColor
            }

            SettingsGroup(
                title = stringResource(R.string.update_title),
                modifier = Modifier.onGloballyPositioned { coordinates ->
                    updateBlockOffset = coordinates.positionInParent().y
                }
            ) {
                SettingsGroupItem(
                    isTop = true,
                    isBottom = false,
                    containerColor = updateContainerColor
                ) {
                    UpdateBlock(
                        state = updateState,
                        progress = updateProgress,
                        showChangelog = true,
                        onDownload = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            viewModel.downloadUpdate()
                        },
                        onInstall = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            viewModel.installUpdate()
                        },
                        onCheck = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            viewModel.checkForUpdate()
                        }
                    )
                }

                SettingsGroupItem(
                    isTop = false, 
                    isBottom = true, 
                    containerColor = blockContainerColor,
                    onClick = {
                        val next = !allowUnstableUpdates
                        HapticUtil.perform(
                            context, 
                            if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                        )
                        viewModel.setAllowUnstableUpdates(next)
                    }
                ) {
                    SwitchRow(
                        label = stringResource(R.string.unstable_updates_title),
                        supportingText = stringResource(R.string.unstable_updates_desc),
                        checked = allowUnstableUpdates,
                        onCheckedChange = { viewModel.setAllowUnstableUpdates(it) }
                    )
                }
            }

            // Язык
            val appLanguage by viewModel.appLanguage.collectAsStateWithLifecycle()
            val languages = listOf("system", "en", "ru")
            val languageLabels = languages.associateWith { lang ->
                stringResource(when (lang) {
                    "en" -> R.string.lang_en
                    "ru" -> R.string.lang_ru
                    else -> R.string.lang_system
                })
            }

            SettingsGroup(title = stringResource(R.string.localization_title)) {
                SettingsGroupItem(isTop = true, isBottom = true, containerColor = blockContainerColor) {
                    LabeledButtonGroup(label = stringResource(R.string.lang_title)) {
                        languages.forEachIndexed { index, lang ->
                            configButtonGroupItem(
                                selected = appLanguage == lang,
                                onSelect = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                    viewModel.setAppLanguage(lang)
                                },
                                label = languageLabels[lang] ?: "",
                                index = index,
                                count = languages.size
                            )
                        }
                    }
                }
            }

            // 4. Сброс настроек
            Surface(
                onClick = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    showResetDialog.value = true
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = Color.Transparent
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        painterResource(R.drawable.delete_24px),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        stringResource(R.string.reset_settings),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // Версия
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
                ) { 
                    Text(stringResource(R.string.reset)) 
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog.value = false }) { 
                    Text(stringResource(R.string.cancel)) 
                }
            }
        )
    }

    if (showListenHelp.value) {
        AlertDialog(
            onDismissRequest = { showListenHelp.value = false },
            title = { Text(stringResource(R.string.local_listen_address)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.local_port_help_text),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.local_port_help_secondary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showListenHelp.value = false }) {
                    Text(stringResource(R.string.btn_close))
                }
            }
        )
    }

    if (showSocksHelp.value) {
        AlertDialog(
            onDismissRequest = { showSocksHelp.value = false },
            title = { Text(stringResource(R.string.socks5)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.olcrtc_socks_help_text),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.olcrtc_socks_help_secondary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSocksHelp.value = false }) {
                    Text(stringResource(R.string.btn_close))
                }
            }
        )
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

            override suspend fun onPostFling(
                consumed: androidx.compose.ui.unit.Velocity, 
                available: androidx.compose.ui.unit.Velocity
            ): androidx.compose.ui.unit.Velocity {
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
