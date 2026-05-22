@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.R
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.XraySettings
import com.wireturn.app.ui.ConfigTopAppBar
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.SettingsGroup
import com.wireturn.app.ui.SettingsGroupItem
import com.wireturn.app.ui.SwitchRow
import com.wireturn.app.ui.TextFieldRow
import com.wireturn.app.ui.ValidatorUtils
import com.wireturn.app.ui.redact
import com.wireturn.app.viewmodel.MainViewModel

@Composable
fun ConnectionSettingsScreen(
    viewModel: MainViewModel,
    initialClientConfig: ClientConfig,
    initialXraySettings: XraySettings,
    onBack: () -> Unit,
    onSave: (ClientConfig, XraySettings) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val privacyMode by viewModel.privacyMode.collectAsStateWithLifecycle()

    // Turnable states
    var listenAddr by remember(initialClientConfig.listenAddr) { mutableStateOf(initialClientConfig.listenAddr) }
    
    // olcRTC states
    var olSocks by remember(initialClientConfig.socksAddr) { mutableStateOf(initialClientConfig.socksAddr) }
    var olAuth by remember(initialClientConfig.isSocksAuthEnabled) { mutableStateOf(initialClientConfig.isSocksAuthEnabled) }
    var olUser by remember(initialClientConfig.socksUser) { mutableStateOf(initialClientConfig.socksUser) }
    var olPass by remember(initialClientConfig.socksPass) { mutableStateOf(initialClientConfig.socksPass) }
    var olPassVisible by rememberSaveable { mutableStateOf(false) }
    
    // Xray states
    var xraySocks by remember(initialXraySettings.socksBindAddress) { mutableStateOf(initialXraySettings.socksBindAddress) }
    var xrayHttp by remember(initialXraySettings.httpBindAddress) { mutableStateOf(initialXraySettings.httpBindAddress) }
    var xrayAuth by remember(initialXraySettings.isProxyAuthEnabled) { mutableStateOf(initialXraySettings.isProxyAuthEnabled) }
    var xrayUser by remember(initialXraySettings.proxyUser) { mutableStateOf(initialXraySettings.proxyUser) }
    var xrayPass by remember(initialXraySettings.proxyPass) { mutableStateOf(initialXraySettings.proxyPass) }
    var xrayPassVisible by rememberSaveable { mutableStateOf(false) }

    val currentClientConfig = remember(listenAddr, olSocks, olAuth, olUser, olPass) {
        initialClientConfig.copy(
            listenAddr = listenAddr,
            socksAddr = olSocks,
            isSocksAuthEnabled = olAuth,
            socksUser = olUser,
            socksPass = olPass
        )
    }
    
    val currentXraySettings = remember(xraySocks, xrayHttp, xrayAuth, xrayUser, xrayPass) {
        initialXraySettings.copy(
            socksBindAddress = xraySocks,
            httpBindAddress = xrayHttp,
            isProxyAuthEnabled = xrayAuth,
            proxyUser = xrayUser,
            proxyPass = xrayPass
        )
    }

    val isModified by remember(currentClientConfig, currentXraySettings) {
        derivedStateOf {
            currentClientConfig != initialClientConfig ||
            currentXraySettings != initialXraySettings
        }
    }

    val showExitDialog = remember { mutableStateOf(false) }

    val handleBack = {
        if (isModified) {
            showExitDialog.value = true
        } else {
            onBack()
        }
    }

    BackHandler(enabled = isModified, onBack = handleBack)

    val showListenHelp = remember { mutableStateOf(false) }
    val showSocksHelp = remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        state = topAppBarState
    )

    val isDark = com.wireturn.app.ui.theme.LocalIsDark.current
    val blockContainerColor = if (isDark) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        MaterialTheme.colorScheme.surface
    }

    if (showExitDialog.value) {
        AlertDialog(
            onDismissRequest = { showExitDialog.value = false },
            title = { Text(stringResource(R.string.unsaved_changes_title)) },
            text = { Text(stringResource(R.string.unsaved_changes_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    showExitDialog.value = false
                    onSave(currentClientConfig, currentXraySettings)
                }) {
                    Text(stringResource(R.string.btn_save))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showExitDialog.value = false
                    onBack()
                }) {
                    Text(stringResource(R.string.btn_discard))
                }
            }
        )
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            ConfigTopAppBar(
                title = stringResource(R.string.connection_settings_title),
                scrollBehavior = scrollBehavior,
                onBack = handleBack
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = isModified,
                enter = scaleIn(
                    initialScale = 0.8f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) + fadeIn(animationSpec = tween(200)),
                exit = scaleOut(
                    targetScale = 0.8f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ) + fadeOut(animationSpec = tween(150))
            ) {
                ExtendedFloatingActionButton(
                    modifier = Modifier.navigationBarsPadding(),
                    onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        onSave(currentClientConfig, currentXraySettings)
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    icon = { Icon(painterResource(R.drawable.save_24px), null) },
                    text = { Text(stringResource(R.string.btn_save)) }
                )
            }
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
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
                .padding(top = 18.dp)
                .navigationBarsPadding()
                .padding(bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(19.dp)
        ) {
            // Turnable
            SettingsGroup(title = stringResource(R.string.settings_group_turnable)) {
                SettingsGroupItem(isTop = true, isBottom = true, containerColor = blockContainerColor) {
                    TextFieldRow(
                        label = stringResource(R.string.local_listen_address),
                        value = listenAddr.redact(privacyMode),
                        onValueChange = { if (!privacyMode) listenAddr = it },
                        placeholder = ClientConfig.DEFAULT_LISTEN_ADDR,
                        isError = !ValidatorUtils.isValidHostPort(listenAddr),
                        readOnly = privacyMode,
                        isModified = listenAddr != initialClientConfig.listenAddr,
                        onHelpClick = { showListenHelp.value = true },
                        privacyMode = privacyMode
                    )
                }
            }

            // olcRTC
            SettingsGroup(title = stringResource(R.string.settings_group_olcrtc)) {
                SettingsGroupItem(isTop = true, isBottom = false, containerColor = blockContainerColor) {
                    TextFieldRow(
                        label = stringResource(R.string.socks5),
                        value = olSocks.redact(privacyMode),
                        onValueChange = { if (!privacyMode) olSocks = it },
                        placeholder = ClientConfig.DEFAULT_SOCKS_ADDR,
                        isError = olSocks.isNotEmpty() && !ValidatorUtils.isValidHostPort(olSocks),
                        readOnly = privacyMode,
                        isModified = olSocks != initialClientConfig.socksAddr,
                        onHelpClick = { showSocksHelp.value = true },
                        privacyMode = privacyMode
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
                        isModified = olAuth != initialClientConfig.isSocksAuthEnabled,
                    )
                }
                
                AnimatedVisibility(visible = olAuth) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                            TextFieldRow(
                                label = stringResource(R.string.xray_proxy_user),
                                value = olUser.redact(privacyMode),
                                onValueChange = { if (!privacyMode) olUser = it },
                                placeholder = stringResource(R.string.proxy_user_placeholder),
                                isError = !ValidatorUtils.isValidProxyUser(olUser),
                                readOnly = privacyMode,
                                isModified = olUser != initialClientConfig.socksUser,
                                privacyMode = privacyMode
                            )
                        }
                        SettingsGroupItem(isTop = false, isBottom = true, containerColor = blockContainerColor) {
                            TextFieldRow(
                                label = stringResource(R.string.xray_proxy_pass),
                                value = olPass.redact(privacyMode),
                                onValueChange = { if (!privacyMode) olPass = it },
                                placeholder = stringResource(R.string.proxy_pass_placeholder),
                                isError = !ValidatorUtils.isValidProxyPass(olPass),
                                readOnly = privacyMode,
                                isModified = olPass != initialClientConfig.socksPass,
                                privacyMode = privacyMode,
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
                SettingsGroupItem(isTop = true, isBottom = false, containerColor = blockContainerColor) {
                    TextFieldRow(
                        label = stringResource(R.string.socks5),
                        value = xraySocks.redact(privacyMode),
                        onValueChange = { if (!privacyMode) xraySocks = it },
                        placeholder = XraySettings.DEFAULT_SOCKS_BIND_ADDRESS,
                        isError = !ValidatorUtils.isValidHostPort(xraySocks),
                        readOnly = privacyMode,
                        isModified = xraySocks != initialXraySettings.socksBindAddress,
                        privacyMode = privacyMode
                    )
                }
                
                SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                    TextFieldRow(
                        label = stringResource(R.string.xray_http),
                        value = xrayHttp.redact(privacyMode),
                        onValueChange = { if (!privacyMode) xrayHttp = it },
                        placeholder = XraySettings.DEFAULT_HTTP_BIND_ADDRESS,
                        isError = xrayHttp.isNotEmpty() && !ValidatorUtils.isValidHostPort(xrayHttp),
                        readOnly = privacyMode,
                        isModified = xrayHttp != initialXraySettings.httpBindAddress,
                        privacyMode = privacyMode
                    )
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
                        isModified = xrayAuth != initialXraySettings.isProxyAuthEnabled,
                    )
                }
                
                AnimatedVisibility(visible = xrayAuth) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                            TextFieldRow(
                                label = stringResource(R.string.xray_proxy_user),
                                value = xrayUser.redact(privacyMode),
                                onValueChange = { if (!privacyMode) xrayUser = it },
                                placeholder = stringResource(R.string.proxy_user_placeholder),
                                isError = !ValidatorUtils.isValidProxyUser(xrayUser),
                                readOnly = privacyMode,
                                isModified = xrayUser != initialXraySettings.proxyUser,
                                privacyMode = privacyMode
                            )
                        }
                        SettingsGroupItem(isTop = false, isBottom = true, containerColor = blockContainerColor) {
                            TextFieldRow(
                                label = stringResource(R.string.xray_proxy_pass),
                                value = xrayPass.redact(privacyMode),
                                onValueChange = { if (!privacyMode) xrayPass = it },
                                placeholder = stringResource(R.string.proxy_pass_placeholder),
                                isError = !ValidatorUtils.isValidProxyPass(xrayPass),
                                readOnly = privacyMode,
                                isModified = xrayPass != initialXraySettings.proxyPass,
                                privacyMode = privacyMode,
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
        }
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
}
