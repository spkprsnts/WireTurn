@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.wireturn.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.R
import com.wireturn.app.data.KernelVariant
import com.wireturn.app.data.OlcrtcConfig.Companion.getTransportDisplayName
import com.wireturn.app.data.Profile
import com.wireturn.app.data.XrayConfiguration
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.AppDropdownMenu
import com.wireturn.app.ui.LargeLeadingIcon
import com.wireturn.app.ui.StandardLeadingIcon
import com.wireturn.app.ui.VerticalAnimatedText
import com.wireturn.app.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ProfileSummary(
    profile: Profile,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    useAnimation: Boolean = false
) {
    val parts = mutableListOf<String>()

    // Core mode
    parts.add(
        stringResource(
            when (profile.kernelVariant) {
                KernelVariant.TURNABLE -> R.string.kernel_turnable
                KernelVariant.OLCRTC -> R.string.kernel_olcrtc
            }
        )
    )

    when (profile.kernelVariant) {
        KernelVariant.TURNABLE -> {
            val config = profile.turnableConfig
            val route = config.routes.find { it.routeId == config.selectedRouteId }
                ?: config.routes.firstOrNull()
            val routeName = route?.name?.ifBlank { route.routeId } ?: config.selectedRouteId
            parts.add("r:$routeName")
            parts.add(config.platformDisplayName)
        }

        KernelVariant.OLCRTC -> {
            val config = profile.olcrtcConfig
            parts.add(getTransportDisplayName(config.transport, short = true))
            parts.add(config.carrierDisplayName)
        }
    }



    if (profile.xrayEnabled) {
        val isValid = when (profile.xrayProtocol) {
            XrayConfiguration.VLESS -> profile.vlessConfig.isValid()
            XrayConfiguration.WIREGUARD -> profile.wgConfig.isValid()
        }

        if (isValid) {
            parts.add(
                when (profile.xrayProtocol) {
                    XrayConfiguration.VLESS -> stringResource(R.string.vless)
                    XrayConfiguration.WIREGUARD -> stringResource(R.string.wg_short)
                }
            )
            if (profile.xrayProtocol == XrayConfiguration.VLESS && profile.vlessConfig.isDualRoute) {
                parts.add(stringResource(R.string.vless_dual_route_short))
            }
        }
    }

    if (parts.isNotEmpty()) {
        val text = parts.joinToString(" • ")
        if (useAnimation) {
            VerticalAnimatedText(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = color,
                maxLines = 1,
                softWrap = false,
                modifier = modifier
            )
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                maxLines = 1,
                softWrap = false,
                modifier = modifier
            )
        }
    }
}

@Composable
fun ProfilesBlock(
    viewModel: MainViewModel,
    onImport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val currentId by viewModel.currentProfileId.collectAsStateWithLifecycle()
    val currentProfile = profiles.find { it.id == currentId } ?: profiles.firstOrNull()
    val context = LocalContext.current

    if (currentProfile != null) {
        Row(
            modifier = modifier
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LargeLeadingIcon {
                Icon(
                    painter = painterResource(getProfileIcon(currentProfile, outlined = false)),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                VerticalAnimatedText(
                    text = currentProfile.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .basicMarquee()
                )
                ProfileSummary(
                    profile = currentProfile,
                    modifier = Modifier
                        .fillMaxWidth()
                        .basicMarquee(),
                    useAnimation = true
                )
            }
            FilledTonalIconButton(onClick = {
                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                val intent = when (currentProfile.kernelVariant) {
                    KernelVariant.TURNABLE -> android.content.Intent(
                        context,
                        com.wireturn.app.ui.activities.TurnableConfigActivity::class.java
                    )

                    KernelVariant.OLCRTC -> android.content.Intent(
                        context,
                        com.wireturn.app.ui.activities.OlcRtcConfigActivity::class.java
                    )
                }
                intent.putExtra("EXTRA_EDIT_MODE", true)
                context.startActivity(intent)
            }) {
                Icon(
                    painter = painterResource(R.drawable.edit_square_24px),
                    contentDescription = null
                )
            }
        }
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LargeLeadingIcon {
                Icon(
                    painter = painterResource(R.drawable.mobile_outlined_24px),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.profile_none_selected),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconButton(onClick = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    onImport()
                }) {
                    Icon(
                        painter = painterResource(R.drawable.file_open_24px),
                        contentDescription = stringResource(R.string.profile_import)
                    )
                }
                FilledTonalIconButton(onClick = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    context.startActivity(
                        android.content.Intent(
                            context,
                            com.wireturn.app.ui.activities.CreateProfileActivity::class.java
                        )
                    )
                }) {
                    Icon(
                        painter = painterResource(R.drawable.add_24px),
                        contentDescription = stringResource(R.string.profile_create)
                    )
                }
            }
        }
    }
}

@Composable
fun ProfileListItem(
    profile: Profile,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    isDragged: Boolean = false,
    isHighlighted: Boolean = false,
    trailingContent: @Composable (() -> Unit)? = null,
    leadingContent: @Composable (() -> Unit)? = null
) {
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isDragged -> MaterialTheme.colorScheme.surfaceContainerHighest
            isSelected -> MaterialTheme.colorScheme.secondaryContainer
            isHighlighted -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        },
        animationSpec = tween(durationMillis = if (isHighlighted) 200 else 1000),
        label = "profile_item_bg"
    )

    Surface(
        onClick = onClick,
        shape = shape,
        color = backgroundColor,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 74.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingContent != null) {
                StandardLeadingIcon(content = leadingContent)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee()
                )
                ProfileSummary(
                    profile = profile,
                    color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.basicMarquee()
                )
            }
            if (trailingContent != null) {
                Spacer(Modifier.width(12.dp))
                trailingContent()
            }
        }
    }
}

@Composable
fun ProfilesDialog(
    viewModel: MainViewModel,
    onImport: () -> Unit,
    onDismiss: () -> Unit
) {
    val profilesSource by viewModel.profiles.collectAsStateWithLifecycle()
    val currentId by viewModel.currentProfileId.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current

    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    val scope = rememberCoroutineScope()

    // Local state for reordering
    val profiles = remember { mutableStateListOf<Profile>() }

    val lazyListState = rememberLazyListState()
    var draggedItemId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var autoScrollSpeed by remember { mutableFloatStateOf(0f) }
    var optimisticSelectedId by remember { mutableStateOf<String?>(null) }

    var addMenuExpanded by remember { mutableStateOf(false) }
    val showRenameDialog = remember { mutableStateOf<Profile?>(null) }
    val showDeleteConfirm = remember { mutableStateOf<Profile?>(null) }
    val showBulkDeleteConfirm = remember { mutableStateOf<List<String>?>(null) }
    val showCloneDialog = remember { mutableStateOf<Profile?>(null) }

    val selectedIds = remember { mutableStateListOf<String>() }
    val isSelectionMode = selectedIds.isNotEmpty()

    val jsonToExport = remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { destination ->
            jsonToExport.value?.let { json ->
                try {
                    context.contentResolver.openOutputStream(destination)?.use {
                        it.write(json.toByteArray())
                    }
                } catch (_: Exception) {
                }
                jsonToExport.value = null
            }
        }
    }

    val zipToExport = remember { mutableStateOf<ByteArray?>(null) }
    val zipExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { destination ->
            zipToExport.value?.let { bytes ->
                try {
                    context.contentResolver.openOutputStream(destination)?.use {
                        it.write(bytes)
                    }
                } catch (_: Exception) {
                }
                zipToExport.value = null
            }
        }
    }

    val highlightedIds = remember { mutableStateListOf<String>() }

    // Sync local list with source of truth only when NOT dragging
    LaunchedEffect(profilesSource) {
        val oldSize = profiles.size
        val oldIds = profiles.map { it.id }.toSet()
        val isFirstLoad = oldSize == 0 && profilesSource.isNotEmpty()

        if (draggedItemId == null && (profiles.size != profilesSource.size || profiles.toList() != profilesSource)) {
            androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                profiles.clear()
                profiles.addAll(profilesSource)
            }

            if (oldSize > 0) {
                val newIds = profilesSource.map { it.id }.filter { it !in oldIds }
                if (newIds.isNotEmpty()) {
                    highlightedIds.addAll(newIds)
                    scope.launch {
                        delay(1000)
                        highlightedIds.removeAll(newIds)
                    }
                }
            }

            // Scroll logic
            scope.launch {
                delay(150) // Wait for layout update
                if (isFirstLoad) {
                    val currentIndex = profiles.indexOfFirst { it.id == currentId }
                    if (currentIndex != -1) {
                        lazyListState.scrollToItem(currentIndex)
                    }
                } else if (profilesSource.size > oldSize && oldSize > 0) {
                    if (oldSize < profiles.size) {
                        lazyListState.animateScrollToItem(oldSize)
                    }
                }
            }
        }
    }

    fun checkAndPerformReorder() {
        val currentId = draggedItemId ?: return
        val currentDraggedIdx =
            profiles.indexOfFirst { it.id == currentId }.takeIf { it != -1 } ?: return

        val layoutInfo = lazyListState.layoutInfo
        val visibleItems = layoutInfo.visibleItemsInfo
        val draggedItemInfo = visibleItems.find { it.key == currentId } ?: return

        val draggedVisualCenter = draggedItemInfo.offset + dragOffset + draggedItemInfo.size / 2

        val targetItem = if (dragOffset > 0) {
            visibleItems.find { it.index > currentDraggedIdx && (it.offset + it.size / 2) < draggedVisualCenter }
        } else if (dragOffset < 0) {
            visibleItems.findLast { it.index < currentDraggedIdx && (it.offset + it.size / 2) > draggedVisualCenter }
        } else null

        if (targetItem != null) {
            val targetIndex = targetItem.index
            val delta = (draggedItemInfo.offset - targetItem.offset).toFloat()

            // Захватываем параметры скролла
            val firstVisibleIndex = lazyListState.firstVisibleItemIndex
            val firstVisibleOffset = lazyListState.firstVisibleItemScrollOffset

            androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                val item = profiles.removeAt(currentDraggedIdx)
                profiles.add(targetIndex, item)
                dragOffset += delta
            }

            // Компенсируем прыжок скролла
            if (currentDraggedIdx == firstVisibleIndex || targetIndex == firstVisibleIndex) {
                scope.launch {
                    lazyListState.scrollToItem(firstVisibleIndex, firstVisibleOffset)
                }
            }
            HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
        }
    }

    fun updateAutoScrollSpeed() {
        val currentId = draggedItemId ?: return
        val layoutInfo = lazyListState.layoutInfo
        val visibleItems = layoutInfo.visibleItemsInfo
        val draggedItem = visibleItems.find { it.key == currentId }

        val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
        val threshold = with(density) { 40.dp.toPx() } // Увеличили порог для стабильности

        if (draggedItem != null) {
            val visualTop = draggedItem.offset + dragOffset
            val visualBottom = visualTop + draggedItem.size

            autoScrollSpeed = when {
                visualTop < threshold && lazyListState.canScrollBackward -> {
                    ((visualTop - threshold) / 10f).coerceIn(-20f, 0f)
                }

                visualBottom > viewportHeight - threshold && lazyListState.canScrollForward -> {
                    ((visualBottom - (viewportHeight - threshold)) / 10f).coerceIn(0f, 20f)
                }

                else -> 0f
            }
        } else {
            // Если элемент на мгновение выпал из видимых, но мы точно знаем куда скроллить
            // (например, dragOffset экстремально большой), продолжаем скролл
            autoScrollSpeed = if (dragOffset < -100f && lazyListState.canScrollBackward) -15f
            else if (dragOffset > 100f && lazyListState.canScrollForward) 15f
            else 0f
        }
    }

    LaunchedEffect(draggedItemId) {
        if (draggedItemId != null) {
            while (true) {
                if (autoScrollSpeed != 0f) {
                    val scrolled = lazyListState.scrollBy(autoScrollSpeed)
                    if (scrolled != 0f) {
                        dragOffset += scrolled
                        checkAndPerformReorder()
                    }
                }
                updateAutoScrollSpeed()
                delay(8) // Немного быстрее цикл
            }
        }
    }

    val noDismissNestedScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Если мы перетаскиваем профиль, ПОЛНОСТЬЮ блокируем скролл шторки (родителя)
                if (draggedItemId != null && source == NestedScrollSource.UserInput) {
                    return available
                }
                return Offset.Zero
            }

            @Suppress("UNUSED_PARAMETER")
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (draggedItemId != null && source == NestedScrollSource.UserInput) {
                    return available
                }
                return Offset.Zero
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        dragHandle = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            ) {
                androidx.compose.material3.BottomSheetDefaults.DragHandle()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isSelectionMode) stringResource(
                            R.string.profile_selected_count,
                            selectedIds.size
                        )
                        else stringResource(R.string.profiles_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isSelectionMode) {
                            FilledTonalIconButton(onClick = {
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                zipToExport.value =
                                    viewModel.exportProfilesToZip(selectedIds.toList())
                                zipExportLauncher.launch("wt_profiles_selected.zip")
                            }) {
                                Icon(
                                    painterResource(R.drawable.ios_share_24px),
                                    contentDescription = null
                                )
                            }
                            FilledTonalIconButton(onClick = {
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                showBulkDeleteConfirm.value = selectedIds.toList()
                            }) {
                                Icon(
                                    painterResource(R.drawable.delete_24px),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                            FilledTonalIconButton(onClick = {
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                val isAllSelected = selectedIds.size == profiles.size
                                if (isAllSelected) {
                                    selectedIds.clear()
                                } else {
                                    selectedIds.clear()
                                    selectedIds.addAll(profiles.map { it.id })
                                }
                            }) {
                                Icon(
                                    painterResource(
                                        if (selectedIds.size == profiles.size) R.drawable.indeterminate_check_box_24px
                                        else R.drawable.check_box_24px
                                    ),
                                    contentDescription = null
                                )
                            }
                        } else {
                            if (profiles.isNotEmpty()) {
                                FilledTonalIconButton(onClick = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                    zipToExport.value = viewModel.exportAllProfilesToZip()
                                    zipExportLauncher.launch("wt_profiles_backup.zip")
                                }) {
                                    Icon(
                                        painterResource(R.drawable.ios_share_24px),
                                        contentDescription = stringResource(R.string.profile_export_all)
                                    )
                                }
                            }
                            Box {
                                FilledTonalIconButton(onClick = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                    addMenuExpanded = true
                                }) {
                                    Icon(
                                        painterResource(R.drawable.add_24px),
                                        contentDescription = stringResource(R.string.profile_create)
                                    )
                                }
                                AppDropdownMenu(
                                    expanded = addMenuExpanded,
                                    onDismissRequest = { addMenuExpanded = false },
                                    title = stringResource(R.string.profile_new)
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.profile_import)) },
                                        leadingIcon = {
                                            Icon(
                                                painterResource(R.drawable.file_open_24px),
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        },
                                        onClick = {
                                            addMenuExpanded = false
                                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                            onImport()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.profile_create)) },
                                        leadingIcon = {
                                            Icon(
                                                painterResource(R.drawable.add_24px),
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        },
                                        onClick = {
                                            addMenuExpanded = false
                                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                            context.startActivity(
                                                android.content.Intent(
                                                    context,
                                                    com.wireturn.app.ui.activities.CreateProfileActivity::class.java
                                                )
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            val bottomPadding =
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .heightIn(max = 640.dp)
                    .fillMaxWidth()
                    .nestedScroll(noDismissNestedScroll)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(
                    top = 8.dp,
                    bottom = 8.dp + bottomPadding + 24.dp
                )
            ) {
                if (profiles.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.mobile_outlined_24px),
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.profiles_empty),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    itemsIndexed(profiles, key = { _, it -> it.id }) { index, profile ->
                        val isDragged = draggedItemId == profile.id
                        val isSelected = profile.id == (optimisticSelectedId ?: currentId)
                        val isSelectedInMode = selectedIds.contains(profile.id)
                        var menuExpanded by remember { mutableStateOf(false) }

                        val itemShape = when {
                            isDragged -> RoundedCornerShape(12.dp)
                            profiles.size == 1 -> MaterialTheme.shapes.medium
                            index == 0 -> RoundedCornerShape(
                                topStart = 16.dp,
                                topEnd = 16.dp,
                                bottomStart = 4.dp,
                                bottomEnd = 4.dp
                            )

                            index == profiles.size - 1 -> RoundedCornerShape(
                                topStart = 4.dp,
                                topEnd = 4.dp,
                                bottomStart = 16.dp,
                                bottomEnd = 16.dp
                            )

                            else -> RoundedCornerShape(4.dp)
                        }

                        val offsetAnim by animateFloatAsState(
                            targetValue = if (isDragged) dragOffset else 0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = if (isDragged) Spring.StiffnessHigh else Spring.StiffnessMedium
                            ),
                            label = "drag_offset"
                        )

                        ProfileListItem(
                            profile = profile,
                            isSelected = isSelected,
                            isHighlighted = highlightedIds.contains(profile.id),
                            shape = itemShape,
                            isDragged = isDragged,
                            onClick = {
                                if (isSelectionMode) {
                                    if (isSelectedInMode) selectedIds.remove(profile.id)
                                    else selectedIds.add(profile.id)
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                } else if (draggedItemId == null) {
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                    optimisticSelectedId = profile.id
                                    viewModel.selectProfileAndRestart(profile.id)
                                    scope.launch {
                                        sheetState.hide()
                                        onDismiss()
                                    }
                                }
                            },
                            leadingContent = {
                                Icon(
                                    painter = painterResource(
                                        getProfileIcon(
                                            profile,
                                            outlined = !isSelected
                                        )
                                    ),
                                    contentDescription = null,
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem(
                                    placementSpec = if (isDragged) null else spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                )
                                .zIndex(if (isDragged) 10f else 0f)
                                .pointerInput(isSelectionMode) {
                                    if (isSelectionMode) return@pointerInput
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                            draggedItemId = profile.id
                                            dragOffset = 0f
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragOffset += dragAmount.y
                                            updateAutoScrollSpeed()
                                            checkAndPerformReorder()
                                        },
                                        onDragEnd = {
                                            draggedItemId = null
                                            dragOffset = 0f
                                            autoScrollSpeed = 0f
                                            viewModel.reorderProfiles(profiles.toList())
                                        },
                                        onDragCancel = {
                                            draggedItemId = null
                                            dragOffset = 0f
                                            autoScrollSpeed = 0f
                                        }
                                    )
                                }
                                .graphicsLayer {
                                    translationY = if (isDragged) dragOffset else offsetAnim
                                    scaleX = if (isDragged) 1.02f else 1f
                                    scaleY = if (isDragged) 1.02f else 1f
                                    shadowElevation = if (isDragged) 8.dp.toPx() else 0f
                                    shape = itemShape
                                    clip = isDragged
                                },
                            trailingContent = {
                                if (isSelectionMode) {
                                    Checkbox(
                                        checked = isSelectedInMode,
                                        onCheckedChange = null
                                    )
                                } else {
                                    Box {
                                        IconButton(onClick = {
                                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                            menuExpanded = true
                                        }) {
                                            Icon(
                                                painterResource(R.drawable.more_vert_24px),
                                                contentDescription = null
                                            )
                                        }
                                        AppDropdownMenu(
                                            expanded = menuExpanded,
                                            onDismissRequest = { menuExpanded = false },
                                            title = stringResource(R.string.profile_actions)
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.profile_select)) },
                                                leadingIcon = {
                                                    Icon(
                                                        painterResource(R.drawable.check_circle_24px),
                                                        null,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                },
                                                onClick = {
                                                    menuExpanded = false
                                                    HapticUtil.perform(
                                                        context,
                                                        HapticUtil.Pattern.CLICK
                                                    )
                                                    selectedIds.add(profile.id)
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.profile_clone)) },
                                                leadingIcon = {
                                                    Icon(
                                                        painterResource(R.drawable.content_copy_24px),
                                                        null,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                },
                                                onClick = {
                                                    menuExpanded = false
                                                    HapticUtil.perform(
                                                        context,
                                                        HapticUtil.Pattern.CLICK
                                                    )
                                                    showCloneDialog.value = profile
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.profile_rename)) },
                                                leadingIcon = {
                                                    Icon(
                                                        painterResource(R.drawable.edit_24px),
                                                        null,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                },
                                                onClick = {
                                                    menuExpanded = false
                                                    HapticUtil.perform(
                                                        context,
                                                        HapticUtil.Pattern.CLICK
                                                    )
                                                    showRenameDialog.value = profile
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.profile_export)) },
                                                leadingIcon = {
                                                    Icon(
                                                        painterResource(R.drawable.ios_share_24px),
                                                        null,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                },
                                                onClick = {
                                                    menuExpanded = false
                                                    HapticUtil.perform(
                                                        context,
                                                        HapticUtil.Pattern.CLICK
                                                    )
                                                    viewModel.getProfileJson(profile.id)
                                                        ?.let { json ->
                                                            jsonToExport.value = json
                                                            val safeName = profile.name.replace(
                                                                Regex("[\\\\/:*?\"<>| ]"),
                                                                "_"
                                                            )
                                                            exportLauncher.launch("wt_$safeName.json")
                                                        }
                                                }
                                            )
                                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.profile_delete)) },
                                                leadingIcon = {
                                                    Icon(
                                                        painterResource(R.drawable.delete_24px),
                                                        null,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                },
                                                onClick = {
                                                    menuExpanded = false
                                                    HapticUtil.perform(
                                                        context,
                                                        HapticUtil.Pattern.CLICK
                                                    )
                                                    showDeleteConfirm.value = profile
                                                },
                                                colors = MenuDefaults.itemColors(
                                                    textColor = MaterialTheme.colorScheme.error,
                                                    leadingIconColor = MaterialTheme.colorScheme.error
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    showRenameDialog.value?.let { profile ->
        ProfileNameDialog(
            title = stringResource(R.string.profile_rename),
            initialName = profile.name,
            onDismiss = { showRenameDialog.value = null },
            onConfirm = { name ->
                viewModel.renameProfile(profile.id, name)
                showRenameDialog.value = null
            }
        )
    }

    showCloneDialog.value?.let { profile ->
        ProfileNameDialog(
            title = stringResource(R.string.profile_clone),
            initialName = profile.name + " (Copy)",
            onDismiss = { showCloneDialog.value = null },
            onConfirm = { name ->
                viewModel.cloneProfile(profile.id, name)
                showCloneDialog.value = null
            }
        )
    }

    showDeleteConfirm.value?.let { profile ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm.value = null },
            title = { Text(stringResource(R.string.profile_delete_confirm, profile.name)) },
            text = { Text(stringResource(R.string.profile_delete_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteProfile(profile.id)
                        showDeleteConfirm.value = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.profile_delete)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteConfirm.value = null
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    showBulkDeleteConfirm.value?.let { ids ->
        AlertDialog(
            onDismissRequest = { showBulkDeleteConfirm.value = null },
            title = { Text(stringResource(R.string.profile_delete_selected_confirm, ids.size)) },
            text = { Text(stringResource(R.string.profile_delete_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteProfiles(ids)
                        selectedIds.clear()
                        showBulkDeleteConfirm.value = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.profile_delete)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showBulkDeleteConfirm.value = null
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

private fun getProfileIcon(profile: Profile, outlined: Boolean): Int {
    return when (profile.kernelVariant) {
        KernelVariant.TURNABLE -> {
            when (profile.turnableConfig.platformId) {
                "vk.com" -> R.drawable.ic_vk
                else -> if (outlined) R.drawable.mobile_outlined_24px else R.drawable.mobile_24px
            }
        }

        KernelVariant.OLCRTC -> {
            when (profile.olcrtcConfig.carrier) {
                "wbstream" -> R.drawable.ic_wbstream
                "telemost" -> R.drawable.ic_telemost
                "jazz" -> R.drawable.ic_jazz
                else -> if (outlined) R.drawable.mobile_outlined_24px else R.drawable.mobile_24px
            }
        }
    }
}

@Composable
fun ProfileNameDialog(
    title: String,
    initialName: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 100) name = it },
                label = { Text(stringResource(R.string.profile_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name) },
                enabled = name.isNotBlank()
            ) { Text(stringResource(R.string.btn_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
