package com.wireturn.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wireturn.app.R
import com.wireturn.app.domain.SubscriptionUserAgents

/** One row of the User-Agent choice: a preset's value, or the entry that opens the custom field. */
private data class UserAgentOption(val value: String?, val isCustom: Boolean = false)

/** The name of a subscription's User-Agent choice ([value] as stored, null - automatic). */
@Composable
fun subscriptionUserAgentLabel(value: String?): String =
    when (val v = SubscriptionUserAgents.normalize(value)) {
        SubscriptionUserAgents.AUTO -> stringResource(R.string.subscription_ua_auto)
        SubscriptionUserAgents.WIRETURN -> stringResource(R.string.subscription_ua_wireturn)
        SubscriptionUserAgents.QWDTT -> "qWDTT"
        SubscriptionUserAgents.CSQTT -> "CSQTT"
        SubscriptionUserAgents.TURNABLE -> "Turnable"
        SubscriptionUserAgents.FREETURN -> "FreeTurn"
        else -> v.orEmpty()
    }

/**
 * Picks the User-Agent a subscription is fetched with: the presets of [SubscriptionUserAgents],
 * or a custom one typed in. [current] is the stored value (null - automatic); [onSelect] gets the
 * new one and is expected to close the dialog.
 */
@Composable
fun SubscriptionUserAgentDialog(
    current: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
    description: String = stringResource(R.string.subscription_ua_desc)
) {
    val normalized = SubscriptionUserAgents.normalize(current)
    val isCustomCurrent = normalized !in SubscriptionUserAgents.presets
    var editingCustom by remember { mutableStateOf(false) }

    if (editingCustom) {
        CustomUserAgentDialog(
            initial = if (isCustomCurrent) normalized.orEmpty() else "",
            onConfirm = onSelect,
            onDismiss = { editingCustom = false }
        )
        return
    }

    val options = SubscriptionUserAgents.presets.map { UserAgentOption(it) } +
            UserAgentOption(null, isCustom = true)
    SelectionDialog(
        title = stringResource(R.string.subscription_user_agent),
        description = description,
        items = options,
        onSelect = { option ->
            if (option.isCustom) editingCustom = true else onSelect(option.value)
        },
        onDismiss = onDismiss,
        dismissOnSelect = false,
        isSelected = { option ->
            if (option.isCustom) isCustomCurrent else !isCustomCurrent && option.value == normalized
        }
    ) { option, _ ->
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (option.isCustom) stringResource(R.string.subscription_ua_custom)
                else subscriptionUserAgentLabel(option.value)
            )
            val supporting = when {
                option.isCustom -> if (isCustomCurrent) normalized.orEmpty()
                else stringResource(R.string.subscription_ua_custom_desc)
                option.value == SubscriptionUserAgents.AUTO -> stringResource(R.string.subscription_ua_auto_desc)
                option.value == SubscriptionUserAgents.WIRETURN -> stringResource(R.string.subscription_ua_wireturn_desc)
                else -> option.value.orEmpty()
            }
            SupportingText(
                text = supporting,
                color = LocalContentColor.current.copy(alpha = 0.75f)
            )
        }
    }
}

@Composable
private fun CustomUserAgentDialog(
    initial: String,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberTextFieldState(initial)
    val value = state.text.toString().trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.subscription_ua_custom_title)) },
        text = {
            OutlinedTextField(
                state = state,
                label = { Text(stringResource(R.string.subscription_user_agent)) },
                placeholder = { Text("v2rayNG/1.10.5") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                lineLimits = TextFieldLineLimits.SingleLine,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(SubscriptionUserAgents.normalize(value)) },
                enabled = value.isNotEmpty()
            ) { Text(stringResource(R.string.btn_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
