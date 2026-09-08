@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wireturn.app.R
import com.wireturn.app.data.XraySettings
import com.wireturn.app.domain.GeoResourceSet
import com.wireturn.app.ui.AppTopAppBar
import com.wireturn.app.ui.ExpandableSection
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.ItemPosition
import com.wireturn.app.ui.LargeLeadingIcon
import com.wireturn.app.ui.RowLabel
import com.wireturn.app.ui.SectionGroup
import com.wireturn.app.ui.SectionItem
import com.wireturn.app.ui.SelectionDialog
import com.wireturn.app.ui.SupportingText
import com.wireturn.app.ui.SwitchRow
import com.wireturn.app.ui.TextFieldRow
import com.wireturn.app.ui.ValidatorUtils
import com.wireturn.app.ui.noFlingExpandConnection
import com.wireturn.app.ui.redact
import com.wireturn.app.viewmodel.GeoAssetsState
import java.text.DateFormat
import java.util.Date

@Composable
fun XraySettingsScreen(
    initialXraySettings: XraySettings,
    privacyMode: Boolean,
    onBack: () -> Unit,
    onSave: (XraySettings) -> Unit,
    modifier: Modifier = Modifier,
    geoAssetsState: GeoAssetsState = GeoAssetsState.Idle,
    geoAssetsProgress: Int = 0,
    geoAssetsInstalled: Boolean = false,
    geoAssetsInstalledAt: Long = 0L,
    onDownloadGeoAssets: (GeoResourceSet) -> Unit = {},
    onCancelGeoAssetsDownload: () -> Unit = {},
    onDeleteGeoAssets: () -> Unit = {}
) {
    val context = LocalContext.current

    var xraySocks by remember { mutableStateOf(initialXraySettings.socksBindAddress) }
    var xrayHttp by remember { mutableStateOf(initialXraySettings.httpBindAddress) }
    var xrayAuth by remember { mutableStateOf(initialXraySettings.isProxyAuthEnabled) }
    var xrayUser by remember { mutableStateOf(initialXraySettings.proxyUser) }
    var xrayPass by remember { mutableStateOf(initialXraySettings.proxyPass) }
    var xrayPassVisible by rememberSaveable { mutableStateOf(false) }

    var dns by remember { mutableStateOf(initialXraySettings.dns) }
    var routeDirect by remember { mutableStateOf(initialXraySettings.routeDirect) }
    var routeBlock by remember { mutableStateOf(initialXraySettings.routeBlock) }
    var fakeDns by remember { mutableStateOf(initialXraySettings.fakeDns) }

    val showGeoDialog = remember { mutableStateOf(false) }
    val showDeleteGeoConfirm = remember { mutableStateOf(false) }

    val installedVariant = GeoResourceSet.fromId(initialXraySettings.geoVariant)
    // While a download is in flight, reflect its target rather than the (possibly stale, or
    // just-deleted) previously installed variant - otherwise the label lies about what's
    // actually happening (e.g. still saying "v2fly" while RunetFreedom is being fetched).
    val displayedVariant = (geoAssetsState as? GeoAssetsState.Downloading)?.variant ?: installedVariant
    val installedVariantLabel = stringResource(geoVariantLabelRes(displayedVariant))
    val geoDateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val geoStatusText = when {
        geoAssetsState is GeoAssetsState.Downloading -> stringResource(R.string.geo_resources_downloading)
        geoAssetsInstalled -> stringResource(R.string.geo_resources_updated_at, geoDateFormat.format(Date(geoAssetsInstalledAt)))
        else -> stringResource(R.string.geo_resources_not_downloaded)
    }

    val scrollState = rememberScrollState()

    val currentXraySettings = remember(
        xraySocks, xrayHttp, xrayAuth, xrayUser, xrayPass,
        dns, routeDirect, routeBlock, fakeDns, initialXraySettings
    ) {
        initialXraySettings.copy(
            socksBindAddress = xraySocks,
            httpBindAddress = xrayHttp,
            isProxyAuthEnabled = xrayAuth,
            proxyUser = xrayUser,
            proxyPass = xrayPass,
            dns = dns,
            routeDirect = routeDirect,
            routeBlock = routeBlock,
            fakeDns = fakeDns
        )
    }

    val isModified = remember(currentXraySettings, initialXraySettings) {
        currentXraySettings.socksBindAddress != initialXraySettings.socksBindAddress ||
        currentXraySettings.httpBindAddress != initialXraySettings.httpBindAddress ||
        currentXraySettings.isProxyAuthEnabled != initialXraySettings.isProxyAuthEnabled ||
        currentXraySettings.proxyUser != initialXraySettings.proxyUser ||
        currentXraySettings.proxyPass != initialXraySettings.proxyPass ||
        currentXraySettings.dns != initialXraySettings.dns ||
        currentXraySettings.routeDirect != initialXraySettings.routeDirect ||
        currentXraySettings.routeBlock != initialXraySettings.routeBlock ||
        currentXraySettings.fakeDns != initialXraySettings.fakeDns
    }

    val showExitDialog = remember { mutableStateOf(false) }

    val handleBack = {
        if (isModified) {
            showExitDialog.value = true
        } else {
            onBack()
        }
    }

    BackHandler(enabled = isModified, onBack = handleBack)

    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        state = topAppBarState,
        flingAnimationSpec = null
    )

    if (showExitDialog.value) {
        AlertDialog(
            onDismissRequest = { showExitDialog.value = false },
            title = { Text(stringResource(R.string.unsaved_changes_title)) },
            text = { Text(stringResource(R.string.unsaved_changes_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    showExitDialog.value = false
                    onSave(currentXraySettings)
                }) {
                    Text(stringResource(R.string.btn_save))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showExitDialog.value = false
                    onBack()
                }) {
                    Text(stringResource(R.string.btn_discard))
                }
            }
        )
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.noFlingExpandConnection()),
        topBar = {
            AppTopAppBar(
                title = stringResource(R.string.xray_settings_title),
                scrollBehavior = scrollBehavior,
                onBack = handleBack
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = isModified,
                enter = scaleIn(
                    initialScale = 0.8f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) + fadeIn(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()),
                exit = scaleOut(
                    targetScale = 0.8f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ) + fadeOut(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec())
            ) {
                ExtendedFloatingActionButton(
                    modifier = Modifier.navigationBarsPadding(),
                    onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        onSave(currentXraySettings)
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    icon = { Icon(painterResource(R.drawable.save_24px), null) },
                    text = { Text(stringResource(R.string.btn_save)) }
                )
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .fillMaxWidth()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 840.dp)
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
                .padding(top = 18.dp)
                .navigationBarsPadding()
                .padding(bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(19.dp)
        ) {
            SectionGroup(title = stringResource(R.string.xray_settings_group_connection)) {
                SectionItem(position = ItemPosition.Top) {
                    TextFieldRow(
                        label = stringResource(R.string.socks5),
                        value = xraySocks.redact(privacyMode),
                        onValueChange = { if (!privacyMode) xraySocks = it },
                        placeholder = XraySettings.DEFAULT_SOCKS_BIND_ADDRESS,
                        isError = !ValidatorUtils.isValidHostPort(xraySocks),
                        readOnly = privacyMode,
                        isModified = xraySocks != initialXraySettings.socksBindAddress,
                        privacyMode = privacyMode
                    )
                }

                SectionItem {
                    TextFieldRow(
                        label = stringResource(R.string.xray_http),
                        value = xrayHttp.redact(privacyMode),
                        onValueChange = { if (!privacyMode) xrayHttp = it },
                        placeholder = XraySettings.DEFAULT_HTTP_BIND_ADDRESS,
                        isError = xrayHttp.isNotEmpty() && !ValidatorUtils.isValidHostPort(xrayHttp),
                        readOnly = privacyMode,
                        isModified = xrayHttp != initialXraySettings.httpBindAddress,
                        privacyMode = privacyMode
                    )
                }

                SectionItem(
                    position = if (xrayAuth) ItemPosition.Middle else ItemPosition.Bottom,
                    onClick = {
                        xrayAuth = !xrayAuth
                        HapticUtil.perform(
                            context,
                            if (xrayAuth) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                        )
                    }
                ) {
                    SwitchRow(
                        label = stringResource(R.string.xray_proxy_auth),
                        supportingText = stringResource(R.string.xray_proxy_auth_desc),
                        checked = xrayAuth,
                        onCheckedChange = { xrayAuth = it },
                        isModified = xrayAuth != initialXraySettings.isProxyAuthEnabled,
                    )
                }

                ExpandableSection(visible = xrayAuth) {
                    SectionGroup {
                        SectionItem {
                            TextFieldRow(
                                label = stringResource(R.string.xray_proxy_user),
                                value = xrayUser.redact(privacyMode),
                                onValueChange = { if (!privacyMode) xrayUser = it },
                                placeholder = stringResource(R.string.proxy_user_placeholder),
                                isError = !ValidatorUtils.isValidProxyUser(xrayUser),
                                readOnly = privacyMode,
                                isModified = xrayUser != initialXraySettings.proxyUser,
                                privacyMode = privacyMode
                            )
                        }
                        SectionItem(position = ItemPosition.Bottom) {
                            TextFieldRow(
                                label = stringResource(R.string.xray_proxy_pass),
                                value = xrayPass.redact(privacyMode),
                                onValueChange = { if (!privacyMode) xrayPass = it },
                                placeholder = stringResource(R.string.proxy_pass_placeholder),
                                isError = !ValidatorUtils.isValidProxyPass(xrayPass),
                                readOnly = privacyMode,
                                isModified = xrayPass != initialXraySettings.proxyPass,
                                privacyMode = privacyMode,
                                trailingIcon = {
                                    IconButton(onClick = { xrayPassVisible = !xrayPassVisible }) {
                                        Icon(
                                            painter = painterResource(
                                                if (xrayPassVisible) R.drawable.visibility_24px
                                                else R.drawable.visibility_off_24px
                                            ),
                                            contentDescription = null
                                        )
                                    }
                                },
                                visualTransformation = if (xrayPassVisible) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                }
                            )
                        }
                    }
                }
            }

            SectionGroup(title = stringResource(R.string.xray_settings_group_dns)) {
                SectionItem(position = ItemPosition.Top) {
                    TextFieldRow(
                        label = stringResource(R.string.xray_settings_dns_label),
                        value = dns.redact(privacyMode),
                        onValueChange = { if (!privacyMode) dns = it },
                        placeholder = XraySettings.DEFAULT_DNS,
                        supportingText = stringResource(R.string.xray_settings_dns_desc),
                        readOnly = privacyMode,
                        isModified = dns != initialXraySettings.dns,
                        privacyMode = privacyMode
                    )
                }

                SectionItem(
                    position = ItemPosition.Bottom,
                    onClick = {
                        fakeDns = !fakeDns
                        HapticUtil.perform(
                            context,
                            if (fakeDns) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                        )
                    }
                ) {
                    SwitchRow(
                        label = stringResource(R.string.xray_settings_fakedns_label),
                        supportingText = stringResource(R.string.xray_settings_fakedns_desc),
                        checked = fakeDns,
                        onCheckedChange = { fakeDns = it },
                        isModified = fakeDns != initialXraySettings.fakeDns
                    )
                }
            }

            SectionGroup(title = stringResource(R.string.xray_settings_group_routing)) {
                SectionItem(position = ItemPosition.Top) {
                    TextFieldRow(
                        label = stringResource(R.string.xray_settings_route_direct_label),
                        value = routeDirect.redact(privacyMode),
                        onValueChange = { if (!privacyMode) routeDirect = it },
                        placeholder = stringResource(R.string.xray_settings_route_direct_placeholder),
                        supportingText = stringResource(R.string.xray_settings_route_direct_desc),
                        readOnly = privacyMode,
                        isModified = routeDirect != initialXraySettings.routeDirect,
                        privacyMode = privacyMode
                    )
                }

                SectionItem(position = ItemPosition.Bottom) {
                    TextFieldRow(
                        label = stringResource(R.string.xray_settings_route_block_label),
                        value = routeBlock.redact(privacyMode),
                        onValueChange = { if (!privacyMode) routeBlock = it },
                        placeholder = stringResource(R.string.xray_settings_route_block_placeholder),
                        supportingText = stringResource(R.string.xray_settings_route_block_desc),
                        readOnly = privacyMode,
                        isModified = routeBlock != initialXraySettings.routeBlock,
                        privacyMode = privacyMode
                    )
                }
            }

            SectionGroup(title = stringResource(R.string.xray_settings_group_geo)) {
                SectionItem(
                    position = ItemPosition.Single,
                    onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        showGeoDialog.value = true
                    }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LargeLeadingIcon {
                            if (geoAssetsState is GeoAssetsState.Downloading) {
                                LoadingIndicator(modifier = Modifier.size(32.dp))
                            } else {
                                Icon(
                                    painter = painterResource(R.drawable.globe_book_24px),
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            RowLabel(text = installedVariantLabel)
                            Spacer(Modifier.height(2.dp))
                            SupportingText(geoStatusText)
                        }
                    }
                }
            }
        }
    }

    if (showGeoDialog.value) {
        GeoResourcesDialog(
            selected = installedVariant,
            state = geoAssetsState,
            progress = geoAssetsProgress,
            installed = geoAssetsInstalled,
            installedAt = geoAssetsInstalledAt,
            onDownload = onDownloadGeoAssets,
            onCancelDownload = onCancelGeoAssetsDownload,
            onRequestDelete = { showDeleteGeoConfirm.value = true },
            onDismiss = { showGeoDialog.value = false }
        )
    }

    if (showDeleteGeoConfirm.value) {
        AlertDialog(
            onDismissRequest = { showDeleteGeoConfirm.value = false },
            title = { Text(stringResource(R.string.geo_resources_delete_confirm_title)) },
            text = { Text(stringResource(R.string.geo_resources_delete_confirm_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        onDeleteGeoAssets()
                        showDeleteGeoConfirm.value = false
                    },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.geo_resources_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteGeoConfirm.value = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun GeoResourcesDialog(
    selected: GeoResourceSet,
    state: GeoAssetsState,
    progress: Int,
    installed: Boolean,
    installedAt: Long,
    onDownload: (GeoResourceSet) -> Unit,
    onCancelDownload: () -> Unit,
    onRequestDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf((state as? GeoAssetsState.Downloading)?.variant ?: selected) }
    val isDownloading = state is GeoAssetsState.Downloading
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    SelectionDialog(
        title = stringResource(R.string.xray_settings_group_geo),
        description = stringResource(R.string.geo_resources_dialog_desc),
        items = GeoResourceSet.entries,
        isSelected = { it == pending },
        onSelect = { if (!isDownloading) pending = it },
        onDismiss = onDismiss,
        dismissOnSelect = false,
        footer = {
            Column(modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp)) {
                val statusText = when {
                    isDownloading -> stringResource(R.string.geo_resources_downloading)
                    state is GeoAssetsState.Error -> stringResource(R.string.geo_resources_download_failed, state.message)
                    installed && pending == selected -> stringResource(
                        R.string.geo_resources_updated_at,
                        dateFormat.format(Date(installedAt))
                    )
                    else -> stringResource(R.string.geo_resources_not_downloaded)
                }
                SupportingText(
                    text = statusText,
                    color = if (state is GeoAssetsState.Error) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isDownloading) {
                            CircularWavyProgressIndicator(
                                progress = { progress / 100f },
                                modifier = Modifier.fillMaxSize()
                            )
                            IconButton(
                                onClick = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                    onCancelDownload()
                                },
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.stop_24px),
                                    contentDescription = stringResource(R.string.cancel),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        } else {
                            FilledTonalIconButton(
                                onClick = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                    onDownload(pending)
                                },
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.arrow_downward_24px),
                                    contentDescription = stringResource(R.string.geo_resources_download)
                                )
                            }
                        }
                    }

                    if (installed && !isDownloading) {
                        IconButton(
                            onClick = {
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                onRequestDelete()
                            },
                            modifier = Modifier.align(Alignment.CenterEnd)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.delete_24px),
                                contentDescription = stringResource(R.string.geo_resources_delete),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    ) { variant, _ ->
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(geoVariantLabelRes(variant)))
                SupportingText(
                    text = stringResource(geoVariantDescRes(variant)),
                    color = LocalContentColor.current.copy(alpha = 0.75f)
                )
            }
        }
    }
}

private fun geoVariantLabelRes(variant: GeoResourceSet): Int = when (variant) {
    GeoResourceSet.RUNETFREEDOM -> R.string.geo_variant_runetfreedom
    GeoResourceSet.LOYALSOLDIER -> R.string.geo_variant_loyalsoldier
    GeoResourceSet.V2FLY -> R.string.geo_variant_v2fly
}

private fun geoVariantDescRes(variant: GeoResourceSet): Int = when (variant) {
    GeoResourceSet.RUNETFREEDOM -> R.string.geo_variant_runetfreedom_desc
    GeoResourceSet.LOYALSOLDIER -> R.string.geo_variant_loyalsoldier_desc
    GeoResourceSet.V2FLY -> R.string.geo_variant_v2fly_desc
}
