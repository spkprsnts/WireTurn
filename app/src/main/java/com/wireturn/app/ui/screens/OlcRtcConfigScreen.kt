@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.wireturn.app.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wireturn.app.R
import com.wireturn.app.data.OlcrtcConfig
import com.wireturn.app.ui.ConfigRowLabel
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.LargeLeadingIcon
import com.wireturn.app.ui.SelectionDialog
import com.wireturn.app.ui.SettingsGroup
import com.wireturn.app.ui.SettingsGroupItem
import com.wireturn.app.ui.StandardLeadingIcon
import com.wireturn.app.ui.SupportingText
import com.wireturn.app.ui.TextFieldRow
import com.wireturn.app.ui.redact
import kotlin.math.roundToInt

@Composable
fun OlcRtcConfigScreen(
    isEditMode: Boolean = false,
    initialConfig: OlcrtcConfig = OlcrtcConfig(),
    privacyMode: Boolean = false,
    onBack: () -> Unit,
    onSave: (OlcrtcConfig) -> Unit
) {
    val isPrivacyActive = privacyMode && isEditMode
    var config by remember(initialConfig) { mutableStateOf(initialConfig) }
    var videoW by remember(initialConfig) { mutableStateOf(initialConfig.videoW.let { if (it == 0) "1080" else it.toString() }) }
    var videoH by remember(initialConfig) { mutableStateOf(initialConfig.videoH.let { if (it == 0) "1080" else it.toString() }) }

    val isModified by remember(config, videoW, videoH) {
        derivedStateOf {
            config != initialConfig ||
            videoW != (initialConfig.videoW.let { if (it == 0) "1080" else it.toString() }) ||
            videoH != (initialConfig.videoH.let { if (it == 0) "1080" else it.toString() })
        }
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

    val showCarrierDialog = remember { mutableStateOf(false) }
    val showTransportDialog = remember { mutableStateOf(false) }
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
                    onSave(config.copy(
                        videoW = videoW.toIntOrNull() ?: 1080,
                        videoH = videoH.toIntOrNull() ?: 1080
                    ))
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
                title = stringResource(R.string.kernel_olcrtc),
                onBack = handleBack,
                scrollBehavior = scrollBehavior,
                actions = {
                    if (isEditMode) {
                        IconButton(onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, config.copy(
                                    videoW = videoW.toIntOrNull() ?: 1080,
                                    videoH = videoH.toIntOrNull() ?: 1080
                                ).toUri())
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
                    onSave(config.copy(
                        videoW = videoW.toIntOrNull() ?: 1080,
                        videoH = videoH.toIntOrNull() ?: 1080
                    ))
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
            // Connection Details
            SettingsGroup(title = stringResource(R.string.connection_details)) {
                SettingsGroupItem(
                    isTop = true,
                    isBottom = false,
                    containerColor = blockContainerColor,
                    onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        showCarrierDialog.value = true
                    }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LargeLeadingIcon {
                            Icon(
                                painter = painterResource(getCarrierIcon(config.carrier)),
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            ConfigRowLabel(
                                text = stringResource(R.string.olcrtc_carrier_label),
                                isModified = isEditMode && config.carrier != initialConfig.carrier
                            )
                            val currentLabel = config.carrierDisplayName
                            Spacer(Modifier.height(2.dp))
                            SupportingText(currentLabel)
                        }
                    }
                }

                SettingsGroupItem(
                    isTop = false,
                    isBottom = false,
                    containerColor = blockContainerColor,
                    onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        showTransportDialog.value = true
                    }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LargeLeadingIcon {
                            Icon(
                                painter = painterResource(getTransportIcon(config.transport)),
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            ConfigRowLabel(
                                text = stringResource(R.string.olcrtc_transport_label),
                                isModified = isEditMode && config.transport != initialConfig.transport
                            )
                            val currentLabel = config.transportDisplayName
                            Spacer(Modifier.height(2.dp))
                            SupportingText(currentLabel)
                        }
                    }
                }

                SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                    TextFieldRow(
                        label = stringResource(R.string.olcrtc_id_label),
                        value = config.id.redact(isPrivacyActive),
                        onValueChange = { if (!isPrivacyActive) config = config.copy(id = it) },
                        readOnly = isPrivacyActive,
                        isModified = isEditMode && config.id != initialConfig.id,
                        privacyMode = isPrivacyActive
                    )
                }
                SettingsGroupItem(isTop = false, isBottom = true, containerColor = blockContainerColor) {
                    TextFieldRow(
                        label = stringResource(R.string.olcrtc_dns_label),
                        value = config.dns.redact(isPrivacyActive),
                        onValueChange = { if (!isPrivacyActive) config = config.copy(dns = it) },
                        readOnly = isPrivacyActive,
                        isModified = isEditMode && config.dns != initialConfig.dns,
                        privacyMode = isPrivacyActive
                    )
                }
            }

            // Server Settings
            SettingsGroup(title = stringResource(R.string.server_settings_title)) {
                SettingsGroupItem(isTop = true, isBottom = false, containerColor = blockContainerColor) {
                    TextFieldRow(
                        label = stringResource(R.string.olcrtc_client_id_label),
                        value = config.clientId.redact(isPrivacyActive),
                        onValueChange = { if (!isPrivacyActive) config = config.copy(clientId = it) },
                        readOnly = isPrivacyActive,
                        isModified = isEditMode && config.clientId != initialConfig.clientId,
                        privacyMode = isPrivacyActive
                    )
                }
                SettingsGroupItem(isTop = false, isBottom = true, containerColor = blockContainerColor) {
                    TextFieldRow(
                        label = stringResource(R.string.olcrtc_key_label),
                        value = config.key.redact(isPrivacyActive),
                        onValueChange = { if (!isPrivacyActive) config = config.copy(key = it) },
                        readOnly = isPrivacyActive,
                        isModified = isEditMode && config.key != initialConfig.key,
                        privacyMode = isPrivacyActive
                    )
                }
            }

            // Additional transport settings
            when (config.transport) {
                "vp8channel" -> {
                    SettingsGroup(title = stringResource(R.string.olcrtc_vp8_settings_title)) {
                        SettingsGroupItem(isTop = true, isBottom = false, containerColor = blockContainerColor) {
                            com.wireturn.app.ui.SliderRow(
                                label = stringResource(R.string.olcrtc_vp8_fps),
                                value = config.vp8Fps.toFloat(),
                                onValueChange = { config = config.copy(vp8Fps = it.roundToInt()) },
                                valueRange = 1f..60f,
                                steps = 59,
                                isModified = isEditMode && config.vp8Fps != initialConfig.vp8Fps
                            )
                        }
                        SettingsGroupItem(isTop = false, isBottom = true, containerColor = blockContainerColor) {
                            com.wireturn.app.ui.SliderRow(
                                label = stringResource(R.string.olcrtc_vp8_batch),
                                value = config.vp8Batch.toFloat(),
                                onValueChange = { config = config.copy(vp8Batch = it.roundToInt()) },
                                valueRange = 1f..100f,
                                steps = 99,
                                isModified = isEditMode && config.vp8Batch != initialConfig.vp8Batch
                            )
                        }
                    }
                }
                "seichannel" -> {
                    SettingsGroup(title = stringResource(R.string.olcrtc_sei_settings_title)) {
                        SettingsGroupItem(isTop = true, isBottom = false, containerColor = blockContainerColor) {
                            com.wireturn.app.ui.SliderRow(
                                label = stringResource(R.string.olcrtc_sei_fps),
                                value = config.seiFps.toFloat(),
                                onValueChange = { config = config.copy(seiFps = it.roundToInt()) },
                                valueRange = 1f..120f,
                                steps = 119,
                                isModified = isEditMode && config.seiFps != initialConfig.seiFps
                            )
                        }
                        SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                            com.wireturn.app.ui.SliderRow(
                                label = stringResource(R.string.olcrtc_sei_batch),
                                value = config.seiBatch.toFloat(),
                                onValueChange = { config = config.copy(seiBatch = it.roundToInt()) },
                                valueRange = 1f..256f,
                                steps = 255,
                                isModified = isEditMode && config.seiBatch != initialConfig.seiBatch
                            )
                        }
                        SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                            com.wireturn.app.ui.SliderRow(
                                label = stringResource(R.string.olcrtc_sei_frag),
                                value = config.seiFrag.toFloat(),
                                onValueChange = { config = config.copy(seiFrag = it.roundToInt()) },
                                valueRange = 100f..1500f,
                                steps = 140,
                                isModified = isEditMode && config.seiFrag != initialConfig.seiFrag
                            )
                        }
                        SettingsGroupItem(isTop = false, isBottom = true, containerColor = blockContainerColor) {
                            com.wireturn.app.ui.SliderRow(
                                label = stringResource(R.string.olcrtc_sei_ack_ms),
                                value = config.seiAckMs.toFloat(),
                                onValueChange = { config = config.copy(seiAckMs = it.roundToInt()) },
                                valueRange = 100f..5000f,
                                steps = 49,
                                isModified = isEditMode && config.seiAckMs != initialConfig.seiAckMs
                            )
                        }
                    }
                }
                "videochannel" -> {
                    SettingsGroup(title = stringResource(R.string.olcrtc_video_settings_title)) {
                        SettingsGroupItem(isTop = true, isBottom = false, containerColor = blockContainerColor) {
                            TextFieldRow(
                                label = stringResource(R.string.olcrtc_video_codec),
                                value = config.videoCodec,
                                onValueChange = { config = config.copy(videoCodec = it) },
                                isModified = isEditMode && config.videoCodec != initialConfig.videoCodec
                            )
                        }
                        SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                TextFieldRow(
                                    label = stringResource(R.string.olcrtc_video_width),
                                    value = videoW,
                                    onValueChange = { videoW = it },
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    isModified = isEditMode && videoW != (initialConfig.videoW.let { if (it == 0) "1080" else it.toString() })
                                )
                                TextFieldRow(
                                    label = stringResource(R.string.olcrtc_video_height),
                                    value = videoH,
                                    onValueChange = { videoH = it },
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    isModified = isEditMode && videoH != (initialConfig.videoH.let { if (it == 0) "1080" else it.toString() })
                                )
                            }
                        }
                        SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                            com.wireturn.app.ui.SliderRow(
                                label = stringResource(R.string.olcrtc_video_fps),
                                value = config.videoFps.toFloat(),
                                onValueChange = { config = config.copy(videoFps = it.roundToInt()) },
                                valueRange = 1f..60f,
                                steps = 59,
                                isModified = isEditMode && config.videoFps != initialConfig.videoFps
                            )
                        }
                        SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                            TextFieldRow(
                                label = stringResource(R.string.olcrtc_video_bitrate),
                                value = config.videoBitrate,
                                onValueChange = { config = config.copy(videoBitrate = it) },
                                isModified = isEditMode && config.videoBitrate != initialConfig.videoBitrate
                            )
                        }
                        SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                            TextFieldRow(
                                label = stringResource(R.string.olcrtc_video_hw),
                                value = config.videoHw,
                                onValueChange = { config = config.copy(videoHw = it) },
                                isModified = isEditMode && config.videoHw != initialConfig.videoHw
                            )
                        }
                        SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                            TextFieldRow(
                                label = stringResource(R.string.olcrtc_video_qr_recovery),
                                value = config.videoQrRecovery,
                                onValueChange = { config = config.copy(videoQrRecovery = it) },
                                isModified = isEditMode && config.videoQrRecovery != initialConfig.videoQrRecovery
                            )
                        }
                        SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                            com.wireturn.app.ui.SliderRow(
                                label = stringResource(R.string.olcrtc_video_qr_size),
                                value = config.videoQrSize.toFloat(),
                                onValueChange = { config = config.copy(videoQrSize = it.roundToInt()) },
                                valueRange = 0f..1000f,
                                steps = 100,
                                isModified = isEditMode && config.videoQrSize != initialConfig.videoQrSize
                            )
                        }
                        SettingsGroupItem(isTop = false, isBottom = false, containerColor = blockContainerColor) {
                            com.wireturn.app.ui.SliderRow(
                                label = stringResource(R.string.olcrtc_video_tile_module),
                                value = config.videoTileModule.toFloat(),
                                onValueChange = { config = config.copy(videoTileModule = it.roundToInt()) },
                                valueRange = 1f..32f,
                                steps = 31,
                                isModified = isEditMode && config.videoTileModule != initialConfig.videoTileModule
                            )
                        }
                        SettingsGroupItem(isTop = false, isBottom = true, containerColor = blockContainerColor) {
                            com.wireturn.app.ui.SliderRow(
                                label = stringResource(R.string.olcrtc_video_tile_rs),
                                value = config.videoTileRs.toFloat(),
                                onValueChange = { config = config.copy(videoTileRs = it.roundToInt()) },
                                valueRange = 0f..100f,
                                steps = 100,
                                isModified = isEditMode && config.videoTileRs != initialConfig.videoTileRs
                            )
                        }
                    }
                }
            }

            // Padding to prevent FAB from overlapping content
            Spacer(Modifier.height(80.dp))
        }
    }

    if (showCarrierDialog.value) {
        OlcrtcCarrierDialog(
            currentCarrier = config.carrier,
            onSelect = {
                config = config.copy(carrier = it)
                showCarrierDialog.value = false
            },
            onDismiss = { showCarrierDialog.value = false }
        )
    }

    if (showTransportDialog.value) {
        OlcrtcTransportDialog(
            currentTransport = config.transport,
            onSelect = {
                config = config.copy(transport = it)
                showTransportDialog.value = false
            },
            onDismiss = { showTransportDialog.value = false }
        )
    }
}

private fun getCarrierIcon(carrier: String): Int = when (carrier) {
    "wbstream" -> R.drawable.ic_wbstream
    "telemost" -> R.drawable.ic_telemost
    "jazz" -> R.drawable.ic_jazz
    else -> R.drawable.call_quality_24px
}

private fun getTransportIcon(transport: String): Int = when (transport) {
    "datachannel" -> R.drawable.data_array_24px
    "vp8channel" -> R.drawable.movie_24px
    "seichannel" -> R.drawable.video_settings_24px
    "videochannel" -> R.drawable.grid_view_24px
    else -> R.drawable.route_24px
}

@Composable
fun OlcrtcCarrierDialog(
    currentCarrier: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val carriers = listOf(
        "wbstream" to "WB Stream",
        "telemost" to "Telemost",
        "jazz" to "Jazz"
    )

    SelectionDialog(
        title = stringResource(R.string.olcrtc_carrier_label),
        items = carriers,
        isSelected = { it.first == currentCarrier },
        onSelect = { onSelect(it.first) },
        onDismiss = onDismiss
    ) { (value, label), isSelected ->
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StandardLeadingIcon {
                Icon(
                    painter = painterResource(getCarrierIcon(value)),
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun OlcrtcTransportDialog(
    currentTransport: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val transports = listOf(
        "datachannel",
        "vp8channel",
        "seichannel",
        "videochannel"
    )

    SelectionDialog(
        title = stringResource(R.string.olcrtc_transport_label),
        items = transports,
        isSelected = { it == currentTransport },
        onSelect = { onSelect(it) },
        onDismiss = onDismiss
    ) { value, isSelected ->
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StandardLeadingIcon {
                Icon(
                    painter = painterResource(getTransportIcon(value)),
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
            Text(
                text = OlcrtcConfig.getTransportDisplayName(value),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
