package com.wireturn.app.ui

import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.R
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelVariant
import com.wireturn.app.data.XrayConfiguration
import com.wireturn.app.viewmodel.MainViewModel
import com.wireturn.app.viewmodel.CoreState

@Composable
fun CoreTriggerController(
    viewModel: MainViewModel,
    content: @Composable (onToggleCore: () -> Unit, onCheckMismatch: (Boolean, () -> Unit) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val coreState by viewModel.coreState.collectAsStateWithLifecycle()
    val vpnSettings by viewModel.vpnSettings.collectAsStateWithLifecycle()
    val autoLaunchSettings by viewModel.autoLaunchSettings.collectAsStateWithLifecycle()
    val xrayConfig by viewModel.xrayConfig.collectAsStateWithLifecycle()
    val clientConfig by viewModel.clientConfig.collectAsStateWithLifecycle()

    val showAutoLaunchOverride = rememberSaveable { mutableStateOf(false) }
    val showMismatchDialog = rememberSaveable { mutableStateOf(false) }
    var mismatchMessage by rememberSaveable { mutableStateOf("") }
    var pendingCoreAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.setVpnEnabled(true)
        }
    }

    val vlessMismatch = stringResource(R.string.warn_core_vless_mismatch)
    val wgMismatch = stringResource(R.string.warn_core_wg_mismatch)

    val checkMismatch = { targetXrayEnabled: Boolean, onConfirmed: () -> Unit ->
        val turnableConfig = (clientConfig.kernelConfig as? KernelConfig.Turnable)?.config
        val selectedRoute = turnableConfig?.routes?.find { it.routeId == turnableConfig.selectedRouteId }
        val isTunnelVless = selectedRoute?.transport?.contains("KCP", ignoreCase = true) == true

        val isTurnable = clientConfig.kernelVariant == KernelVariant.TURNABLE
        val mismatch = isTurnable && targetXrayEnabled && (
                (isTunnelVless && xrayConfig.protocol == XrayConfiguration.WIREGUARD) ||
                        (!isTunnelVless && xrayConfig.protocol == XrayConfiguration.VLESS)
                )

        if (mismatch) {
            mismatchMessage = if (isTunnelVless) vlessMismatch else wgMismatch
            pendingCoreAction = onConfirmed
            showMismatchDialog.value = true
        } else {
            onConfirmed()
        }
    }

    val triggerCoreAction = {
        val action = {
            when (coreState) {
                is CoreState.Idle, is CoreState.Error -> {
                    checkMismatch(xrayConfig.enabled) {
                        HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                        if (vpnSettings.enabled) {
                            val intent = VpnService.prepare(context)
                            if (intent != null) {
                                vpnLauncher.launch(intent)
                            } else {
                                viewModel.startCore()
                            }
                        } else {
                            viewModel.startCore()
                        }
                    }
                }
                else -> {
                    HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_OFF)
                    viewModel.stopCore()
                }
            }
        }

        if (autoLaunchSettings.enabled) {
            pendingCoreAction = action
            showAutoLaunchOverride.value = true
        } else {
            action()
        }
    }

    content(triggerCoreAction, checkMismatch)

    if (showMismatchDialog.value) {
        AlertDialog(
            onDismissRequest = { showMismatchDialog.value = false },
            title = { Text(stringResource(R.string.mismatch_title)) },
            text = { Text(mismatchMessage) },
            confirmButton = {
                TextButton(onClick = {
                    showMismatchDialog.value = false
                    pendingCoreAction?.invoke()
                    pendingCoreAction = null
                }) {
                    Text(stringResource(R.string.btn_start))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showMismatchDialog.value = false
                    pendingCoreAction = null
                }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showAutoLaunchOverride.value) {
        AlertDialog(
            onDismissRequest = { showAutoLaunchOverride.value = false },
            title = { Text(stringResource(R.string.auto_launch_override_title)) },
            text = { Text(stringResource(R.string.auto_launch_override_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    showAutoLaunchOverride.value = false
                    viewModel.updateAutoLaunchSettings(autoLaunchSettings.copy(enabled = false))
                    pendingCoreAction?.invoke()
                    pendingCoreAction = null
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
}
