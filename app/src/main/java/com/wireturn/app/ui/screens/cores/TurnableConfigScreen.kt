@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui.screens.cores

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wireturn.app.R
import com.wireturn.app.data.TurnableConfig
import com.wireturn.app.data.TurnableRoute
import com.wireturn.app.ui.AppTopAppBar
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.ItemPosition
import com.wireturn.app.ui.LabeledButtonGroup
import com.wireturn.app.ui.LargeLeadingIcon
import com.wireturn.app.ui.ModifiedIndicator
import com.wireturn.app.ui.QrCodeDialog
import com.wireturn.app.ui.RowLabel
import com.wireturn.app.ui.SectionGroup
import com.wireturn.app.ui.SectionItem
import com.wireturn.app.ui.SelectionDialog
import com.wireturn.app.ui.ShareDropdownMenu
import com.wireturn.app.ui.SliderRow
import com.wireturn.app.ui.StandardLeadingIcon
import com.wireturn.app.ui.SupportingText
import com.wireturn.app.ui.SwitchRow
import com.wireturn.app.ui.TextFieldRow
import com.wireturn.app.ui.ValidatorUtils
import com.wireturn.app.ui.redact
import com.wireturn.app.ui.selectableButtonItem
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
    val editingRoute = remember { mutableStateOf<TurnableRoute?>(null) }
    val isAddingRoute = remember { mutableStateOf(false) }

    val isModified by remember(config) {
        derivedStateOf { config != initialConfig }
    }

    val isRoutesModified by remember(config.routes) {
        derivedStateOf { config.routes != initialConfig.routes }
    }

    val showExitDialog = remember { mutableStateOf(false) }
    val showQrDialog = remember { mutableStateOf(false) }
    val showMenu = remember { mutableStateOf(false) }

    val handleBack = {
        if (isEditMode && isModified) {
            showExitDialog.value = true
        } else {
            onBack()
        }
    }

    BackHandler(enabled = isEditMode && isModified, onBack = handleBack)
    
    val scrollState = rememberScrollState()
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
            AppTopAppBar(
                title = stringResource(R.string.kernel_turnable),
                onBack = handleBack,
                scrollBehavior = scrollBehavior,
                actions = {
                    if (isEditMode) {
                        Box {
                            IconButton(onClick = { showMenu.value = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.share_24px),
                                    contentDescription = stringResource(R.string.share)
                                )
                            }

                            ShareDropdownMenu(
                                expanded = showMenu.value,
                                onDismissRequest = { showMenu.value = false },
                                textToShare = config.toUri(),
                                onShowQr = { showQrDialog.value = true }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = !isEditMode || isModified,
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
                        onSave(config)
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    icon = {
                        Icon(
                            painter = painterResource(
                                if (isEditMode) R.drawable.save_24px 
                                else R.drawable.arrow_forward_ios_24px
                            ),
                            contentDescription = null
                        )
                    },
                    text = {
                        Text(
                            text = stringResource(if (isEditMode) R.string.btn_save else R.string.btn_next)
                        )
                    }
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
            SectionGroup(
                title = stringResource(R.string.route_title),
                isModified = isEditMode && isRoutesModified
            ) {
                SectionItem(
                    position = ItemPosition.Single,
                    onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        showRoutesDialog.value = true
                    }
                ) {
                    if (config.routes.isNotEmpty()) {
                        RoutesBlock(
                            config = config,
                            isModified = isEditMode && config.selectedRouteId != initialConfig.selectedRouteId
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LargeLeadingIcon {
                                Icon(
                                    painter = painterResource(R.drawable.route_24px),
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = stringResource(R.string.route_add),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            // connection details
            SectionGroup(title = stringResource(R.string.connection_details)) {
                SectionItem(
                    position = ItemPosition.Top
                ) {
                    SliderRow(
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
                SectionItem {
                    TextFieldRow(
                        label = stringResource(R.string.username_label),
                        value = config.username.redact(isPrivacyActive),
                        onValueChange = { if (!isPrivacyActive) config = config.copy(username = it) },
                        readOnly = isPrivacyActive,
                        supportingText = stringResource(R.string.username_desc),
                        isModified = isEditMode && config.username != initialConfig.username,
                        isError = config.username.isBlank(),
                        privacyMode = isPrivacyActive
                    )
                }
                SectionItem(position = ItemPosition.Bottom) {
                    TextFieldRow(
                        label = stringResource(R.string.call_id_label),
                        value = config.callId.redact(isPrivacyActive),
                        onValueChange = { if (!isPrivacyActive) config = config.copy(callId = it) },
                        readOnly = isPrivacyActive,
                        supportingText = stringResource(R.string.call_id_desc),
                        isModified = isEditMode && config.callId != initialConfig.callId,
                        isError = config.callId.isBlank(),
                        privacyMode = isPrivacyActive
                    )
                }
            }

            // server settings
            SectionGroup(title = stringResource(R.string.server_settings_title)) {
                SectionItem(position = ItemPosition.Top) {
                    val invalidUuid = config.userUuid?.let { it.isNotBlank() && !ValidatorUtils.isValidUuid4(it) } ?: false

                    TextFieldRow(
                        label = stringResource(R.string.user_uuid_label),
                        value = (config.userUuid ?: "").redact(isPrivacyActive),
                        onValueChange = { if (!isPrivacyActive) config = config.copy(userUuid = it) },
                        readOnly = isPrivacyActive,
                        supportingText = stringResource(R.string.user_uuid_desc),
                        isModified = isEditMode && config.userUuid != initialConfig.userUuid,
                        isError = config.userUuid.isNullOrBlank() || invalidUuid,
                        privacyMode = isPrivacyActive
                    )
                }
                SectionItem(
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
                                tint = if (config.platformId.isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            RowLabel(
                                text = stringResource(R.string.olcrtc_carrier_label),
                                isModified = isEditMode && config.platformId != initialConfig.platformId
                            )
                            val currentLabel = config.platformDisplayName
                            Spacer(Modifier.height(2.dp))
                            SupportingText(
                                text = currentLabel,
                                color = if (config.platformId.isBlank()) MaterialTheme.colorScheme.error else Color.Unspecified
                            )
                        }
                    }
                }
                SectionItem {
                    LabeledButtonGroup(
                        label = stringResource(R.string.connection_type_label),
                        supportingText = stringResource(R.string.connection_type_desc),
                        isModified = isEditMode && config.type != initialConfig.type
                    ) {
                        val types = listOf("relay", "direct")
                        types.forEachIndexed { index, t ->
                            selectableButtonItem(
                                selected = config.type == t,
                                onSelect = { config = config.copy(type = t) },
                                label = t.replaceFirstChar { it.uppercase() },
                                index = index,
                                count = types.size
                            )
                        }
                    }
                }
                SectionItem {
                    TextFieldRow(
                        label = stringResource(R.string.pub_key_label),
                        value = (config.pubKey ?: "").redact(isPrivacyActive),
                        onValueChange = { if (!isPrivacyActive) config = config.copy(pubKey = it) },
                        readOnly = isPrivacyActive,
                        supportingText = stringResource(R.string.pub_key_desc),
                        isModified = isEditMode && config.pubKey != initialConfig.pubKey,
                        isError = config.pubKey.isNullOrBlank(),
                        privacyMode = isPrivacyActive
                    )
                }
                SectionItem {
                    LabeledButtonGroup(
                        label = stringResource(R.string.encryption_label),
                        supportingText = stringResource(R.string.encryption_desc),
                        isModified = isEditMode && config.encryption != initialConfig.encryption
                    ) {
                        val options = listOf("handshake", "full")
                        options.forEachIndexed { index, e ->
                            selectableButtonItem(
                                selected = config.encryption == e,
                                onSelect = { config = config.copy(encryption = e) },
                                label = e.replaceFirstChar { it.uppercase() },
                                index = index,
                                count = options.size
                            )
                        }
                    }
                }
                SectionItem {
                    TextFieldRow(
                        label = stringResource(R.string.gateway_label),
                        value = config.gateway.redact(isPrivacyActive),
                        onValueChange = { if (!isPrivacyActive) config = config.copy(gateway = it) },
                        readOnly = isPrivacyActive,
                        supportingText = stringResource(R.string.gateway_desc),
                        isModified = isEditMode && config.gateway != initialConfig.gateway,
                        isError = !ValidatorUtils.isValidHostPort(config.gateway),
                        privacyMode = isPrivacyActive
                    )
                }
                SectionItem {
                    LabeledButtonGroup(
                        label = stringResource(R.string.proto_label),
                        supportingText = stringResource(R.string.proto_desc),
                        isModified = isEditMode && config.proto != initialConfig.proto
                    ) {
                        val options = listOf("dtls", "srtp", "none")
                        val currentProto = config.proto ?: "none"
                        options.forEachIndexed { index, p ->
                            selectableButtonItem(
                                selected = currentProto == p,
                                onSelect = { config = config.copy(proto = if (p == "none") null else p) },
                                label = p.uppercase(),
                                index = index,
                                count = options.size
                            )
                        }
                    }
                }
                SectionItem(
                    position = ItemPosition.Bottom,
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
        }
    }

    if (showRoutesDialog.value) {
        RoutesDialog(
            config = config,
            onSelect = { routeId ->
                config = config.copy(selectedRouteId = routeId)
                showRoutesDialog.value = false
            },
            onAdd = {
                showRoutesDialog.value = false
                isAddingRoute.value = true
            },
            onEdit = { route ->
                showRoutesDialog.value = false
                editingRoute.value = route
            },
            onDelete = { route ->
                val newRoutes = config.routes.filter { it.routeId != route.routeId }
                config = config.copy(
                    routes = newRoutes,
                    selectedRouteId = if (config.selectedRouteId == route.routeId) {
                        newRoutes.firstOrNull()?.routeId ?: ""
                    } else {
                        config.selectedRouteId
                    }
                )
            },
            onDismiss = { showRoutesDialog.value = false }
        )
    }

    if (isAddingRoute.value || editingRoute.value != null) {
        val routeToEdit = editingRoute.value
        RouteEditDialog(
            route = routeToEdit,
            onSave = { newRoute ->
                if (routeToEdit != null) {
                    // Update
                    val newRoutes = config.routes.map {
                        if (it.routeId == routeToEdit.routeId) newRoute else it
                    }
                    config = config.copy(
                        routes = newRoutes,
                        selectedRouteId = if (config.selectedRouteId == routeToEdit.routeId) newRoute.routeId else config.selectedRouteId
                    )
                } else {
                    // Add
                    config = config.copy(
                        routes = config.routes + newRoute,
                        selectedRouteId = if (config.routes.isEmpty()) newRoute.routeId else config.selectedRouteId
                    )
                }
                editingRoute.value = null
                isAddingRoute.value = false
                showRoutesDialog.value = true
            },
            onDismiss = {
                editingRoute.value = null
                isAddingRoute.value = false
                showRoutesDialog.value = true
            }
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

    if (showQrDialog.value) {
        QrCodeDialog(
            text = config.toUri(),
            onDismiss = { showQrDialog.value = false }
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
    color: Color = Color.Unspecified
) {
    Text(
        text = "${route.socket.uppercase()} • ${route.transport?.uppercase() ?: "none"}",
        style = MaterialTheme.typography.labelSmall,
        color = color.takeOrElse { LocalContentColor.current },
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
                    ModifiedIndicator(isModified)
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
    onAdd: () -> Unit,
    onEdit: (TurnableRoute) -> Unit,
    onDelete: (TurnableRoute) -> Unit,
    onDismiss: () -> Unit
) {
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.9f)
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(
                modifier = Modifier
                    .padding(vertical = 24.dp, horizontal = 12.dp)
                    .heightIn(max = 600.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.route_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                config.routes.forEach { route ->
                    val isSelected = route.routeId == config.selectedRouteId
                    val iconRes = when (route.socket.lowercase()) {
                        "tcp" -> R.drawable.compare_arrows_24px
                        "udp" -> R.drawable.arrow_forward_24px
                        else -> R.drawable.route_24px
                    }

                    Surface(
                        onClick = { onSelect(route.routeId) },
                        shape = MaterialTheme.shapes.medium,
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StandardLeadingIcon {
                                Icon(
                                    painter = painterResource(iconRes),
                                    contentDescription = null,
                                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = route.name.ifBlank { route.routeId },
                                    maxLines = 1,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                RouteSummary(
                                    route = route,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else Color.Unspecified
                                )
                            }
                            
                            var showMenu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(
                                        painter = painterResource(R.drawable.more_vert_24px),
                                        contentDescription = stringResource(R.string.profile_actions),
                                        tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.route_edit)) },
                                        onClick = {
                                            showMenu = false
                                            onEdit(route)
                                        },
                                        leadingIcon = {
                                            Icon(
                                                painterResource(R.drawable.edit_24px),
                                                contentDescription = null
                                            )
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.route_delete)) },
                                        onClick = {
                                            showMenu = false
                                            onDelete(route)
                                        },
                                        leadingIcon = {
                                            Icon(
                                                painterResource(R.drawable.delete_24px),
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                Surface(
                    onClick = onAdd,
                    shape = MaterialTheme.shapes.medium,
                    color = Color.Transparent
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StandardLeadingIcon {
                            Icon(
                                painter = painterResource(R.drawable.add_24px),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = stringResource(R.string.route_add),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RouteEditDialog(
    route: TurnableRoute?,
    onSave: (TurnableRoute) -> Unit,
    onDismiss: () -> Unit
) {
    var id by remember { mutableStateOf(route?.routeId ?: "") }
    var name by remember { mutableStateOf(route?.name ?: "") }
    var socket by remember { mutableStateOf(route?.socket ?: "udp") }
    var transport by remember { mutableStateOf(route?.transport ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (route == null) stringResource(R.string.route_add) else stringResource(R.string.route_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.route_name_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text(stringResource(R.string.route_id_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    isError = id.isBlank(),
                    singleLine = true
                )
                
                SectionGroup(title = stringResource(R.string.route_socket_label)) {
                    LabeledButtonGroup {
                        val options = listOf("tcp", "udp")
                        options.forEachIndexed { index, s ->
                            selectableButtonItem(
                                selected = socket == s,
                                onSelect = { socket = s },
                                label = s.uppercase(),
                                index = index,
                                count = options.size
                            )
                        }
                    }
                }

                SectionGroup(title = stringResource(R.string.route_transport_label)) {
                    LabeledButtonGroup {
                        val options = listOf("kcp", "none")
                        options.forEachIndexed { index, t ->
                            val isNone = t == "none"
                            selectableButtonItem(
                                selected = if (isNone) transport.isBlank() else transport == t,
                                onSelect = { transport = if (isNone) "" else t },
                                label = t.uppercase(),
                                index = index,
                                count = options.size
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(TurnableRoute(routeId = id, name = name.ifBlank { id }, socket = socket, transport = transport.ifBlank { null }))
                },
                enabled = id.isNotBlank() && socket.isNotBlank()
            ) {
                Text(stringResource(R.string.btn_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
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
    ) { value, _ ->
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StandardLeadingIcon {
                Icon(
                    painter = painterResource(getPlatformIcon(value)),
                    contentDescription = null
                )
            }
            Text(
                text = TurnableConfig.getPlatformDisplayName(value),
                modifier = Modifier.weight(1f)
            )
        }
    }
}
