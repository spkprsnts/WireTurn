@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.wireturn.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.R
import com.wireturn.app.data.KernelVariant
import com.wireturn.app.data.OlcrtcConfig.Companion.getTransportDisplayName
import com.wireturn.app.data.Profile
import com.wireturn.app.data.Subscription
import com.wireturn.app.data.XrayConfiguration
import com.wireturn.app.domain.ImportStatus
import com.wireturn.app.domain.isLocalNetworkHost
import com.wireturn.app.ui.AppDropdownMenu
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.LargeLeadingIcon
import com.wireturn.app.ui.StandardLeadingIcon
import com.wireturn.app.ui.ValidatorUtils
import com.wireturn.app.ui.VerticalAnimatedText
import com.wireturn.app.ui.activities.SubscriptionConfigActivity
import com.wireturn.app.ui.activities.cores.OlcRtcConfigActivity
import com.wireturn.app.ui.activities.cores.TurnableConfigActivity
import com.wireturn.app.ui.showExclusiveToast
import com.wireturn.app.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun ProfileSummary(
    profile: Profile,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    useAnimation: Boolean = false
) {
    val parts = mutableListOf<String>()
    val context = LocalContext.current

    parts.add(profile.getKernelDescription(context))

    when (profile.kernelVariant) {
        KernelVariant.TURNABLE -> {
            parts.add(profile.turnableConfig.platformDisplayName)
        }

        KernelVariant.OLCRTC -> {
            parts.add(getTransportDisplayName(profile.olcrtcConfig.transport, short = true))
        }

        KernelVariant.WEBDAV -> {
            val login = profile.webdavConfig.login
            if (login.isNotBlank()) {
                parts.add(login.substringBefore('@'))
            }
        }

        else -> {}
    }



    if (profile.xrayEnabled) {
        val isValid = when (profile.xrayProtocol) {
            XrayConfiguration.VLESS -> profile.vlessConfig.isValid()
            XrayConfiguration.WIREGUARD -> profile.wgConfig.isValid()
        }

        if (isValid) {
            parts.add(
                when (profile.xrayProtocol) {
                    XrayConfiguration.VLESS -> stringResource(ValidatorUtils.uriProtocolStringRes(profile.vlessConfig.vlessLink))
                    XrayConfiguration.WIREGUARD -> stringResource(R.string.wg_short)
                }
            )
            if (profile.xrayProtocol == XrayConfiguration.VLESS && profile.vlessConfig.isDualRoute) {
                parts.add(stringResource(R.string.xray_uri_dual_route_short))
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
                val intent = Intent(context, configActivityClassFor(currentProfile.kernelVariant))
                intent.putExtra("EXTRA_EDIT_MODE", true)
                intent.putExtra("EXTRA_PROFILE_NAME", currentProfile.name)
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
                    context.startActivity(
                        Intent(
                            context,
                            com.wireturn.app.ui.activities.AddProfileActivity::class.java
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
            isHighlighted -> MaterialTheme.colorScheme.surfaceVariant
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = tween(durationMillis = if (isHighlighted) 200 else 300),
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
                    else MaterialTheme.colorScheme.outline,
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
    onDismiss: () -> Unit
) {
    val profilesSource by viewModel.profiles.collectAsStateWithLifecycle()
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    val updatingSubIds by viewModel.updatingSubIds.collectAsStateWithLifecycle()
    val currentId by viewModel.currentProfileId.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current

    var draggedItemId by remember { mutableStateOf<String?>(null) }

    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        confirmValueChange = { target -> draggedItemId == null || target == SheetValue.Expanded }
    )
    val scope = rememberCoroutineScope()

    val errorConnection = stringResource(R.string.import_error_connection)
    val errorEmpty = stringResource(R.string.import_error_empty)
    val errorInvalidProfile = stringResource(R.string.import_error_invalid_profile)
    val errorServerFormat = stringResource(R.string.import_error_server)

    fun showSubUpdateError(status: ImportStatus) {
        val message = when (status) {
            is ImportStatus.NetworkError -> errorConnection
            is ImportStatus.ServerError -> errorServerFormat.format(status.code)
            is ImportStatus.EmptyResponse -> errorEmpty
            is ImportStatus.InvalidFormat -> errorInvalidProfile
            is ImportStatus.Success, is ImportStatus.KernelConfigDetected -> return
        }
        HapticUtil.perform(context, HapticUtil.Pattern.ERROR)
        context.showExclusiveToast(message)
    }

    suspend fun doRefreshSubscription(url: String) {
        val status = viewModel.importProfileFromLink(url)
        if (status is ImportStatus.Success) {
            status.summary?.let { showUpdateToast(context, it) }
        } else {
            showSubUpdateError(status)
        }
    }

    var pendingSubUpdateUrl by remember { mutableStateOf<String?>(null) }
    val showManualUpdateTunnelWarning = remember { mutableStateOf<Subscription?>(null) }

    val localNetworkPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        val url = pendingSubUpdateUrl
        pendingSubUpdateUrl = null
        if (url != null) {
            scope.launch { doRefreshSubscription(url) }
        }
    }

    fun refreshSubscription(sub: Subscription) {
        val isTunnelActive = com.wireturn.app.domain.activeLocalSocksProxy() != java.net.Proxy.NO_PROXY
        if (sub.requireTunnelForUpdate && !isTunnelActive) {
            showManualUpdateTunnelWarning.value = sub
            return
        }

        val url = sub.url
        val needsLocalNetworkPermission = Build.VERSION.SDK_INT >= 37 &&
            isLocalNetworkHost(url) &&
            ContextCompat.checkSelfPermission(
                context,
                "android.permission.ACCESS_LOCAL_NETWORK"
            ) != PackageManager.PERMISSION_GRANTED

        if (needsLocalNetworkPermission) {
            pendingSubUpdateUrl = url
            localNetworkPermissionLauncher.launch("android.permission.ACCESS_LOCAL_NETWORK")
            return
        }

        scope.launch { doRefreshSubscription(url) }
    }

    // Local state for reordering
    val profiles = remember { mutableStateListOf<Profile>() }

    // Grouping derived from `profiles`/`subscriptions` - derivedStateOf so it only recomputes when
    // those actually change, not on every recomposition of this dialog (e.g. drag auto-scroll frames).
    val standaloneProfiles by remember { derivedStateOf { profiles.filter { it.subscriptionId == null } } }
    val subscriptionGroups by remember {
        derivedStateOf { subscriptions.map { sub -> sub to profiles.filter { it.subscriptionId == sub.id } } }
    }

    val lazyListState = rememberLazyListState()
    var autoScrollSpeed by remember { mutableFloatStateOf(0f) }
    var fingerAbsoluteY by remember { mutableFloatStateOf(0f) }
    var dragAnchorOffset by remember { mutableFloatStateOf(0f) }
    var optimisticSelectedId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentId) {
        optimisticSelectedId = null
    }

    var exportFormatMenuExpanded by remember { mutableStateOf(false) }
    var exportActionMenuExpanded by remember { mutableStateOf(false) }
    var exportTargetIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var exportAsJson by remember { mutableStateOf(true) }

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

    // Clear temporary UI states when returning to foreground to ensure sync with DataStore
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                optimisticSelectedId = null
                draggedItemId = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Sync local list with source of truth only when NOT dragging
    LaunchedEffect(profilesSource) {
        if (optimisticSelectedId != null && profilesSource.none { it.id == optimisticSelectedId }) {
            optimisticSelectedId = null
        }

        val oldSize = profiles.size
        val oldIds = profiles.map { it.id }.toSet()
        val oldSubIds = profiles.mapNotNull { it.subscriptionId }.toSet()
        val isFirstLoad = oldSize == 0 && profilesSource.isNotEmpty()

        if (draggedItemId == null && (profiles.size != profilesSource.size || profiles.toList() != profilesSource)) {
            val updatedOrNewIds = if (oldSize > 0) {
                profilesSource.filter { newP ->
                    val oldP = profiles.find { it.id == newP.id }
                    oldP == null || oldP != newP
                }.map { it.id }
            } else emptyList()

            androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                profiles.clear()
                profiles.addAll(profilesSource)
            }

            if (updatedOrNewIds.isNotEmpty()) {
                // Если профили принадлежат подписке, подсвечиваем и её заголовок (даже если подписка не новая).
                val subIdsToHighlight = updatedOrNewIds.mapNotNull { id ->
                    profilesSource.find { it.id == id }?.subscriptionId
                }.distinct()
                
                val allToHighlight = (updatedOrNewIds + subIdsToHighlight).distinct()
                highlightedIds.addAll(allToHighlight)
                scope.launch {
                    delay(1_000.milliseconds)
                    highlightedIds.removeAll(allToHighlight)
                }
            }

            // Scroll logic
            scope.launch {
                delay(150.milliseconds) // Wait for layout update

                val hasStandaloneGuard = standaloneProfiles.isNotEmpty()

                fun findLazyIndex(targetId: String): Int {
                    val standaloneIdx = standaloneProfiles.indexOfFirst { it.id == targetId }
                    if (standaloneIdx != -1) {
                        return if (hasStandaloneGuard) standaloneIdx + 1 else standaloneIdx
                    }
                    
                    var runningIndex = if (hasStandaloneGuard) standaloneProfiles.size + 1 else standaloneProfiles.size
                    for ((sub, subProfiles) in subscriptionGroups) {
                        if (sub.id == targetId) return runningIndex
                        
                        val inSubIdx = subProfiles.indexOfFirst { it.id == targetId }
                        if (inSubIdx != -1) {
                            return runningIndex + 1 + inSubIdx
                        }
                        runningIndex += 1 // header
                        runningIndex += if (subProfiles.isEmpty()) 1 else subProfiles.size
                    }
                    return -1
                }

                // UI signals processing (internal to the same scope to reuse findLazyIndex)
                launch {
                    com.wireturn.app.ui.UIEventBus.scrollRequest.collect { targetId ->
                        // Подсвечиваем цель и всё связанное (профили подписки или заголовок профиля)
                        val relatedIds = mutableListOf(targetId)
                        profilesSource.filter { it.subscriptionId == targetId }.forEach { relatedIds.add(it.id) }
                        profilesSource.find { it.id == targetId }?.subscriptionId?.let { relatedIds.add(it) }
                        
                        val uniqueRelated = relatedIds.distinct()
                        highlightedIds.addAll(uniqueRelated)
                        scope.launch {
                            delay(1_000.milliseconds)
                            highlightedIds.removeAll(uniqueRelated)
                        }

                        delay(100.milliseconds)
                        val targetLazyIndex = findLazyIndex(targetId)
                        if (targetLazyIndex != -1) {
                            lazyListState.animateScrollToItem(targetLazyIndex)
                        }
                    }
                }

                if (isFirstLoad) {
                    val targetLazyIndex = findLazyIndex(currentId)
                    if (targetLazyIndex != -1) {
                        lazyListState.scrollToItem(targetLazyIndex)
                    }
                } else if (profilesSource.size > oldSize && oldSize > 0) {
                    val newIds = profilesSource.map { it.id }.toSet() - oldIds
                    val firstNewId = newIds.firstOrNull()
                    
                    if (firstNewId != null) {
                        val newProfile = profilesSource.find { it.id == firstNewId }
                        val subId = newProfile?.subscriptionId
                        
                        // Scroll ONLY if it's a NEW subscription (not previously seen)
                        // or if it's a new standalone profile.
                        val isExistingSub = subId != null && oldSubIds.contains(subId)
                        
                        if (!isExistingSub) {
                            val targetLazyIndex = findLazyIndex(firstNewId)
                            if (targetLazyIndex != -1) {
                                lazyListState.animateScrollToItem(targetLazyIndex)
                            }
                        }
                    }
                }
            }
        }
    }

    fun desiredAbsoluteY(): Float = fingerAbsoluteY + dragAnchorOffset

    fun clampedAbsoluteY(): Float? {
        val currentId = draggedItemId ?: return null
        val layoutInfo = lazyListState.layoutInfo
        val draggedItem = layoutInfo.visibleItemsInfo.find { it.key == currentId } ?: return null
        val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
        val slack = with(density) { 32.dp.toPx() }
        val minY = -slack
        val maxY = (viewportHeight - draggedItem.size) + slack
        return if (maxY >= minY) desiredAbsoluteY().coerceIn(minY, maxY) else desiredAbsoluteY()
    }

    fun clampedDragOffset(): Float {
        val currentId = draggedItemId ?: return 0f
        val draggedItem = lazyListState.layoutInfo.visibleItemsInfo.find { it.key == currentId } ?: return 0f
        val absY = clampedAbsoluteY() ?: return 0f
        return absY - draggedItem.offset
    }

    fun updateAutoScrollSpeed() {
        val currentId = draggedItemId ?: return
        val layoutInfo = lazyListState.layoutInfo
        val draggedItem = layoutInfo.visibleItemsInfo.find { it.key == currentId }

        val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
        val threshold = with(density) { 60.dp.toPx() }

        if (draggedItem != null) {
            val slack = with(density) { 32.dp.toPx() }
            val minY = -slack
            val maxY = (viewportHeight - draggedItem.size) + slack
            val visualTop = if (maxY >= minY) desiredAbsoluteY().coerceIn(minY, maxY) else desiredAbsoluteY()
            val visualBottom = visualTop + draggedItem.size

            autoScrollSpeed = when {
                visualTop < threshold && lazyListState.canScrollBackward -> {
                    // Quadratic acceleration for smoother control
                    val dist = (threshold - visualTop).coerceAtLeast(0f)
                    val ratio = dist / threshold
                    -(ratio * ratio * 45f).coerceIn(3f, 45f)
                }

                visualBottom > viewportHeight - threshold && lazyListState.canScrollForward -> {
                    val dist = (visualBottom - (viewportHeight - threshold)).coerceAtLeast(0f)
                    val ratio = dist / threshold
                    (ratio * ratio * 45f).coerceIn(3f, 45f)
                }

                else -> 0f
            }
        } else {
            autoScrollSpeed = 0f
        }
    }

    fun checkAndPerformReorder() {
        val currentId = draggedItemId ?: return

        val layoutInfo = lazyListState.layoutInfo
        val visibleItems = layoutInfo.visibleItemsInfo
        val initialInfo = visibleItems.find { it.key == currentId } ?: return
        val absY = clampedAbsoluteY() ?: return

        val draggedCenter = absY + initialInfo.size / 2
        val originalCenter = initialInfo.offset + initialInfo.size / 2
        val goingDown = draggedCenter > originalCenter

        var virtualIndex = initialInfo.index
        var didSwap = false

        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            while (true) {
                val currentDraggedIdx =
                    profiles.indexOfFirst { it.id == currentId }.takeIf { it != -1 } ?: break

                val targetItem = if (goingDown) {
                    visibleItems.find { item ->
                        item.index > virtualIndex &&
                        (item.offset + item.size / 2) < draggedCenter &&
                        item.key is String && !(item.key as String).startsWith("sub_header_") &&
                        item.key != "standalone_top_guard"
                    }
                } else {
                    visibleItems.findLast { item ->
                        item.index < virtualIndex &&
                        (item.offset + item.size / 2) > draggedCenter &&
                        item.key is String && !(item.key as String).startsWith("sub_header_") &&
                        item.key != "standalone_top_guard"
                    }
                }

                if (targetItem == null) break

                val targetProfileId = targetItem.key as String
                val targetProfileIdx = profiles.indexOfFirst { it.id == targetProfileId }.takeIf { it != -1 } ?: break

                // Allow reorder ONLY within the same group (standalone or specific subscription)
                val draggedProfile = profiles[currentDraggedIdx]
                val targetProfile = profiles[targetProfileIdx]
                if (draggedProfile.subscriptionId != targetProfile.subscriptionId) break

                val item = profiles.removeAt(currentDraggedIdx)
                profiles.add(targetProfileIdx, item)

                virtualIndex = targetItem.index
                didSwap = true
            }
        }

        if (didSwap) {
            updateAutoScrollSpeed()
            scope.launch { HapticUtil.perform(context, HapticUtil.Pattern.SELECTION) }
        }
    }

    fun onProfileDrag(delta: Float) {
        fingerAbsoluteY += delta
    }

    LaunchedEffect(draggedItemId) {
        if (draggedItemId != null) {
            while (true) {
                withFrameNanos { }
                try {
                    if (autoScrollSpeed != 0f) {
                        lazyListState.scrollBy(autoScrollSpeed)
                    }
                    checkAndPerformReorder()
                    updateAutoScrollSpeed()

                    val currentId = draggedItemId
                    if (currentId != null &&
                        lazyListState.layoutInfo.visibleItemsInfo.none { it.key == currentId }
                    ) {
                        val trueIdx = profiles.indexOfFirst { it.id == currentId }
                        if (trueIdx != -1) {
                            val firstVisible = lazyListState.firstVisibleItemIndex
                            if (desiredAbsoluteY() < 0f) {
                                lazyListState.scrollToItem((firstVisible - 1).coerceAtLeast(0))
                            } else {
                                lazyListState.scrollToItem(firstVisible + 1)
                            }
                            checkAndPerformReorder()
                            updateAutoScrollSpeed()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ProfileDrag", "autoscroll tick failed, draggedItemId=$draggedItemId", e)
                }
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

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                // Если мы перетаскиваем профиль (reorder), полностью блокируем шторку
                if (draggedItemId != null && source == NestedScrollSource.UserInput) {
                    return available
                }
                
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                // Вместо полного гашения, оставляем 5% энергии.
                // Это заставит шторку слегка "вздрогнуть" при резком свайпе, 
                // но этой энергии не хватит для её закрытия.
                if (available.y > 0 && !lazyListState.canScrollBackward) {
                    return available * 0.95f
                }
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                // Прокидываем чуть-чуть остаточной энергии вниз
                if (available.y > 0) {
                    return available * 0.95f
                }
                return Velocity.Zero
            }
        }
    }

    val exportTitle = stringResource(R.string.profile_export)
    val onExportActionSelected = { save: Boolean ->
        exportActionMenuExpanded = false
        if (save) {
            if (exportAsJson) {
                val json = viewModel.getProfilesJson(exportTargetIds)
                jsonToExport.value = json
                val fileName = if (exportTargetIds.size == 1) {
                    val profile = profiles.find { it.id == exportTargetIds[0] }
                    val safeName = profile?.name?.replace(Regex("[\\\\/:*?\"<>| ]"), "_") ?: "profile"
                    "wt_$safeName.json"
                } else "wt_profiles_${exportTargetIds.size}.json"
                exportLauncher.launch(fileName)
            } else {
                zipToExport.value = viewModel.exportProfilesToZip(exportTargetIds)
                zipExportLauncher.launch("wt_profiles_${exportTargetIds.size}.zip")
            }
        } else {
            if (exportAsJson) {
                val json = viewModel.getProfilesJson(exportTargetIds)
                val encoded = com.wireturn.app.domain.ProfileEncoder.encode(json)
                shareText(context, "wireturn://$encoded", exportTitle)
            } else {
                val bytes = viewModel.exportProfilesToZip(exportTargetIds)
                val fileName = if (exportTargetIds.size == 1) {
                    val profile = profiles.find { it.id == exportTargetIds[0] }
                    val safeName = profile?.name?.replace(Regex("[\\\\/:*?\"<>| ]"), "_") ?: "profile"
                    "wt_$safeName.zip"
                } else "wt_profiles_${exportTargetIds.size}.zip"
                shareFile(context, bytes, fileName, "application/zip")
            }
        }
    }

    val onExportClick = { targets: List<String>, showMenu: Boolean ->
        if (showMenu) HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
        exportTargetIds = targets
        if (targets.size > 1) {
            if (showMenu) exportFormatMenuExpanded = true
        } else {
            exportAsJson = true
            if (showMenu) exportActionMenuExpanded = true
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
                            Box {
                                FilledTonalIconButton(onClick = { onExportClick(selectedIds.toList(), true) }) {
                                    Icon(
                                        painterResource(R.drawable.ios_share_24px),
                                        contentDescription = null
                                    )
                                }
                                ExportDropdownMenus(
                                    expandedFormat = exportFormatMenuExpanded,
                                    onDismissFormat = { exportFormatMenuExpanded = false },
                                    expandedAction = exportActionMenuExpanded,
                                    onDismissAction = { exportActionMenuExpanded = false },
                                    onFormatSelected = { asJson ->
                                        exportAsJson = asJson
                                        exportFormatMenuExpanded = false
                                        exportActionMenuExpanded = true
                                    },
                                    onActionSelected = onExportActionSelected
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
                                Box {
                                    FilledTonalIconButton(onClick = { onExportClick(profiles.map { it.id }, true) }) {
                                        Icon(
                                            painterResource(R.drawable.ios_share_24px),
                                            contentDescription = stringResource(R.string.profile_export_all)
                                        )
                                    }
                                    ExportDropdownMenus(
                                        expandedFormat = exportFormatMenuExpanded,
                                        onDismissFormat = { exportFormatMenuExpanded = false },
                                        expandedAction = exportActionMenuExpanded,
                                        onDismissAction = { exportActionMenuExpanded = false },
                                        onFormatSelected = { asJson ->
                                            exportAsJson = asJson
                                            exportFormatMenuExpanded = false
                                            exportActionMenuExpanded = true
                                        },
                                        onActionSelected = onExportActionSelected
                                    )
                                }
                            }
                            Box {
                                FilledTonalIconButton(onClick = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                    context.startActivity(
                                        Intent(
                                            context,
                                            com.wireturn.app.ui.activities.AddProfileActivity::class.java
                                        )
                                    )
                                }) {
                                    Icon(
                                        painterResource(R.drawable.add_24px),
                                        contentDescription = stringResource(R.string.profile_create)
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
                userScrollEnabled = draggedItemId == null,
                modifier = Modifier
                    .heightIn(max = 640.dp)
                    .fillMaxWidth()
                    .nestedScroll(noDismissNestedScroll)
                    .pointerInput(isSelectionMode) {
                        if (isSelectionMode) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture

                            val hitItem = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                                val key = item.key
                                key is String &&
                                    !key.startsWith("sub_header_") &&
                                    key != "standalone_top_guard" &&
                                    longPress.position.y >= item.offset &&
                                    longPress.position.y < item.offset + item.size
                            } ?: return@awaitEachGesture
                            val targetId = hitItem.key as String

                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            draggedItemId = targetId
                            fingerAbsoluteY = longPress.position.y
                            dragAnchorOffset = hitItem.offset - longPress.position.y

                            drag(longPress.id) { change ->
                                val delta = change.positionChange().y
                                change.consume()
                                onProfileDrag(delta)
                            }
                            if (draggedItemId == targetId) {
                                draggedItemId = null
                                autoScrollSpeed = 0f
                                viewModel.reorderProfiles(profiles.toList())
                            }
                        }
                    }
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(
                    top = 8.dp,
                    bottom = 8.dp + bottomPadding + 24.dp
                )
            ) {
                if (profiles.isEmpty() && subscriptions.isEmpty()) {
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
                                tint = MaterialTheme.colorScheme.outlineVariant
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
                    if (standaloneProfiles.isNotEmpty()) {
                        item(key = "standalone_top_guard") {
                            Spacer(Modifier.height(1.dp))
                        }
                    }

                    // Standalone Profiles
                    itemsIndexed(standaloneProfiles, key = { _, it -> it.id }) { index, profile ->
                        ProfileItemRow(
                            profile = profile,
                            index = index,
                            totalInGroup = standaloneProfiles.size,
                            viewModel = viewModel,
                            currentId = currentId,
                            optimisticSelectedId = optimisticSelectedId,
                            selectedIds = selectedIds,
                            highlightedIds = highlightedIds,
                            draggedItemId = draggedItemId,
                            dragOffset = clampedDragOffset(),
                            isSelectionMode = isSelectionMode,
                            onDismiss = onDismiss,
                            sheetState = sheetState,
                            scope = scope,
                            context = context,
                            onDragEnd = { endedId ->
                                if (draggedItemId == endedId) {
                                    draggedItemId = null; autoScrollSpeed = 0f
                                    viewModel.reorderProfiles(profiles.toList())
                                }
                            },
                            onExportClick = onExportClick,
                            onExportActionSelected = onExportActionSelected,
                            onClone = { showCloneDialog.value = it },
                            onRename = { showRenameDialog.value = it },
                            onDelete = { showDeleteConfirm.value = it },
                            onOptimisticSelect = { optimisticSelectedId = it },
                            modifier = Modifier.animateItem()
                        )
                    }

                    // Subscription Groups
                    subscriptionGroups.forEach { (sub, subProfiles) ->
                        val isAnySelected = subProfiles.any { it.id == (optimisticSelectedId ?: currentId) }
                        val isUpdating = updatingSubIds.contains(sub.id)

                        item(key = "sub_header_${sub.id}") {
                            SubscriptionHeaderRow(
                                sub = sub,
                                isAnyChildSelected = isAnySelected,
                                isSelectionMode = isSelectionMode,
                                isAllSubSelected = subProfiles.isNotEmpty() && subProfiles.all { selectedIds.contains(it.id) },
                                isUpdating = isUpdating,
                                onUpdate = { refreshSubscription(sub) },
                                onSettings = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                    val intent = Intent(context, SubscriptionConfigActivity::class.java).apply {
                                        putExtra("EXTRA_SUB_ID", sub.id)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                },
                                onSelect = {
                                    val targetId = subProfiles.find { it.id == sub.activeProfileId }?.id 
                                        ?: subProfiles.firstOrNull()?.id
                                    if (targetId != null) {
                                        optimisticSelectedId = targetId
                                        viewModel.selectProfileAndRestart(targetId)
                                    }
                                },
                                onToggleSelection = {
                                    val allInSub = subProfiles.map { it.id }
                                    val isAllInSubSelected = allInSub.all { selectedIds.contains(it) }
                                    if (isAllInSubSelected) {
                                        selectedIds.removeAll(allInSub)
                                    } else {
                                        selectedIds.addAll(allInSub.filter { it !in selectedIds })
                                    }
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                },
                                isHighlighted = highlightedIds.contains(sub.id)
                            )
                        }

                        itemsIndexed(subProfiles, key = { _, it -> it.id }) { index, profile ->
                            ProfileItemRow(
                                profile = profile,
                                index = index,
                                totalInGroup = subProfiles.size,
                                isInsideSubscription = true,
                                viewModel = viewModel,
                                currentId = currentId,
                                optimisticSelectedId = optimisticSelectedId,
                                selectedIds = selectedIds,
                                highlightedIds = highlightedIds,
                                draggedItemId = draggedItemId,
                                dragOffset = clampedDragOffset(),
                                isSelectionMode = isSelectionMode,
                                onDismiss = onDismiss,
                                sheetState = sheetState,
                                scope = scope,
                                context = context,
                                onDragEnd = { endedId ->
                                    if (draggedItemId == endedId) {
                                        draggedItemId = null; autoScrollSpeed = 0f
                                        viewModel.reorderProfiles(profiles.toList())
                                    }
                                },
                                onExportClick = onExportClick,
                                onExportActionSelected = onExportActionSelected,
                                onClone = { showCloneDialog.value = it },
                                onRename = { showRenameDialog.value = it },
                                onDelete = { showDeleteConfirm.value = it },
                                onOptimisticSelect = { optimisticSelectedId = it },
                                modifier = Modifier.animateItem()
                            )
                        }

                        if (subProfiles.isEmpty()) {
                            item(key = "sub_empty_${sub.id}") {
                                SubscriptionEmptyRow()
                            }
                        }
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

    showManualUpdateTunnelWarning.value?.let { sub ->
        AlertDialog(
            onDismissRequest = { showManualUpdateTunnelWarning.value = null },
            title = { Text(stringResource(R.string.update_manual_no_tunnel_title)) },
            text = { Text(stringResource(R.string.update_manual_no_tunnel_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val url = sub.url
                        showManualUpdateTunnelWarning.value = null
                        // Proceed without tunnel check again
                        scope.launch { doRefreshSubscription(url) }
                    }
                ) { Text(stringResource(R.string.btn_proceed)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showManualUpdateTunnelWarning.value = null
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun ProfileItemRow(
    profile: Profile,
    index: Int,
    totalInGroup: Int,
    viewModel: MainViewModel,
    currentId: String,
    optimisticSelectedId: String?,
    selectedIds: SnapshotStateList<String>,
    highlightedIds: List<String>,
    draggedItemId: String?,
    dragOffset: Float,
    isSelectionMode: Boolean,
    onDismiss: () -> Unit,
    sheetState: androidx.compose.material3.SheetState,
    scope: kotlinx.coroutines.CoroutineScope,
    context: Context,
    onDragEnd: (String) -> Unit,
    onExportClick: (List<String>, Boolean) -> Unit,
    onExportActionSelected: (Boolean) -> Unit,
    onClone: (Profile) -> Unit,
    onRename: (Profile) -> Unit,
    onDelete: (Profile) -> Unit,
    onOptimisticSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    isInsideSubscription: Boolean = false
) {
    val isDragged = draggedItemId == profile.id

    val isDraggedState = rememberUpdatedState(isDragged)
    val onDragEndState = rememberUpdatedState(onDragEnd)
    DisposableEffect(profile.id) {
        onDispose {
            if (isDraggedState.value) {
                Log.e("ProfileDrag", "composable disposed mid-drag for profile=${profile.id}, forcing reset")
                onDragEndState.value(profile.id)
            }
        }
    }

    val isSelected = profile.id == (optimisticSelectedId ?: currentId)
    val isSelectedInMode = selectedIds.contains(profile.id)
    var menuExpanded by remember { mutableStateOf(false) }
    var editMenuExpanded by remember { mutableStateOf(false) }
    var itemExportActionMenuExpanded by remember { mutableStateOf(false) }

    val itemShape = when {
        isDragged -> RoundedCornerShape(12.dp)
        totalInGroup == 1 && !isInsideSubscription -> MaterialTheme.shapes.medium
        isInsideSubscription -> {
            if (index == totalInGroup - 1) RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
            else RoundedCornerShape(4.dp)
        }
        index == 0 -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
        index == totalInGroup - 1 -> RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
        else -> RoundedCornerShape(4.dp)
    }

    val offsetAnim by animateFloatAsState(
        targetValue = if (isDragged) dragOffset else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = if (isDragged) Spring.StiffnessHigh else Spring.StiffnessMedium),
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
                onOptimisticSelect(profile.id)
                viewModel.selectProfileAndRestart(profile.id)
                scope.launch {
                    sheetState.hide()
                    onDismiss()
                }
            }
        },
        leadingContent = {
            Icon(
                painter = painterResource(getProfileIcon(profile, outlined = !isSelected)),
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            )
        },
        modifier = modifier
            .fillMaxWidth()
            .zIndex(if (isDragged) 10f else 0f)
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
                Checkbox(checked = isSelectedInMode, onCheckedChange = null)
            } else {
                Box {
                    IconButton(onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        menuExpanded = true
                    }) {
                        Icon(painterResource(R.drawable.more_vert_24px), contentDescription = null)
                    }
                    AppDropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }, title = stringResource(R.string.profile_actions)) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profile_select)) },
                            leadingIcon = { Icon(painterResource(R.drawable.check_circle_24px), null, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                menuExpanded = false
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                selectedIds.add(profile.id)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profile_clone)) },
                            leadingIcon = { Icon(painterResource(R.drawable.content_copy_24px), null, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                menuExpanded = false
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                onClone(profile)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profile_edit)) },
                            leadingIcon = { Icon(painterResource(R.drawable.edit_square_24px), null, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                menuExpanded = false
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                editMenuExpanded = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profile_rename)) },
                            leadingIcon = { Icon(painterResource(R.drawable.edit_24px), null, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                menuExpanded = false
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                onRename(profile)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profile_export)) },
                            leadingIcon = { Icon(painterResource(R.drawable.ios_share_24px), null, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                menuExpanded = false
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                itemExportActionMenuExpanded = true
                            }
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profile_delete)) },
                            leadingIcon = { Icon(painterResource(R.drawable.delete_24px), null, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                menuExpanded = false
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                onDelete(profile)
                            },
                            colors = MenuDefaults.itemColors(
                                textColor = MaterialTheme.colorScheme.error,
                                leadingIconColor = MaterialTheme.colorScheme.error
                            )
                        )
                    }
                    AppDropdownMenu(
                        expanded = editMenuExpanded,
                        onDismissRequest = { editMenuExpanded = false },
                        title = stringResource(R.string.profile_edit)
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profile_edit_config)) },
                            leadingIcon = {
                                Icon(
                                    painterResource(R.drawable.edit_square_24px),
                                    null,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                editMenuExpanded = false
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                val intent = Intent(context, configActivityClassFor(profile.kernelVariant))
                                intent.putExtra("EXTRA_EDIT_MODE", true)
                                intent.putExtra("EXTRA_PROFILE_NAME", profile.name)
                                intent.putExtra("EXTRA_PROFILE_ID", profile.id)
                                context.startActivity(intent)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.xray_title)) },
                            leadingIcon = {
                                Icon(
                                    painterResource(R.drawable.ic_xray_24px),
                                    null,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                editMenuExpanded = false
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                val intent = Intent(context, com.wireturn.app.ui.activities.XrayEditActivity::class.java)
                                intent.putExtra("EXTRA_PROFILE_ID", profile.id)
                                context.startActivity(intent)
                            }
                        )
                    }
                    ExportDropdownMenus(
                        expandedFormat = false,
                        onDismissFormat = { },
                        expandedAction = itemExportActionMenuExpanded,
                        onDismissAction = { itemExportActionMenuExpanded = false },
                        onFormatSelected = { },
                        onActionSelected = { save ->
                            itemExportActionMenuExpanded = false
                            onExportClick(listOf(profile.id), false)
                            onExportActionSelected(save)
                        }
                    )
                }
            }
        }
    )
}

@Composable
private fun SubscriptionHeaderRow(
    sub: Subscription,
    isAnyChildSelected: Boolean,
    isSelectionMode: Boolean,
    isAllSubSelected: Boolean,
    isUpdating: Boolean,
    onUpdate: () -> Unit,
    onSettings: () -> Unit,
    onSelect: () -> Unit,
    onToggleSelection: () -> Unit,
    modifier: Modifier = Modifier,
    isHighlighted: Boolean = false
) {
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isHighlighted -> MaterialTheme.colorScheme.surfaceVariant
            isAnyChildSelected -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = tween(durationMillis = if (isHighlighted) 200 else 300),
        label = "sub_header_bg"
    )

    Surface(
        color = backgroundColor,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 4.dp),
        onClick = {
            if (isSelectionMode) onToggleSelection()
            else if (!isAnyChildSelected) onSelect()
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isAllSubSelected,
                    onCheckedChange = { onToggleSelection() },
                    modifier = Modifier.padding(end = 16.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sub.name, 
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isAnyChildSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee()
                )
                if (!sub.description.isNullOrBlank()) {
                    Text(
                        text = sub.description, 
                        style = MaterialTheme.typography.bodySmall, 
                        color = if (isAnyChildSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.outline
                    )
                }
            }
            if (!isSelectionMode) {
                IconButton(onClick = onSettings) {
                    Icon(
                        painter = painterResource(R.drawable.settings_24px), 
                        contentDescription = null, 
                        tint = if (isAnyChildSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onUpdate, enabled = !isUpdating) {
                    if (isUpdating) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = if (isAnyChildSelected) MaterialTheme.colorScheme.onSecondaryContainer
                                    else MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.refresh_24px), 
                            contentDescription = null, 
                            modifier = Modifier.size(20.dp),
                            tint = if (isAnyChildSelected) MaterialTheme.colorScheme.onSecondaryContainer
                                    else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubscriptionEmptyRow() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier.padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.profiles_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun ExportDropdownMenus(
    expandedFormat: Boolean,
    onDismissFormat: () -> Unit,
    expandedAction: Boolean,
    onDismissAction: () -> Unit,
    onFormatSelected: (Boolean) -> Unit,
    onActionSelected: (Boolean) -> Unit // true = save, false = share
) {
    AppDropdownMenu(
        expanded = expandedFormat,
        onDismissRequest = onDismissFormat,
        title = stringResource(R.string.profile_export)
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.profile_export_json)) },
            onClick = { onFormatSelected(true) },
            leadingIcon = {
                Icon(
                    painterResource(R.drawable.data_array_24px),
                    null,
                    modifier = Modifier.size(20.dp)
                )
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.profile_export_zip)) },
            onClick = { onFormatSelected(false) },
            leadingIcon = {
                Icon(
                    painterResource(R.drawable.abc_24px),
                    null,
                    modifier = Modifier.size(20.dp)
                )
            }
        )
    }

    AppDropdownMenu(
        expanded = expandedAction,
        onDismissRequest = onDismissAction,
        title = stringResource(R.string.profile_export)
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.profile_export_save)) },
            onClick = { onActionSelected(true) },
            leadingIcon = {
                Icon(
                    painterResource(R.drawable.save_24px),
                    null,
                    modifier = Modifier.size(20.dp)
                )
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.profile_export_share)) },
            onClick = { onActionSelected(false) },
            leadingIcon = {
                Icon(
                    painterResource(R.drawable.share_24px),
                    null,
                    modifier = Modifier.size(20.dp)
                )
            }
        )
    }
}

private fun shareFile(context: Context, bytes: ByteArray, fileName: String, mimeType: String) {
    try {
        val file = java.io.File(context.cacheDir, fileName)
        file.writeBytes(bytes)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, null))
    } catch (_: Exception) {}
}

private fun shareText(context: Context, text: String, title: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, title))
}

/** The config screen Activity for editing a profile of this core type. */
private fun configActivityClassFor(variant: KernelVariant): Class<out Activity> = when (variant) {
    KernelVariant.TURNABLE -> TurnableConfigActivity::class.java
    KernelVariant.OLCRTC -> OlcRtcConfigActivity::class.java
    KernelVariant.WEBDAV -> com.wireturn.app.ui.activities.cores.WebdavConfigActivity::class.java
    KernelVariant.FREETURN -> com.wireturn.app.ui.activities.cores.FreeTurnConfigActivity::class.java
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
            when (profile.olcrtcConfig.provider) {
                "wbstream" -> R.drawable.ic_wbstream
                "telemost" -> R.drawable.ic_telemost
                "jitsi" -> R.drawable.ic_jitsi
                else -> if (outlined) R.drawable.mobile_outlined_24px else R.drawable.mobile_24px
            }
        }

        KernelVariant.WEBDAV -> {
            R.drawable.ic_dav
        }

        KernelVariant.FREETURN -> {
            R.drawable.ic_vk
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

private fun showUpdateToast(context: Context, summary: com.wireturn.app.domain.ImportResult) {
    val appContext = context.applicationContext
    val message = if (summary.added == 0 && summary.updated == 0 && summary.removed == 0) {
        appContext.getString(R.string.subscription_update_no_changes)
    } else {
        appContext.getString(R.string.subscription_update_summary, summary.added, summary.removed, summary.total)
    }
    appContext.showExclusiveToast(message)
}
