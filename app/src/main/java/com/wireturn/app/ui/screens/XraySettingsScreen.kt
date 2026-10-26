@file:OptIn(
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wireturn.app.ByeDpi
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
import com.wireturn.app.ui.StandardLeadingIcon
import com.wireturn.app.ui.SliderRow
import com.wireturn.app.ui.noFlingExpandConnection
import com.wireturn.app.ui.redact
import com.wireturn.app.viewmodel.GeoAssetsState
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

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
    onDeleteGeoAssets: () -> Unit = {},
    customGeoSources: List<GeoResourceSet> = emptyList(),
    onSaveCustomGeoSource: (GeoResourceSet) -> Unit = {},
    onDeleteCustomGeoSource: (String) -> Unit = {}
) {
    val context = LocalContext.current

    var xraySocks by remember { mutableStateOf(initialXraySettings.socksBindAddress) }
    var xrayHttp by remember { mutableStateOf(initialXraySettings.httpBindAddress) }
    var xrayAuth by remember { mutableStateOf(initialXraySettings.isProxyAuthEnabled) }
    var xrayUser by remember { mutableStateOf(initialXraySettings.proxyUser) }
    var xrayPass by remember { mutableStateOf(initialXraySettings.proxyPass) }

    var dns by remember { mutableStateOf(initialXraySettings.dns) }
    var routeDirect by remember { mutableStateOf(initialXraySettings.routeDirect) }
    var routeBlock by remember { mutableStateOf(initialXraySettings.routeBlock) }
    var fakeDns by remember { mutableStateOf(initialXraySettings.fakeDns) }
    var fragment by remember { mutableStateOf(initialXraySettings.fragment) }
    var dpiBypass by remember { mutableStateOf(initialXraySettings.dpiBypass) }
    var dpiBypassArgs by remember { mutableStateOf(initialXraySettings.dpiBypassArgs) }
    var dpiBypassRoute by remember { mutableStateOf(initialXraySettings.dpiBypassRoute) }
    var dpiBypassPort by remember { mutableStateOf(initialXraySettings.dpiBypassPort) }
    var pingConcurrency by remember { mutableIntStateOf(initialXraySettings.pingCheckConcurrency.coerceIn(1, XraySettings.MAX_PING_CHECK_CONCURRENCY)) }

    val showGeoDialog = remember { mutableStateOf(false) }
    val showRoutingPresets = remember { mutableStateOf(false) }
    val showGeoSourceEditor = remember { mutableStateOf(false) }
    // Null while adding a new source.
    val editedGeoSource = remember { mutableStateOf<GeoResourceSet?>(null) }
    val showDeleteGeoConfirm = remember { mutableStateOf(false) }

    // Null: a custom source that was removed from the list after its files were downloaded.
    val installedVariant = GeoResourceSet.find(initialXraySettings.geoVariant, customGeoSources)
    // While a download is in flight, reflect its target rather than the (possibly stale, or
    // just-deleted) previously installed variant - otherwise the label lies about what's
    // actually happening (e.g. still saying "v2fly" while RunetFreedom is being fetched).
    val displayedVariant = (geoAssetsState as? GeoAssetsState.Downloading)?.variant ?: installedVariant
    val installedVariantLabel = geoVariantLabel(displayedVariant)
    val geoDateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val geoStatusText = when {
        geoAssetsState is GeoAssetsState.Downloading -> stringResource(R.string.geo_resources_downloading)
        geoAssetsInstalled -> stringResource(R.string.geo_resources_updated_at, geoDateFormat.format(Date(geoAssetsInstalledAt)))
        else -> stringResource(R.string.geo_resources_not_downloaded)
    }

    val scrollState = rememberScrollState()

    val currentXraySettings = remember(
        xraySocks, xrayHttp, xrayAuth, xrayUser, xrayPass,
        dns, routeDirect, routeBlock, fakeDns, fragment, dpiBypass, dpiBypassArgs, dpiBypassRoute, dpiBypassPort, pingConcurrency, initialXraySettings
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
            fakeDns = fakeDns,
            fragment = fragment,
            dpiBypass = dpiBypass,
            dpiBypassArgs = dpiBypassArgs,
            dpiBypassRoute = dpiBypassRoute,
            dpiBypassPort = dpiBypassPort,
            pingCheckConcurrency = pingConcurrency
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
        currentXraySettings.fakeDns != initialXraySettings.fakeDns ||
        currentXraySettings.fragment != initialXraySettings.fragment ||
        currentXraySettings.dpiBypass != initialXraySettings.dpiBypass ||
        currentXraySettings.dpiBypassArgs != initialXraySettings.dpiBypassArgs ||
        currentXraySettings.dpiBypassRoute != initialXraySettings.dpiBypassRoute ||
        currentXraySettings.dpiBypassPort != initialXraySettings.dpiBypassPort ||
        currentXraySettings.pingCheckConcurrency != initialXraySettings.pingCheckConcurrency
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
                                isSecret = true
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
                        privacyMode = privacyMode,
                        singleLine = false,
                        minLines = 1,
                        maxLines = 5
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

            // Before routing: its geosite:/geoip: entries (and the presets) need these files.
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

            SectionGroup(title = stringResource(R.string.xray_settings_group_routing)) {
                SectionItem(
                    position = ItemPosition.Top,
                    onClick = {
                        if (privacyMode) return@SectionItem
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        showRoutingPresets.value = true
                    }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StandardLeadingIcon {
                            Icon(
                                painter = painterResource(R.drawable.route_24px),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            RowLabel(text = stringResource(R.string.routing_presets_title))
                            SupportingText(
                                RoutingPreset.matching(routeDirect)?.let { stringResource(it.labelRes) }
                                    ?: stringResource(R.string.routing_preset_custom)
                            )
                        }
                    }
                }

                SectionItem(position = ItemPosition.Middle) {
                    TextFieldRow(
                        label = stringResource(R.string.xray_settings_route_direct_label),
                        value = routeDirect.redact(privacyMode),
                        onValueChange = { if (!privacyMode) routeDirect = it },
                        placeholder = stringResource(R.string.xray_settings_route_direct_placeholder),
                        supportingText = stringResource(R.string.xray_settings_route_direct_desc),
                        readOnly = privacyMode,
                        isModified = routeDirect != initialXraySettings.routeDirect,
                        privacyMode = privacyMode,
                        singleLine = false,
                        minLines = 1,
                        maxLines = 5
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
                        privacyMode = privacyMode,
                        singleLine = false,
                        minLines = 1,
                        maxLines = 5
                    )
                }
            }

            SectionGroup(title = stringResource(R.string.xray_settings_group_dpi)) {
                SectionItem(
                    position = ItemPosition.Top,
                    onClick = {
                        fragment = !fragment
                        HapticUtil.perform(
                            context,
                            if (fragment) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                        )
                    }
                ) {
                    SwitchRow(
                        label = stringResource(R.string.xray_settings_fragment_label),
                        supportingText = stringResource(R.string.xray_settings_fragment_desc),
                        checked = fragment,
                        onCheckedChange = { fragment = it },
                        isModified = fragment != initialXraySettings.fragment
                    )
                }

                SectionItem(
                    position = if (dpiBypass) ItemPosition.Middle else ItemPosition.Bottom,
                    onClick = {
                        dpiBypass = !dpiBypass
                        HapticUtil.perform(
                            context,
                            if (dpiBypass) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                        )
                    }
                ) {
                    SwitchRow(
                        label = stringResource(R.string.xray_settings_byedpi_label),
                        supportingText = stringResource(R.string.xray_settings_byedpi_desc),
                        checked = dpiBypass,
                        onCheckedChange = { dpiBypass = it },
                        isModified = dpiBypass != initialXraySettings.dpiBypass
                    )
                }

                ExpandableSection(visible = dpiBypass) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SectionItem(position = ItemPosition.Middle) {
                            TextFieldRow(
                                label = stringResource(R.string.xray_settings_byedpi_route_label),
                                value = dpiBypassRoute,
                                onValueChange = { dpiBypassRoute = it },
                                placeholder = stringResource(R.string.xray_settings_byedpi_route_placeholder),
                                supportingText = stringResource(R.string.xray_settings_byedpi_route_desc),
                                isModified = dpiBypassRoute != initialXraySettings.dpiBypassRoute,
                                singleLine = false,
                                minLines = 1,
                                maxLines = 5
                            )
                        }
                        SectionItem(position = ItemPosition.Middle) {
                            TextFieldRow(
                                label = stringResource(R.string.xray_settings_byedpi_args_label),
                                value = dpiBypassArgs,
                                onValueChange = { dpiBypassArgs = it },
                                placeholder = ByeDpi.DEFAULT_ARGS,
                                supportingText = stringResource(R.string.xray_settings_byedpi_args_desc),
                                isModified = dpiBypassArgs != initialXraySettings.dpiBypassArgs,
                                singleLine = false,
                                minLines = 1,
                                maxLines = 5
                            )
                        }
                        SectionItem(position = ItemPosition.Bottom) {
                            TextFieldRow(
                                label = stringResource(R.string.xray_settings_byedpi_port_label),
                                value = dpiBypassPort,
                                onValueChange = { value -> dpiBypassPort = value.filter(Char::isDigit).take(5) },
                                placeholder = stringResource(R.string.xray_settings_byedpi_port_placeholder),
                                supportingText = stringResource(R.string.xray_settings_byedpi_port_desc),
                                isError = dpiBypassPort.isNotEmpty() && !ByeDpi.isValidPort(dpiBypassPort),
                                isModified = dpiBypassPort != initialXraySettings.dpiBypassPort,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        }
                    }
                }
            }

            SectionGroup(title = stringResource(R.string.xray_settings_group_ping_check)) {
                SectionItem(position = ItemPosition.Single) {
                    SliderRow(
                        label = stringResource(R.string.xray_settings_ping_concurrency_label),
                        supportingText = stringResource(R.string.xray_settings_ping_concurrency_desc),
                        value = pingConcurrency.toFloat(),
                        onValueChange = { pingConcurrency = it.roundToInt() },
                        valueRange = 1f..XraySettings.MAX_PING_CHECK_CONCURRENCY.toFloat(),
                        steps = XraySettings.MAX_PING_CHECK_CONCURRENCY - 2,
                        isModified = pingConcurrency != initialXraySettings.pingCheckConcurrency
                    )
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
            onDismiss = { showGeoDialog.value = false },
            customSources = customGeoSources,
            onAddSource = { editedGeoSource.value = null; showGeoSourceEditor.value = true },
            onEditSource = { editedGeoSource.value = it; showGeoSourceEditor.value = true },
            onDeleteSource = onDeleteCustomGeoSource
        )
    }

    if (showRoutingPresets.value) {
        // Which presets can run depends on the categories in the installed files; with none
        // installed yet the footer already says to download them first.
        val installedGeoId = initialXraySettings.geoVariant.takeIf { geoAssetsInstalled }
        val isAvailable = { preset: RoutingPreset -> installedGeoId == null || preset.supports(installedGeoId) }
        SelectionDialog(
            title = stringResource(R.string.routing_presets_title),
            description = stringResource(R.string.routing_presets_desc),
            items = RoutingPreset.entries,
            isSelected = { it == RoutingPreset.matching(routeDirect) },
            onSelect = { preset ->
                if (isAvailable(preset)) {
                    routeBlock = RoutingPreset.routeBlockFor(preset, routeBlock)
                    routeDirect = preset.routeDirect
                    showRoutingPresets.value = false
                }
            },
            onDismiss = { showRoutingPresets.value = false },
            footer = if (geoAssetsInstalled) null else {
                {
                    // geosite:/geoip: entries need the geo files - without them Xray won't start.
                    SupportingText(
                        text = stringResource(R.string.routing_presets_no_geo),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        ) { preset, _ ->
            Column(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalArrangement = Arrangement.Center
            ) {
                val available = isAvailable(preset)
                Text(
                    text = stringResource(preset.labelRes),
                    color = if (available) Color.Unspecified else LocalContentColor.current.copy(alpha = 0.38f)
                )
                SupportingText(
                    text = when {
                        available -> stringResource(preset.descRes)
                        preset.requiredGeo != null -> stringResource(R.string.routing_preset_needs_geo, geoVariantLabel(GeoResourceSet.find(preset.requiredGeo, emptyList())))
                        else -> stringResource(R.string.routing_preset_unsupported_geo, geoVariantLabel(GeoResourceSet.find(installedGeoId.orEmpty(), customGeoSources)))
                    },
                    color = if (available) LocalContentColor.current.copy(alpha = 0.75f) else MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (showGeoSourceEditor.value) {
        GeoSourceEditDialog(
            source = editedGeoSource.value,
            onSave = {
                onSaveCustomGeoSource(it)
                showGeoSourceEditor.value = false
            },
            onDismiss = { showGeoSourceEditor.value = false }
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

/**
 * Ready-made "direct routes" (-route-direct) sets, like v2rayNG's routing presets (issue #45).
 * Choosing one replaces the field; saving the screen applies it as usual. Every category used
 * here is in all three built-in geo sources (runetfreedom, v2fly, Loyalsoldier).
 */
private enum class RoutingPreset(
    val labelRes: Int,
    val descRes: Int,
    val routeDirect: String,
    // Also replaces the blocked list; null leaves it as it is.
    val routeBlock: String? = null,
    // The installed geo source it needs (a GeoResourceSet id), or null: any with its categories.
    val requiredGeo: String? = null,
    // Geo sources it can't run on - Xray won't start on a missing category.
    val unsupportedGeo: Set<String> = emptySet()
) {
    // v2rayNG's "Russia whitelist", minus what -route-direct can't express (bittorrent, QUIC block).
    RUSSIA(
        R.string.routing_preset_russia,
        R.string.routing_preset_russia_desc,
        "geosite:private,geoip:private,geosite:category-ru,geoip:ru",
        // RoscomVPN's geoip.dat has no geoip:ru.
        unsupportedGeo = setOf(GeoResourceSet.ROSCOMVPN.id)
    ),
    // hydraponique/roscomvpn-routing's DEFAULT profile, with its own geo files. Its "proxy" list
    // (YouTube, Telegram, GitHub, Google Play, Twitch ads) has no field here - checked against
    // these files, of those only Twitch ads also falls under a direct category (twitch).
    ROSCOMVPN(
        R.string.routing_preset_roscomvpn,
        R.string.routing_preset_roscomvpn_desc,
        "geosite:private,geosite:category-ru,geosite:whitelist,geosite:microsoft,geosite:apple," +
            "geosite:epicgames,geosite:riot,geosite:escapefromtarkov,geosite:steam,geosite:twitch," +
            "geosite:pinterest,geosite:faceit,geoip:private,geoip:direct",
        routeBlock = "geosite:win-spy,geosite:torrent,geosite:category-ads",
        requiredGeo = GeoResourceSet.ROSCOMVPN.id
    ),
    LOCAL(
        R.string.routing_preset_local,
        R.string.routing_preset_local_desc,
        "geosite:private,geoip:private"
    ),
    ALL_TUNNEL(
        R.string.routing_preset_all_tunnel,
        R.string.routing_preset_all_tunnel_desc,
        ""
    );

    /** Whether it can run on the installed geo source [geoId]. */
    fun supports(geoId: String): Boolean =
        (requiredGeo == null || requiredGeo == geoId) && geoId !in unsupportedGeo

    companion object {
        private fun entriesOf(list: String) = list.split(',').map(String::trim).filter(String::isNotEmpty).toSet()

        /** The preset the field holds as it is (order and spacing aside), or null for a custom one. */
        fun matching(routeDirect: String): RoutingPreset? {
            val entries = entriesOf(routeDirect)
            return RoutingPreset.entries.find { entriesOf(it.routeDirect) == entries }
        }

        /**
         * The blocked list after switching to [preset]: its own, if it has one; otherwise the
         * current one - unless that is just another preset's, whose categories may be missing
         * from the geo files the new one runs on (RoscomVPN's win-spy elsewhere).
         */
        fun routeBlockFor(preset: RoutingPreset, current: String): String = preset.routeBlock
            ?: if (RoutingPreset.entries.any { it.routeBlock != null && entriesOf(it.routeBlock) == entriesOf(current) }) ""
            else current
    }
}

@Composable
private fun GeoResourcesDialog(
    selected: GeoResourceSet?,
    state: GeoAssetsState,
    progress: Int,
    installed: Boolean,
    installedAt: Long,
    onDownload: (GeoResourceSet) -> Unit,
    onCancelDownload: () -> Unit,
    onRequestDelete: () -> Unit,
    onDismiss: () -> Unit,
    customSources: List<GeoResourceSet>,
    onAddSource: () -> Unit,
    onEditSource: (GeoResourceSet) -> Unit,
    onDeleteSource: (String) -> Unit
) {
    val context = LocalContext.current
    // Ids, not objects: an edited custom source is a new object with the same id.
    var pendingId by remember { mutableStateOf(((state as? GeoAssetsState.Downloading)?.variant ?: selected)?.id) }
    val items = GeoResourceSet.BUILT_IN + customSources
    val pending = items.find { it.id == pendingId }
    val isDownloading = state is GeoAssetsState.Downloading
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    SelectionDialog(
        title = stringResource(R.string.xray_settings_group_geo),
        description = stringResource(R.string.geo_resources_dialog_desc),
        items = items,
        isSelected = { it.id == pendingId },
        onSelect = { if (!isDownloading) pendingId = it.id },
        onDismiss = onDismiss,
        dismissOnSelect = false,
        footer = {
            Surface(
                onClick = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    onAddSource()
                },
                enabled = !isDownloading,
                shape = MaterialTheme.shapes.extraLarge,
                color = Color.Transparent,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StandardLeadingIcon {
                        Icon(
                            painter = painterResource(R.drawable.add_24px),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = stringResource(R.string.geo_source_add),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            Column(modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp)) {
                val statusText = when {
                    isDownloading -> stringResource(R.string.geo_resources_downloading)
                    state is GeoAssetsState.Error -> stringResource(R.string.geo_resources_download_failed, state.message)
                    installed && pendingId != null && pendingId == selected?.id -> stringResource(
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
                                    pending?.let(onDownload)
                                },
                                enabled = pending != null,
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
                Text(text = geoVariantLabel(variant))
                SupportingText(
                    text = geoVariantDescription(variant),
                    color = LocalContentColor.current.copy(alpha = 0.75f)
                )
            }
            if (variant.isCustom) {
                var showMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { showMenu = true }, enabled = !isDownloading) {
                        Icon(
                            painter = painterResource(R.drawable.more_vert_24px),
                            contentDescription = stringResource(R.string.profile_actions)
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.geo_source_edit)) },
                            onClick = { showMenu = false; onEditSource(variant) },
                            leadingIcon = { Icon(painterResource(R.drawable.edit_24px), null) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.geo_source_delete)) },
                            onClick = {
                                showMenu = false
                                if (pendingId == variant.id) pendingId = selected?.id
                                onDeleteSource(variant.id)
                            },
                            leadingIcon = {
                                Icon(
                                    painterResource(R.drawable.delete_24px),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

/** Null: a custom source removed from the list after its files were installed. */
@Composable
private fun geoVariantLabel(variant: GeoResourceSet?): String = when {
    variant == null -> stringResource(R.string.geo_source_removed)
    variant.isCustom -> variant.displayName
    else -> stringResource(
        when (variant.id) {
            GeoResourceSet.LOYALSOLDIER.id -> R.string.geo_variant_loyalsoldier
            GeoResourceSet.ROSCOMVPN.id -> R.string.geo_variant_roscomvpn
            GeoResourceSet.V2FLY.id -> R.string.geo_variant_v2fly
            else -> R.string.geo_variant_runetfreedom
        }
    )
}

@Composable
private fun geoVariantDescription(variant: GeoResourceSet): String =
    if (variant.isCustom) stringResource(R.string.geo_source_custom_desc)
    else stringResource(
        when (variant.id) {
            GeoResourceSet.LOYALSOLDIER.id -> R.string.geo_variant_loyalsoldier_desc
            GeoResourceSet.ROSCOMVPN.id -> R.string.geo_variant_roscomvpn_desc
            GeoResourceSet.V2FLY.id -> R.string.geo_variant_v2fly_desc
            else -> R.string.geo_variant_runetfreedom_desc
        }
    )

@Composable
private fun GeoSourceEditDialog(
    source: GeoResourceSet?,
    onSave: (GeoResourceSet) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(source?.name ?: "") }
    var geoipUrl by remember { mutableStateOf(source?.geoipUrl ?: "") }
    var geositeUrl by remember { mutableStateOf(source?.geositeUrl ?: "") }
    val geoipValid = ValidatorUtils.isValidUrl(geoipUrl.trim())
    val geositeValid = ValidatorUtils.isValidUrl(geositeUrl.trim())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (source == null) R.string.geo_source_add else R.string.geo_source_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.geo_source_name_label)) },
                    supportingText = { Text(stringResource(R.string.geo_source_name_desc)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = geoipUrl,
                    onValueChange = { geoipUrl = it },
                    label = { Text(stringResource(R.string.geo_source_geoip_label)) },
                    supportingText = { Text(stringResource(R.string.geo_source_url_desc)) },
                    isError = geoipUrl.isNotBlank() && !geoipValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = geositeUrl,
                    onValueChange = { geositeUrl = it },
                    label = { Text(stringResource(R.string.geo_source_geosite_label)) },
                    supportingText = { Text(stringResource(R.string.geo_source_url_desc)) },
                    isError = geositeUrl.isNotBlank() && !geositeValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        source?.copy(name = name.trim(), geoipUrl = geoipUrl.trim(), geositeUrl = geositeUrl.trim())
                            ?: GeoResourceSet.custom(name, geoipUrl, geositeUrl)
                    )
                },
                enabled = geoipValid && geositeValid
            ) {
                Text(stringResource(R.string.btn_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
