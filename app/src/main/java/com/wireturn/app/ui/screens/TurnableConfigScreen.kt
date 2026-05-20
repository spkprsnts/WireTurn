@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.TopAppBarDefaults
import com.wireturn.app.ui.ConfigTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wireturn.app.R
import com.wireturn.app.data.TurnableConfig
import com.wireturn.app.data.TurnableRoute
import com.wireturn.app.ui.ConfigRowLabel
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.LabeledButtonGroup
import com.wireturn.app.ui.LargeLeadingIcon
import com.wireturn.app.ui.SelectionDialog
import com.wireturn.app.ui.SettingsGroup
import com.wireturn.app.ui.SettingsGroupItem
import com.wireturn.app.ui.StandardLeadingIcon
import com.wireturn.app.ui.SupportingText
import com.wireturn.app.ui.SwitchRow
import com.wireturn.app.ui.TextFieldRow
import com.wireturn.app.ui.configButtonGroupItem
import com.wireturn.app.ui.InlineConfigIndicator
import com.wireturn.app.ui.redact
import kotlin.math.roundToInt

@Composable
fun TurnableConfigScreen(
    isEditMode: Boolean = false,
    initialConfig: TurnableConfig = TurnableConfig(),
    privacyMode: Boolean = false,
    onBack: () -> Unit,
    onSave: (TurnableConfig) -> Unit
) {
    val isPrivacyActive = privacyMode && isEditMode
    var config by remember(initialConfig) { mutableStateOf(initialConfig) }
    val showRoutesDialog = remember { mutableStateOf(false) }
    val showPlatformDialog = remember { mutableStateOf(false) }

    val isModified by remember(config) {
        derivedStateOf { config != initialConfig }
    }

    val showExitDialog = remember { mutableStateOf(false) }

    val handleBack = {
        if (isEditMode && isModified) {
            showExitDialog.value = true
        } else {
            onBack()
        }
    }

    BackHandler(enabled = isEditMode && isModified, onBack = handleBack)
    
    val scrollState = rememberScrollState()
    val isDark = com.wireturn.app.ui.theme.LocalIsDark.current
    val blockContainerColor = if (isDark) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface
    val context = LocalContext.current

    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        state = topAppBarState
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
                    onSave(config)
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
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            ConfigTopAppBar(
                title = stringResource(R.string.kernel_turnable),
                onBack = handleBack,
                scrollBehavior = scrollBehavior,
                actions = {
                    if (isEditMode) {
                        IconButton(onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, config.toUrl())
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.share_24px),
                                contentDescription = stringResource(R.string.share)
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            androidx.compose.material3.MediumFloatingActionButton(
                onClick = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    onSave(config)
                },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(
                    painter = painterResource(
                        if (isEditMode) R.drawable.save_24px 
                        else R.drawable.arrow_forward_ios_24px
                    ),
                    contentDescription = stringResource(if (isEditMode) R.string.btn_save else R.string.btn_next)
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
                .padding(top = 18.dp),
            verticalArrangement = Arrangement.spacedBy(19.dp)
        ) {
            // connection details
            SettingsGroup(title = stringResource(R.string.connection_details)) {
                if (config.routes.isNotEmpty()) {
                    SettingsGroupItem(
                        isTop = true,
                        isBottom = false,
                        containerColor = blockContainerColor,
                        onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            showRoutesDialog.value = true
                        }
                    ) {
                        RoutesBlock(
                            config = config,
                            isModified = isEditMode && config.selectedRouteId != initialConfig.selectedRouteId
                        )
                    }
                }
                SettingsGroupItem(
                    isTop = config.routes.isEmpty(),
                    isBottom = false,
                    containerColor = blockContainerColor
                ) {
                    com.wireturn.app.ui.SliderRow(
                        label = stringResource(R.string.peers_label),
                        value = config.peers.toFloat(),
                        onValueChange = {
                            config = config.copy(peers = it.roundToInt())
                        },
                        valueRange = 1f..32f,
                        steps = 30,
                        supportingText = stringResource(R.string.peers_desc),
                        isModified = isEditMode && config.peers != initialConfig.peers
                    )
                }
                SettingsGroupItem(
                    isTop = false,
                    isBottom = false,
                    containerColor = blockContainerColor
                ) {
                    TextFieldRow(
                        label = stringResource(R.string.username_label),
                        value = config.username.redact(isPrivacyActive),
                        onValueChange = { if (!isPrivacyActive) config = config.copy(username = it) },
                        readOnly = isPrivacyActive,
                        supportingText = stringResource(R.string.username_desc),
                        isModified = isEditMode && config.username != initialConfig.username,
                        privacyMode = isPrivacyActive
                    )
                }
                SettingsGroupItem(
                    isTop = false,
                    isBottom = true,
                    containerColor = blockContainerColor
                ) {
                    TextFieldRow(
                        label = stringResource(R.string.call_id_label),
                        value = config.callId.redact(isPrivacyActive),
                        onValueChange = { if (!isPrivacyActive) config = config.copy(callId = it) },
                        readOnly = isPrivacyActive,
                        supportingText = stringResource(R.string.call_id_desc),
                        isModified = isEditMode && config.callId != initialConfig.callId,
                        privacyMode = isPrivacyActive
                    )
                }
            }

            // server settings
            SettingsGroup(title = stringResource(R.string.server_settings_title)) {
                SettingsGroupItem(
                    isTop = true,
                    isBottom = false,
                    containerColor = blockContainerColor
                ) {
                    TextFieldRow(
                        label = stringResource(R.string.user_uuid_label),
                        value = (config.userUuid ?: "").redact(isPrivacyActive),
                        onValueChange = { if (!isPrivacyActive) config = config.copy(userUuid = it) },
                        readOnly = isPrivacyActive,
                        supportingText = stringResource(R.string.user_uuid_desc),
                        isModified = isEditMode && config.userUuid != initialConfig.userUuid,
                        privacyMode = isPrivacyActive
                    )
                }
                SettingsGroupItem(
                    isTop = false,
                    isBottom = false,
                    containerColor = blockContainerColor,
                    onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        showPlatformDialog.value = true
                    }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LargeLeadingIcon {
                            Icon(
                                painter = painterResource(getPlatformIcon(config.platformId)),
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            ConfigRowLabel(
                                text = stringResource(R.string.olcrtc_carrier_label),
                                isModified = isEditMode && config.platformId != initialConfig.platformId
                            )
                            val currentLabel = config.platformDisplayName
                            Spacer(Modifier.height(2.dp))
                            SupportingText(currentLabel)
                        }
                    }
                }
                SettingsGroupItem(
                    isTop = false,
                    isBottom = false,
                    containerColor = blockContainerColor
                ) {
                    LabeledButtonGroup(
                        label = stringResource(R.string.connection_type_label),
                        supportingText = stringResource(R.string.connection_type_desc),
                        isModified = isEditMode && config.type != initialConfig.type
                    ) {
                        val types = listOf("relay", "direct")
                        types.forEachIndexed { index, t ->
                            configButtonGroupItem(
                                selected = config.type == t,
                                onSelect = { config = config.copy(type = t) },
                                label = t.replaceFirstChar { it.uppercase() },
                                index = index,
                                count = types.size
                            )
                        }
                    }
                }
                SettingsGroupItem(
                    isTop = false,
                    isBottom = false,
                    containerColor = blockContainerColor
                ) {
                    TextFieldRow(
                        label = stringResource(R.string.pub_key_label),
                        value = (config.pubKey ?: "").redact(isPrivacyActive),
                        onValueChange = { if (!isPrivacyActive) config = config.copy(pubKey = it) },
                        readOnly = isPrivacyActive,
                        supportingText = stringResource(R.string.pub_key_desc),
                        isModified = isEditMode && config.pubKey != initialConfig.pubKey,
                        privacyMode = isPrivacyActive
                    )
                }
                SettingsGroupItem(
                    isTop = false,
                    isBottom = false,
                    containerColor = blockContainerColor
                ) {
                    LabeledButtonGroup(
                        label = stringResource(R.string.encryption_label),
                        supportingText = stringResource(R.string.encryption_desc),
                        isModified = isEditMode && config.encryption != initialConfig.encryption
                    ) {
                        val options = listOf("handshake", "full")
                        options.forEachIndexed { index, e ->
                            configButtonGroupItem(
                                selected = config.encryption == e,
                                onSelect = { config = config.copy(encryption = e) },
                                label = e.replaceFirstChar { it.uppercase() },
                                index = index,
                                count = options.size
                            )
                        }
                    }
                }
                SettingsGroupItem(
                    isTop = false,
                    isBottom = false,
                    containerColor = blockContainerColor
                ) {
                    TextFieldRow(
                        label = stringResource(R.string.gateway_label),
                        value = config.gateway.redact(isPrivacyActive),
                        onValueChange = { if (!isPrivacyActive) config = config.copy(gateway = it) },
                        readOnly = isPrivacyActive,
                        supportingText = stringResource(R.string.gateway_desc),
                        isModified = isEditMode && config.gateway != initialConfig.gateway,
                        privacyMode = isPrivacyActive
                    )
                }
                SettingsGroupItem(
                    isTop = false,
                    isBottom = false,
                    containerColor = blockContainerColor
                ) {
                    LabeledButtonGroup(
                        label = stringResource(R.string.proto_label),
                        supportingText = stringResource(R.string.proto_desc),
                        isModified = isEditMode && config.proto != initialConfig.proto
                    ) {
                        val options = listOf("dtls", "srtp", "none")
                        val currentProto = config.proto ?: "none"
                        options.forEachIndexed { index, p ->
                            configButtonGroupItem(
                                selected = currentProto == p,
                                onSelect = { config = config.copy(proto = if (p == "none") null else p) },
                                label = p.uppercase(),
                                index = index,
                                count = options.size
                            )
                        }
                    }
                }
                SettingsGroupItem(
                    isTop = false,
                    isBottom = true,
                    containerColor = blockContainerColor,
                    onClick = {
                        val next = !config.forceTurn
                        HapticUtil.perform(context, if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                        config = config.copy(forceTurn = next)
                    }
                ) {
                    SwitchRow(
                        label = stringResource(R.string.force_turn_label),
                        supportingText = stringResource(R.string.force_turn_desc),
                        checked = config.forceTurn,
                        onCheckedChange = { config = config.copy(forceTurn = it) },
                        isModified = isEditMode && config.forceTurn != initialConfig.forceTurn
                    )
                }
            }

            // Padding to prevent FAB from overlapping content
            Spacer(Modifier.height(80.dp))
        }
    }

    if (showRoutesDialog.value) {
        RoutesDialog(
            config = config,
            onSelect = { routeId ->
                config = config.copy(selectedRouteId = routeId)
                showRoutesDialog.value = false
            },
            onDismiss = { showRoutesDialog.value = false }
        )
    }

    if (showPlatformDialog.value) {
        TurnablePlatformDialog(
            currentPlatform = config.platformId,
            onSelect = { platform ->
                config = config.copy(platformId = platform)
                showPlatformDialog.value = false
            },
            onDismiss = { showPlatformDialog.value = false }
        )
    }
}

private fun getPlatformIcon(platformId: String): Int = when (platformId) {
    "vk.com" -> R.drawable.ic_vk
    else -> R.drawable.call_quality_24px
}

@Composable
fun RouteSummary(
    route: TurnableRoute,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Text(
        text = "${route.socket.uppercase()} • ${route.transport?.uppercase() ?: "none"}",
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        modifier = modifier
    )
}

@Composable
fun RoutesBlock(
    config: TurnableConfig,
    modifier: Modifier = Modifier,
    isModified: Boolean = false
) {
    val selectedRoute = config.routes.find { it.routeId == config.selectedRouteId } ?: config.routes.firstOrNull()
    if (selectedRoute != null) {
        val iconRes = when (selectedRoute.socket.lowercase()) {
            "tcp" -> R.drawable.compare_arrows_24px
            "udp" -> R.drawable.arrow_forward_24px
            else -> R.drawable.route_24px
        }
        Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LargeLeadingIcon {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = selectedRoute.name.ifBlank { selectedRoute.routeId },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee().weight(1f, fill = false)
                    )
                    InlineConfigIndicator(isModified)
                }
                RouteSummary(route = selectedRoute)
            }
        }
    }
}

@Composable
fun RoutesDialog(
    config: TurnableConfig,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    SelectionDialog(
        title = stringResource(R.string.route_title),
        items = config.routes,
        isSelected = { it.routeId == config.selectedRouteId },
        onSelect = { route -> onSelect(route.routeId) },
        onDismiss = onDismiss
    ) { route, isSelected ->
        val iconRes = when (route.socket.lowercase()) {
            "tcp" -> R.drawable.compare_arrows_24px
            "udp" -> R.drawable.arrow_forward_24px
            else -> R.drawable.route_24px
        }
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StandardLeadingIcon {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = route.name.ifBlank { route.routeId },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                RouteSummary(
                    route = route,
                    color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
fun TurnablePlatformDialog(
    currentPlatform: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val platforms = listOf(
        "vk.com"
    )

    SelectionDialog(
        title = stringResource(R.string.olcrtc_carrier_label),
        items = platforms,
        isSelected = { it == currentPlatform },
        onSelect = { onSelect(it) },
        onDismiss = onDismiss
    ) { value, isSelected ->
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StandardLeadingIcon {
                Icon(
                    painter = painterResource(getPlatformIcon(value)),
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
            Text(
                text = TurnableConfig.getPlatformDisplayName(value),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
