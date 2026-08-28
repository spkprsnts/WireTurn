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
import com.wireturn.app.data.variant
import com.wireturn.app.viewmodel.CoreState
import com.wireturn.app.viewmodel.MainViewModel

@Composable
private fun kernelDisplayName(kernelConfig: KernelConfig): String = when (kernelConfig.variant) {
    KernelVariant.TURNABLE -> stringResource(R.string.kernel_turnable)
    KernelVariant.OLCRTC -> stringResource(R.string.kernel_olcrtc)
    KernelVariant.WEBDAV -> stringResource(R.string.kernel_webdav)
    KernelVariant.FREETURN -> stringResource(R.string.kernel_freeturn)
}

@Composable
fun CoreTriggerController(
    viewModel: MainViewModel,
    content: @Composable (onToggleCore: () -> Unit) -> Unit
) {
    val context = LocalContext.current
    val coreState by viewModel.coreState.collectAsStateWithLifecycle()
    val vpnSettings by viewModel.vpnSettings.collectAsStateWithLifecycle()
    val autoLaunchSettings by viewModel.autoLaunchSettings.collectAsStateWithLifecycle()
    val clientConfig by viewModel.clientConfig.collectAsStateWithLifecycle()
    val xrayConfig by viewModel.xrayConfig.collectAsStateWithLifecycle()
    val vlessConfig by viewModel.vlessConfig.collectAsStateWithLifecycle()

    val mismatchTitle = stringResource(R.string.mismatch_title)
    val mismatchKernelName = kernelDisplayName(clientConfig.kernelConfig)
    val mismatchTcpBody = stringResource(R.string.xray_uri_mismatch_tcp, mismatchKernelName)
    val mismatchUdpBody = stringResource(R.string.xray_uri_mismatch_udp, mismatchKernelName)
    val mismatchFreeTurnBody = stringResource(R.string.xray_uri_kernel_udp_only_mismatch, mismatchKernelName)

    val showAutoLaunchOverride = rememberSaveable { mutableStateOf(false) }
    var pendingCoreAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            // The user asked to start; consent was the only thing missing.
            viewModel.startCore()
        }
    }

    val triggerCoreAction = {
        val action = {
            when (coreState) {
                is CoreState.Idle, is CoreState.Error -> {
                    HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)

                    // Non-blocking heads-up for a protocol/transport mismatch (e.g. a Hysteria2 link on
                    // a tcp-only route) regardless of how it got here - manual edit, subscription sync,
                    // or a route switch - since only the Xray edit screen itself shows this otherwise.
                    if (xrayConfig.enabled) {
                        val mismatchSocket = ValidatorUtils.kernelTransportMismatch(
                            clientConfig.kernelConfig, xrayConfig.protocol, vlessConfig.vlessLink
                        )
                        if (mismatchSocket != null) {
                            val body = if (clientConfig.kernelConfig is KernelConfig.FreeTurn) {
                                mismatchFreeTurnBody
                            } else if (mismatchSocket == "tcp") mismatchTcpBody else mismatchUdpBody
                            context.showExclusiveToast("$mismatchTitle: $body", android.widget.Toast.LENGTH_LONG)
                        }
                    }

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

    content(triggerCoreAction)

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
