package com.wireturn.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.wireturn.app.data.Profile
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.viewmodel.MainViewModel
import java.util.Collections

@Composable
fun ProfilesBlock(
    viewModel: MainViewModel,
    onShowDialog: () -> Unit
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val currentId by viewModel.currentProfileId.collectAsStateWithLifecycle()
    val currentProfile = profiles.find { it.id == currentId } ?: profiles.firstOrNull()

    if (currentProfile != null) {
        Card(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            onClick = onShowDialog
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.mobile_24px),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                    Column {
                        Text(
                            text = currentProfile.name,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.btn_change_profile),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
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

    // Local state for reordering
    val profiles = remember { mutableStateListOf<Profile>() }
    
    // Sync local list with source of truth only when contents change
    LaunchedEffect(profilesSource) {
        if (profilesSource != profiles) {
            profiles.clear()
            profiles.addAll(profilesSource)
        }
    }

    val showCreateDialog = remember { mutableStateOf(false) }
    var addMenuExpanded by remember { mutableStateOf(false) }
    val showRenameDialog = remember { mutableStateOf<Profile?>(null) }
    val showDeleteConfirm = remember { mutableStateOf<Profile?>(null) }
    val showCloneDialog = remember { mutableStateOf<Profile?>(null) }

    var jsonToExport by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { destination ->
            jsonToExport?.let { json ->
                try {
                    context.contentResolver.openOutputStream(destination)?.use {
                        it.write(json.toByteArray())
                    }
                } catch (_: Exception) { }
                jsonToExport = null
            }
        }
    }

    val lazyListState = rememberLazyListState()
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.profiles_title))
                Box {
                    IconButton(onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        addMenuExpanded = true
                    }) {
                        Icon(
                            painterResource(R.drawable.add_24px),
                            contentDescription = stringResource(R.string.profile_create)
                        )
                    }
                    DropdownMenu(
                        expanded = addMenuExpanded,
                        onDismissRequest = { addMenuExpanded = false }
                    ) {
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
                                showCreateDialog.value = true
                            }
                        )
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
                    }
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    itemsIndexed(profiles, key = { _, it -> it.id }) { index, profile ->
                        val isSelected = profile.id == currentId
                        var menuExpanded by remember { mutableStateOf(false) }
                        val isDragged = draggedIndex == index
                        
                        val currentItemIndex by rememberUpdatedState(index)
                        val itemShape = MaterialTheme.shapes.medium

                        // Use a very stiff spring for the return animation to avoid "lazy" feel
                        val offsetAnim by animateFloatAsState(
                            targetValue = if (isDragged) dragOffset else 0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = if (isDragged) Spring.StiffnessHigh else Spring.StiffnessMedium
                            ),
                            label = "drag_offset"
                        )

                        Surface(
                            onClick = {
                                if (draggedIndex == null) {
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                    viewModel.selectProfile(profile.id, profile)
                                }
                            },
                            shape = itemShape,
                            color = when {
                                isDragged -> MaterialTheme.colorScheme.surfaceContainerHighest
                                isSelected -> MaterialTheme.colorScheme.surfaceContainerHigh
                                else -> Color.Transparent
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                // Snappy animations for add/remove/reorder
                                .animateItem(
                                    fadeInSpec = spring(stiffness = Spring.StiffnessMedium),
                                    fadeOutSpec = spring(stiffness = Spring.StiffnessMedium),
                                    placementSpec = if (isDragged) null else spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                )
                                .zIndex(if (isDragged) 10f else 0f)
                                .graphicsLayer {
                                    translationY = if (isDragged) dragOffset else offsetAnim
                                    scaleX = if (isDragged) 1.02f else 1f
                                    scaleY = if (isDragged) 1.02f else 1f
                                    shadowElevation = if (isDragged) 8.dp.toPx() else 0f
                                    shape = itemShape
                                    clip = isDragged
                                }
                                .pointerInput(Unit) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                            draggedIndex = currentItemIndex
                                            dragOffset = 0f
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragOffset += dragAmount.y
                                            
                                            val currentDraggedIdx = draggedIndex ?: return@detectDragGesturesAfterLongPress
                                            // Standard M3 ListItem height is usually 56dp
                                            val itemHeight = 56.dp.toPx()
                                            
                                            val targetIndex = when {
                                                dragOffset > itemHeight * 0.6f -> currentDraggedIdx + 1
                                                dragOffset < -itemHeight * 0.6f -> currentDraggedIdx - 1
                                                else -> currentDraggedIdx
                                            }
                                            
                                            if (targetIndex in profiles.indices && targetIndex != currentDraggedIdx) {
                                                Collections.swap(profiles, currentDraggedIdx, targetIndex)
                                                draggedIndex = targetIndex
                                                // Adjust offset to keep the item under the finger
                                                dragOffset -= (targetIndex - currentDraggedIdx) * itemHeight
                                                HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                                            }
                                        },
                                        onDragEnd = {
                                            val finalIndex = draggedIndex
                                            draggedIndex = null
                                            dragOffset = 0f
                                            if (finalIndex != null) {
                                                viewModel.reorderProfiles(profiles.toList())
                                            }
                                        },
                                        onDragCancel = {
                                            draggedIndex = null
                                            dragOffset = 0f
                                        }
                                    )
                                }
                        ) {
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = {
                                    Text(
                                        profile.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.onSurface 
                                                else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                leadingContent = {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = null
                                    )
                                },
                                trailingContent = {
                                    Box {
                                        IconButton(onClick = {
                                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                            menuExpanded = true
                                        }) {
                                            Icon(
                                                painterResource(R.drawable.more_vert_24px),
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = menuExpanded,
                                            onDismissRequest = { menuExpanded = false }
                                        ) {
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
                                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
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
                                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                                    viewModel.getProfileJson(profile.id)?.let { json ->
                                                        jsonToExport = json
                                                        val safeName = profile.name.replace(Regex("[\\\\/:*?\"<>| ]"), "_")
                                                        exportLauncher.launch("wt_$safeName.json")
                                                    }
                                                }
                                            )
                                            if (profiles.size > 1) {
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
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_close)) }
        }
    )

    if (showCreateDialog.value) {
        ProfileNameDialog(
            title = stringResource(R.string.profile_create),
            onDismiss = { showCreateDialog.value = false },
            onConfirm = { name ->
                viewModel.createProfile(name)
                showCreateDialog.value = false
            }
        )
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
                TextButton(onClick = { showDeleteConfirm.value = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
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
                onValueChange = { name = it },
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
