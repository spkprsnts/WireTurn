@file:OptIn(ExperimentalMaterial3Api::class)

package com.wireturn.app.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import com.wireturn.app.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AppExclusionTooltip(
    hintShown: Boolean,
    onHintShown: () -> Unit,
    content: @Composable () -> Unit
) {
    val tooltipState = rememberTooltipState(isPersistent = true)

    LaunchedEffect(Unit) {
        if (!hintShown) {
            delay(1000)
            launch {
                tooltipState.show()
            }
            onHintShown()
        }
    }

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            positioning = TooltipAnchorPosition.Above
        ),
        tooltip = {
            PlainTooltip(
                caretShape = TooltipDefaults.caretShape()
            ) {
                Text(
                    text = stringResource(R.string.vpn_apps_hint),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        },
        state = tooltipState,
        content = content
    )
}
