@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui.screens

import android.content.pm.ApplicationInfo
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.zIndex
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.R
import com.wireturn.app.ui.AppSnackbar
import com.wireturn.app.ui.AppTopAppBar
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.ItemPosition
import com.wireturn.app.ui.LabeledButtonGroup
import com.wireturn.app.ui.MainSwitchItem
import com.wireturn.app.ui.SectionHeader
import com.wireturn.app.ui.SectionGroup
import com.wireturn.app.ui.SectionItem
import com.wireturn.app.ui.SwitchRow
import com.wireturn.app.ui.selectableButtonItem
import com.wireturn.app.ui.showExclusiveSnackbar
import com.wireturn.app.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private object AppExceptionsDefaults {
    val IconSize = 32.dp
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
    val vpnSettings by viewModel.vpnSettings.collectAsStateWithLifecycle()
    val excludedApps = vpnSettings.excludedApps
    
    var isAppsLoading by remember { mutableStateOf(true) }
    var appList by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    
    var sortSnapshot by remember { mutableStateOf(emptySet<String>()) }
    
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
    var showMenu by remember { mutableStateOf(false) }

    var isSearchBarVisible by rememberSaveable { mutableStateOf(true) }
    var searchBarZIndex by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(expanded) {
        if (expanded) {
            searchBarZIndex = 5f
            isSearchBarVisible = true
        } else {
            delay(300)
            searchBarZIndex = 2f
        }
    }

    
    var sortedAppList by remember { mutableStateOf<List<AppInfo>>(emptyList()) }

    val updateSortedList = {
        val filtered = if (vpnSettings.hideSystemApps) {
            appList.filter { !it.isSystem || excludedApps.contains(it.packageName) }
        } else {
            appList
        }
        sortedAppList = filtered.sortedWith(
            if (vpnSettings.groupAppsByLetter) {
                compareBy<AppInfo> { it.name.firstOrNull()?.uppercaseChar() ?: '#' }
                    .thenByDescending { sortSnapshot.contains(it.packageName) }
                    .thenBy { it.name.lowercase() }
            } else {
                compareByDescending<AppInfo> { sortSnapshot.contains(it.packageName) }
                    .thenBy { it.name.lowercase() }
            }
        )
    }

    LaunchedEffect(appList, sortSnapshot, vpnSettings.hideSystemApps, excludedApps, vpnSettings.groupAppsByLetter) {
        updateSortedList()
    }

    val loadApps = suspend {
        isAppsLoading = true
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

            val filtered = if (vpnSettings.hideSystemApps) {
                result.filter { !it.isSystem || excludedApps.contains(it.packageName) }
            } else {
                result
            }
            val sorted = filtered.sortedWith(
                if (vpnSettings.groupAppsByLetter) {
                    compareBy<AppInfo> { it.name.firstOrNull()?.uppercaseChar() ?: '#' }
                        .thenByDescending { excludedApps.contains(it.packageName) }
                        .thenBy { it.name.lowercase() }
                } else {
                    compareByDescending<AppInfo> { excludedApps.contains(it.packageName) }
                        .thenBy { it.name.lowercase() }
                }
            )

            withContext(Dispatchers.Main) {
                appList = result
                sortSnapshot = excludedApps
                sortedAppList = sorted
                isAppsLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadApps()
    }

    val searchDisplayList = remember(appliedSearchQuery, expanded, appList, vpnSettings.hideSystemApps, sortSnapshot, excludedApps) {
        val filtered = if (vpnSettings.hideSystemApps) {
            appList.filter { !it.isSystem || excludedApps.contains(it.packageName) }
        } else {
            appList
        }
        
        val baseList = if (appliedSearchQuery.isBlank()) {
            filtered.filter { excludedApps.contains(it.packageName) }
        } else {
            filtered.filter {
                it.name.contains(appliedSearchQuery, ignoreCase = true) ||
                        it.packageName.contains(appliedSearchQuery, ignoreCase = true)
            }
        }

        baseList.sortedWith(
            if (vpnSettings.groupAppsByLetter) {
                compareBy<AppInfo> { it.name.firstOrNull()?.uppercaseChar() ?: '#' }
                    .thenByDescending { sortSnapshot.contains(it.packageName) }
                    .thenBy { it.name.lowercase() }
            } else {
                compareByDescending<AppInfo> { sortSnapshot.contains(it.packageName) }
                    .thenBy { it.name.lowercase() }
            }
        )
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboard.current
    val listState = rememberLazyListState()

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
    val listCopiedMsg = stringResource(R.string.list_copied)

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

                    val newlyAdded = validPackages.filter { !excludedApps.contains(it) }.toSet()

                    if (newlyAdded.isNotEmpty()) {
                        isAppsLoading = true
                        val newExcluded = excludedApps + newlyAdded
                        viewModel.saveExcludedApps(newExcluded)
                        newlyAddedPackages = newlyAdded
                        sortSnapshot = newExcluded
                        
                        // Принудительно обновляем отсортированный список перед выключением индикатора
                        updateSortedList()
                        
                        listState.animateScrollToItem(0)
                        isAppsLoading = false
                        snackbarHostState.showExclusiveSnackbar(
                            appsImportedFormat.format(newlyAdded.size)
                        )
                    } else {
                        snackbarHostState.showExclusiveSnackbar(noAppsMsg)
                    }
                }
            }
        }
    }

    val onExportToClipboard = {
        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
        val text = excludedApps.joinToString("\n")
        scope.launch {
            try {
                val clipData = android.content.ClipData.newPlainText("WireTurn Apps", text)
                clipboard.setClipEntry(ClipEntry(clipData))
                snackbarHostState.showExclusiveSnackbar(listCopiedMsg)
            } catch (_: Exception) { }
        }
    }

    val topAppBarState = rememberTopAppBarState()

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        state = topAppBarState
    )

    val searchScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (expanded) return super.onPostScroll(consumed, available, source)
                val delta = consumed.y + available.y
                if (delta < -24f && scrollBehavior.state.collapsedFraction == 1f) {
                    isSearchBarVisible = false
                } else if (delta > 12f) {
                    isSearchBarVisible = true
                }
                return super.onPostScroll(consumed, available, source)
            }
        }
    }

    LaunchedEffect(scrollBehavior.state.collapsedFraction) {
        if (scrollBehavior.state.collapsedFraction < 1f) {
            isSearchBarVisible = true
        }
    }

    // Анимируем коэффициент прогресса (0 = закрыто, 1 = открыто)
    val searchProgress by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(
            durationMillis = 300,
            easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        ),
        label = "search_expansion_progress"
    )

    val searchBarHideOffset by animateDpAsState(
        targetValue = if (isSearchBarVisible) 0.dp else (-SearchBarDefaults.InputFieldHeight - 16.dp),
        label = "search_bar_hide_offset"
    )

    var appBarHeightPx by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current

    // Превращаем пиксели в DP для использования в модификаторах
    val appBarHeightDp = with(density) { appBarHeightPx.toDp() }
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(modifier = Modifier.fillMaxSize()) {
        val screenBackgroundColor = MaterialTheme.colorScheme.background

        Scaffold(
            modifier = modifier
                .fillMaxSize()
                .nestedScroll(searchScrollConnection)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState
                ) { data ->
                    AppSnackbar(data)
                }
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = screenBackgroundColor
        ) { innerPadding ->
            val finalTopPadding by remember(appBarHeightPx, searchProgress, searchBarHideOffset) {
                derivedStateOf { (lerp(appBarHeightDp, 0.dp, searchProgress) + searchBarHideOffset).coerceAtLeast(0.dp) }
            }
            val finalHorizontalPadding by remember(searchProgress, scrollBehavior.state.collapsedFraction) {
                derivedStateOf {
                    val basePadding = lerp(16.dp, 24.dp, scrollBehavior.state.collapsedFraction)
                    lerp(basePadding, 0.dp, searchProgress)
                }
            }

            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = 840.dp)
                        .zIndex(1f)
                        .padding(top = innerPadding.calculateTopPadding() + appBarHeightDp),
                    contentPadding = PaddingValues(
                        bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues()
                            .calculateBottomPadding(),
                        top = 24.dp + SearchBarDefaults.InputFieldHeight
                    )
                ) {
                    item {
                        Column(modifier = Modifier.padding(bottom = 16.dp)) {
                            MainSwitchItem(
                                label = stringResource(R.string.filtering_enabled),
                                checked = vpnSettings.filteringEnabled,
                                onCheckedChange = {
                                    viewModel.saveVpnSettings(
                                        vpnSettings.copy(filteringEnabled = it)
                                    )
                                },
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )

                            Spacer(Modifier.height(16.dp))

                            val vpnModeInclude = stringResource(R.string.vpn_apps_inclusions)
                            val vpnModeBypass = stringResource(R.string.vpn_apps_exceptions)

                            SectionItem(
                                position = ItemPosition.Single,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            ) {
                                LabeledButtonGroup(
                                    label = stringResource(R.string.bypass_mode),
                                    supportingText = if (vpnSettings.bypassMode) stringResource(R.string.vpn_mode_bypass_desc)
                                    else stringResource(R.string.vpn_mode_include_desc)
                                ) {
                                    selectableButtonItem(
                                        selected = !vpnSettings.bypassMode,
                                        onSelect = {
                                            viewModel.saveVpnSettings(
                                                vpnSettings.copy(bypassMode = false)
                                            )
                                        },
                                        label = vpnModeInclude,
                                        index = 0,
                                        count = 2
                                    )
                                    selectableButtonItem(
                                        selected = vpnSettings.bypassMode,
                                        onSelect = {
                                            viewModel.saveVpnSettings(
                                                vpnSettings.copy(bypassMode = true)
                                            )
                                        },
                                        label = vpnModeBypass,
                                        index = 1,
                                        count = 2
                                    )
                                }
                            }

                            Spacer(Modifier.height(16.dp))

                            SectionGroup {
                                SectionItem(
                                    position = ItemPosition.Top,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    onClick = {
                                        viewModel.saveVpnSettings(
                                            vpnSettings.copy(groupAppsByLetter = !vpnSettings.groupAppsByLetter)
                                        )
                                    }
                                ) {
                                    SwitchRow(
                                        label = stringResource(R.string.group_apps_by_letter),
                                        checked = vpnSettings.groupAppsByLetter,
                                        onCheckedChange = {
                                            viewModel.saveVpnSettings(
                                                vpnSettings.copy(groupAppsByLetter = it)
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(painterResource(R.drawable.abc_24px), null)
                                        }
                                    )
                                }
                                SectionItem(
                                    position = ItemPosition.Bottom,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    onClick = {
                                        viewModel.saveVpnSettings(
                                            vpnSettings.copy(hideSystemApps = !vpnSettings.hideSystemApps)
                                        )
                                    }
                                ) {
                                    SwitchRow(
                                        label = stringResource(R.string.hide_system_apps),
                                        checked = vpnSettings.hideSystemApps,
                                        onCheckedChange = {
                                            viewModel.saveVpnSettings(
                                                vpnSettings.copy(hideSystemApps = it)
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(painterResource(R.drawable.android_24px), null)
                                        }
                                    )
                                }
                            }
                        }
                    }
                    if (isAppsLoading) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 64.dp),
                                contentAlignment = Alignment.TopCenter
                            ) {
                                LoadingIndicator(
                                    modifier = Modifier.size(64.dp)
                                )
                            }
                        }
                    } else if (sortedAppList.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 64.dp),
                                contentAlignment = Alignment.TopCenter
                            ) {
                                Text(
                                    noAppsFoundMsg,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        appListItems(
                            apps = sortedAppList,
                            excludedApps = excludedApps,
                            newlyAddedPackages = newlyAddedPackages,
                            showHeaders = vpnSettings.groupAppsByLetter,
                            onToggleExclusion = { pkg ->
                                HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                                viewModel.toggleAppExclusion(pkg)
                            }
                        )
                    }
                }

                SearchBar(
                    modifier = Modifier
                        .widthIn(max = lerp(840.dp, 2400.dp, searchProgress))
                        .fillMaxWidth()
                        .zIndex(searchBarZIndex)
                        .padding(top = finalTopPadding)
                        .padding(horizontal = finalHorizontalPadding),
                    inputField = {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            SearchBarDefaults.InputField(
                                modifier = Modifier
                                    .widthIn(max = 840.dp)
                                    .fillMaxWidth()
                                    .padding(top = statusBarPadding * searchProgress),
                                query = searchQuery,
                                onQueryChange = { searchQuery = it },
                                onSearch = {
                                    isSearching = false
                                    expanded = false
                                },
                                expanded = expanded,
                                onExpandedChange = { expanded = it },
                                placeholder = { Text(stringResource(R.string.search_apps)) },
                                leadingIcon = {
                                    if (expanded) {
                                        IconButton(onClick = { expanded = false }) {
                                            Icon(
                                                painterResource(R.drawable.arrow_back_24px),
                                                contentDescription = null
                                            )
                                        }
                                    } else {
                                        Icon(
                                            painterResource(R.drawable.search_24px),
                                            contentDescription = null
                                        )
                                    }
                                },
                                trailingIcon = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (searchQuery.isNotEmpty()) {
                                            IconButton(onClick = { searchQuery = "" }) {
                                                Icon(
                                                    painterResource(R.drawable.close_24px),
                                                    contentDescription = null
                                                )
                                            }
                                        }
                                        if (expanded) {
                                            IconButton(onClick = { onImportFromClipboard() }) {
                                                Icon(
                                                    painterResource(R.drawable.content_paste_24px),
                                                    contentDescription = null
                                                )
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
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxHeight()
                                .widthIn(max = 840.dp)
                                .imePadding(),
                            contentPadding = PaddingValues(
                                top = 16.dp,
                                bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues()
                                    .calculateBottomPadding()
                            )
                        ) {
                            if (isAppsLoading || isSearching) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 64.dp),
                                        contentAlignment = Alignment.TopCenter
                                    ) {
                                        LoadingIndicator(
                                            modifier = Modifier.size(64.dp)
                                        )
                                    }
                                }
                            } else if (searchDisplayList.isEmpty() && searchQuery.isNotBlank()) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 64.dp),
                                        contentAlignment = Alignment.TopCenter
                                    ) {
                                        Text(
                                            noAppsFoundMsg,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            } else {
                                appListItems(
                                    apps = searchDisplayList,
                                    excludedApps = excludedApps,
                                    newlyAddedPackages = newlyAddedPackages,
                                    showHeaders = false,
                                    onToggleExclusion = { pkg ->
                                        HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                                        viewModel.toggleAppExclusion(pkg)
                                    }
                                )
                            }
                        }
                    }
                }

                AppTopAppBar(
                    title = stringResource(R.string.vpn_apps_hint),
                    onBack = onBack,
                    scrollBehavior = scrollBehavior,
                    modifier = Modifier
                        .zIndex(3f)
                        .onGloballyPositioned { coordinates ->
                            appBarHeightPx = coordinates.size.height.toFloat()
                        },
                    actions = {
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(
                                    painterResource(R.drawable.more_vert_24px),
                                    contentDescription = null
                                )
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                                offset = androidx.compose.ui.unit.DpOffset(12.dp, (-16).dp),
                                containerColor = Color.Transparent,
                                shadowElevation = 0.dp,
                                tonalElevation = 0.dp,
                                shape = RectangleShape,
                                properties = androidx.compose.ui.window.PopupProperties(
                                    focusable = true
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .padding(16.dp)
                                        .widthIn(min = 240.dp)
                                ) {
                                    DropdownMenuGroup(
                                        shapes = MenuDefaults.groupShape(0, 1),
                                        shadowElevation = 4.dp,
                                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.clear_list)) },
                                            leadingIcon = {
                                                Icon(
                                                    painterResource(R.drawable.indeterminate_check_box_24px),
                                                    contentDescription = null
                                                )
                                            },
                                            onClick = {
                                                showMenu = false
                                                viewModel.saveExcludedApps(emptySet())
                                                sortSnapshot = emptySet()
                                            },
                                            shape = MenuDefaults.itemShape(0, 3).shape
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.copy_list)) },
                                            leadingIcon = {
                                                Icon(
                                                    painterResource(R.drawable.content_copy_24px),
                                                    contentDescription = null
                                                )
                                            },
                                            onClick = {
                                                showMenu = false
                                                onExportToClipboard()
                                            },
                                            shape = MenuDefaults.itemShape(1, 3).shape
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.import_from_clipboard)) },
                                            leadingIcon = {
                                                Icon(
                                                    painterResource(R.drawable.content_paste_24px),
                                                    contentDescription = null
                                                )
                                            },
                                            onClick = {
                                                showMenu = false
                                                onImportFromClipboard()
                                            },
                                            shape = MenuDefaults.itemShape(2, 3).shape
                                        )
                                    }
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}

private fun LazyListScope.appListItems(
    apps: List<AppInfo>,
    excludedApps: Set<String>,
    newlyAddedPackages: Set<String>,
    showHeaders: Boolean = true,
    onToggleExclusion: (String) -> Unit
) {
    if (showHeaders) {
        val grouped = apps.groupBy { it.name.firstOrNull()?.uppercaseChar() ?: '#' }

        grouped.forEach { (letter, groupApps) ->
            item(key = "header_$letter") {
                SectionHeader(
                    title = letter.toString(),
                    modifier = Modifier
                        .padding(start = 24.dp, top = 16.dp, bottom = 8.dp)
                )
            }
            itemsIndexed(groupApps, key = { _, app -> app.packageName }) { index, app ->
                AppListItem(
                    app = app,
                    index = index,
                    groupSize = groupApps.size,
                    excludedApps = excludedApps,
                    newlyAddedPackages = newlyAddedPackages,
                    onToggleExclusion = onToggleExclusion
                )
            }
        }
    } else {
        itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
            AppListItem(
                app = app,
                index = index,
                groupSize = apps.size,
                excludedApps = excludedApps,
                newlyAddedPackages = newlyAddedPackages,
                onToggleExclusion = onToggleExclusion
            )
        }
    }
}

@Composable
private fun AppListItem(
    app: AppInfo,
    index: Int,
    groupSize: Int,
    excludedApps: Set<String>,
    newlyAddedPackages: Set<String>,
    onToggleExclusion: (String) -> Unit
) {
    val context = LocalContext.current
    val isExcluded = excludedApps.contains(app.packageName)
    val isNewlyAdded = newlyAddedPackages.contains(app.packageName)

    val backgroundColor by animateColorAsState(
        targetValue = if (isNewlyAdded) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface,
        label = "item_bg_color"
    )

    SectionItem(
        position = when {
            groupSize == 1 -> ItemPosition.Single
            index == 0 -> ItemPosition.Top
            index == groupSize - 1 -> ItemPosition.Bottom
            else -> ItemPosition.Middle
        },
        containerColor = backgroundColor,
        onClick = { onToggleExclusion(app.packageName) },
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .padding(bottom = 2.dp)
    ) {
        var iconBitmap by remember(app.packageName) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
        LaunchedEffect(app.packageName) {
            withContext(Dispatchers.IO) {
                try {
                    val drawable = context.packageManager.getApplicationIcon(app.packageName)
                    val bitmap = drawable.toBitmap().asImageBitmap()
                    withContext(Dispatchers.Main) {
                        iconBitmap = bitmap
                    }
                } catch (_: Exception) { }
            }
        }

        SwitchRow(
            label = app.name,
            checked = isExcluded,
            onCheckedChange = { onToggleExclusion(app.packageName) },
            supportingText = app.packageName,
            useLargeIcon = true,
            leadingIcon = {
                if (iconBitmap != null) {
                    Image(
                        bitmap = iconBitmap!!,
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
            }
        )
    }
}
