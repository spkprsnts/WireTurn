@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.TwoRowsTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.wireturn.app.R

@Composable
fun TopAppBarScrollBehavior.noFlingExpandConnection(): NestedScrollConnection {
    val inner = nestedScrollConnection
    return remember(inner) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (source == NestedScrollSource.SideEffect && available.y > 0f) Offset.Zero
                else inner.onPreScroll(available, source)

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (source == NestedScrollSource.SideEffect && available.y > 0f) Offset.Zero
                else inner.onPostScroll(consumed, available, source)

            override suspend fun onPreFling(available: Velocity): Velocity = inner.onPreFling(available)
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = inner.onPostFling(consumed, available)
        }
    }
}

@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
fun AppTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable (RowScope.() -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    expandedHeight: Dp = Dp.Unspecified,
    collapsedHeight: Dp = TopAppBarDefaults.LargeAppBarCollapsedHeight - 8.dp,
    containerColor: Color = Color.Unspecified,
    startCollapsed: Boolean = true,
) {
    val screenBackgroundColor = containerColor.takeOrElse {
        MaterialTheme.colorScheme.background
    }

    val annotatedTitle = remember(title) {
        buildAnnotatedString {
            append(title)
        }
    }

    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    val configuration = LocalConfiguration.current
    val textMeasurer = rememberTextMeasurer()
    val titleStyle = MaterialTheme.typography.headlineLarge

    val isMultiLine = remember(annotatedTitle, windowInfo.containerSize.width, configuration.screenWidthDp) {
        val width = windowInfo.containerSize.width.takeIf { it > 0 }
            ?: with(density) { configuration.screenWidthDp.dp.toPx() }.toInt()
        val horizontalPadding = with(density) { 32.dp.toPx() }.toInt()
        val availableWidth = width - horizontalPadding
        val measuredText = textMeasurer.measure(
            text = annotatedTitle,
            style = titleStyle,
            constraints = Constraints(maxWidth = availableWidth)
        )
        measuredText.lineCount > 1
    }

    val finalExpandedHeight = remember(isMultiLine, expandedHeight) {
        if (expandedHeight != Dp.Unspecified) return@remember expandedHeight
        if (isMultiLine) 212.dp else 179.dp
    }

    if (startCollapsed && scrollBehavior != null) {
        LaunchedEffect(scrollBehavior.state.heightOffsetLimit, finalExpandedHeight) {
            if (scrollBehavior.state.heightOffsetLimit != 0f) {
                scrollBehavior.state.heightOffset = scrollBehavior.state.heightOffsetLimit
            }
        }
    }

    TwoRowsTopAppBar(
        expandedHeight = finalExpandedHeight,
        collapsedHeight = collapsedHeight,
        title = { isExpanded ->
            Text(
                text = annotatedTitle,
                style = if (isExpanded) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleLarge,
                maxLines = if (isExpanded) Int.MAX_VALUE else 1,
                overflow = if (isExpanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .padding(top = if (isExpanded) 28.dp else 0.dp)
                    .padding(bottom = if (isExpanded) 24.dp else 0.dp)
            )
        },
        modifier = modifier.fillMaxWidth(),
        navigationIcon = {
            if (onBack != null) {
                FilledTonalIconButton(
                    onClick = onBack,
                    modifier = Modifier.padding(start = 15.dp, end = 3.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Icon(
                        painterResource(R.drawable.arrow_back_24px),
                        contentDescription = null
                    )
                }
            }
        },
        actions = { actions?.invoke(this) },
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = screenBackgroundColor,
            scrolledContainerColor = screenBackgroundColor,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface,
            subtitleContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}

/**
 * Description line for a screen, meant as the first item in its scrollable body content - not
 * [AppTopAppBar]'s own subtitle slot, since that's tied to the app bar's expand/collapse
 * animation and disappears once the bar collapses on scroll. This instead behaves like Android
 * Settings' own screen descriptions: part of the normal content, always there right under the
 * (possibly already-collapsed) app bar, scrolling away with the rest of the content.
 */
@Composable
fun ScreenSubtitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            // Closes the gap to the app bar above (the screen's own top padding already
            // reserves more than this needs) without touching that shared, screen-wide value.
            .offset(y = (-8).dp)
            .padding(horizontal = 8.dp)
            .padding(bottom = 10.dp)
    )
}

/**
 * Samples, once per new touch gesture, whether [atBoundary] holds right as the finger goes down -
 * never consumes the event. Reports the result via [onGestureStart] so a sibling
 * NestedScrollConnection can distinguish "a swipe that merely scrolls up to a boundary" from "a
 * further swipe that starts already sitting there" (e.g. deciding whether a drag-to-dismiss or a
 * reveal-on-swipe gesture should fire).
 */
fun Modifier.trackGestureStartedAtBoundary(
    atBoundary: () -> Boolean,
    onGestureStart: (startedAtBoundary: Boolean) -> Unit
): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        onGestureStart(atBoundary())
    }
}
