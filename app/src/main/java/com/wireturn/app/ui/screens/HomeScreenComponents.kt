@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wireturn.app.CaptchaSession
import com.wireturn.app.R
import com.wireturn.app.domain.LocalAddresses
import com.wireturn.app.domain.LocalInterfaceKind
import com.wireturn.app.ui.ItemPosition
import com.wireturn.app.ui.LabelGroup
import com.wireturn.app.ui.LabelGroupDefaults
import com.wireturn.app.ui.ModifiedIndicator
import com.wireturn.app.ui.SectionItem
import com.wireturn.app.ui.SelectionDialog
import com.wireturn.app.ui.StandardLeadingIcon
import com.wireturn.app.ui.UpdateBlock
import com.wireturn.app.ui.privacySpoiler
import com.wireturn.app.ui.redact
import com.wireturn.app.viewmodel.UpdateState
import com.wireturn.app.viewmodel.isImportant
import kotlin.math.ln
import kotlin.math.pow

// --- Update Banner Component ---
@Composable
internal fun UpdateBanner(
    state: UpdateState,
    progress: Int,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCheck: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest
) {
    AnimatedVisibility(
        visible = state.isImportant,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        SectionItem(
            position = ItemPosition.Single,
            containerColor = containerColor,
            modifier = Modifier.padding(bottom = 24.dp)
        ) {
            UpdateBlock(
                state = state,
                progress = progress,
                showChangelog = false,
                onDownload = onDownload,
                onInstall = onInstall,
                onCheck = onCheck
            )
        }
    }
}

// --- Captcha Card Component ---
@Composable
internal fun CaptchaCard(
    session: CaptchaSession?,
    onSolve: (url: String) -> Unit
) {
    // Keeps the content through the exit animation once the session is gone.
    var lastSession by remember { mutableStateOf(session) }
    if (session != null) lastSession = session
    val shown = session ?: lastSession

    AnimatedVisibility(
        visible = session != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        val partial = shown?.partial == true
        val containerColor = if (partial) MaterialTheme.colorScheme.tertiaryContainer
            else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
        val contentColor = if (partial) MaterialTheme.colorScheme.onTertiaryContainer
            else MaterialTheme.colorScheme.onErrorContainer
        SectionItem(
            position = ItemPosition.Single,
            containerColor = containerColor,
            onClick = { shown?.let { onSolve(it.url) } },
            modifier = Modifier.padding(top = 16.dp)
        ) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StandardLeadingIcon {
                        Icon(
                            painter = painterResource(R.drawable.smart_toy_24px),
                            contentDescription = null,
                            tint = contentColor
                        )
                    }
                    LabelGroup(
                        label = stringResource(R.string.core_captcha_required),
                        supportingText = stringResource(if (partial) R.string.captcha_partial_card_desc else R.string.captcha_card_desc),
                        labelColor = contentColor,
                        supportingColor = contentColor,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = { shown?.let { onSolve(it.url) } },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (partial) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                            contentColor = if (partial) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onError
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(
                            stringResource(R.string.captcha_card_action),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}

// --- Utils ---
internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    if (bytes < 1024) return "$bytes B"
    val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt()
    val pre = "KMGTPE"[exp - 1]
    return String.format(java.util.Locale.US, "%.1f %siB", bytes / 1024.0.pow(exp.toDouble()), pre)
}


internal fun formatSpeed(bytesPerSecond: Long): String {
    return formatBytes(bytesPerSecond) + "/s"
}

@Composable
internal fun ProxyAddressRow(
    label: String,
    address: String,
    isModified: Boolean,
    isCopied: Boolean,
    leadingIcon: @Composable (() -> Unit)? = null,
    privacyMode: Boolean = false
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            StandardLeadingIcon(content = leadingIcon)
        }

        Column(modifier = Modifier.weight(1f)) {
            val displayText = if (privacyMode) {
                if (address.isBlank()) " ".repeat(25) else address.redact(true)
            } else {
                address.ifBlank { stringResource(R.string.dash) }
            }
            Text(
                displayText,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (address.isBlank() && !privacyMode) MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = 0.5f
                )
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.privacySpoiler(privacyMode)
            )
            Spacer(Modifier.height(LabelGroupDefaults.SupportingGap))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ModifiedIndicator(isModified)
            }
        }
        Icon(
            painter = painterResource(if (isCopied) R.drawable.check_circle_24px else R.drawable.content_copy_24px),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (isCopied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

internal fun isWildcardAddress(bindAddress: String) = bindAddress.startsWith("0.0.0.0:")

/** A copy of a proxy address bound to 0.0.0.0, waiting for the user to pick the real host. */
internal class WildcardCopy(val clipLabel: String, val address: String, val onCopied: () -> Unit) {
    // Only the host part of "[user:pass@]0.0.0.0:port" - credentials and port stay as they are.
    fun withHost(ip: String): String = address.replace(WILDCARD_HOST, "$1$ip:")

    private companion object {
        val WILDCARD_HOST = Regex("""(^|@)0\.0\.0\.0:""")
    }
}

// String resources, not resolved text - resolved at render time, so they follow the configuration.
private class AddressChoice(
    val labelRes: Int,
    val interfaceName: String?,
    val ip: String,
    val iconRes: Int,
    val hintRes: Int?
)

@Composable
internal fun LocalAddressPickerDialog(
    port: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // Read once when the dialog opens, so a hotspot switched on just before shows up.
    val choices = remember {
        listOf(
            AddressChoice(R.string.local_address_this_device, null, "127.0.0.1", R.drawable.mobile_24px, null)
        ) + LocalAddresses.list().map { address ->
            val (labelRes, iconRes) = when (address.kind) {
                LocalInterfaceKind.WIFI -> R.string.local_address_wifi to R.drawable.wifi_24px
                LocalInterfaceKind.HOTSPOT -> R.string.local_address_hotspot to R.drawable.wifi_tethering_24px
                LocalInterfaceKind.USB -> R.string.local_address_usb to R.drawable.usb_24px
                LocalInterfaceKind.BLUETOOTH -> R.string.local_address_bluetooth to R.drawable.bluetooth_24px
                LocalInterfaceKind.ETHERNET -> R.string.local_address_ethernet to R.drawable.ethernet_24px
                LocalInterfaceKind.MOBILE -> R.string.local_address_mobile to R.drawable.signal_cellular_24px
                LocalInterfaceKind.OTHER -> R.string.local_address_other to R.drawable.lan_24px
            }
            AddressChoice(
                labelRes, address.interfaceName, address.ip, iconRes,
                R.string.local_address_mobile_hint.takeIf { address.kind == LocalInterfaceKind.MOBILE }
            )
        }
    }

    SelectionDialog(
        title = stringResource(R.string.local_address_title),
        description = stringResource(R.string.local_address_desc),
        items = choices,
        isSelected = { false },
        onSelect = { onPick(it.ip) },
        onDismiss = onDismiss
    ) { choice, _ ->
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StandardLeadingIcon {
                Icon(painter = painterResource(choice.iconRes), contentDescription = null)
            }
            Column(modifier = Modifier.weight(1f)) {
                val label = stringResource(choice.labelRes)
                Text(text = choice.interfaceName?.let { "$label · $it" } ?: label)
                Text(
                    text = "${choice.ip}:$port",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                choice.hintRes?.let {
                    Text(
                        text = stringResource(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
