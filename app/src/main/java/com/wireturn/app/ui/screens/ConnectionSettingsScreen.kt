@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import com.wireturn.app.R
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.kernel.KernelRegistry
import com.wireturn.app.ui.AppTopAppBar
import com.wireturn.app.ui.ExpandableSection
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.ItemPosition
import com.wireturn.app.ui.SectionGroup
import com.wireturn.app.ui.SectionItem
import com.wireturn.app.ui.SwitchRow
import com.wireturn.app.ui.TextFieldRow
import com.wireturn.app.ui.ValidatorUtils
import com.wireturn.app.ui.noFlingExpandConnection
import com.wireturn.app.ui.redact

@Composable
fun ConnectionSettingsScreen(
    initialClientConfig: ClientConfig,
    privacyMode: Boolean,
    onBack: () -> Unit,
    onSave: (ClientConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Turnable states
    var listenAddr by remember { mutableStateOf(initialClientConfig.listenAddr) }
    var goDnsGo by remember { mutableStateOf(initialClientConfig.goDnsGo) }
    var useCustomCerts by remember { mutableStateOf(initialClientConfig.useCustomCerts) }

    // Default for olcRTC profiles that leave their own dns blank - WebDAV always uses its own
    // per-profile dns instead, never this one (see OlcrtcConfig.dns/WebdavConfig.dns).
    var dns by remember { mutableStateOf(initialClientConfig.dns) }

    // SOCKS5-native kernel states (olcRTC, WebDAV, qWDTT)
    var clientSocks by remember { mutableStateOf(initialClientConfig.socksAddr) }
    var clientSocksAuth by remember { mutableStateOf(initialClientConfig.isSocksAuthEnabled) }
    var clientSocksUser by remember { mutableStateOf(initialClientConfig.socksUser) }
    var clientSocksPass by remember { mutableStateOf(initialClientConfig.socksPass) }
    var clientSocksPassVisible by rememberSaveable { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    val currentClientConfig = remember(listenAddr, clientSocks, clientSocksAuth, clientSocksUser, clientSocksPass, dns, goDnsGo, useCustomCerts, initialClientConfig) {
        initialClientConfig.copy(
            listenAddr = listenAddr,
            socksAddr = clientSocks,
            isSocksAuthEnabled = clientSocksAuth,
            socksUser = clientSocksUser,
            socksPass = clientSocksPass,
            dns = dns,
            goDnsGo = goDnsGo,
            useCustomCerts = useCustomCerts
        )
    }
    
    val isModified = remember(currentClientConfig, initialClientConfig) {
        currentClientConfig.listenAddr != initialClientConfig.listenAddr ||
        currentClientConfig.dns != initialClientConfig.dns ||
        currentClientConfig.goDnsGo != initialClientConfig.goDnsGo ||
        currentClientConfig.useCustomCerts != initialClientConfig.useCustomCerts ||
        currentClientConfig.socksAddr != initialClientConfig.socksAddr ||
        currentClientConfig.isSocksAuthEnabled != initialClientConfig.isSocksAuthEnabled ||
        currentClientConfig.socksUser != initialClientConfig.socksUser ||
        currentClientConfig.socksPass != initialClientConfig.socksPass
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

    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        state = topAppBarState,
        flingAnimationSpec = null
    )

    if (showExitDialog.value) {
        AlertDialog(
            onDismissRequest = { showExitDialog.value = false },
            title = { Text(stringResource(R.string.unsaved_changes_title)) },
            text = { Text(stringResource(R.string.unsaved_changes_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    showExitDialog.value = false
                    onSave(currentClientConfig)
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
        modifier = modifier.nestedScroll(scrollBehavior.noFlingExpandConnection()),
        topBar = {
            AppTopAppBar(
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
                ) + fadeIn(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()),
                exit = scaleOut(
                    targetScale = 0.8f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ) + fadeOut(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec())
            ) {
                ExtendedFloatingActionButton(
                    modifier = Modifier.navigationBarsPadding(),
                    onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        onSave(currentClientConfig)
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
            // General
            SectionGroup(title = stringResource(R.string.network_settings_title)) {
                SectionItem(
                    position = ItemPosition.Top,
                    onClick = {
                        goDnsGo = !goDnsGo
                        HapticUtil.perform(
                            context,
                            if (goDnsGo) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                        )
                    }
                ) {
                    SwitchRow(
                        label = stringResource(R.string.go_internal_dns_title),
                        supportingText = stringResource(R.string.go_internal_dns_desc),
                        checked = goDnsGo,
                        onCheckedChange = { goDnsGo = it },
                        isModified = goDnsGo != initialClientConfig.goDnsGo,
                    )
                }

                SectionItem(
                    position = ItemPosition.Bottom,
                    onClick = {
                        useCustomCerts = !useCustomCerts
                        HapticUtil.perform(
                            context,
                            if (useCustomCerts) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                        )
                    }
                ) {
                    SwitchRow(
                        label = stringResource(R.string.custom_certs_title),
                        supportingText = stringResource(R.string.custom_certs_desc),
                        checked = useCustomCerts,
                        onCheckedChange = { useCustomCerts = it },
                        isModified = useCustomCerts != initialClientConfig.useCustomCerts,
                    )
                }
            }

            // Local listen port (Turnable / FreeTurn / qWDTT - see the `listenAddr` comment above)
            SectionGroup(title = stringResource(R.string.settings_group_kernel_params)) {
                SectionItem(position = ItemPosition.Single) {
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

            // SOCKS5-native kernels (olcRTC, WebDAV, qWDTT, OpenFlux - see KernelVariant.isSocks5Native)
            val kernelSupportsSocksAuth = initialClientConfig.kernelVariant.socks5SupportsAuth
            val clientSocksIsPublic = clientSocks.isNotEmpty() &&
                    ValidatorUtils.isValidHostPort(clientSocks) && !ValidatorUtils.isLoopbackHostPort(clientSocks)
            // Mirrors ClientConfig.socksNativeValidationError: OpenFlux can't authenticate at all,
            // so a public bind is never allowed for it, regardless of the auth toggle below.
            val clientSocksPublicNoAuthSupport = clientSocksIsPublic && !kernelSupportsSocksAuth
            val clientSocksPublicNeedsAuth = clientSocksIsPublic && kernelSupportsSocksAuth && !clientSocksAuth
            SectionGroup(title = stringResource(R.string.settings_group_kernel_socks5)) {
                SectionItem(position = ItemPosition.Top) {
                    TextFieldRow(
                        label = stringResource(R.string.socks5),
                        value = clientSocks.redact(privacyMode),
                        onValueChange = { if (!privacyMode) clientSocks = it },
                        placeholder = ClientConfig.DEFAULT_SOCKS_ADDR,
                        isError = (clientSocks.isNotEmpty() && !ValidatorUtils.isValidHostPort(clientSocks)) || clientSocksPublicNeedsAuth || clientSocksPublicNoAuthSupport,
                        supportingText = when {
                            clientSocksPublicNoAuthSupport -> stringResource(R.string.error_socks_public_no_auth_support)
                            clientSocksPublicNeedsAuth -> stringResource(R.string.error_socks_public_requires_auth)
                            else -> null
                        },
                        readOnly = privacyMode,
                        isModified = clientSocks != initialClientConfig.socksAddr,
                        onHelpClick = { showSocksHelp.value = true },
                        privacyMode = privacyMode
                    )
                }

                SectionItem {
                    // Blank is valid here (falls back to the OS resolver - see ClientConfig.dns's
                    // own doc comment) - only a non-empty value has to be a real host:port, same
                    // rule and isError-only treatment as clientSocks/listenAddr above. An
                    // unvalidated value used to be able to reach the kernel binary's -dns flag
                    // as-is and hard-crash the tunnel with an opaque Go dial error instead of
                    // failing here with a clear signal.
                    TextFieldRow(
                        label = stringResource(R.string.client_dns_label),
                        value = dns.redact(privacyMode),
                        onValueChange = { if (!privacyMode) dns = it },
                        placeholder = ClientConfig.DEFAULT_DNS,
                        isError = dns.isNotEmpty() && !ValidatorUtils.isValidHostPort(dns),
                        supportingText = stringResource(R.string.client_dns_desc),
                        readOnly = privacyMode,
                        isModified = dns != initialClientConfig.dns,
                        privacyMode = privacyMode
                    )
                }

                SectionItem(
                    position = if (clientSocksAuth) ItemPosition.Middle else ItemPosition.Bottom,
                    onClick = {
                        clientSocksAuth = !clientSocksAuth
                        HapticUtil.perform(
                            context,
                            if (clientSocksAuth) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                        )
                    }
                ) {
                    SwitchRow(
                        label = stringResource(R.string.client_socks_auth),
                        supportingText = if (kernelSupportsSocksAuth) {
                            stringResource(R.string.client_socks_auth_desc)
                        } else {
                            stringResource(
                                R.string.client_socks_auth_not_supported_by_kernel,
                                stringResource(KernelRegistry.get(initialClientConfig.kernelVariant).displayNameRes)
                            )
                        },
                        checked = clientSocksAuth,
                        onCheckedChange = { clientSocksAuth = it },
                        isModified = clientSocksAuth != initialClientConfig.isSocksAuthEnabled,
                    )
                }

                ExpandableSection(visible = clientSocksAuth) {
                    SectionGroup {
                        SectionItem {
                            TextFieldRow(
                                label = stringResource(R.string.client_socks_user),
                                value = clientSocksUser.redact(privacyMode),
                                onValueChange = { if (!privacyMode) clientSocksUser = it },
                                placeholder = stringResource(R.string.proxy_user_placeholder),
                                isError = !ValidatorUtils.isValidProxyUser(clientSocksUser),
                                readOnly = privacyMode,
                                isModified = clientSocksUser != initialClientConfig.socksUser,
                                privacyMode = privacyMode
                            )
                        }
                        SectionItem(position = ItemPosition.Bottom) {
                            TextFieldRow(
                                label = stringResource(R.string.client_socks_pass),
                                value = clientSocksPass.redact(privacyMode),
                                onValueChange = { if (!privacyMode) clientSocksPass = it },
                                placeholder = stringResource(R.string.proxy_pass_placeholder),
                                isError = !ValidatorUtils.isValidProxyPass(clientSocksPass),
                                readOnly = privacyMode,
                                isModified = clientSocksPass != initialClientConfig.socksPass,
                                privacyMode = privacyMode,
                                trailingIcon = {
                                    IconButton(onClick = { clientSocksPassVisible = !clientSocksPassVisible }) {
                                        Icon(
                                            painter = painterResource(
                                                if (clientSocksPassVisible) R.drawable.visibility_24px
                                                else R.drawable.visibility_off_24px
                                            ),
                                            contentDescription = null
                                        )
                                    }
                                },
                                visualTransformation = if (clientSocksPassVisible) {
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
                        text = stringResource(R.string.client_socks_help_text),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.client_socks_help_secondary),
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
