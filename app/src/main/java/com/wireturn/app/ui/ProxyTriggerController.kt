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
import com.wireturn.app.data.KernelVariant
import com.wireturn.app.data.XrayConfiguration
import com.wireturn.app.viewmodel.MainViewModel
import com.wireturn.app.viewmodel.ProxyState

@Composable
fun ProxyTriggerController(
    viewModel: MainViewModel,
    content: @Composable (onToggleProxy: () -> Unit, onCheckMismatch: (Boolean, () -> Unit) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val proxyState by viewModel.proxyState.collectAsStateWithLifecycle()
    val vpnEnabled by viewModel.vpnEnabled.collectAsStateWithLifecycle()
    val autoLaunchSettings by viewModel.autoLaunchSettings.collectAsStateWithLifecycle()
    val xrayConfig by viewModel.xrayConfig.collectAsStateWithLifecycle()
    val clientConfig by viewModel.clientConfig.collectAsStateWithLifecycle()

    val showAutoLaunchOverride = rememberSaveable { mutableStateOf(false) }
    val showMismatchDialog = rememberSaveable { mutableStateOf(false) }
    var mismatchMessage by rememberSaveable { mutableStateOf("") }
    var pendingProxyAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.setVpnEnabled(true)
        }
    }

    val vlessMismatch = stringResource(R.string.warn_proxy_vless_mismatch)
    val wgMismatch = stringResource(R.string.warn_proxy_wg_mismatch)

    val checkMismatch = { targetXrayEnabled: Boolean, onConfirmed: () -> Unit ->
        val selectedRoute = clientConfig.turnableConfig.routes.find { it.routeId == clientConfig.turnableConfig.selectedRouteId }
        val isTunnelVless = selectedRoute?.transport?.contains("KCP", ignoreCase = true) == true

        val isTurnable = clientConfig.kernelVariant == KernelVariant.TURNABLE
        val mismatch = isTurnable && targetXrayEnabled && (
                (isTunnelVless && xrayConfig.protocol == XrayConfiguration.WIREGUARD) ||
                        (!isTunnelVless && xrayConfig.protocol == XrayConfiguration.VLESS)
                )

        if (mismatch) {
            mismatchMessage = if (isTunnelVless) vlessMismatch else wgMismatch
            pendingProxyAction = onConfirmed
            showMismatchDialog.value = true
        } else {
            onConfirmed()
        }
    }

    val triggerProxyAction = {
        val action = {
            when (proxyState) {
                is ProxyState.Idle, is ProxyState.Error -> {
                    checkMismatch(xrayConfig.enabled) {
                        HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                        if (vpnEnabled) {
                            val intent = VpnService.prepare(context)
                            if (intent != null) {
                                vpnLauncher.launch(intent)
                            } else {
                                viewModel.startProxy()
                            }
                        } else {
                            viewModel.startProxy()
                        }
                    }
                }
                else -> {
                    HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_OFF)
                    viewModel.stopProxy()
                }
            }
        }

        if (autoLaunchSettings.enabled) {
            pendingProxyAction = action
            showAutoLaunchOverride.value = true
        } else {
            action()
        }
    }

    content(triggerProxyAction, checkMismatch)

    if (showMismatchDialog.value) {
        AlertDialog(
            onDismissRequest = { showMismatchDialog.value = false },
            title = { Text(stringResource(R.string.mismatch_title)) },
            text = { Text(mismatchMessage) },
            confirmButton = {
                TextButton(onClick = {
                    showMismatchDialog.value = false
                    pendingProxyAction?.invoke()
                    pendingProxyAction = null
                }) {
                    Text(stringResource(R.string.btn_start))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showMismatchDialog.value = false
                    pendingProxyAction = null
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
                    pendingProxyAction?.invoke()
                    pendingProxyAction = null
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
