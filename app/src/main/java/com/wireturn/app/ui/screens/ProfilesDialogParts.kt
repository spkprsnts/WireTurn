@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)

package com.wireturn.app.ui.screens

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wireturn.app.R
import com.wireturn.app.data.Profile
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.viewmodel.MainViewModel

// Parts of ProfilesDialog that stand on their own: exporting, the sheet's header, the empty list
// and the confirmations. The list itself (grouping, drag to reorder, sticky subscription headers)
// stays there - all of it works off the same list state.

/**
 * Exporting profiles from the sheet: which ones ([targetIds]), as JSON or ZIP ([asJson]), saved to
 * a file or shared - and the two menus that ask (format only for several profiles, then the action).
 * See [rememberProfilesExport].
 */
@Stable
internal class ProfilesExport {
    var formatMenuExpanded by mutableStateOf(false)
    var actionMenuExpanded by mutableStateOf(false)
    var targetIds by mutableStateOf<List<String>>(emptyList())
    var asJson by mutableStateOf(true)

    /** true = save to a file, false = share. Set by [rememberProfilesExport] on every composition. */
    var onActionSelected: (Boolean) -> Unit = {}
        internal set

    /** Export of [targets] asked for: opens the menus when [showMenu], else only aims them. */
    fun onExportClick(context: Context, targets: List<String>, showMenu: Boolean) {
        if (showMenu) HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
        targetIds = targets
        if (targets.size > 1) {
            if (showMenu) formatMenuExpanded = true
        } else {
            asJson = true
            if (showMenu) actionMenuExpanded = true
        }
    }

    @Composable
    fun Menus() {
        ExportDropdownMenus(
            expandedFormat = formatMenuExpanded,
            onDismissFormat = { formatMenuExpanded = false },
            expandedAction = actionMenuExpanded,
            onDismissAction = { actionMenuExpanded = false },
            onFormatSelected = { json ->
                asJson = json
                formatMenuExpanded = false
                actionMenuExpanded = true
            },
            onActionSelected = onActionSelected
        )
    }
}

/** [ProfilesExport] with its file-save launchers; [profiles] names a single exported profile's file. */
@Composable
internal fun rememberProfilesExport(viewModel: MainViewModel, profiles: List<Profile>): ProfilesExport {
    val context = LocalContext.current
    val export = remember { ProfilesExport() }

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

    val exportTitle = stringResource(R.string.profile_export)
    export.onActionSelected = { save: Boolean ->
        export.actionMenuExpanded = false
        val exportTargetIds = export.targetIds
        if (save) {
            if (export.asJson) {
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
            if (export.asJson) {
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
                shareFile(context, bytes, fileName)
            }
        }
    }
    return export
}

/**
 * The sheet's title row: in selection mode the count and export/delete/select-all of the selection,
 * otherwise collapse/expand all subscriptions, export all and add a profile.
 */
@Composable
internal fun ProfilesSheetHeader(
    export: ProfilesExport,
    isSelectionMode: Boolean,
    selectedCount: Int,
    allSelected: Boolean,
    hasProfiles: Boolean,
    hasSubscriptions: Boolean,
    anySubscriptionExpanded: Boolean,
    onExportSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onToggleAllCollapsed: () -> Unit,
    onExportAll: () -> Unit
) {
    val context = LocalContext.current
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
                    selectedCount
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
                        FilledTonalIconButton(onClick = onExportSelected) {
                            Icon(
                                painterResource(R.drawable.ios_share_24px),
                                contentDescription = stringResource(R.string.profile_export)
                            )
                        }
                        export.Menus()
                    }
                    FilledTonalIconButton(onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        onDeleteSelected()
                    }) {
                        Icon(
                            painterResource(R.drawable.delete_24px),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                    FilledTonalIconButton(onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        onToggleSelectAll()
                    }) {
                        Icon(
                            painterResource(
                                if (allSelected) R.drawable.indeterminate_check_box_24px
                                else R.drawable.check_box_24px
                            ),
                            contentDescription = null
                        )
                    }
                } else {
                    if (hasSubscriptions) {
                        // Collapses them all while any is open, otherwise opens them all.
                        FilledTonalIconButton(onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            onToggleAllCollapsed()
                        }) {
                            Icon(
                                painterResource(if (anySubscriptionExpanded) R.drawable.unfold_less_24px else R.drawable.unfold_more_24px),
                                contentDescription = stringResource(
                                    if (anySubscriptionExpanded) R.string.subscriptions_collapse_all else R.string.subscriptions_expand_all
                                )
                            )
                        }
                    }
                    if (hasProfiles) {
                        Box {
                            FilledTonalIconButton(onClick = onExportAll) {
                                Icon(
                                    painterResource(R.drawable.ios_share_24px),
                                    contentDescription = stringResource(R.string.profile_export_all)
                                )
                            }
                            export.Menus()
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

/** No profiles and no subscriptions yet. */
@Composable
internal fun ProfilesEmptyState() {
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

/** Confirms deleting profiles: [title] names what goes. */
@Composable
internal fun ProfilesDeleteDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(stringResource(R.string.profile_delete_desc)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text(stringResource(R.string.profile_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/** A manual refresh of a subscription that wants the tunnel, asked while it's down. */
@Composable
internal fun ManualUpdateNoTunnelDialog(onProceed: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_manual_no_tunnel_title)) },
        text = { Text(stringResource(R.string.update_manual_no_tunnel_desc)) },
        confirmButton = {
            TextButton(onClick = onProceed) { Text(stringResource(R.string.btn_proceed)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
