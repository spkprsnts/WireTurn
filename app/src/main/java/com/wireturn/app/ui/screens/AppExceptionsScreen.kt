@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui.screens

import android.content.pm.ApplicationInfo
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import kotlin.math.abs
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.R
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private object AppExceptionsDefaults {
    val AppBarMaxHeight = 152.dp
    val AppBarCollapsedHeight = 64.dp
    val SearchBarHeight = 72.dp
    val SearchBarGap = 8.dp
    val HorizontalPadding = 16.dp
    val IconSize = 40.dp
}

data class AppInfo(
    val packageName: String,
    val name: String,
    val isSystem: Boolean
)

@Composable
fun AppExceptionsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val excludedApps by viewModel.excludedApps.collectAsStateWithLifecycle()
    
    var isAppsLoading by remember { mutableStateOf(true) }
    var appList by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    
    // Дебаунс поискового запроса
    var appliedSearchQuery by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            appliedSearchQuery = ""
            isSearching = false
        } else if (searchQuery != appliedSearchQuery) {
            isSearching = true
            delay(700)
            appliedSearchQuery = searchQuery
            isSearching = false
        }
    }

    var expanded by rememberSaveable { mutableStateOf(false) }
    // Задержка для предотвращения перекрытия TopAppBar анимации SearchBar
    var showTopBarOnTop by remember { mutableStateOf(!expanded) }
    LaunchedEffect(expanded) {
        if (expanded) {
            showTopBarOnTop = false
        } else {
            delay(400)
            showTopBarOnTop = true
        }
    }
    
    // Загрузка списка приложений
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(0)
            val result = apps.map { app ->
                AppInfo(
                    packageName = app.packageName,
                    name = pm.getApplicationLabel(app).toString(),
                    isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }.filter { it.name.isNotBlank() && it.packageName != context.packageName }
            
            withContext(Dispatchers.Main) {
                appList = result
                isAppsLoading = false
            }
        }
    }

    // Сортировка: исключенные приложения всегда сверху, далее по алфавиту
    // excludedApps намеренно исключен из ключей для предотвращения "прыжков" списка при кликах
    val sortedAppList = remember(appliedSearchQuery, appList) {
        appList.sortedWith(
            compareByDescending<AppInfo> { excludedApps.contains(it.packageName) }
                .thenBy { it.name.lowercase() }
        )
    }

    val mainDisplayList = remember(appliedSearchQuery, sortedAppList) {
        if (appliedSearchQuery.isBlank()) sortedAppList
        else sortedAppList.filter {
            it.name.contains(appliedSearchQuery, ignoreCase = true) ||
                    it.packageName.contains(appliedSearchQuery, ignoreCase = true)
        }
    }

    // Список для режима поиска: исключенные (если пусто) или результаты поиска
    val searchDisplayList = remember(appliedSearchQuery, expanded, appList) {
        if (appliedSearchQuery.isBlank()) {
            appList.filter { excludedApps.contains(it.packageName) }
                .sortedBy { it.name.lowercase() }
        } else {
            appList.filter {
                it.name.contains(appliedSearchQuery, ignoreCase = true) ||
                        it.packageName.contains(appliedSearchQuery, ignoreCase = true)
            }.sortedBy { it.name.lowercase() }
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboard.current
    val listState = rememberLazyListState()

    // Временная подсветка импортированных приложений
    var newlyAddedPackages by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(newlyAddedPackages) {
        if (newlyAddedPackages.isNotEmpty()) {
            delay(1500)
            newlyAddedPackages = emptySet()
        }
    }

    val noAppsMsg = stringResource(R.string.no_apps_imported)
    val noAppsFoundMsg = stringResource(R.string.no_apps_found)
    val appsImportedFormat = stringResource(R.string.apps_imported_count)

    val onImportFromClipboard = {
        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
        scope.launch {
            val clipData = clipboard.getClipEntry()?.clipData
            if (clipData != null && clipData.itemCount > 0) {
                val text = clipData.getItemAt(0).text?.toString() ?: ""
                val packagesToImport = text.split(Regex("[\\s,;|]+"))
                    .map { it.trim() }
                    .filter { it.isNotBlank() }

                if (packagesToImport.isNotEmpty()) {
                    val allPackages = appList.map { it.packageName }.toSet()
                    val validPackages = packagesToImport.filter { allPackages.contains(it) }.toSet()

                    if (validPackages.isNotEmpty()) {
                        val currentExcluded = excludedApps
                        val newExcluded = currentExcluded + validPackages
                        if (newExcluded != currentExcluded) {
                            viewModel.saveExcludedApps(newExcluded)
                            newlyAddedPackages = validPackages
                            listState.animateScrollToItem(0)
                        }
                        snackbarHostState.showSnackbar(
                            appsImportedFormat.format(validPackages.size)
                        )
                    } else {
                        snackbarHostState.showSnackbar(noAppsMsg)
                    }
                }
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val appBarExpandableHeight = AppExceptionsDefaults.AppBarMaxHeight - AppExceptionsDefaults.AppBarCollapsedHeight
    val totalHeaderAreaHeight = appBarExpandableHeight + AppExceptionsDefaults.SearchBarHeight + (AppExceptionsDefaults.SearchBarGap * 2)

    val topBarCollapseLimitPx = with(density) { appBarExpandableHeight.toPx() }
    val totalHideLimitPx = with(density) { totalHeaderAreaHeight.toPx() }

    var enterAlwaysOffsetPx by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var lastScrollDirection by remember { mutableFloatStateOf(0f) }
    var isSnapAnimating by remember { mutableStateOf(false) }

    val isScrollable by remember {
        derivedStateOf {
            listState.canScrollForward || listState.canScrollBackward
        }
    }

    val isAtTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    val updateHeaderOffset = { delta: Float ->
        val maxOffset = if (delta < 0f || isAtTop) 0f else -topBarCollapseLimitPx
        enterAlwaysOffsetPx = (enterAlwaysOffsetPx + delta).coerceIn(-totalHideLimitPx, maxOffset)
    }

    val customNestedScrollConnection = remember {
        object : NestedScrollConnection {
            @Suppress("MethodAlwaysReturnsConstant", "SameReturnValue")
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (expanded || !isScrollable) return Offset.Zero

                if (source == NestedScrollSource.UserInput) {
                    isDragging = true
                    if (available.y != 0f) lastScrollDirection = available.y
                    updateHeaderOffset(available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                isDragging = false
                return Velocity.Zero
            }

            @Suppress("MethodAlwaysReturnsConstant", "SameReturnValue")
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (!isScrollable) return Offset.Zero
                scrollBehavior.state.contentOffset += consumed.y
                
                if (!expanded && !isDragging && !isSnapAnimating) {
                    updateHeaderOffset(consumed.y)
                }

                if (!expanded && available.y > 0f && isAtTop && listState.firstVisibleItemScrollOffset == 0) {
                    enterAlwaysOffsetPx = 0f
                }
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(isScrollable, expanded) {
        if (!isScrollable && !expanded) {
            enterAlwaysOffsetPx = 0f
        }
    }

    // Автоматическая доводка (Snap) заголовка
    LaunchedEffect(isDragging) {
        if (isDragging || expanded) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress }.first { !it }

        val isAtTopPos = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        val snapForwardThreshold = topBarCollapseLimitPx * 0.3f
        val target = when {
            enterAlwaysOffsetPx > -snapForwardThreshold -> 0f
            enterAlwaysOffsetPx > -topBarCollapseLimitPx -> if (isAtTopPos && lastScrollDirection >= 0f) 0f else -topBarCollapseLimitPx
            else -> {
                val distToVisible = abs(enterAlwaysOffsetPx - (-topBarCollapseLimitPx))
                val distToHidden = abs(enterAlwaysOffsetPx - (-totalHideLimitPx))
                if (distToVisible <= distToHidden) -topBarCollapseLimitPx else -totalHideLimitPx
            }
        }

        if (abs(enterAlwaysOffsetPx - target) < 0.5f) return@LaunchedEffect

        isSnapAnimating = true
        try {
            val anim = Animatable(enterAlwaysOffsetPx)
            var prevValue = enterAlwaysOffsetPx

            val animJob = launch {
                anim.animateTo(target, spring(stiffness = Spring.StiffnessMedium))
            }
            val collectJob = launch {
                snapshotFlow { anim.value }.collect { newValue ->
                    val delta = newValue - prevValue
                    prevValue = newValue
                    enterAlwaysOffsetPx = newValue
                    if (abs(delta) > 0.01f) {
                        listState.scroll { scrollBy(-delta) }
                    }
                }
            }
            animJob.join()
            collectJob.cancel()
        } finally {
            isSnapAnimating = false
        }
    }

    val animatedOffsetPx by animateFloatAsState(
        targetValue = enterAlwaysOffsetPx,
        animationSpec = if (isDragging || listState.isScrollInProgress || isSnapAnimating) snap() else spring(stiffness = Spring.StiffnessMedium),
        label = "animated_offset"
    )

    val headerHeightOffsetPx = if (isAtTop) {
        animatedOffsetPx
    } else {
        animatedOffsetPx.coerceAtMost(-topBarCollapseLimitPx)
    }

    LaunchedEffect(headerHeightOffsetPx) {
        if (!expanded) {
            scrollBehavior.state.heightOffset = headerHeightOffsetPx.coerceIn(-topBarCollapseLimitPx, 0f)
        }
    }

    val expansionFraction by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "expansion_fraction"
    )

    val searchBarOffsetPx = if (isAtTop) animatedOffsetPx
                            else animatedOffsetPx.coerceAtMost(-topBarCollapseLimitPx)
    
    val searchBarY = (statusBarHeight + AppExceptionsDefaults.AppBarCollapsedHeight + 
                     with(density) { searchBarOffsetPx.toDp() } + 
                     appBarExpandableHeight + AppExceptionsDefaults.SearchBarGap) * (1f - expansionFraction)
    
    val horizontalPadding = AppExceptionsDefaults.HorizontalPadding * (1f - expansionFraction)

    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(customNestedScrollConnection),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { innerPadding ->
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = statusBarHeight + AppExceptionsDefaults.AppBarCollapsedHeight,
                    bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + innerPadding.calculateBottomPadding()
                )
            ) {
                item(key = "header_spacer") {
                    Spacer(Modifier.height(totalHeaderAreaHeight))
                }

                if (isAppsLoading) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(top = 64.dp), contentAlignment = Alignment.TopCenter) {
                            CircularWavyProgressIndicator()
                        }
                    }
                } else if (mainDisplayList.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(top = 64.dp), contentAlignment = Alignment.TopCenter) {
                            Text(noAppsFoundMsg, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    appListItems(
                        apps = mainDisplayList,
                        excludedApps = excludedApps,
                        newlyAddedPackages = newlyAddedPackages,
                        onToggleExclusion = { pkg ->
                            HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                            viewModel.toggleAppExclusion(pkg)
                        }
                    )
                }
            }
        }

        SearchBar(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .zIndex(2f)
                .offset { IntOffset(0, searchBarY.roundToPx()) }
                .padding(horizontal = horizontalPadding),
            inputField = {
                Column(modifier = Modifier.fillMaxWidth().then(if (expanded) Modifier.statusBarsPadding() else Modifier)) {
                    SearchBarDefaults.InputField(
                        modifier = Modifier.fillMaxWidth(),
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onSearch = { 
                            appliedSearchQuery = searchQuery
                            isSearching = false
                            expanded = false 
                        },
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                        placeholder = { Text(stringResource(R.string.search_apps)) },
                        leadingIcon = {
                            if (expanded) {
                                IconButton(onClick = { expanded = false }) {
                                    Icon(painterResource(R.drawable.arrow_back_24px), contentDescription = null)
                                }
                            } else {
                                Icon(painterResource(R.drawable.search_24px), contentDescription = null)
                            }
                        },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(painterResource(R.drawable.close_24px), contentDescription = null)
                                    }
                                }
                                if (expanded) {
                                    IconButton(onClick = { onImportFromClipboard() }) {
                                        Icon(painterResource(R.drawable.content_paste_24px), contentDescription = null)
                                    }
                                }
                            }
                        },
                    )
                }
            },
            expanded = expanded,
            onExpandedChange = { expanded = it },
            windowInsets = WindowInsets(0, 0, 0, 0)
        ) {
            if (isSearching) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 64.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    CircularWavyProgressIndicator()
                }
            } else if (searchDisplayList.isEmpty() && searchQuery.isNotBlank()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 64.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Text(noAppsFoundMsg, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    )
                ) {
                    appListItems(
                        apps = searchDisplayList,
                        excludedApps = excludedApps,
                        newlyAddedPackages = newlyAddedPackages,
                        onToggleExclusion = { pkg ->
                            HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                            viewModel.toggleAppExclusion(pkg)
                        }
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .zIndex(if (showTopBarOnTop) 3f else 1f)
        ) {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.vpn_apps_exceptions)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.arrow_back_24px), contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { onImportFromClipboard() }) {
                        Icon(painterResource(R.drawable.content_paste_24px), contentDescription = null)
                    }
                },
                scrollBehavior = if (isScrollable) scrollBehavior else null,
                windowInsets = TopAppBarDefaults.windowInsets,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    }
}

private fun LazyListScope.appListItems(
    apps: List<AppInfo>,
    excludedApps: Set<String>,
    newlyAddedPackages: Set<String>,
    onToggleExclusion: (String) -> Unit
) {
    items(apps, key = { it.packageName }) { app ->
        val context = LocalContext.current
        val isExcluded = excludedApps.contains(app.packageName)
        val isNewlyAdded = newlyAddedPackages.contains(app.packageName)

        val backgroundColor by animateColorAsState(
            targetValue = when {
                isNewlyAdded -> MaterialTheme.colorScheme.primaryContainer
                isExcluded -> MaterialTheme.colorScheme.surfaceContainerHigh
                else -> Color.Transparent
            },
            label = "item_bg_color"
        )

        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .animateItem(),
            colors = ListItemDefaults.colors(containerColor = backgroundColor),
            headlineContent = {
                Text(
                    app.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (isExcluded || isNewlyAdded) FontWeight.SemiBold else FontWeight.Normal
                )
            },
            supportingContent = {
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            },
            leadingContent = {
                val icon = remember(app.packageName) {
                    try {
                        context.packageManager.getApplicationIcon(app.packageName)
                            .toBitmap().asImageBitmap()
                    } catch (_: Exception) { null }
                }
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = null,
                        modifier = Modifier.size(AppExceptionsDefaults.IconSize)
                    )
                } else {
                    Icon(
                        painterResource(R.drawable.mobile_24px),
                        null,
                        modifier = Modifier.size(AppExceptionsDefaults.IconSize),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            },
            trailingContent = {
                Switch(
                    checked = isExcluded,
                    onCheckedChange = { onToggleExclusion(app.packageName) }
                )
            }
        )
    }
}
