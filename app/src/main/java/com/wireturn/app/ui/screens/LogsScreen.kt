@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.AppLogsState
import com.wireturn.app.LogLevel
import com.wireturn.app.R
import com.wireturn.app.ui.AppDropdownMenu
import com.wireturn.app.ui.AppTopAppBar
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.components.CoreToggleButton
import com.wireturn.app.ui.theme.ContentAlpha
import com.wireturn.app.ui.theme.extendedColorScheme
import com.wireturn.app.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onToggleCore: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val minLevel by viewModel.logsMinLevel.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    var lastLogId by remember { mutableStateOf(logs.lastOrNull()?.id) }
    var showScrollButton by remember { mutableStateOf(false) }
    // Whether new lines scroll the list to the end. Only the user's own scrolling changes it:
    // deciding from where the list sits when new lines land broke under a flood of output - the
    // layout lags the list by a frame or more, and one bad read stopped autoscroll for good.
    var followTail by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        if (logs.isNotEmpty()) {
            listState.scrollToItem(logs.lastIndex)
        }
    }

    val isAtBottom by remember {
        derivedStateOf {
            !listState.canScrollForward
        }
    }

    LaunchedEffect(listState) {
        var userScrolling = false
        launch {
            listState.interactionSource.interactions.collect {
                if (it is DragInteraction.Start) {
                    userScrolling = true
                    followTail = false
                }
            }
        }
        snapshotFlow { listState.isScrollInProgress }.collect { inProgress ->
            if (!inProgress && userScrolling) {
                userScrolling = false
                // Near the end rather than exactly at it - lines keep arriving while the fling
                // settles. layoutInfo's own item count keeps both sides from the same frame.
                val info = listState.layoutInfo
                val lastVisible = info.visibleItemsInfo.lastOrNull()
                followTail = lastVisible == null || lastVisible.index >= info.totalItemsCount - 2
            }
        }
    }

    LaunchedEffect(logs) {
        val currentLastId = logs.lastOrNull()?.id
        if (currentLastId == null) showScrollButton = false
        // Ids only grow, so a smaller or equal last id isn't new output - it's the buffer being
        // trimmed (raising the log level drops stored lines, possibly including the last one).
        if (currentLastId != null && currentLastId > (lastLogId ?: -1L)) {
            if (followTail) {
                listState.scrollToItem(logs.lastIndex)
            } else {
                showScrollButton = true
            }
        }
        lastLogId = currentLastId
    }

    LaunchedEffect(isAtBottom) {
        if (isAtBottom) {
            showScrollButton = false
            followTail = true
        }
    }

    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(topAppBarState)

    Scaffold(
        modifier = modifier,
        topBar = {
            AppTopAppBar(
                title = stringResource(R.string.logs_title),
                onBack = onBack,
                scrollBehavior = scrollBehavior,
                actions = {
                    val saveLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.CreateDocument("text/plain")
                    ) { uri ->
                        if (uri != null) {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                                        outputStream.write(logs.joinToString("\n") { it.message }.toByteArray())
                                    }
                                    withContext(Dispatchers.Main) {
                                        HapticUtil.perform(context, HapticUtil.Pattern.SUCCESS)
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        }
                    }

                    LogLevelFilterButton(
                        selected = minLevel,
                        onSelect = viewModel::setLogsMinLevel
                    )
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
                    IconButton(
                        onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
                            saveLauncher.launch("wireturn_logs_$timestamp.txt")
                        },
                        enabled = logs.isNotEmpty()
                    ) {
                        Icon(
                            painterResource(R.drawable.save_24px),
                            contentDescription = stringResource(R.string.save),
                            tint = if (logs.isNotEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.disabled)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            val profiles by viewModel.profiles.collectAsStateWithLifecycle()
            if (profiles.isNotEmpty()) {
                CoreToggleButton(
                    viewModel = viewModel,
                    modifier = Modifier.navigationBarsPadding(),
                    size = 86.dp,
                    onClick = onToggleCore,
                    isFloat = true,
                    isVisible = true
                )
            }
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
                        .widthIn(max = 840.dp)
                        .navigationBarsPadding(),
                    contentPadding = PaddingValues(bottom = 148.dp)
                ) {
                    items(logs, key = { it.id }) { entry ->
                        LogLine(entry = entry)
                    }
                }

                AnimatedVisibility(
                    // A trimmed list can end up fitting the screen with the flag still set, and
                    // then isAtBottom never changes again to clear it.
                    visible = showScrollButton && !isAtBottom,
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 100.dp)
                ) {
                    ElevatedButton(
                        onClick = {
                            followTail = true
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


private val LOG_LEVEL_OPTIONS = listOf(
    LogLevel.DEBUG to R.string.logs_level_all,
    LogLevel.INFO to R.string.logs_level_info,
    LogLevel.WARN to R.string.logs_level_warn,
    LogLevel.ERROR to R.string.logs_level_error
)

@Composable
private fun LogLevelFilterButton(
    selected: LogLevel,
    onSelect: (LogLevel) -> Unit
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = {
                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                expanded = true
            }
        ) {
            Icon(
                painterResource(R.drawable.filter_list_24px),
                contentDescription = stringResource(R.string.logs_filter),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AppDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            title = stringResource(R.string.logs_filter)
        ) {
            LOG_LEVEL_OPTIONS.forEach { (level, labelRes) ->
                DropdownMenuItem(
                    text = { Text(stringResource(labelRes)) },
                    onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        onSelect(level)
                        expanded = false
                    },
                    trailingIcon = if (level == selected) {
                        {
                            Icon(
                                painterResource(R.drawable.check_24px),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else null
                )
            }
        }
    }
}

private val SUCCESS_KEYWORDS = listOf(
    "запущен", "подключен", "success", "started", "established", "connected",
    "received handshake", "peer online", "turn allocation up"
)

@Composable
private fun LogLine(entry: AppLogsState.LogEntry) {
    val line = entry.message
    val isHeader = line.startsWith("*")
    val isInternalLog = line.startsWith("* [")
    val isSuccess = entry.level == LogLevel.INFO && line.lowercase().let { lower ->
        SUCCESS_KEYWORDS.any { lower.contains(it) }
    }

    val textColor = when {
        entry.level == LogLevel.ERROR -> MaterialTheme.colorScheme.error
        entry.level == LogLevel.WARN  -> MaterialTheme.extendedColorScheme.warning
        entry.level == LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ContentAlpha.secondary)
        isSuccess -> MaterialTheme.extendedColorScheme.success
        isHeader  -> MaterialTheme.colorScheme.primary
        else      -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val hasDot = entry.level == LogLevel.ERROR || entry.level == LogLevel.WARN ||
        (entry.level == LogLevel.INFO && (isSuccess || isHeader))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (hasDot) {
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
                        entry.level != LogLevel.DEBUG && (isHeader || isInternalLog) -> FontWeight.SemiBold
                        else -> FontWeight.Normal
                    }
                ),
                color = textColor
            )
        }
    }
}
