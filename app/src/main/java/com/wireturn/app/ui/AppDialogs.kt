@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wireturn.app.R

@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 4.dp,
        tonalElevation = 4.dp,
        shape = MaterialTheme.shapes.medium,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
        modifier = modifier
    ) {
        if (!title.isNullOrBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
        content()
    }
}

@Composable
fun <T> SelectionDialog(
    title: String,
    items: List<T>,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    dismissOnSelect: Boolean = true,
    isSelected: (T) -> Boolean,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    itemContent: @Composable (T, Boolean) -> Unit
) {
    val context = LocalContext.current

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.fillMaxWidth(0.9f)
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(
                modifier = Modifier
                    .padding(vertical = 24.dp, horizontal = 12.dp)
                    .heightIn(max = 600.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = if (description != null) 8.dp else 16.dp)
                )
                if (description != null) {
                    SupportingText(
                        text = description,
                        textAlign = TextAlign.Center,
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                            .padding(horizontal = 12.dp)
                    )
                }
                items.forEach { item ->
                    val selected = isSelected(item)
                    val shape = MaterialTheme.shapes.extraLarge

                    Surface(
                        onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            onSelect(item)
                            if (dismissOnSelect) onDismiss()
                        },
                        shape = shape,
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                    ) {
                        CompositionLocalProvider(
                            LocalContentColor provides if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                        ) {
                            ProvideTextStyle(value = MaterialTheme.typography.titleMedium) {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    itemContent(item, selected)
                                }
                            }
                        }
                    }
                }
                footer?.invoke(this)
            }
        }
    }
}

@Composable
fun ShareDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    textToShare: String,
    onShowQr: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    AppDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.show_qr_code)) },
            onClick = {
                onDismissRequest()
                onShowQr()
            },
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.qr_code_24px),
                    contentDescription = null
                )
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.share_profile)) },
            onClick = {
                onDismissRequest()
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, textToShare)
                }
                context.startActivity(Intent.createChooser(intent, null))
            },
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.share_24px),
                    contentDescription = null
                )
            }
        )
    }
}
