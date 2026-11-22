@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)

package com.wireturn.app.ui.screens

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.maxLengthReject
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CheckableDropdownMenuItem
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.SegmentedListItem
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.R
import com.wireturn.app.data.Profile
import com.wireturn.app.data.Subscription
import com.wireturn.app.domain.ImportStatus
import com.wireturn.app.domain.isLocalNetworkHost
import com.wireturn.app.ui.AppDropdownMenu
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.activities.SubscriptionConfigActivity
import com.wireturn.app.ui.showExclusiveToast
import com.wireturn.app.ui.trackGestureStartedAtBoundary
import com.wireturn.app.viewmodel.MainViewModel
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/** How many profiles to keep visible above the selected one when the sheet first opens. */
private const val PROFILES_ABOVE_SELECTED_ON_OPEN = 2

/**
 * [list] by its last ping: the ones that answered, fastest first, then the never-measured ones,
 * then the ones that failed. A profile still waiting in a running check ([pending]) counts as
 * never measured - its old ping is about to be replaced, so the fresh results go above it. Its
 * stored ping stays untouched, for a check that gets stopped. Stable - equal ones keep their order.
 */
private fun sortedByPing(
    list: List<Profile>,
    pings: Map<String, Long>,
    pending: Set<String>
): List<Profile> =
    list.sortedBy { profile ->
        when (val ms = pings[profile.id]?.takeIf { profile.id !in pending }) {
            null -> Long.MAX_VALUE - 1
            com.wireturn.app.domain.ProfileManager.PING_FAILED -> Long.MAX_VALUE
            else -> ms
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
    val density = LocalDensity.current

    // Distance (in px) over which a subscription header morphs into its stuck appearance -
    // scrubbed directly by scroll position rather than snapping once fully pinned. Kept fairly
    // long so the fold reads as gradual rather than a quick snap over a couple of scroll ticks.
    val headerStuckTransitionPx = with(density) { 96.dp.toPx() }

    var draggedItemId by remember { mutableStateOf<String?>(null) }

    // Was the list already at rest at the top when the current touch began? Distinguishes a
    // swipe that merely scrolls up to the top (never dismiss) from a deliberate second swipe
    // starting with nothing left to scroll (dismiss normally).
    var gestureStartedAtTop by remember { mutableStateOf(false) }

    // Passed to the LazyColumn below so noDismissNestedScroll can also manually drive it for
    // top-boundary overscroll (see there) - same native stretch effect as the bottom edge.
    val topOverscroll = rememberOverscrollEffect()

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
    val errorTls = stringResource(R.string.import_error_tls)
    val errorInsecureHttp = stringResource(R.string.import_insecure_http_title)

    fun showSubUpdateError(status: ImportStatus) {
        val message = when (status) {
            is ImportStatus.NetworkError -> errorConnection
            is ImportStatus.TlsError -> errorTls
            // Not expected on a refresh (a subscription already added is confirmed), kept for
            // exhaustiveness.
            is ImportStatus.InsecureHttp -> errorInsecureHttp
            is ImportStatus.ServerError -> errorServerFormat.format(status.code)
            is ImportStatus.EmptyResponse -> errorEmpty
            is ImportStatus.InvalidFormat -> errorInvalidProfile
            is ImportStatus.Success, is ImportStatus.KernelConfigDetected, is ImportStatus.Cancelled -> return
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
        val isTunnelActive =
            com.wireturn.app.domain.activeLocalSocksProxy() != java.net.Proxy.NO_PROXY
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
    val profilePings by viewModel.profilePings.collectAsStateWithLifecycle()
    val testingSubIds by viewModel.testingSubIds.collectAsStateWithLifecycle()
    val pingingProfileIds by viewModel.pingingProfileIds.collectAsStateWithLifecycle()
    // Pinned subscriptions first (sortedBy is stable: each part keeps its own order), and a
    // subscription sorted by ping lists its profiles in that order - only here, on screen: the
    // stored order stays the server's, so a refresh doesn't undo the sorting.
    val subscriptionGroups by remember {
        derivedStateOf {
            subscriptions.sortedBy { !it.pinned }.map { sub ->
                val own = profiles.filter { it.subscriptionId == sub.id }
                sub to if (sub.sortMode == Subscription.SORT_PING) sortedByPing(
                    own,
                    profilePings,
                    pingingProfileIds
                ) else own
            }
        }
    }
    // Single source of truth for the visual order: standalone profiles as one implicit group
    // (null subscription), then each subscription's own group, in list order. findLazyIndex and
    // the open-time scroll anchor (visualOrderIds, below) both walk this same list instead of
    // separately re-deriving the grouping/order from standaloneProfiles/subscriptionGroups by
    // hand, so the two can't silently disagree if the grouping rules change later.
    val visualGroups by remember {
        derivedStateOf { listOf<Pair<Subscription?, List<Profile>>>(null to standaloneProfiles) + subscriptionGroups }
    }

    fun findLazyIndex(targetId: String): Int {
        var runningIndex = 0
        for ((sub, groupProfiles) in visualGroups) {
            if (sub == null) {
                // Standalone "group" only gets its 1-item top guard once it's non-empty.
                val hasGuard = groupProfiles.isNotEmpty()
                val idx = groupProfiles.indexOfFirst { it.id == targetId }
                if (idx != -1) return if (hasGuard) idx + 1 else idx
                runningIndex += if (hasGuard) groupProfiles.size + 1 else groupProfiles.size
            } else {
                if (sub.id == targetId) return runningIndex
                val idx = groupProfiles.indexOfFirst { it.id == targetId }
                // A collapsed subscription is its header alone - a profile in it lands there.
                if (idx != -1) return if (sub.collapsed) runningIndex else runningIndex + 1 + idx
                runningIndex += 1 // header
                if (!sub.collapsed) runningIndex += if (groupProfiles.isEmpty()) 1 else groupProfiles.size
            }
        }
        return -1
    }

    val lazyListState = rememberLazyListState()
    // Suppresses the drag-reorder swap animation while actively scrolling, so the continuous
    // layout change from the sticky header's stuck transition (see headerStuckTransitionPx)
    // doesn't get misread as "this profile moved in the list" and chased with a lagging spring -
    // only a genuine reorder while the list is at rest (e.g. after a subscription refresh) still
    // animates. Excludes an active drag (draggedItemId != null) because the drag's own auto-scroll
    // loop below also drives lazyListState.scrollBy(...), which would otherwise make
    // isScrollInProgress true and kill the swap animation during the most common way to reorder a
    // profile that isn't already on screen - dragging it to the edge to trigger auto-scroll.
    val profileItemPlacementSpec = if (lazyListState.isScrollInProgress && draggedItemId == null) {
        null
    } else {
        spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset(1, 1))
    }
    // The dragged row itself never animates to its new slot: its drag offset is measured from
    // the slot it's laid out in, so a placement spring after each swap would fling it a
    // neighbour's height away from the finger and back - a jump at every profile it passes.
    // Its neighbours still slide out of its way.
    fun profilePlacementSpec(id: String) =
        if (id == draggedItemId) null else profileItemPlacementSpec

    var autoScrollSpeed by remember { mutableFloatStateOf(0f) }
    var fingerAbsoluteY by remember { mutableFloatStateOf(0f) }
    var dragAnchorOffset by remember { mutableFloatStateOf(0f) }
    var optimisticSelectedId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentId) {
        optimisticSelectedId = null
    }

    val showRenameDialog = remember { mutableStateOf<Profile?>(null) }
    val showDeleteConfirm = remember { mutableStateOf<Profile?>(null) }
    val showBulkDeleteConfirm = remember { mutableStateOf<List<String>?>(null) }
    val showCloneDialog = remember { mutableStateOf<Profile?>(null) }

    val selectedIds = remember { mutableStateListOf<String>() }
    val isSelectionMode = selectedIds.isNotEmpty()

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

    // Listens for scroll-to-profile requests for the dialog's whole lifetime - a single persistent
    // subscription, not re-created on every profilesSource change (that used to leak a new collector
    // per list change, see the LaunchedEffect(profilesSource) below for the list-sync logic itself).
    LaunchedEffect(Unit) {
        com.wireturn.app.ui.UIEventBus.scrollRequest.collect { targetId ->
            // Подсвечиваем цель и всё связанное (профили подписки или заголовок профиля)
            val relatedIds = mutableListOf(targetId)
            profilesSource.filter { it.subscriptionId == targetId }
                .forEach { relatedIds.add(it.id) }
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
            // One lookup map instead of a find() per profile: with a subscription of hundreds of
            // profiles the nested scan was tens of thousands of deep equals() on the main thread
            // for every refresh.
            val updatedOrNew = if (oldSize > 0) {
                val oldById = profiles.associateBy { it.id }
                profilesSource.filter { newP -> oldById[newP.id] != newP }
            } else emptyList()
            val updatedOrNewIds = updatedOrNew.map { it.id }

            androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                profiles.clear()
                profiles.addAll(profilesSource)
            }

            if (updatedOrNewIds.isNotEmpty()) {
                // Если профили принадлежат подписке, подсвечиваем и её заголовок (даже если подписка не новая).
                val subIdsToHighlight = updatedOrNew.mapNotNull { it.subscriptionId }.distinct()

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

                if (isFirstLoad) {
                    // Land with a couple of profiles already visible above the selected one
                    // instead of pinning it to the very top edge, so it reads less abruptly.
                    // Subscription headers count as a block here too (findLazyIndex resolves a
                    // subscription id to its header row) - otherwise a profile that's first in
                    // its subscription would land right at the top with its own header scrolled
                    // out of view above it.
                    val visualOrderIds = visualGroups.flatMap { (sub, groupProfiles) ->
                        (if (sub != null) listOf(sub.id) else emptyList()) + groupProfiles.map { it.id }
                    }
                    val currentVisualIdx = visualOrderIds.indexOf(currentId)
                    val anchorId = if (currentVisualIdx >= 0) {
                        visualOrderIds[(currentVisualIdx - PROFILES_ABOVE_SELECTED_ON_OPEN).coerceAtLeast(
                            0
                        )]
                    } else {
                        currentId
                    }
                    val targetLazyIndex = findLazyIndex(anchorId)
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
        val draggedItem =
            lazyListState.layoutInfo.visibleItemsInfo.find { it.key == currentId } ?: return 0f
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
            val visualTop =
                if (maxY >= minY) desiredAbsoluteY().coerceIn(minY, maxY) else desiredAbsoluteY()
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
                val targetProfileIdx =
                    profiles.indexOfFirst { it.id == targetProfileId }.takeIf { it != -1 } ?: break

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

    // Read by the dragged row in its graphicsLayer only - so a finger move redraws that one row
    // instead of recomposing every visible one with a new offset.
    val dragOffsetProvider: () -> Float = remember { { clampedDragOffset() } }

    // TalkBack's way to reorder (the row's custom actions): the step a drag across one neighbour
    // makes, within the profile's own group, saved the same way a drop is.
    fun moveProfile(id: String, step: Int) {
        val from = profiles.indexOfFirst { it.id == id }.takeIf { it != -1 } ?: return
        val group = profiles[from].subscriptionId
        var to = from + step
        while (to in profiles.indices && profiles[to].subscriptionId != group) to += step
        if (to !in profiles.indices) return
        profiles.add(to, profiles.removeAt(from))
        viewModel.reorderProfiles(profiles.toList())
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
                } catch (e: CancellationException) {
                    // The drag ended (the effect is restarting) - not a failed tick.
                    throw e
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

                // Второй жест, начатый уже с упора - однозначный драг шторки, не мешаем.
                if (gestureStartedAtTop) {
                    return Offset.Zero
                }

                // Упёрлись в верх ВНУТРИ жеста - в шторку не пускаем (иначе она поедет),
                // рисуем вместо этого нативный stretch через topOverscroll (performScroll
                // ничего не отдаёт дальше, поэтому весь остаток уходит в растяжку).
                if (available.y > 0) {
                    return topOverscroll?.applyToScroll(available, source) { Offset.Zero }
                        ?: available
                }

                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (gestureStartedAtTop) {
                    return Velocity.Zero
                }
                if (available.y > 0 && !lazyListState.canScrollBackward) {
                    topOverscroll?.applyToFling(available) { Velocity.Zero }
                    return available
                }
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (gestureStartedAtTop) {
                    return Velocity.Zero
                }
                if (available.y > 0) {
                    topOverscroll?.applyToFling(available) { Velocity.Zero }
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    val export = rememberProfilesExport(viewModel, profiles)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        dragHandle = {
            ProfilesSheetHeader(
                export = export,
                isSelectionMode = isSelectionMode,
                selectedCount = selectedIds.size,
                allSelected = selectedIds.size == profiles.size,
                hasProfiles = profiles.isNotEmpty(),
                hasSubscriptions = subscriptions.isNotEmpty(),
                anySubscriptionExpanded = subscriptions.any { !it.collapsed },
                onExportSelected = { export.onExportClick(context, selectedIds.toList(), true) },
                onDeleteSelected = { showBulkDeleteConfirm.value = selectedIds.toList() },
                onToggleSelectAll = {
                    val isAllSelected = selectedIds.size == profiles.size
                    if (isAllSelected) {
                        selectedIds.clear()
                    } else {
                        selectedIds.clear()
                        selectedIds.addAll(profiles.map { it.id })
                    }
                },
                onToggleAllCollapsed = {
                    val anyExpanded = subscriptions.any { !it.collapsed }
                    if (anyExpanded) scope.launch { lazyListState.scrollToItem(0) }
                    viewModel.setAllSubscriptionsCollapsed(anyExpanded)
                },
                onExportAll = { export.onExportClick(context, profiles.map { it.id }, true) }
            )
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
                overscrollEffect = topOverscroll,
                modifier = Modifier
                    .heightIn(max = 640.dp)
                    .fillMaxWidth()
                    .nestedScroll(noDismissNestedScroll)
                    .trackGestureStartedAtBoundary(
                        atBoundary = { !lazyListState.canScrollBackward },
                        onGestureStart = { gestureStartedAtTop = it }
                    )
                    .pointerInput(isSelectionMode) {
                        if (isSelectionMode) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val longPress =
                                awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture

                            // A stuck header is rendered pinned at the top, covering whatever
                            // item is really scrolled underneath it there - that item's true
                            // (unclamped) offset can still be within touch range, so without this
                            // guard a long press on the visible header could hit-test straight
                            // through to the hidden row and start dragging it.
                            val pinnedHeaderBottom = lazyListState.layoutInfo.visibleItemsInfo
                                .filter { (it.key as? String)?.startsWith("sub_header_") == true && it.offset <= 0 }
                                .maxOfOrNull { it.offset + it.size } ?: 0
                            if (longPress.position.y < pinnedHeaderBottom) return@awaitEachGesture

                            val hitItem =
                                lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                                    val key = item.key
                                    key is String &&
                                            !key.startsWith("sub_header_") &&
                                            key != "standalone_top_guard" &&
                                            longPress.position.y >= item.offset &&
                                            longPress.position.y < item.offset + item.size
                                } ?: return@awaitEachGesture
                            val targetId = hitItem.key as String

                            // A subscription sorted by ping shows its profiles in an order that
                            // isn't the stored one the drag rearranges - no manual reorder there.
                            val targetSubId = profiles.find { it.id == targetId }?.subscriptionId
                            if (targetSubId != null &&
                                subscriptions.find { it.id == targetSubId }?.sortMode == Subscription.SORT_PING
                            ) return@awaitEachGesture

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
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                contentPadding = PaddingValues(
                    top = 8.dp,
                    bottom = 8.dp + bottomPadding + 24.dp
                )
            ) {
                if (profiles.isEmpty() && subscriptions.isEmpty()) {
                    item {
                        ProfilesEmptyState()
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
                            dragOffset = dragOffsetProvider,
                            onMove = if (isSelectionMode || draggedItemId != null) null
                            else { step -> moveProfile(profile.id, step) },
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
                            onExportClick = { targets, showMenu ->
                                export.onExportClick(
                                    context,
                                    targets,
                                    showMenu
                                )
                            },
                            onExportActionSelected = { export.onActionSelected(it) },
                            onClone = { showCloneDialog.value = it },
                            onRename = { showRenameDialog.value = it },
                            onDelete = { showDeleteConfirm.value = it },
                            onOptimisticSelect = { optimisticSelectedId = it },
                            modifier = Modifier.animateItem(
                                placementSpec = profilePlacementSpec(profile.id)
                            )
                        )
                    }

                    // Subscription Groups
                    subscriptionGroups.forEach { (sub, subProfiles) ->
                        val isAnySelected =
                            subProfiles.any { it.id == (optimisticSelectedId ?: currentId) }
                        val isUpdating = updatingSubIds.contains(sub.id)

                        stickyHeader(key = "sub_header_${sub.id}") {
                            // findLazyIndex is an O(profiles + subscriptions) scan - resolved only
                            // when the visual grouping actually changes, not on every pixel of
                            // scroll (lazyListState.firstVisibleItemIndex/Offset change on every
                            // pixel, and used to be read inside the same derivedStateOf that also
                            // called findLazyIndex, re-running the whole scan every scroll frame).
                            val headerIdx = remember(sub.id, visualGroups) { findLazyIndex(sub.id) }
                            // 0f = resting at its natural spot, 1f = fully pinned. Scrubs with
                            // firstVisibleItemScrollOffset while this header is still the first
                            // visible item (i.e. as soon as scrolling starts eating into it, which
                            // is well before a full item's worth of content has passed underneath
                            // it), then latches to 1f once the list has scrolled past it entirely.
                            val stuckProgress by remember(headerIdx) {
                                derivedStateOf {
                                    when {
                                        headerIdx == -1 -> 0f
                                        lazyListState.firstVisibleItemIndex > headerIdx -> 1f
                                        lazyListState.firstVisibleItemIndex == headerIdx ->
                                            (lazyListState.firstVisibleItemScrollOffset / headerStuckTransitionPx).coerceIn(
                                                0f,
                                                1f
                                            )

                                        else -> 0f
                                    }
                                }
                            }
                            SubscriptionHeaderRow(
                                sub = sub,
                                isAnyChildSelected = isAnySelected,
                                isSelectionMode = isSelectionMode,
                                isAllSubSelected = subProfiles.isNotEmpty() && subProfiles.all {
                                    selectedIds.contains(
                                        it.id
                                    )
                                },
                                isUpdating = isUpdating,
                                stuckProgress = stuckProgress,
                                onUpdate = { refreshSubscription(sub) },
                                onSettings = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                    val intent = Intent(
                                        context,
                                        SubscriptionConfigActivity::class.java
                                    ).apply {
                                        putExtra("EXTRA_SUB_ID", sub.id)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                },
                                onSelect = {
                                    val targetId =
                                        subProfiles.find { it.id == sub.activeProfileId }?.id
                                            ?: subProfiles.firstOrNull()?.id
                                    if (targetId != null) {
                                        optimisticSelectedId = targetId
                                        viewModel.selectProfileAndRestart(targetId)
                                    }
                                },
                                onToggleSelection = {
                                    val allInSub = subProfiles.map { it.id }
                                    val isAllInSubSelected =
                                        allInSub.all { selectedIds.contains(it) }
                                    if (isAllInSubSelected) {
                                        selectedIds.removeAll(allInSub)
                                    } else {
                                        selectedIds.addAll(allInSub.filter { it !in selectedIds })
                                    }
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                },
                                isHighlighted = highlightedIds.contains(sub.id),
                                onTogglePinned = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                    viewModel.updateSubscriptionById(sub.id) { it.copy(pinned = !it.pinned) }
                                },
                                onToggleSortByPing = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                    viewModel.updateSubscriptionById(sub.id) {
                                        it.copy(sortMode = if (it.sortMode == Subscription.SORT_PING) Subscription.SORT_SERVER else Subscription.SORT_PING)
                                    }
                                },
                                isTestingPings = sub.id in testingSubIds,
                                onTestPings = if (subProfiles.none { it.kernelConfig is com.wireturn.app.data.KernelConfig.Xray }) null else {
                                    {
                                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                        if (sub.id in testingSubIds) viewModel.stopSubscriptionPingTest(
                                            sub.id
                                        )
                                        else viewModel.testSubscriptionPings(sub.id)
                                    }
                                },
                                onToggleCollapsed = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                    // Collapsing from deep inside the subscription (its header stuck
                                    // at the top, its real spot scrolled far above) would leave the
                                    // list somewhere past it - bring the header back to the top.
                                    // Its index doesn't depend on its own profiles, which follow it.
                                    if (!sub.collapsed && headerIdx != -1 &&
                                        lazyListState.firstVisibleItemIndex > headerIdx
                                    ) {
                                        scope.launch { lazyListState.animateScrollToItem(headerIdx) }
                                    }
                                    viewModel.updateSubscriptionById(sub.id) { it.copy(collapsed = !it.collapsed) }
                                }
                            )
                        }

                        // A collapsed subscription is its header alone (see findLazyIndex).
                        if (!sub.collapsed) itemsIndexed(
                            subProfiles,
                            key = { _, it -> it.id }) { index, profile ->
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
                                dragOffset = dragOffsetProvider,
                                // A subscription sorted by ping isn't in the stored order a move
                                // rearranges - same as the drag, no manual reorder there.
                                onMove = if (isSelectionMode || draggedItemId != null ||
                                    sub.sortMode == Subscription.SORT_PING
                                ) null else { step -> moveProfile(profile.id, step) },
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
                                onExportClick = { targets, showMenu ->
                                    export.onExportClick(
                                        context,
                                        targets,
                                        showMenu
                                    )
                                },
                                onExportActionSelected = { export.onActionSelected(it) },
                                onClone = { showCloneDialog.value = it },
                                onRename = { showRenameDialog.value = it },
                                onDelete = { showDeleteConfirm.value = it },
                                onOptimisticSelect = { optimisticSelectedId = it },
                                modifier = Modifier.animateItem(
                                    placementSpec = profilePlacementSpec(profile.id)
                                )
                            )
                        }

                        if (subProfiles.isEmpty() && !sub.collapsed) {
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
        ProfilesDeleteDialog(
            title = stringResource(R.string.profile_delete_confirm, profile.name),
            onConfirm = {
                viewModel.deleteProfile(profile.id)
                showDeleteConfirm.value = null
            },
            onDismiss = { showDeleteConfirm.value = null }
        )
    }

    showBulkDeleteConfirm.value?.let { ids ->
        ProfilesDeleteDialog(
            title = stringResource(R.string.profile_delete_selected_confirm, ids.size),
            onConfirm = {
                viewModel.deleteProfiles(ids)
                selectedIds.clear()
                showBulkDeleteConfirm.value = null
            },
            onDismiss = { showBulkDeleteConfirm.value = null }
        )
    }

    showManualUpdateTunnelWarning.value?.let { sub ->
        ManualUpdateNoTunnelDialog(
            onProceed = {
                val url = sub.url
                showManualUpdateTunnelWarning.value = null
                // Proceed without tunnel check again
                scope.launch { doRefreshSubscription(url) }
            },
            onDismiss = { showManualUpdateTunnelWarning.value = null }
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
    // The dragged row's offset from its slot - a provider, read only while drawing.
    dragOffset: () -> Float,
    // Moves this profile a step (-1 up, +1 down) within its group; null where it can't be
    // reordered. Exposed as accessibility actions.
    onMove: ((Int) -> Unit)?,
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
    val profileCountries by viewModel.profileCountries.collectAsStateWithLifecycle()
    val profilePings by viewModel.profilePings.collectAsStateWithLifecycle()
    val pingingProfileIds by viewModel.pingingProfileIds.collectAsStateWithLifecycle()

    val isDraggedState = rememberUpdatedState(isDragged)
    val onDragEndState = rememberUpdatedState(onDragEnd)
    DisposableEffect(profile.id) {
        onDispose {
            if (isDraggedState.value) {
                Log.e(
                    "ProfileDrag",
                    "composable disposed mid-drag for profile=${profile.id}, forcing reset"
                )
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
        totalInGroup == 1 && !isInsideSubscription -> MaterialTheme.shapes.medium
        isInsideSubscription -> {
            if (index == totalInGroup - 1) RoundedCornerShape(
                topStart = 4.dp,
                topEnd = 4.dp,
                bottomStart = 16.dp,
                bottomEnd = 16.dp
            )
            else RoundedCornerShape(4.dp)
        }

        index == 0 -> RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomStart = 4.dp,
            bottomEnd = 4.dp
        )

        index == totalInGroup - 1 -> RoundedCornerShape(
            topStart = 4.dp,
            topEnd = 4.dp,
            bottomStart = 16.dp,
            bottomEnd = 16.dp
        )

        else -> RoundedCornerShape(4.dp)
    }

    // Follows the dragged offset while dragging (outside composition), so on release it springs
    // back into the row's slot from exactly where the row was drawn.
    val releaseOffset = remember { Animatable(0f) }
    LaunchedEffect(isDragged) {
        if (isDragged) {
            snapshotFlow { dragOffset() }.collect { releaseOffset.snapTo(it) }
        } else {
            releaseOffset.animateTo(
                0f,
                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
            )
        }
    }

    // The drag as an interaction of the item: it draws the dragged state itself.
    val interactionSource = remember { MutableInteractionSource() }
    // The long press that starts a drag stays a press of the item until the finger moves (only
    // then does the list's drag consume it) - its ripple stacked on the dragged state layer, and
    // the pressed shape outranked the dragged one, so the row flashed bright on pickup and only
    // settled once moved. Tracked here to end it the moment the drag starts.
    val activePress = remember { arrayOfNulls<PressInteraction.Press>(1) }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> activePress[0] = interaction
                is PressInteraction.Release ->
                    if (activePress[0] == interaction.press) activePress[0] = null
                is PressInteraction.Cancel ->
                    if (activePress[0] == interaction.press) activePress[0] = null
            }
        }
    }
    LaunchedEffect(isDragged) {
        if (isDragged) {
            activePress[0]?.let { interactionSource.emit(PressInteraction.Cancel(it)) }
            val start = DragInteraction.Start()
            interactionSource.emit(start)
            try {
                awaitCancellation()
            } finally {
                interactionSource.tryEmit(DragInteraction.Stop(start))
            }
        }
    }

    // A slight lift on top of the dragged state's shadow and shape - the app's own touch, not a
    // token. Read in graphicsLayer only, like the offset.
    val dragScale by animateFloatAsState(
        targetValue = if (isDragged) 1.02f else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "drag_scale"
    )

    val moveUpLabel = stringResource(R.string.profile_move_up)
    val moveDownLabel = stringResource(R.string.profile_move_down)
    val moveActions = if (onMove == null) emptyList() else listOfNotNull(
        CustomAccessibilityAction(moveUpLabel) { onMove(-1); true }.takeIf { index > 0 },
        CustomAccessibilityAction(moveDownLabel) { onMove(1); true }
            .takeIf { index < totalInGroup - 1 }
    )

    ProfileListItem(
        profile = profile,
        isSelected = isSelected,
        isHighlighted = highlightedIds.contains(profile.id),
        countryCode = profileCountries[profile.id],
        pingMs = profilePings[profile.id],
        isPinging = profile.id in pingingProfileIds,
        checked = if (isSelectionMode) isSelectedInMode else null,
        shape = itemShape,
        interactionSource = interactionSource,
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
                modifier = Modifier.size(32.dp),
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            )
        },
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (moveActions.isEmpty()) Modifier
                else Modifier.semantics { customActions = moveActions }
            )
            .zIndex(if (isDragged) 10f else 0f)
            .graphicsLayer {
                translationY = if (isDragged) dragOffset() else releaseOffset.value
                scaleX = dragScale
                scaleY = dragScale
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
                        Icon(
                            painterResource(R.drawable.more_vert_24px),
                            contentDescription = stringResource(R.string.profile_actions)
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
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
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
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                onClone(profile)
                            }
                        )
                        // Only an Xray-only profile can be pinged without bringing a tunnel up.
                        if (profile.kernelConfig is com.wireturn.app.data.KernelConfig.Xray) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.subscription_test_ping)) },
                                leadingIcon = {
                                    Icon(
                                        painterResource(R.drawable.wifi_24px),
                                        null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                enabled = profile.id !in pingingProfileIds,
                                onClick = {
                                    menuExpanded = false
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                    viewModel.testProfilePing(profile.id)
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profile_edit)) },
                            leadingIcon = {
                                Icon(
                                    painterResource(R.drawable.edit_square_24px),
                                    null,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                editMenuExpanded = true
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
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                onRename(profile)
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
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                itemExportActionMenuExpanded = true
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
                                val intent =
                                    Intent(context, configActivityClassFor(profile.kernelVariant))
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
                                val intent = Intent(
                                    context,
                                    com.wireturn.app.ui.activities.XrayEditActivity::class.java
                                )
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
    stuckProgress: Float,
    onUpdate: () -> Unit,
    onSettings: () -> Unit,
    onSelect: () -> Unit,
    onToggleSelection: () -> Unit,
    modifier: Modifier = Modifier,
    isHighlighted: Boolean = false,
    onTogglePinned: () -> Unit = {},
    onToggleSortByPing: () -> Unit = {},
    onToggleCollapsed: () -> Unit = {},
    // Null: nothing in it can be pinged on its own (no Xray-only profiles).
    onTestPings: (() -> Unit)? = null,
    isTestingPings: Boolean = false
) {
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isHighlighted -> MaterialTheme.colorScheme.surfaceVariant
            isAnyChildSelected -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "sub_header_bg"
    )

    // Pinned at the top, the header is visually detached from the item now scrolled underneath
    // it, so it reads as a standalone rounded chip (bottom corners matching the top ones, plus a
    // shadow to sell that it's floating above the content) instead of the "attached to what's
    // below" look it has at its natural, unpinned position. Driven directly off stuckProgress -
    // no animate*AsState here - so it scrubs in lockstep with the scroll instead of playing its
    // own timed animation once some threshold is crossed.
    // Collapsed, nothing hangs below it - all four corners round, like a single item.
    val restingBottomCorner by animateDpAsState(
        targetValue = if (sub.collapsed) 16.dp else 4.dp,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "sub_header_bottom_corner"
    )
    val bottomCorner = lerp(restingBottomCorner, 16.dp, stuckProgress)
    val shadowElevation = lerp(0.dp, 6.dp, stuckProgress)
    // M3(E)'s own collapsing app bars shrink toward a smaller/compact bar on scroll (never grow),
    // and the standard "more compact" motion cue elsewhere in the system is a scale-down, not up
    // (e.g. press states settle to ~0.95). So pinned reads as a slightly inset, compact chip -
    // smaller than the list around it - rather than wider.
    val stuckInset = lerp(0.dp, 4.dp, stuckProgress)

    val quota = sub.quotaOrNull()
    // Where the title starts: the row's own start padding plus the leading control - a 48dp
    // chevron button, or a 48dp checkbox with its 16dp gap. The quota line starts there too.
    val rowStart = if (isSelectionMode) 16.dp else 4.dp
    val titleStart = rowStart + if (isSelectionMode) 64.dp else 48.dp
    val quotaColors = quota?.let { quotaColors(it, isAnyChildSelected) }

    Surface(
        color = backgroundColor,
        shape = RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomStart = bottomCorner,
            bottomEnd = bottomCorner
        ),
        shadowElevation = shadowElevation,
        onClick = {
            if (isSelectionMode) onToggleSelection()
            else if (!isAnyChildSelected) onSelect()
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .padding(horizontal = stuckInset)
    ) {
        Box {
            Column(modifier = Modifier.padding(bottom = 16.dp)) {
                Row(
                    // The leading chevron button brings its own padding - less of the row's own before it.
                    modifier = Modifier.padding(
                        start = rowStart,
                        top = 16.dp,
                        end = 8.dp
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isSelectionMode) {
                        Checkbox(
                            checked = isAllSubSelected,
                            onCheckedChange = { onToggleSelection() },
                            modifier = Modifier.padding(end = 16.dp)
                        )
                    } else {
                        // A leading disclosure chevron: pointing right while collapsed, down while open.
                        val chevronRotation by animateFloatAsState(
                            targetValue = if (sub.collapsed) -90f else 0f,
                            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                            label = "sub_chevron"
                        )
                        IconButton(onClick = onToggleCollapsed) {
                            Icon(
                                painter = painterResource(R.drawable.expand_more_24px),
                                contentDescription = stringResource(if (sub.collapsed) R.string.subscription_expand else R.string.subscription_collapse),
                                tint = if (isAnyChildSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .size(20.dp)
                                    .graphicsLayer { rotationZ = chevronRotation }
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (sub.pinned) {
                                Icon(
                                    painter = painterResource(R.drawable.keep_24px),
                                    contentDescription = stringResource(R.string.subscription_pinned),
                                    tint = if (isAnyChildSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .padding(end = 4.dp)
                                        .size(16.dp)
                                )
                            }
                            Text(
                                text = sub.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = if (isAnyChildSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                modifier = Modifier.basicMarquee()
                            )
                        }
                        if (isTestingPings) {
                            Text(
                                text = stringResource(R.string.subscription_testing_ping),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isAnyChildSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary,
                                maxLines = 1
                            )
                        } else if (sub.updatedAt > 0) {
                            // When the last successful refresh landed (0 = never refreshed yet).
                            val context = LocalContext.current
                            val updatedAt = remember(sub.updatedAt) {
                                android.text.format.DateUtils.formatDateTime(
                                    context, sub.updatedAt,
                                    android.text.format.DateUtils.FORMAT_SHOW_DATE or
                                            android.text.format.DateUtils.FORMAT_SHOW_TIME or
                                            android.text.format.DateUtils.FORMAT_ABBREV_MONTH
                                )
                            }
                            Text(
                                text = stringResource(R.string.subscription_updated_at, updatedAt),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isAnyChildSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.outline,
                                maxLines = 1
                            )
                        }
                        if (!sub.description.isNullOrBlank()) {
                            // Keeps the description from running into the update time line above.
                            if (sub.updatedAt > 0) Spacer(Modifier.height(4.dp))
                            SubscriptionDescriptionText(
                                text = sub.description,
                                color = if (isAnyChildSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.outline,
                                stuckProgress = stuckProgress
                            )
                        }
                        if (!sub.announce.isNullOrBlank()) {
                            if (sub.updatedAt > 0 || !sub.description.isNullOrBlank()) {
                                Spacer(Modifier.height(4.dp))
                            }
                            SubscriptionAnnounce(
                                subscriptionId = sub.id,
                                text = sub.announce,
                                color = if (isAnyChildSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary,
                                stuckProgress = stuckProgress
                            )
                        }
                    }
                    if (!isSelectionMode) {
                        val iconTint =
                            if (isAnyChildSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
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
                                    tint = iconTint
                                )
                            }
                        }

                        var showMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.more_vert_24px),
                                    contentDescription = stringResource(R.string.subscription_actions),
                                    tint = iconTint,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            AppDropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                                title = stringResource(R.string.subscription_actions)
                            ) {
                                // Expressive items, each shaped by its place in the group; the ping test
                                // is only there with a handler for it.
                                val providerLinks = listOfNotNull(
                                    sub.supportUrl?.let { Triple(R.string.subscription_support, R.drawable.support_agent_24px, it) },
                                    sub.webPageUrl?.let { Triple(R.string.subscription_web_page, R.drawable.language_24px, it) }
                                )
                                // The subscription's own actions, then - past a divider - the provider's
                                // links; each part shapes its items on its own (MenuDefaults.itemShape).
                                val itemCount = if (onTestPings != null) 4 else 3
                                var itemIndex = 0
                                DropdownMenuItem(
                                    onClick = { showMenu = false; onTogglePinned() },
                                    text = { Text(stringResource(if (sub.pinned) R.string.subscription_unpin else R.string.subscription_pin)) },
                                    shape = MenuDefaults.itemShape(itemIndex++, itemCount).shape,
                                    leadingIcon = {
                                        Icon(
                                            painterResource(if (sub.pinned) R.drawable.keep_off_24px else R.drawable.keep_24px),
                                            null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                )
                                if (onTestPings != null) {
                                    DropdownMenuItem(
                                        onClick = { showMenu = false; onTestPings() },
                                        text = {
                                            Text(stringResource(if (isTestingPings) R.string.subscription_test_ping_stop else R.string.subscription_test_ping))
                                        },
                                        shape = MenuDefaults.itemShape(itemIndex++, itemCount).shape,
                                        leadingIcon = {
                                            Icon(
                                                painterResource(if (isTestingPings) R.drawable.stop_24px else R.drawable.wifi_24px),
                                                null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    )
                                }
                                // An on/off setting: announced as a checkbox with its state, the check
                                // standing in for the sort icon while it's on.
                                CheckableDropdownMenuItem(
                                    checked = sub.sortMode == Subscription.SORT_PING,
                                    onCheckedChange = { showMenu = false; onToggleSortByPing() },
                                    text = { Text(stringResource(R.string.subscription_sort_by_ping)) },
                                    shapes = MenuDefaults.itemShape(itemIndex++, itemCount),
                                    // Only the checked state stands out, like the plain items around it.
                                    colors = MenuDefaults.selectableItemColors(containerColor = Color.Transparent),
                                    leadingIcon = {
                                        Icon(
                                            painterResource(R.drawable.sort_24px),
                                            null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    checkedLeadingIcon = {
                                        Icon(
                                            painterResource(R.drawable.check_24px),
                                            null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    onClick = { showMenu = false; onSettings() },
                                    text = { Text(stringResource(R.string.subscription_settings)) },
                                    shape = MenuDefaults.itemShape(itemIndex, itemCount).shape,
                                    leadingIcon = {
                                        Icon(
                                            painterResource(R.drawable.settings_24px),
                                            null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                )
                                // The provider's support chat and website, when its panel sends them -
                                // set apart: they leave the app instead of acting on the subscription.
                                if (providerLinks.isNotEmpty()) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(MenuDefaults.HorizontalDividerPadding)
                                    )
                                    val uriHandler = LocalUriHandler.current
                                    providerLinks.forEachIndexed { linkIndex, (labelRes, iconRes, link) ->
                                        DropdownMenuItem(
                                            onClick = {
                                                showMenu = false
                                                // No app for the link (e.g. tg:// without Telegram) - nothing to open.
                                                runCatching { uriHandler.openUri(link) }
                                            },
                                            text = { Text(stringResource(labelRes)) },
                                            shape = MenuDefaults.itemShape(linkIndex, providerLinks.size).shape,
                                            leadingIcon = {
                                                Icon(
                                                    painterResource(iconRes),
                                                    null,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }

                    }
                }
                // Under the whole row - buttons included - instead of squeezed into the text column
                // beside them; the buttons stay centered on the title block above.
                if (quota != null && quotaColors != null) {
                    SubscriptionQuotaRow(
                        quota = quota,
                        colors = quotaColors,
                        textColor = if (isAnyChildSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.outline,
                        isAnyChildSelected = isAnyChildSelected,
                        stuckProgress = stuckProgress,
                        modifier = Modifier.padding(start = titleStart, end = 20.dp)
                    )
                }
            }
            // Pinned, the quota line above folds away - its bar lives on as a thin strip along the
            // chip's bottom edge, inside the row's bottom padding, so the remaining traffic stays
            // in sight without making the pinned header taller.
            if (quota != null && quota.hasLimit && quotaColors != null && stuckProgress > 0f) {
                LinearProgressIndicator(
                    progress = { quota.fraction },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                        .height(3.dp)
                        .graphicsLayer { alpha = stuckProgress }
                        .clearAndSetSemantics {},
                    color = quotaColors.bar,
                    trackColor = quotaColors.track,
                    gapSize = 0.dp,
                    drawStopIndicator = {}
                )
            }
        }
    }
}

/**
 * The provider's notice in the subscription header: its own color to stand apart from the
 * description, and two lines at most - panels send whole paragraphs (blank lines dropped here),
 * which would make the header taller than the profiles under it. When it doesn't fit, a small
 * "More"/"Less" toggle under it opens it in place - a tap of its own, since a tap on the header
 * selects the subscription. Open, it folds back to two lines as the header pins, like the
 * description does.
 */
@Composable
private fun SubscriptionAnnounce(
    subscriptionId: String,
    text: String,
    color: Color,
    stuckProgress: Float
) {
    val compact = remember(text) {
        text.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.joinToString("\n")
    }
    var expanded by rememberSaveable(subscriptionId) { mutableStateOf(false) }
    var overflows by remember(compact) { mutableStateOf(false) }
    val style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 18.sp)

    Column(modifier = Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())) {
        if (expanded) {
            SubscriptionDescriptionText(text = compact, color = color, stuckProgress = stuckProgress)
        } else {
            Text(
                text = compact,
                style = style,
                color = color,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { result -> if (result.hasVisualOverflow) overflows = true }
            )
        }

        val shown = 1f - stuckProgress
        if ((overflows || expanded) && shown > 0f) {
            Text(
                text = stringResource(
                    if (expanded) R.string.subscription_announce_less else R.string.subscription_announce_more
                ),
                style = MaterialTheme.typography.labelMedium,
                color = color,
                modifier = Modifier
                    // Folds away with the pin, like the quota line.
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, (placeable.height * shown).roundToInt()) {
                            placeable.placeRelative(0, 0)
                        }
                    }
                    .graphicsLayer {
                        alpha = shown
                        clip = true
                    }
                    .padding(top = 2.dp)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .padding(vertical = 4.dp)
            )
        }
    }
}

// The year only for a date in another one - "Dec 6", not "Dec 6, 2026".
private fun noYearIfThisYear(millis: Long): Int {
    val year = { t: Long -> java.util.Calendar.getInstance().apply { timeInMillis = t }.get(java.util.Calendar.YEAR) }
    return if (year(millis) == year(System.currentTimeMillis())) android.text.format.DateUtils.FORMAT_NO_YEAR else 0
}

/** What a subscription says about its traffic and term; null when it says nothing of either. */
private data class SubscriptionQuota(
    val used: Long,
    val total: Long,
    val expiresAt: Long,
    val refillAt: Long = 0
) {
    val hasLimit: Boolean get() = total > 0
    val fraction: Float get() = if (total > 0) (used.toFloat() / total).coerceIn(0f, 1f) else 0f
    // The last 10% (or past the limit) - the bar and its line turn to the error color.
    val nearlyOut: Boolean get() = hasLimit && used >= total * 0.9
}

private fun Subscription.quotaOrNull(): SubscriptionQuota? =
    if (bytesTotal > 0 || bytesUsed > 0 || expiresAt > 0) {
        SubscriptionQuota(used = bytesUsed, total = bytesTotal, expiresAt = expiresAt, refillAt = refillAt)
    } else null

private data class QuotaColors(val bar: Color, val track: Color)

@Composable
private fun quotaColors(quota: SubscriptionQuota, isAnyChildSelected: Boolean): QuotaColors {
    val scheme = MaterialTheme.colorScheme
    return when {
        // On the selected header's secondaryContainer the default track would vanish.
        isAnyChildSelected -> QuotaColors(
            scheme.onSecondaryContainer,
            scheme.onSecondaryContainer.copy(alpha = 0.24f)
        )
        quota.nearlyOut -> QuotaColors(scheme.error, scheme.errorContainer)
        else -> QuotaColors(scheme.primary, ProgressIndicatorDefaults.linearTrackColor)
    }
}

/**
 * The header's quota line: a bar of the used share (with a limit) and "used / total", plus when
 * the subscription runs out. Folds away in step with [stuckProgress] - pinned, a thin strip along
 * the header's bottom edge stands in for it (see SubscriptionHeaderRow).
 */
@Composable
private fun SubscriptionQuotaRow(
    quota: SubscriptionQuota,
    colors: QuotaColors,
    textColor: Color,
    isAnyChildSelected: Boolean,
    stuckProgress: Float,
    modifier: Modifier = Modifier
) {
    val shown = 1f - stuckProgress
    if (shown <= 0f) return

    val context = LocalContext.current
    val expired = quota.expiresAt > 0 && quota.expiresAt <= System.currentTimeMillis()
    val expiresText = if (quota.expiresAt > 0) {
        val date = remember(quota.expiresAt) {
            android.text.format.DateUtils.formatDateTime(
                context, quota.expiresAt,
                android.text.format.DateUtils.FORMAT_SHOW_DATE or
                        android.text.format.DateUtils.FORMAT_ABBREV_MONTH or
                        noYearIfThisYear(quota.expiresAt)
            )
        }
        stringResource(
            if (expired) R.string.subscription_expired else R.string.subscription_expires,
            date
        )
    } else null
    val trafficText = when {
        quota.hasLimit -> stringResource(
            R.string.subscription_traffic_of,
            formatBytes(quota.used),
            formatBytes(quota.total)
        )
        quota.used > 0 -> stringResource(R.string.subscription_traffic_used, formatBytes(quota.used))
        else -> null
    }
    val trafficColor =
        if (!isAnyChildSelected && quota.nearlyOut) MaterialTheme.colorScheme.error else textColor
    val expiresColor =
        if (!isAnyChildSelected && expired) MaterialTheme.colorScheme.error else textColor
    val barDescription = if (quota.hasLimit) stringResource(
        R.string.subscription_traffic_a11y,
        formatBytes(quota.used),
        formatBytes(quota.total)
    ) else null

    // Two lines, so neither squeezes the other: the numbers (traffic at the start, the term at the
    // end), then the bar across the whole width.
    Column(
        modifier = modifier
            // Scrubbed with the pin like the description: the height shrinks to nothing as the
            // header sticks, clipped, fading out - not a timed animation.
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, (placeable.height * shown).roundToInt()) {
                    placeable.placeRelative(0, 0)
                }
            }
            .graphicsLayer {
                alpha = shown
                clip = true
            }
            .padding(top = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (trafficText != null) {
                Text(
                    text = trafficText,
                    style = MaterialTheme.typography.labelSmall,
                    color = trafficColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            if (expiresText != null) {
                Text(
                    text = expiresText,
                    style = MaterialTheme.typography.labelSmall,
                    color = expiresColor,
                    maxLines = 1,
                    modifier = Modifier.padding(start = if (trafficText != null) 8.dp else 0.dp)
                )
            }
        }
        if (quota.hasLimit && barDescription != null) {
            LinearProgressIndicator(
                progress = { quota.fraction },
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .semantics { contentDescription = barDescription },
                color = colors.bar,
                trackColor = colors.track
            )
        }
        // A limit that resets (e.g. daily): when, so "X / 50 GiB" isn't read as the whole term's.
        // A date already past is stale until the next refresh - left out.
        if (quota.hasLimit && quota.refillAt > System.currentTimeMillis()) {
            val refill = remember(quota.refillAt) {
                android.text.format.DateUtils.formatDateTime(
                    context, quota.refillAt,
                    android.text.format.DateUtils.FORMAT_SHOW_DATE or
                            android.text.format.DateUtils.FORMAT_SHOW_TIME or
                            android.text.format.DateUtils.FORMAT_ABBREV_MONTH or
                            noYearIfThisYear(quota.refillAt)
                )
            }
            Text(
                text = stringResource(R.string.subscription_traffic_refill, refill),
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                maxLines = 1,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * Subscription header's description line - clamps to 2 lines with an ellipsis once fully stuck,
 * but scrubs the height continuously in between instead of snapping, so it folds in lockstep
 * with the same scroll that drives the header's corner/shadow/scale. An invisible probe copy
 * (laid out at zero height so it doesn't affect the row) measures the description's natural
 * height and where its second line ends; those two numbers are then interpolated by
 * [stuckProgress] to clip the visible copy. The ellipsis itself only ever appears at the fully
 * stuck endpoint - mid-scroll it's a plain vertical crop, since animating the glyph in/out isn't
 * possible and a raw crop mid-transition reads fine for something passed through quickly.
 *
 * This does change the header item's measured height every scroll frame, which would normally
 * make profileItemPlacementSpec (see its declaration) chase it with a lagging spring - that's
 * why profile rows suppress their placement animation while lazyListState.isScrollInProgress.
 */
@Composable
private fun SubscriptionDescriptionText(
    text: String,
    color: Color,
    stuckProgress: Float
) {
    var naturalHeightPx by remember(text) { mutableFloatStateOf(0f) }
    var twoLineHeightPx by remember(text) { mutableFloatStateOf(0f) }
    // Same size/weight as the kernel description in ProfileListItem/ProfilesBlock.
    val style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 18.sp)

    Text(
        text = text,
        style = style,
        color = Color.Transparent,
        maxLines = Int.MAX_VALUE,
        onTextLayout = { result ->
            naturalHeightPx = result.size.height.toFloat()
            twoLineHeightPx = if (result.lineCount > 2) result.getLineBottom(1) else naturalHeightPx
        },
        modifier = Modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, 0) {}
            }
            .clearAndSetSemantics {}
    )

    when {
        // Not yet measured (e.g. this header was just freshly composed already mid-transition,
        // such as scrolling back into a recycled one) - show unclamped rather than clip to a
        // stale/zero height for a frame before the probe's onTextLayout above corrects it. text
        // is always non-blank here (see call site), so naturalHeightPx settles above 0f once
        // measured and this branch only ever applies on that first, pre-measurement frame.
        naturalHeightPx <= 0f -> {
            Text(text = text, style = style, color = color)
        }

        stuckProgress <= 0f -> {
            Text(text = text, style = style, color = color)
        }

        stuckProgress >= 1f -> {
            Text(
                text = text,
                style = style,
                color = color,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        else -> {
            val clampedHeightPx = minOf(naturalHeightPx, twoLineHeightPx)
            val heightPx = naturalHeightPx + (clampedHeightPx - naturalHeightPx) * stuckProgress
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(LocalDensity.current) { heightPx.toDp() })
                    .clipToBounds()
            ) {
                Text(text = text, style = style, color = color, maxLines = Int.MAX_VALUE)
            }
        }
    }
}

@Composable
private fun SubscriptionEmptyRow() {
    // The last segment under the subscription header, like the profile rows it stands in for.
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index = 1, count = 2),
        modifier = Modifier.fillMaxWidth(),
        colors = ListItemDefaults.segmentedColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        contentPadding = PaddingValues(16.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
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
internal fun ExportDropdownMenus(
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

internal fun shareFile(context: Context, bytes: ByteArray, fileName: String) {
    try {
        val file = java.io.File(context.cacheDir, fileName)
        file.writeBytes(bytes)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, null))
    } catch (_: Exception) {
    }
}

internal fun shareText(context: Context, text: String, title: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, title))
}

@Composable
fun ProfileNameDialog(
    title: String,
    initialName: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val nameState = rememberTextFieldState(initialName)
    val name = nameState.text.toString()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                state = nameState,
                inputTransformation = InputTransformation.maxLengthReject(100),
                label = { Text(stringResource(R.string.profile_name_label)) },
                lineLimits = TextFieldLineLimits.SingleLine,
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
        appContext.getString(
            R.string.subscription_update_summary,
            summary.added,
            summary.removed,
            summary.total
        )
    }
    appContext.showExclusiveToast(message)
}
