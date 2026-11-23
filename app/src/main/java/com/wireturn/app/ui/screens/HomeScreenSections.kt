@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui.screens

import android.content.ClipData
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.wireturn.app.CoreServiceState
import com.wireturn.app.R
import com.wireturn.app.XrayServiceState
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.Profile
import com.wireturn.app.data.XraySettings
import com.wireturn.app.kernel.KernelRegistry
import com.wireturn.app.ui.AppExclusionTooltip
import com.wireturn.app.ui.CompactItem
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.ItemPosition
import com.wireturn.app.ui.LabelGroup
import com.wireturn.app.ui.RowLabel
import com.wireturn.app.ui.SectionGroup
import com.wireturn.app.ui.SectionItem
import com.wireturn.app.ui.StandardLeadingIcon
import com.wireturn.app.ui.SubscriptionUserAgentDialog
import com.wireturn.app.ui.SupportingText
import com.wireturn.app.ui.SwitchRow
import com.wireturn.app.ui.VerticalAnimatedText
import com.wireturn.app.ui.showExclusiveToast
import com.wireturn.app.ui.theme.ContentAlpha
import com.wireturn.app.viewmodel.MainViewModel
import com.wireturn.app.viewmodel.VpnState
import com.wireturn.app.viewmodel.XrayState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

// Sections of HomeScreen, top to bottom. Each takes what it shows and reports what the user does;
// what that does to the rest of the screen (launchers, warnings, the profiles sheet) stays there.

/** What the app needs to keep running in the background: notifications, no battery optimization. */
@Composable
internal fun ColumnScope.PermissionsBanner(
    visible: Boolean,
    needsNotificationPermission: Boolean,
    needsBatteryExemption: Boolean,
    onRequestNotifications: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AnimatedVisibility(
        visible = visible,
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
                            if (needsNotificationPermission) {
                                Button(
                                    onClick = {
                                        HapticUtil.perform(
                                            context,
                                            HapticUtil.Pattern.CLICK
                                        )
                                        onRequestNotifications()
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError
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
                            if (needsNotificationPermission && needsBatteryExemption) {
                                Spacer(Modifier.width(8.dp))
                            }
                            if (needsBatteryExemption) {
                                Button(
                                    onClick = {
                                        HapticUtil.perform(
                                            context,
                                            HapticUtil.Pattern.CLICK
                                        )
                                        onRequestBatteryExemption()
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError
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
                                onDismiss()
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
}

/**
 * Traffic and ping while the tunnel is up. Works off Xray when it's running, or off VPN mode's own
 * hev-socks5-tunnel counters when VPN is up without Xray (e.g. straight OLCRTC/WEBDAV) - see
 * MainViewModel. [showPing]: the ping is measured through the local SOCKS5, which kernel TUN mode
 * has none of.
 */
@Composable
internal fun ColumnScope.TunnelStats(
    visible: Boolean,
    transfer: MainViewModel.TransferResult?,
    showPing: Boolean,
    ping: MainViewModel.PingResult?,
    lastSuccessPing: MainViewModel.PingResult.Success?,
    onPingClick: () -> Unit
) {
    val context = LocalContext.current
    AnimatedVisibility(
        visible = visible,
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
                        // Download
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.arrow_downward_24px),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ContentAlpha.secondary),
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
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ContentAlpha.secondary),
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
                if (showPing) CompactItem(
                    modifier = Modifier.fillMaxHeight(),
                    onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        onPingClick()
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
                            when (ping) {
                                is MainViewModel.PingResult.Loading -> {
                                    // Only while measuring: an infinite transition ticks on
                                    // every frame for as long as it's composed, so outside
                                    // this branch it woke the main thread ~60 times a second
                                    // on an idle home screen. The alpha is applied in the
                                    // draw phase, never read during composition.
                                    val pulseAlpha by rememberInfiniteTransition(label = "ping_pulse").animateFloat(
                                        initialValue = 0.3f,
                                        targetValue = 0.9f,
                                        animationSpec = infiniteRepeatable(
                                            animation = tween(1000, easing = LinearEasing),
                                            repeatMode = RepeatMode.Reverse
                                        ),
                                        label = "pulse_alpha"
                                    )
                                    if (lastSuccessPing != null) {
                                        VerticalAnimatedText(
                                            text = stringResource(
                                                R.string.ping_ms,
                                                lastSuccessPing.ms
                                            ),
                                            modifier = Modifier.graphicsLayer {
                                                alpha = pulseAlpha
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (lastSuccessPing.ms < 1100) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
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
                                            ping.ms
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (ping.ms < 1100) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
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
}

/**
 * wireturn:// / wt:// deep link import. MainActivity forwards ACTION_VIEW links here via
 * DeepLinkBus instead of importing directly, since the link may have been opened from outside the
 * app with no prior user action inside WireTurn. The confirmation dialog stands alone (nothing else
 * competes for the window) - the profile list itself only opens *after* a successful add
 * ([onImported], with what to scroll to and briefly highlight), so opening it shows a result
 * rather than an empty backdrop behind the confirmation prompt.
 */
@Composable
internal fun DeepLinkImportDialogs(
    viewModel: MainViewModel,
    onImported: (scrollTargetId: String?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingDeepLink by remember { mutableStateOf<com.wireturn.app.ui.DeepLinkRequest?>(null) }
    var isImportingDeepLink by remember { mutableStateOf(false) }
    var deepLinkImportJob by remember { mutableStateOf<Job?>(null) }

    val deepLinkPreview = remember(pendingDeepLink) {
        pendingDeepLink?.let { com.wireturn.app.domain.previewDeepLink(it.link) }
    }

    LaunchedEffect(Unit) {
        com.wireturn.app.ui.DeepLinkBus.pendingLink.collect { request ->
            pendingDeepLink = request
        }
    }

    val importProfilesSuccessMsg = stringResource(R.string.import_profiles_success)
    val importSubscriptionSuccessMsg = stringResource(R.string.import_subscription_success)
    val importErrorMsg = stringResource(R.string.import_error)
    val importTlsErrorMsg = stringResource(R.string.import_error_tls)

    fun dismissDeepLink() {
        deepLinkImportJob?.cancel()
        deepLinkImportJob = null
        isImportingDeepLink = false
        pendingDeepLink = null
    }

    var pendingSubscriptionUrl by remember { mutableStateOf<String?>(null) }
    // The deep-linked subscription waiting for its User-Agent (after "Add"), and the one chosen.
    var deepLinkUserAgentUrl by remember { mutableStateOf<String?>(null) }
    var deepLinkUserAgent by remember { mutableStateOf<String?>(null) }
    val deepLinkLocalNetworkPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val url = pendingSubscriptionUrl
        pendingSubscriptionUrl = null
        if (isGranted && url != null) {
            isImportingDeepLink = true
            deepLinkImportJob = scope.launch {
                try {
                    val status = viewModel.smartImport(
                        url,
                        allowInsecureHttp = true,
                        userAgent = deepLinkUserAgent
                    )
                    if (status is com.wireturn.app.domain.ImportStatus.Success) {
                        context.showExclusiveToast(importSubscriptionSuccessMsg)
                        onImported(status.id)
                    } else {
                        context.showExclusiveToast(
                            if (status is com.wireturn.app.domain.ImportStatus.TlsError) importTlsErrorMsg else importErrorMsg
                        )
                    }
                } finally {
                    isImportingDeepLink = false
                    deepLinkImportJob = null
                    pendingDeepLink = null
                }
            }
        } else if (url != null) {
            pendingDeepLink = null
        }
    }

    fun confirmDeepLinkSubscription(url: String, userAgent: String?) {
        deepLinkUserAgent = userAgent
        val needsLocalNetworkPermission = Build.VERSION.SDK_INT >= 37 &&
                com.wireturn.app.domain.isLocalNetworkHost(url) &&
                ContextCompat.checkSelfPermission(
                    context,
                    "android.permission.ACCESS_LOCAL_NETWORK"
                ) != PackageManager.PERMISSION_GRANTED

        if (needsLocalNetworkPermission) {
            pendingSubscriptionUrl = url
            deepLinkLocalNetworkPermissionLauncher.launch("android.permission.ACCESS_LOCAL_NETWORK")
            return
        }

        isImportingDeepLink = true
        deepLinkImportJob = scope.launch {
            try {
                // The dialog's "Add" is also the go-ahead for a cleartext http:// link.
                val status = viewModel.smartImport(
                    url,
                    allowInsecureHttp = true,
                    userAgent = userAgent
                )
                if (status is com.wireturn.app.domain.ImportStatus.Success) {
                    context.showExclusiveToast(importSubscriptionSuccessMsg)
                    onImported(status.id)
                } else {
                    context.showExclusiveToast(
                        if (status is com.wireturn.app.domain.ImportStatus.TlsError) importTlsErrorMsg else importErrorMsg
                    )
                }
            } finally {
                isImportingDeepLink = false
                deepLinkImportJob = null
                pendingDeepLink = null
            }
        }
    }

    when (deepLinkPreview) {
        is com.wireturn.app.domain.DeepLinkPreview.Profiles -> {
            val referrerHost = pendingDeepLink?.referrerHost
            val countPhrase = androidx.compose.ui.res.pluralStringResource(
                R.plurals.deep_link_import_profiles_count,
                deepLinkPreview.count,
                deepLinkPreview.count
            )
            val title = if (referrerHost != null) {
                stringResource(
                    R.string.deep_link_import_profiles_title_with_site,
                    countPhrase,
                    referrerHost
                )
            } else {
                stringResource(R.string.deep_link_import_profiles_title_no_site, countPhrase)
            }
            val defaultName = stringResource(R.string.profile_default_name)
            AlertDialog(
                onDismissRequest = { dismissDeepLink() },
                title = { Text(title) },
                text = {
                    Text(
                        stringResource(
                            R.string.deep_link_import_profiles_desc,
                            deepLinkPreview.names.joinToString("\n") { "• ${it.ifBlank { defaultName }}" }
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            isImportingDeepLink = true
                            deepLinkImportJob = scope.launch {
                                try {
                                    val (result, imported) = viewModel.importProfilesWithResult(
                                        listOf(null to deepLinkPreview.json)
                                    )
                                    if (result.total > 0) {
                                        context.showExclusiveToast(importProfilesSuccessMsg)
                                        onImported(imported.firstOrNull()?.id)
                                    } else {
                                        context.showExclusiveToast(importErrorMsg)
                                    }
                                } finally {
                                    dismissDeepLink()
                                }
                            }
                        },
                        enabled = !isImportingDeepLink
                    ) {
                        if (isImportingDeepLink) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.btn_add))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { dismissDeepLink() }) { Text(stringResource(R.string.cancel)) }
                }
            )
        }

        is com.wireturn.app.domain.DeepLinkPreview.SubscriptionLink -> {
            val userAgentUrl = deepLinkUserAgentUrl
            if (userAgentUrl != null) {
                // After "Add" on a subscription not added yet: which User-Agent to fetch it with.
                SubscriptionUserAgentDialog(
                    current = null,
                    description = stringResource(R.string.subscription_ua_add_desc),
                    onSelect = { userAgent ->
                        deepLinkUserAgentUrl = null
                        confirmDeepLinkSubscription(userAgentUrl, userAgent)
                    },
                    onDismiss = {
                        deepLinkUserAgentUrl = null
                        dismissDeepLink()
                    }
                )
            } else {
                val host = remember(deepLinkPreview.url) {
                    try {
                        deepLinkPreview.url.toUri().host
                    } catch (_: Exception) {
                        null
                    } ?: deepLinkPreview.url
                }
                // Confirming this dialog is also the go-ahead for a cleartext http://
                // subscription (ImportStatus.InsecureHttp), so its warning goes right here.
                val insecure = remember(deepLinkPreview.url) {
                    deepLinkPreview.url.startsWith("http://", ignoreCase = true) &&
                            !com.wireturn.app.domain.isLocalNetworkHost(deepLinkPreview.url)
                }
                AlertDialog(
                    onDismissRequest = { dismissDeepLink() },
                    title = {
                        Text(
                            stringResource(
                                R.string.deep_link_import_subscription_title,
                                host
                            )
                        )
                    },
                    text = {
                        Text(
                            if (insecure) {
                                stringResource(R.string.deep_link_import_subscription_desc) + "\n\n" +
                                        stringResource(R.string.import_insecure_http_note)
                            } else {
                                stringResource(R.string.deep_link_import_subscription_desc)
                            }
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                if (viewModel.isNewSubscriptionLink(deepLinkPreview.url)) {
                                    deepLinkUserAgentUrl = deepLinkPreview.url
                                } else {
                                    confirmDeepLinkSubscription(deepLinkPreview.url, null)
                                }
                            },
                            enabled = !isImportingDeepLink
                        ) {
                            if (isImportingDeepLink) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(stringResource(R.string.btn_add))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { dismissDeepLink() }) { Text(stringResource(R.string.cancel)) }
                    }
                )
            }
        }

        com.wireturn.app.domain.DeepLinkPreview.Invalid -> {
            AlertDialog(
                onDismissRequest = { pendingDeepLink = null },
                title = { Text(stringResource(R.string.deep_link_import_invalid_title)) },
                text = { Text(stringResource(R.string.deep_link_import_invalid)) },
                confirmButton = {
                    TextButton(onClick = {
                        pendingDeepLink = null
                    }) { Text(stringResource(R.string.btn_close)) }
                }
            )
        }

        null -> {}
    }
}

/**
 * The Xray and VPN mode switches. [isXrayProfile]: the profile is Xray itself (XrayKernel) - there's
 * no overlay to set up or switch, only VPN mode. [vpnNeedsXray]: VPN mode is on but has nothing to
 * relay to without Xray.
 */
@Composable
internal fun XrayVpnSection(
    isXrayProfile: Boolean,
    profilesExist: Boolean,
    configValid: Boolean,
    xrayProtocol: String,
    xrayEnabled: Boolean,
    xrayState: XrayState,
    currentProfile: Profile?,
    vpnEnabled: Boolean,
    vpnNeedsXray: Boolean,
    vpnServiceState: VpnState,
    isVpnModified: Boolean,
    vpnFilteringEnabled: Boolean,
    appsExclusionHintShown: Boolean,
    onXrayClick: () -> Unit,
    onXrayCheckedChange: (Boolean) -> Unit,
    onVpnCheckedChange: (Boolean) -> Unit,
    onAppsExclusionHintShown: () -> Unit,
    onAppsExclusionsClick: () -> Unit
) {
    val context = LocalContext.current
    SectionGroup {
        if (!isXrayProfile) SectionItem(
            position = ItemPosition.Top,
            onClick = onXrayClick
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                SwitchRow(
                    labelPrefix = stringResource(R.string.xray_title),
                    label = if (configValid && profilesExist) xrayProtocol else "",
                    checked = xrayEnabled,
                    onCheckedChange = onXrayCheckedChange,
                    isSplit = true,
                    labelMarquee = true,
                    supportingText = if (!profilesExist) null else if (!configValid) stringResource(
                        R.string.xray_config_invalid
                    ) else {
                        when (xrayState) {
                            XrayState.Starting -> stringResource(R.string.starting)
                            XrayState.Connecting -> stringResource(R.string.connecting)
                            XrayState.Running -> stringResource(R.string.running)
                            XrayState.DirectRoute -> stringResource(R.string.direct_route_active)
                            else -> stringResource(R.string.idle)
                        }
                    },
                    supportingSingleLine = true,
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

                // Xray-setting tags (protocol, dual-route, chain) as a third line under
                // the status text, lined up under the label (52dp = large leading icon's
                // 40dp box + 12dp trailing padding) - kernel tags stay on the profile
                // block instead, see ProfilesBlock.
                if (currentProfile != null) {
                    val xrayTags = profileSummaryParts(currentProfile).xrayTags
                    if (xrayTags.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        ProfileTagChipRow(
                            tags = xrayTags,
                            modifier = Modifier.padding(start = 52.dp)
                        )
                    }
                }
            }
        }

        SectionItem(
            position = if (isXrayProfile) ItemPosition.Single else ItemPosition.Bottom,
            onClick = { onVpnCheckedChange(!vpnEnabled) }
        ) {
            SwitchRow(
                label = stringResource(R.string.vpn_mode),
                checked = vpnEnabled,
                onCheckedChange = onVpnCheckedChange,
                isModified = isVpnModified,
                supportingText = when {
                    vpnNeedsXray -> stringResource(R.string.vpn_status_xray_needed)
                    vpnServiceState == VpnState.Starting -> stringResource(R.string.starting)
                    vpnServiceState == VpnState.Running -> stringResource(R.string.running)
                    vpnServiceState is VpnState.Error -> vpnServiceState.message
                    else -> stringResource(R.string.idle)
                },
                supportingSingleLine = true,
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
                        onHintShown = onAppsExclusionHintShown
                    ) {
                        FilledTonalIconButton(
                            onClick = {
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                if (!appsExclusionHintShown) {
                                    onAppsExclusionHintShown()
                                }
                                onAppsExclusionsClick()
                            }
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.route_24px),
                                contentDescription = stringResource(R.string.vpn_apps_exceptions),
                                modifier = Modifier.size(24.dp),
                                tint = if (vpnFilteringEnabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        }
    }
}

/** The local proxy's addresses as shown and as copied (with credentials), and whether a change awaits a restart. */
internal class ProxyAddresses(
    val displaySocks: String,
    val copySocks: String,
    val displayHttp: String,
    val copyHttp: String,
    val socksModified: Boolean,
    val httpModified: Boolean
)

private fun formatProxyAddr(
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

/**
 * Where the local proxy listens: Xray's SOCKS5/HTTP when it's on, else a SOCKS5-native kernel's own
 * SOCKS5 (no HTTP), else Xray's as configured (shown dimmed - nothing listens there).
 */
internal fun proxyAddresses(
    xrayOn: Boolean,
    isSocks5Native: Boolean,
    activeXraySettings: XraySettings,
    activeConfig: ClientConfig,
    xraySession: XrayServiceState.RunningSession?,
    proxySession: CoreServiceState.RunningSession?
): ProxyAddresses {
    val displaySocksAddr = when {
        xrayOn -> activeXraySettings.socksBindAddress
        isSocks5Native -> activeConfig.socksAddr
        else -> activeXraySettings.socksBindAddress
    }

    val copySocksAddr = when {
        xrayOn -> formatProxyAddr(
            activeXraySettings.socksBindAddress,
            activeXraySettings.proxyUser,
            activeXraySettings.proxyPass,
            activeXraySettings.isProxyAuthEnabled
        )

        isSocks5Native -> formatProxyAddr(
            activeConfig.socksAddr,
            activeConfig.socksUser,
            activeConfig.socksPass,
            // Some kernels' embedded SOCKS5 server has no auth support upstream (see
            // Kernel.socks5SupportsAuth / XrayService's own guard) - never show credentials
            // that would make an external client attempt an auth handshake it doesn't understand.
            activeConfig.isSocksAuthEnabled && KernelRegistry.get(activeConfig.kernelVariant).socks5SupportsAuth
        )

        else -> formatProxyAddr(
            activeXraySettings.socksBindAddress,
            activeXraySettings.proxyUser,
            activeXraySettings.proxyPass,
            activeXraySettings.isProxyAuthEnabled
        )
    }

    val displayHttpAddr = when {
        xrayOn -> activeXraySettings.httpBindAddress
        isSocks5Native -> ""
        else -> activeXraySettings.httpBindAddress
    }

    val copyHttpAddr = when {
        xrayOn -> formatProxyAddr(
            activeXraySettings.httpBindAddress,
            activeXraySettings.proxyUser,
            activeXraySettings.proxyPass,
            activeXraySettings.isProxyAuthEnabled
        )

        isSocks5Native -> ""
        else -> formatProxyAddr(
            activeXraySettings.httpBindAddress,
            activeXraySettings.proxyUser,
            activeXraySettings.proxyPass,
            activeXraySettings.isProxyAuthEnabled
        )
    }

    val isSocksModified = when {
        xrayOn -> xraySession != null && activeXraySettings.socksBindAddress != xraySession.settings.socksBindAddress
        isSocks5Native -> proxySession != null && activeConfig.socksAddr != proxySession.clientConfig.socksAddr
        else -> xraySession != null && activeXraySettings.socksBindAddress != xraySession.settings.socksBindAddress
    }

    val isHttpModified = when {
        xrayOn -> xraySession != null && activeXraySettings.httpBindAddress != xraySession.settings.httpBindAddress
        else -> false
    }

    return ProxyAddresses(
        displaySocksAddr,
        copySocksAddr,
        displayHttpAddr,
        copyHttpAddr,
        isSocksModified,
        isHttpModified
    )
}

/**
 * The local proxy's addresses, tap to copy. [socksEnabled]/[httpEnabled]: something listens there
 * (Xray, or a SOCKS5-native kernel for SOCKS5) - the group is dimmed otherwise.
 */
@Composable
internal fun ProxyAddressesSection(
    addresses: ProxyAddresses,
    socksEnabled: Boolean,
    httpEnabled: Boolean,
    privacyMode: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val displaySocksAddr = addresses.displaySocks
    val copySocksAddr = addresses.copySocks
    val displayHttpAddr = addresses.displayHttp
    val copyHttpAddr = addresses.copyHttp
    val isHttpModified = addresses.httpModified

    SectionGroup(
        modifier = Modifier.graphicsLayer {
            alpha = if (socksEnabled) 1f else 0.38f
        }
    ) {
        val clipboard = LocalClipboard.current
        val socks5Label = stringResource(R.string.clipboard_label_socks5)
        val httpLabel = stringResource(R.string.clipboard_label_http)

        // A proxy bound to all interfaces has no single address to hand out - copying
        // 0.0.0.0 is useless to whoever pastes it, so let the user pick which of this
        // device's addresses the other side will actually connect through.
        var pendingWildcardCopy by remember { mutableStateOf<WildcardCopy?>(null) }
        pendingWildcardCopy?.let { pending ->
            LocalAddressPickerDialog(
                port = pending.address.substringAfterLast(':'),
                onPick = { ip ->
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipData.newPlainText(pending.clipLabel, pending.withHost(ip))
                                .toClipEntry()
                        )
                        pending.onCopied()
                    }
                },
                onDismiss = { pendingWildcardCopy = null }
            )
        }

        var socksCopied by remember { mutableStateOf(false) }
        LaunchedEffect(socksCopied) {
            if (socksCopied) {
                delay(1_500.milliseconds)
                socksCopied = false
            }
        }

        val showHttpBlock = displayHttpAddr.isNotBlank() || isHttpModified

        SectionItem(
            position = ItemPosition.Top,
            enabled = socksEnabled,
            onClick = {
                if (privacyMode) return@SectionItem
                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                if (isWildcardAddress(displaySocksAddr)) {
                    pendingWildcardCopy =
                        WildcardCopy(socks5Label, copySocksAddr) { socksCopied = true }
                    return@SectionItem
                }
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
                isModified = addresses.socksModified,
                isCopied = socksCopied,
                privacyMode = privacyMode,
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.lan_24px),
                        contentDescription = null,
                        tint = if (socksEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(
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
                    delay(1_500.milliseconds)
                    httpCopied = false
                }
            }

            SectionItem(
                enabled = httpEnabled,
                onClick = {
                    if (privacyMode) return@SectionItem
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    if (isWildcardAddress(displayHttpAddr)) {
                        pendingWildcardCopy =
                            WildcardCopy(httpLabel, copyHttpAddr) { httpCopied = true }
                        return@SectionItem
                    }
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
                            tint = if (httpEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = 0.38f
                            )
                        )
                    }
                )
            }
        }
    }
}

/** The way to the connection settings screen. */
@Composable
internal fun ConnectionSettingsItem(onClick: () -> Unit) {
    val context = LocalContext.current
    SectionItem(
        position = ItemPosition.Bottom,
        onClick = {
            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
            onClick()
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
            LabelGroup(
                label = stringResource(R.string.connection_settings_title),
                supportingText = stringResource(R.string.connection_settings_desc),
                modifier = Modifier.weight(1f)
            )
            Icon(
                painter = painterResource(R.drawable.arrow_forward_ios_24px),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
