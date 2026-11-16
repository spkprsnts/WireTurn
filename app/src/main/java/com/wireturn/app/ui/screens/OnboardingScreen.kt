@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.wireturn.app.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.graphics.shapes.Morph
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.wireturn.app.R
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.ItemPosition
import com.wireturn.app.ui.LabelGroup
import com.wireturn.app.ui.SectionItem
import com.wireturn.app.ui.StandardLeadingIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

private const val PAGE_WELCOME = 0
private const val PAGE_PERMISSIONS = 1
private const val PAGE_READY = 2
private const val PAGE_COUNT = 3

/**
 * First launch: what the app is, the two permissions it needs to keep a tunnel alive in the
 * background, then off to adding a profile. [onFinish]'s flag says whether the user asked to add
 * one now or chose to do it later.
 */
@Composable
fun OnboardingScreen(
    onFinish: (addProfile: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState { PAGE_COUNT }
    val isLastPage = pagerState.currentPage == PAGE_READY

    BackHandler(enabled = pagerState.currentPage > 0) {
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { page ->
            when (page) {
                PAGE_WELCOME -> WelcomePage()
                PAGE_PERMISSIONS -> PermissionsPage()
                else -> ReadyPage()
            }
        }

        val effectsSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        val spatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
        val fastEffectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
        AnimatedContent(
            targetState = isLastPage,
            transitionSpec = {
                (fadeIn(effectsSpec) + scaleIn(spatialSpec, initialScale = 0.92f))
                    .togetherWith(fadeOut(fastEffectsSpec))
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            label = "onboarding_controls"
        ) { last ->
            if (last) {
                // Same bar as on the pages before, "Later" where the page dots were.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val height = ButtonDefaults.MediumContainerHeight
                    TextButton(
                        onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            onFinish(false)
                        },
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.contentPaddingFor(height),
                        modifier = Modifier.heightIn(min = height)
                    ) {
                        Text(
                            text = stringResource(R.string.onboarding_later),
                            style = ButtonDefaults.textStyleFor(height)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            onFinish(true)
                        },
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.contentPaddingFor(height),
                        modifier = Modifier.heightIn(min = height)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.add_24px),
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.iconSizeFor(height))
                        )
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(
                            text = stringResource(R.string.profile_add),
                            style = ButtonDefaults.textStyleFor(height)
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PageIndicator(
                        pageCount = PAGE_COUNT,
                        currentPage = pagerState.currentPage,
                        modifier = Modifier.weight(1f)
                    )
                    val height = ButtonDefaults.MediumContainerHeight
                    Button(
                        onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        },
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.contentPaddingFor(height),
                        modifier = Modifier.heightIn(min = height)
                    ) {
                        Text(
                            text = stringResource(R.string.btn_next),
                            style = ButtonDefaults.textStyleFor(height)
                        )
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Icon(
                            painter = painterResource(R.drawable.arrow_forward_24px),
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.iconSizeFor(height))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomePage() {
    OnboardingPage(
        hero = {
            MorphingHero(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                icon = R.drawable.ic_logo_full,
                size = 200.dp,
                iconSize = 96.dp
            )
        },
        title = stringResource(R.string.onboarding_title),
        titleStyleLarge = true,
        description = stringResource(R.string.onboarding_subtitle)
    ) {
        FeatureGroup(
            items = listOf(
                Feature(
                    icon = R.drawable.memory_alt_24px,
                    title = stringResource(R.string.onboarding_feature_tunnels_title),
                    description = stringResource(R.string.onboarding_feature_tunnels_desc)
                ),
                Feature(
                    icon = R.drawable.ic_xray_24px,
                    title = stringResource(R.string.onboarding_feature_xray_title),
                    description = stringResource(R.string.onboarding_feature_xray_desc)
                ),
                Feature(
                    icon = R.drawable.vpn_key_24px,
                    title = stringResource(R.string.onboarding_feature_vpn_title),
                    description = stringResource(R.string.onboarding_feature_vpn_desc)
                )
            )
        )
    }
}

@SuppressLint("BatteryLife")
@Composable
private fun PermissionsPage() {
    val context = LocalContext.current
    // Below Android 13 notifications need no runtime permission - nothing to ask for.
    val asksNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    var notificationsGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    var batteryGranted by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    fun refresh() {
        notificationsGranted = hasNotificationPermission(context)
        batteryGranted = isIgnoringBatteryOptimizations(context)
    }

    // Either can also be changed in system settings while the app is in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh() }
    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refresh() }

    OnboardingPage(
        hero = {
            MorphingHero(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                icon = R.drawable.lock_24px,
                size = 160.dp,
                iconSize = 64.dp
            )
        },
        title = stringResource(R.string.permissions_title),
        description = stringResource(R.string.onboarding_permissions_desc)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (asksNotifications) {
                PermissionItem(
                    icon = R.drawable.notifications_24px,
                    title = stringResource(R.string.permission_notifications),
                    description = stringResource(R.string.onboarding_permission_notifications_desc),
                    granted = notificationsGranted,
                    position = ItemPosition.Top,
                    onRequest = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                )
            }
            PermissionItem(
                icon = R.drawable.battery_android_frame_5_24px,
                title = stringResource(R.string.onboarding_permission_battery_title),
                description = stringResource(R.string.onboarding_permission_battery_desc),
                granted = batteryGranted,
                position = if (asksNotifications) ItemPosition.Bottom else ItemPosition.Single,
                onRequest = {
                    batteryLauncher.launch(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = "package:${context.packageName}".toUri()
                        }
                    )
                }
            )
        }
    }
}

@Composable
private fun ReadyPage() {
    OnboardingPage(
        hero = {
            MorphingHero(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                icon = R.drawable.check_24px,
                size = 200.dp,
                iconSize = 88.dp
            )
        },
        title = stringResource(R.string.onboarding_ready_title),
        description = stringResource(R.string.onboarding_ready_desc)
    )
}

/** One page's layout: hero shape, emphasized headline, description and optional content below. */
@Composable
private fun OnboardingPage(
    hero: @Composable () -> Unit,
    title: String,
    description: String,
    titleStyleLarge: Boolean = false,
    content: (@Composable () -> Unit)? = null
) {
    // Centered while it fits, scrollable on a short screen: the content is at least the page's
    // height (heightIn after verticalScroll), so there's a height to center in.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            hero()
            Spacer(Modifier.height(32.dp))
            Text(
                text = title,
                style = if (titleStyleLarge) {
                    MaterialTheme.typography.displayMediumEmphasized
                } else {
                    MaterialTheme.typography.headlineLargeEmphasized
                },
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 420.dp)
            )
            if (content != null) {
                Spacer(Modifier.height(32.dp))
                Box(modifier = Modifier.widthIn(max = 520.dp)) { content() }
            }
        }
    }
}

/**
 * The page's highlight moment: the connect button's own shapes (CoreToggleButton - idle,
 * connecting, active) morphing through that sequence on its spring and slowly turning, with the
 * icon upright in the middle.
 */
@Composable
private fun MorphingHero(
    containerColor: Color,
    contentColor: Color,
    @DrawableRes icon: Int,
    size: Dp,
    iconSize: Dp
) {
    val cycle =
        remember { listOf(MaterialShapes.Cookie7Sided, MaterialShapes.Pill, MaterialShapes.Sunny) }
    var index by remember { mutableIntStateOf(0) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_400.milliseconds)
            index = (index + 1) % cycle.size
            progress.snapTo(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
        }
    }
    val morph = remember(index) {
        Morph(cycle[(index - 1 + cycle.size) % cycle.size], cycle[index])
    }

    val rotation by rememberInfiniteTransition(label = "hero_rotation").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            tween(24_000, easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "rotation"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .drawWithCache {
                // MaterialShapes are normalized to a unit square - scaled up to this box. Clamped
                // like the connect button's: past 1 the spring's overshoot would extrapolate the shape.
                val path = morph.toPath(progress.value.coerceIn(0f, 1f), Path())
                path.transform(Matrix().apply {
                    scale(
                        this@drawWithCache.size.width,
                        this@drawWithCache.size.height
                    )
                })
                onDrawBehind {
                    rotate(rotation) { drawPath(path, containerColor) }
                }
            }
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(iconSize)
        )
    }
}

private data class Feature(
    @DrawableRes val icon: Int,
    val title: String,
    val description: String
)

@Composable
private fun FeatureGroup(items: List<Feature>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEachIndexed { i, feature ->
            SectionItem(
                position = when {
                    items.size == 1 -> ItemPosition.Single
                    i == 0 -> ItemPosition.Top
                    i == items.lastIndex -> ItemPosition.Bottom
                    else -> ItemPosition.Middle
                }
            ) {
                ItemRow(
                    icon = feature.icon,
                    title = feature.title,
                    description = feature.description
                )
            }
        }
    }
}

@Composable
private fun PermissionItem(
    @DrawableRes icon: Int,
    title: String,
    description: String,
    granted: Boolean,
    position: ItemPosition,
    onRequest: () -> Unit
) {
    val context = LocalContext.current
    SectionItem(position = position) {
        ItemRow(icon = icon, title = title, description = description) {
            val effectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
            val spatialSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
            AnimatedContent(
                targetState = granted,
                transitionSpec = {
                    (fadeIn(effectsSpec) + scaleIn(spatialSpec, initialScale = 0.6f))
                        .togetherWith(fadeOut(effectsSpec))
                },
                contentAlignment = Alignment.Center,
                label = "permission_state"
            ) { isGranted ->
                if (isGranted) {
                    Icon(
                        painter = painterResource(R.drawable.check_circle_24px),
                        contentDescription = stringResource(R.string.onboarding_granted),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                } else {
                    FilledTonalButton(
                        onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            onRequest()
                        },
                        shapes = ButtonDefaults.shapes()
                    ) {
                        Text(stringResource(R.string.onboarding_allow))
                    }
                }
            }
        }
    }
}

/** A row the way the rest of the app lays them out: leading icon, label group, optional trailing. */
@Composable
private fun ItemRow(
    @DrawableRes icon: Int,
    title: String,
    description: String,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StandardLeadingIcon {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
        LabelGroup(
            label = title,
            supportingText = description,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** Current page as a longer pill, the rest as dots - springs between them. */
@Composable
private fun PageIndicator(pageCount: Int, currentPage: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(pageCount) { page ->
            val selected = page == currentPage
            val width by animateDpAsState(
                targetValue = if (selected) 24.dp else 8.dp,
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                label = "indicator_width"
            )
            val color by animateColorAsState(
                targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                label = "indicator_color"
            )
            Box(
                modifier = Modifier
                    .height(8.dp)
                    .width(width)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName) == true
