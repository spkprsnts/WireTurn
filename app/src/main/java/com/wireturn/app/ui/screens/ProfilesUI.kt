@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)

package com.wireturn.app.ui.screens

import android.app.Activity
import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.R
import com.wireturn.app.data.KernelVariant
import com.wireturn.app.data.Profile
import com.wireturn.app.data.XrayConfiguration
import com.wireturn.app.kernel.KernelRegistry
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.ItemPosition
import com.wireturn.app.ui.LabelGroupDefaults
import com.wireturn.app.ui.LargeLeadingIcon
import com.wireturn.app.ui.SectionItem
import com.wireturn.app.ui.TappableRowIndicator
import com.wireturn.app.ui.ValidatorUtils
import com.wireturn.app.ui.VerticalAnimatedText
import com.wireturn.app.viewmodel.MainViewModel

/** Small flat rectangular flag for an ISO-3166 alpha-2 country code, fetched from flagcdn.com. */
@Composable
private fun FlagImage(countryCode: String, modifier: Modifier = Modifier) {
    coil.compose.AsyncImage(
        model = "https://flagcdn.com/w80/${countryCode.lowercase()}.png",
        contentDescription = null,
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        modifier = modifier.clip(RoundedCornerShape(2.dp))
    )
}

/**
 * [primary] is the kernel description shown as plain text; [kernelTags] and [xrayTags] are the
 * rest of what [ProfileSummary] used to join with " • ", now shown as separate chip clusters -
 * kernel tags on the profile block, Xray tags on the Xray row (see HomeScreen), both combined in
 * the profiles list where there's no separate Xray row to split them onto.
 */
internal class ProfileSummaryParts(
    val primary: String,
    val kernelTags: List<String>,
    val xrayTags: List<String>
) {
    val extraTags: List<String> get() = kernelTags + xrayTags
}

@Composable
internal fun profileSummaryParts(profile: Profile): ProfileSummaryParts {
    val context = LocalContext.current

    val kernel = KernelRegistry.get(profile.kernelVariant)
    val primary = kernel.description(context, profile.kernelConfig)
    val kernelTags = kernel.profileSummaryExtra(context, profile.kernelConfig).toMutableList()
    // The profile can run VPN mode on the kernel's own TUN, no hev - whenever Xray is off at the
    // time (see usesNativeTun), so the tag marks the capability regardless of either toggle.
    if (kernel.supportsNativeTun(profile.kernelConfig)) {
        kernelTags.add(stringResource(R.string.kernel_tag_native_tun))
    }

    val xrayTags = mutableListOf<String>()
    if (profile.xrayEnabled) {
        val isValid = when (profile.xrayProtocol) {
            XrayConfiguration.VLESS -> profile.vlessConfig.isValid()
            XrayConfiguration.WIREGUARD -> profile.wgConfig.isValid()
        }

        if (isValid) {
            xrayTags.add(
                when (profile.xrayProtocol) {
                    XrayConfiguration.VLESS -> stringResource(ValidatorUtils.uriProtocolStringRes(profile.vlessConfig.vlessLink))
                    XrayConfiguration.WIREGUARD -> stringResource(R.string.wg_short)
                }
            )
            if (profile.xrayProtocol == XrayConfiguration.VLESS && profile.vlessConfig.isDualRoute) {
                xrayTags.add(stringResource(R.string.xray_uri_dual_route_short))
            }
            if (profile.xrayProtocol == XrayConfiguration.VLESS && profile.vlessConfig.isSocks5Chain) {
                xrayTags.add(stringResource(R.string.xray_uri_socks5_chain_short))
            }
        }
    }

    return ProfileSummaryParts(primary, kernelTags, xrayTags)
}

@Composable
fun ProfileSummary(
    profile: Profile,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    useAnimation: Boolean = false,
    countryCode: String? = null,
    maxParts: Int? = null
) {
    val summary = profileSummaryParts(profile)
    val allParts = listOf(summary.primary) + summary.extraTags
    val parts = if (maxParts != null) allParts.take(maxParts) else allParts

    if (parts.isNotEmpty()) {
        val text = parts.joinToString(" • ")
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (countryCode != null) {
                val flagWidth = if (useAnimation) 14.dp else 12.dp
                val flagHeight = if (useAnimation) 10.5.dp else 9.dp
                FlagImage(
                    countryCode = countryCode,
                    modifier = Modifier
                        .padding(end = if (useAnimation) 5.dp else 4.dp)
                        .size(width = flagWidth, height = flagHeight)
                )
            }
            if (useAnimation) {
                VerticalAnimatedText(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 18.sp),
                    color = color,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.weight(1f, fill = false).basicMarquee()
                )
            } else {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.weight(1f, fill = false).basicMarquee()
                )
            }
        }
    }
}

/**
 * Small tonal pill for a single summary fragment - see [ProfileTagChipRow]. Defaults to
 * secondaryContainer, but callers can override both colors - the profiles list's selected row is
 * already secondaryContainer, so it passes the inverted pair instead of letting chips disappear
 * into it.
 */
@Composable
private fun ProfileTagChip(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        contentColor = contentColor
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/**
 * Row of [ProfileTagChip]s for extra summary fragments - used both as a corner cluster
 * overlapping a card's top edge (home screen's profile block and Xray row) and, contained within
 * its own row's bounds, in the profiles list. [modifier] controls the positioning/offset in each
 * case; this composable itself only lays the chips out horizontally.
 */
@Composable
internal fun ProfileTagChipRow(
    tags: List<String>,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer
) {
    if (tags.isEmpty()) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        tags.forEach { label ->
            ProfileTagChip(text = label, containerColor = containerColor, contentColor = contentColor)
        }
    }
}

@Composable
fun ProfilesBlock(
    viewModel: MainViewModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val currentId by viewModel.currentProfileId.collectAsStateWithLifecycle()
    val currentProfile = profiles.find { it.id == currentId } ?: profiles.firstOrNull()
    val context = LocalContext.current
    val profileCountries by viewModel.profileCountries.collectAsStateWithLifecycle()

    SectionItem(
        position = ItemPosition.Single,
        onClick = onClick,
        modifier = modifier
    ) {
        if (currentProfile != null) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
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
                            Spacer(Modifier.height(LabelGroupDefaults.SupportingGap))
                            // Only the first summary fragment (kernel description) stays here as
                            // text - the rest (protocol, dual-route, chain, ...) show as a chip
                            // row on their own third line below, see kernelTags below.
                            ProfileSummary(
                                profile = currentProfile,
                                modifier = Modifier.fillMaxWidth(),
                                useAnimation = true,
                                countryCode = profileCountries[currentProfile.id],
                                maxParts = 1
                            )
                        }
                        // Same indicator SwitchRow's isSplit uses for "tap this row to open a
                        // list" - hints that the row itself (not just the edit button) opens it.
                        TappableRowIndicator()
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
                }
                val kernelTags = profileSummaryParts(currentProfile).kernelTags
                if (kernelTags.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    ProfileTagChipRow(tags = kernelTags, modifier = Modifier.padding(start = 52.dp))
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
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
    countryCode: String? = null,
    // The last ping measured through this profile, shown at the end of the summary row.
    pingMs: Long? = null,
    // In a running ping check, waiting for its result - shown instead of the last one.
    isPinging: Boolean = false,
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
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "profile_item_bg"
    )

    val summary = profileSummaryParts(profile)
    val summaryColor = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
    else MaterialTheme.colorScheme.outline

    Surface(
        onClick = onClick,
        shape = shape,
        color = backgroundColor,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 74.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (leadingContent != null) {
                    LargeLeadingIcon(content = leadingContent)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee()
                    )
                    Spacer(Modifier.height(LabelGroupDefaults.SupportingGap))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (countryCode != null) {
                            FlagImage(
                                countryCode = countryCode,
                                modifier = Modifier
                                    .padding(end = 5.dp)
                                    .size(width = 14.dp, height = 10.5.dp)
                            )
                        }
                        Text(
                            text = summary.primary,
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 18.sp),
                            color = summaryColor,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (trailingContent != null) {
                    Spacer(Modifier.width(12.dp))
                    trailingContent()
                }
            }
            if (summary.extraTags.isNotEmpty() || pingMs != null || isPinging) {
                Spacer(Modifier.height(LabelGroupDefaults.SupportingGap))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = if (leadingContent != null) 52.dp else 0.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Only the tags scroll; the ping stays put at the end of the row.
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .basicMarquee(
                                iterations = Int.MAX_VALUE,
                                spacing = MarqueeSpacing.fractionOfContainer(1f / 6f)
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        summary.extraTags.forEach { label ->
                            ProfileTagChip(
                                text = label,
                                containerColor = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                    if (isPinging || pingMs != null) {
                        // One fixed height for the text and the pending indicator (which is
                        // shorter), so swapping one for the other doesn't move the row.
                        val pingTextStyle = MaterialTheme.typography.labelMedium
                        val pingSlotHeight = with(LocalDensity.current) { pingTextStyle.lineHeight.toDp() }
                        Box(
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .height(pingSlotHeight),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isPinging) {
                                PingPendingIndicator()
                            } else if (pingMs != null) {
                                val pingFailed = pingMs == com.wireturn.app.domain.ProfileManager.PING_FAILED
                                Text(
                                    text = if (pingFailed) stringResource(R.string.ping_failed_short)
                                        else stringResource(R.string.ping_ms, pingMs),
                                    style = pingTextStyle,
                                    // Same threshold as the home screen's live ping.
                                    color = if (!pingFailed && pingMs < 1100) summaryColor else MaterialTheme.colorScheme.error,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A pulsing pill where the ping will be, like the home screen's while it waits for an answer. */
@Composable
private fun PingPendingIndicator(modifier: Modifier = Modifier) {
    val pulseAlpha by rememberInfiniteTransition(label = "profile_ping_pulse").animateFloat(
        initialValue = 0.3f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "profile_ping_pulse_alpha"
    )
    Box(
        modifier = modifier
            .size(width = 36.dp, height = 12.dp)
            .graphicsLayer { alpha = pulseAlpha }
            .background(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                shape = CircleShape
            )
    )
}

/** The config screen Activity for editing a profile of this core type. */
internal fun configActivityClassFor(variant: KernelVariant): Class<out Activity> =
    KernelRegistry.get(variant).configActivityClass

internal fun getProfileIcon(profile: Profile, outlined: Boolean): Int =
    KernelRegistry.get(profile.kernelVariant).iconRes(profile.kernelConfig, outlined)
