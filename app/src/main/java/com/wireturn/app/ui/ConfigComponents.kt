package com.wireturn.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SingleChoiceSegmentedButtonRowScope
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wireturn.app.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Inline indicator for rows, usually placed after a text label.
 */
@Composable
fun InlineConfigIndicator(isModified: Boolean, modifier: Modifier = Modifier) {
    if (isModified) {
        Box(
            modifier = modifier
                .padding(start = 6.dp, end = 4.dp)
                .size(6.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        )
    }
}

/**
 * A helper to create a label row with an optional modification indicator.
 */
@Composable
fun ConfigLabelRow(isModified: Boolean, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        content()
        InlineConfigIndicator(isModified)
    }
}

@Composable
fun String.redact(enabled: Boolean): String {
    return if (enabled) stringResource(R.string.redacted_value) else this
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
fun LabeledSegmentedButton(
    label: String,
    subLabel: String,
    isModified: Boolean,
    content: @Composable SingleChoiceSegmentedButtonRowScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                InlineConfigIndicator(isModified)
            }
            Text(subLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    isModified: Boolean = false,
    enabled: Boolean = true
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                InlineConfigIndicator(isModified)
            }
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
fun FieldTrailingIcons(
    history: List<String>,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    privacyMode: Boolean,
    iconSize: Dp = 24.dp
) {
    if (history.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HistoryIconButton(
                history = history,
                onSelect = onSelect,
                onRemove = onRemove,
                privacyMode = privacyMode,
                iconSize = iconSize
            )
        }
    }
}

@Composable
fun HistoryIconButton(
    history: List<String>,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    privacyMode: Boolean,
    iconSize: Dp = 24.dp
) {
    if (history.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(if (iconSize < 24.dp) 40.dp else 48.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.history_24px),
                contentDescription = stringResource(R.string.history_label),
                modifier = Modifier.size(iconSize)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            history.forEach { historyItem ->
                DropdownMenuItem(
                    modifier = Modifier.pointerInput(historyItem) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var isLongPress = false
                            val job = scope.launch {
                                delay(1500)
                                HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                                isLongPress = true
                                HapticUtil.perform(context, HapticUtil.Pattern.ERROR)
                                onRemove(historyItem)
                            }
                            val up = waitForUpOrCancellation()
                            job.cancel()
                            if (isLongPress) {
                                up?.consume()
                            }
                        }
                    },
                    text = {
                        val text = historyItem.redact(privacyMode)
                        Text(
                            text = if (text.length > 30) {
                                text.take(21) + "..." + text.takeLast(6)
                            } else {
                                text
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Visible
                        )
                    },
                    onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                        onSelect(historyItem)
                        expanded = false
                    }
                )
            }
        }
    }
}
