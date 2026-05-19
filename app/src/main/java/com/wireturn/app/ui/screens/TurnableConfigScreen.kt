@file:OptIn(ExperimentalMaterial3Api::class)

package com.wireturn.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wireturn.app.R
import com.wireturn.app.data.TurnableConfig
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.LabeledButtonGroup
import com.wireturn.app.ui.SettingsGroup
import com.wireturn.app.ui.SettingsGroupItem
import com.wireturn.app.ui.SwitchRow
import com.wireturn.app.ui.TextFieldRow
import com.wireturn.app.ui.configButtonGroupItem
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TurnableConfigScreen(
    initialConfig: TurnableConfig = TurnableConfig(),
    onBack: () -> Unit,
    onSave: (TurnableConfig) -> Unit
) {
    var config by remember { mutableStateOf(initialConfig) }
    val showRoutesDialog = remember { mutableStateOf(false) }
    
    val scrollState = rememberScrollState()
    val isDark = com.wireturn.app.ui.theme.LocalIsDark.current
    val blockContainerColor = if (isDark) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface
    val context = androidx.compose.ui.platform.LocalContext.current

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.kernel_turnable)) },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back_24px),
                            contentDescription = null
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onSave(config) }) {
                        Icon(
                            painter = painterResource(R.drawable.check_24px),
                            contentDescription = stringResource(R.string.btn_save)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
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
                        RoutesBlock(config = config)
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
                        supportingText = stringResource(R.string.peers_desc)
                    )
                }
                SettingsGroupItem(
                    isTop = false,
                    isBottom = false,
                    containerColor = blockContainerColor
                ) {
                    TextFieldRow(
                        label = stringResource(R.string.username_label),
                        value = config.username,
                        onValueChange = { config = config.copy(username = it) },
                        supportingText = stringResource(R.string.username_desc)
                    )
                }
                SettingsGroupItem(
                    isTop = false,
                    isBottom = true,
                    containerColor = blockContainerColor
                ) {
                    TextFieldRow(
                        label = stringResource(R.string.call_id_label),
                        value = config.callId,
                        onValueChange = { config = config.copy(callId = it) },
                        supportingText = stringResource(R.string.call_id_desc)
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
                        value = config.userUuid ?: "",
                        onValueChange = { config = config.copy(userUuid = it) },
                        supportingText = stringResource(R.string.user_uuid_desc)
                    )
                }
                SettingsGroupItem(
                    isTop = false,
                    isBottom = false,
                    containerColor = blockContainerColor
                ) {
                    TextFieldRow(
                        label = stringResource(R.string.platform_id_label),
                        value = config.platformId,
                        onValueChange = { config = config.copy(platformId = it) },
                        supportingText = stringResource(R.string.platform_id_desc)
                    )
                }
                SettingsGroupItem(
                    isTop = false,
                    isBottom = false,
                    containerColor = blockContainerColor
                ) {
                    LabeledButtonGroup(
                        label = stringResource(R.string.connection_type_label),
                        supportingText = stringResource(R.string.connection_type_desc)
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
                        value = config.pubKey ?: "",
                        onValueChange = { config = config.copy(pubKey = it) },
                        supportingText = stringResource(R.string.pub_key_desc)
                    )
                }
                SettingsGroupItem(
                    isTop = false,
                    isBottom = false,
                    containerColor = blockContainerColor
                ) {
                    LabeledButtonGroup(
                        label = stringResource(R.string.encryption_label),
                        supportingText = stringResource(R.string.encryption_desc)
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
                        value = config.gateway,
                        onValueChange = { config = config.copy(gateway = it) },
                        supportingText = stringResource(R.string.gateway_desc)
                    )
                }
                SettingsGroupItem(
                    isTop = false,
                    isBottom = false,
                    containerColor = blockContainerColor
                ) {
                    LabeledButtonGroup(
                        label = stringResource(R.string.proto_label),
                        supportingText = stringResource(R.string.proto_desc)
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
                    containerColor = blockContainerColor
                ) {
                    SwitchRow(
                        label = stringResource(R.string.force_turn_label),
                        supportingText = stringResource(R.string.force_turn_desc),
                        checked = config.forceTurn,
                        onCheckedChange = { config = config.copy(forceTurn = it) }
                    )
                }
            }
        }
    }

    if (showRoutesDialog.value) {
        RoutesDialog(
            config = config,
            onSelect = {
                config = config.copy(selectedRouteId = it)
                showRoutesDialog.value = false
            },
            onDismiss = { showRoutesDialog.value = false }
        )
    }
}
