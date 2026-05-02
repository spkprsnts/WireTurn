@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wireturn.app.ui.screens

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.R
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.theme.extendedColorScheme
import com.wireturn.app.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@Composable
fun LogsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    var lastLogsSize by remember { mutableIntStateOf(logs.size) }
    var showScrollButton by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (logs.isNotEmpty()) {
            listState.scrollToItem(logs.lastIndex)
        }
    }

    val isAtBottom by remember {
        derivedStateOf {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisibleItem == null || lastVisibleItem.index >= listState.layoutInfo.totalItemsCount - 2
        }
    }

    LaunchedEffect(logs.size) {
        if (logs.size > lastLogsSize) {
            val wasAtBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.let {
                it.index >= lastLogsSize - 2
            } ?: true

            if (wasAtBottom) {
                listState.animateScrollToItem(logs.lastIndex)
            } else {
                showScrollButton = true
            }
        }
        lastLogsSize = logs.size
    }

    LaunchedEffect(isAtBottom) {
        if (isAtBottom) showScrollButton = false
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.logs_title)) },
                actions = {
                    var isCopied by remember { mutableStateOf(false) }
                    LaunchedEffect(isCopied) {
                        if (isCopied) {
                            kotlinx.coroutines.delay(1500)
                            isCopied = false
                        }
                    }
                    IconButton(
                        onClick = {
                            isCopied = true
                            scope.launch {
                                clipboard.setClipEntry(ClipData.newPlainText("wireturn logs", logs.joinToString("\n") { it.message }).toClipEntry())
                                HapticUtil.perform(context, HapticUtil.Pattern.SUCCESS)
                            }
                        },
                        enabled = logs.isNotEmpty()
                    ) {
                        Icon(
                            painterResource(if (isCopied) R.drawable.check_circle_24px else R.drawable.content_copy_24px),
                            contentDescription = stringResource(R.string.copy),
                            tint = when {
                                isCopied -> MaterialTheme.colorScheme.primary
                                !logs.isNotEmpty() -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    IconButton(
                        onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            viewModel.clearLogs()
                        },
                        enabled = logs.isNotEmpty()
                    ) {
                        Icon(
                            painterResource(R.drawable.delete_24px),
                            contentDescription = stringResource(R.string.clear)
                        )
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.no_logs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .fillMaxWidth()
                        .wrapContentWidth(Alignment.CenterHorizontally)
                        .widthIn(max = 840.dp),
                    contentPadding = PaddingValues(bottom = 76.dp)
                ) {
                    items(logs, key = { it.id }) { entry ->
                        LogLine(line = entry.message)
                    }
                }

                AnimatedVisibility(
                    visible = showScrollButton,
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 76.dp)
                ) {
                    ElevatedButton(
                        onClick = {
                            scope.launch {
                                if (logs.isNotEmpty()) {
                                    listState.animateScrollToItem(logs.lastIndex)
                                }
                            }
                        },
                        colors = ButtonDefaults.elevatedButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        elevation = ButtonDefaults.elevatedButtonElevation(defaultElevation = 4.dp)
                    ) {
                        Icon(
                            painterResource(R.drawable.arrow_downward_24px),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.new_logs))
                    }
                }
            }
        }
    }
}

@Composable
private fun LogLine(line: String) {
    val lower = line.lowercase()
    val isHeader = line.startsWith("===")
    val isXrayLog = line.startsWith("[xray]") || line.startsWith("[vpn]")
    val isError = lower.contains("ошибка") || lower.contains("error") ||
                  lower.contains("критическая") || lower.contains("failed") ||
                  lower.contains("fatal") || lower.contains("panic") ||
                  lower.contains("did not complete")
    val isWarning = lower.contains("watchdog") || lower.contains("перезапуск") ||
                    lower.contains("quota") || lower.contains("warn") ||
                    lower.contains(">>>") || lower.contains("stopped")
    val isSuccess = lower.contains("запущен") || lower.contains("подключен") ||
                    lower.contains("success") || lower.contains("started") ||
                    lower.contains("ok") || lower.contains("established") ||
                    lower.contains("received handshake") || lower.contains("connected") ||
                    lower.contains("peer online")

    val textColor = when {
        isError   -> MaterialTheme.colorScheme.error
        isWarning -> MaterialTheme.extendedColorScheme.warning
        isSuccess -> MaterialTheme.extendedColorScheme.success
        isHeader  -> MaterialTheme.colorScheme.primary
        else      -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (isHeader || isError || isWarning || isSuccess) {
            Box(
                modifier = Modifier
                    .padding(top = 5.dp, end = 6.dp)
                    .size(5.dp)
                    .background(textColor, CircleShape)
            )
        } else {
            Spacer(Modifier.width(11.dp))
        }
        SelectionContainer {
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = when {
                        isHeader || isXrayLog -> FontWeight.SemiBold
                        else -> FontWeight.Normal
                    }
                ),
                color = textColor
            )
        }
    }
}

