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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wireturn.app.R
import com.wireturn.app.data.VpnSettings
import com.wireturn.app.ui.AppTopAppBar
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.ItemPosition
import com.wireturn.app.ui.LabelGroup
import com.wireturn.app.ui.SectionGroup
import com.wireturn.app.ui.SectionItem
import com.wireturn.app.ui.StandardLeadingIcon
import com.wireturn.app.ui.SwitchRow
import com.wireturn.app.ui.TextFieldRow
import com.wireturn.app.ui.noFlingExpandConnection

@Composable
fun VpnSettingsScreen(
    initialVpnSettings: VpnSettings,
    onBack: () -> Unit,
    onSave: (VpnSettings) -> Unit,
    onOpenAppFiltering: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var mtu by remember { mutableStateOf(initialVpnSettings.mtu.toString()) }
    var ipv6 by remember { mutableStateOf(initialVpnSettings.ipv6) }
    var icmpReply by remember { mutableStateOf(initialVpnSettings.icmpReply) }

    val mtuValue = mtu.toIntOrNull()
    val mtuValid = mtuValue != null && mtuValue in VpnSettings.MIN_MTU..VpnSettings.MAX_MTU

    // An invalid MTU is flagged in the field and simply isn't applied, rather than blocking the
    // other switches from being saved.
    val currentVpnSettings = remember(mtu, ipv6, icmpReply, initialVpnSettings) {
        initialVpnSettings.copy(
            mtu = if (mtuValid) mtuValue else initialVpnSettings.mtu,
            ipv6 = ipv6,
            icmpReply = icmpReply
        )
    }
    val isModified = currentVpnSettings != initialVpnSettings

    val showExitDialog = remember { mutableStateOf(false) }
    val handleBack = {
        if (isModified) showExitDialog.value = true else onBack()
    }
    BackHandler(enabled = isModified, onBack = handleBack)

    val scrollState = rememberScrollState()
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
                    onSave(currentVpnSettings)
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
                title = stringResource(R.string.vpn_settings_title),
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
                        onSave(currentVpnSettings)
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
            SectionGroup {
                SectionItem(position = ItemPosition.Top) {
                    TextFieldRow(
                        label = stringResource(R.string.vpn_settings_mtu_label),
                        value = mtu,
                        onValueChange = { mtu = it.filter(Char::isDigit).take(4) },
                        placeholder = VpnSettings.DEFAULT_MTU.toString(),
                        supportingText = stringResource(
                            R.string.vpn_settings_mtu_desc,
                            VpnSettings.MIN_MTU,
                            VpnSettings.MAX_MTU
                        ),
                        isError = !mtuValid,
                        isModified = mtuValid && mtuValue != initialVpnSettings.mtu,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }

                SectionItem(
                    onClick = {
                        ipv6 = !ipv6
                        HapticUtil.perform(
                            context,
                            if (ipv6) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                        )
                    }
                ) {
                    SwitchRow(
                        label = stringResource(R.string.vpn_settings_ipv6_label),
                        supportingText = stringResource(R.string.vpn_settings_ipv6_desc),
                        checked = ipv6,
                        onCheckedChange = { ipv6 = it },
                        isModified = ipv6 != initialVpnSettings.ipv6
                    )
                }

                SectionItem(
                    position = ItemPosition.Bottom,
                    onClick = {
                        icmpReply = !icmpReply
                        HapticUtil.perform(
                            context,
                            if (icmpReply) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                        )
                    }
                ) {
                    SwitchRow(
                        label = stringResource(R.string.vpn_settings_icmp_label),
                        supportingText = stringResource(R.string.vpn_settings_icmp_desc),
                        checked = icmpReply,
                        onCheckedChange = { icmpReply = it },
                        isModified = icmpReply != initialVpnSettings.icmpReply
                    )
                }
            }

            SectionGroup {
                SectionItem(
                    position = ItemPosition.Single,
                    onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        onOpenAppFiltering()
                    }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StandardLeadingIcon {
                            Icon(
                                painter = painterResource(R.drawable.lan_24px),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        LabelGroup(
                            label = stringResource(R.string.vpn_apps_hint),
                            supportingText = stringResource(R.string.vpn_settings_apps_desc),
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
        }
    }
}
