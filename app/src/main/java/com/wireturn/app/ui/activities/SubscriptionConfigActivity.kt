package com.wireturn.app.ui.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.R
import com.wireturn.app.ui.AppTopAppBar
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.ItemPosition
import com.wireturn.app.ui.SectionGroup
import com.wireturn.app.ui.SectionItem
import com.wireturn.app.ui.SwitchRow
import com.wireturn.app.ui.TextFieldRow
import com.wireturn.app.ui.theme.WireturnTheme
import com.wireturn.app.viewmodel.MainViewModel

class SubscriptionConfigActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        splashScreen.setKeepOnScreenCondition { !viewModel.isInitialized.value }

        val subId = intent.getStringExtra("EXTRA_SUB_ID") ?: run {
            finish()
            return
        }

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val dynamicTheme by viewModel.dynamicTheme.collectAsStateWithLifecycle()
            val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
            val isInitialized by viewModel.isInitialized.collectAsStateWithLifecycle()
            
            val sub = subscriptions.find { it.id == subId }

            if (!isInitialized || sub == null) {
                if (isInitialized) {
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        finish()
                    }
                }
                return@setContent
            }

            val context = LocalContext.current
            val topAppBarState = rememberTopAppBarState()
            val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
                state = topAppBarState,
                flingAnimationSpec = null
            )

            var url by remember(sub) { mutableStateOf(sub.url) }
            var autoUpdate by remember(sub) { mutableStateOf(sub.autoUpdate) }
            var onlyUpdateIfSelected by remember(sub) { mutableStateOf(sub.onlyUpdateIfSelected) }
            var requireTunnelForUpdate by remember(sub) { mutableStateOf(sub.requireTunnelForUpdate) }
            var updateIntervalMinutes by remember(sub) { mutableIntStateOf(sub.updateIntervalMinutes) }

            val isIntervalValid = !autoUpdate || updateIntervalMinutes >= 20
            val isModified = url != sub.url || 
                             autoUpdate != sub.autoUpdate || 
                             onlyUpdateIfSelected != sub.onlyUpdateIfSelected ||
                             requireTunnelForUpdate != sub.requireTunnelForUpdate ||
                             updateIntervalMinutes != sub.updateIntervalMinutes

            val showExitDialog = remember { mutableStateOf(false) }
            val showDeleteConfirm = remember { mutableStateOf(false) }

            val handleBack = {
                if (isModified) {
                    showExitDialog.value = true
                } else {
                    finish()
                }
            }

            BackHandler(onBack = handleBack)

            WireturnTheme(themeMode = themeMode, dynamicColor = dynamicTheme) {
                Scaffold(
                    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                    topBar = {
                        AppTopAppBar(
                            title = stringResource(R.string.subscription_settings),
                            subtitle = sub.name,
                            onBack = handleBack,
                            scrollBehavior = scrollBehavior
                        )
                    },
                    floatingActionButton = {
                        AnimatedVisibility(
                            visible = isModified && url.isNotBlank() && isIntervalValid,
                            enter = scaleIn(
                                initialScale = 0.8f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            ) + fadeIn(animationSpec = tween(200)),
                            exit = scaleOut(
                                targetScale = 0.8f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMedium
                                )
                            ) + fadeOut(animationSpec = tween(150))
                        ) {
                            ExtendedFloatingActionButton(
                                onClick = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                    viewModel.updateSubscription(sub.copy(
                                        url = url,
                                        autoUpdate = autoUpdate,
                                        onlyUpdateIfSelected = onlyUpdateIfSelected,
                                        requireTunnelForUpdate = requireTunnelForUpdate,
                                        updateIntervalMinutes = updateIntervalMinutes
                                    ))
                                    finish()
                                },
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                icon = { Icon(painterResource(R.drawable.save_24px), contentDescription = null) },
                                text = { Text(stringResource(R.string.btn_save)) }
                            )
                        }
                    }
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        SectionGroup(title = stringResource(R.string.subscription_info)) {
                            SectionItem(position = ItemPosition.Single) {
                                TextFieldRow(
                                    label = stringResource(R.string.freeturn_sub_label),
                                    value = url,
                                    onValueChange = { url = it },
                                    isModified = url != sub.url
                                )
                            }
                        }

                        SectionGroup(title = stringResource(R.string.auto_update)) {
                            SectionItem(
                                position = ItemPosition.Top,
                                onClick = {
                                    val next = !requireTunnelForUpdate
                                    HapticUtil.perform(context, if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                                    requireTunnelForUpdate = next
                                }
                            ) {
                                SwitchRow(
                                    label = stringResource(R.string.update_require_tunnel),
                                    supportingText = stringResource(R.string.update_require_tunnel_desc),
                                    checked = requireTunnelForUpdate,
                                    onCheckedChange = { requireTunnelForUpdate = it },
                                    isModified = requireTunnelForUpdate != sub.requireTunnelForUpdate
                                )
                            }

                            SectionItem(
                                position = if (autoUpdate) ItemPosition.Middle else ItemPosition.Bottom,
                                onClick = {
                                    val next = !autoUpdate
                                    HapticUtil.perform(context, if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                                    autoUpdate = next
                                }
                            ) {
                                SwitchRow(
                                    label = stringResource(R.string.auto_update),
                                    checked = autoUpdate,
                                    onCheckedChange = { autoUpdate = it },
                                    isModified = autoUpdate != sub.autoUpdate
                                )
                            }

                            if (autoUpdate) {
                                SectionItem(
                                    position = ItemPosition.Middle,
                                    onClick = {
                                        val next = !onlyUpdateIfSelected
                                        HapticUtil.perform(context, if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                                        onlyUpdateIfSelected = next
                                    }
                                ) {
                                    SwitchRow(
                                        label = stringResource(R.string.auto_update_only_if_selected),
                                        supportingText = stringResource(R.string.auto_update_only_if_selected_desc),
                                        checked = onlyUpdateIfSelected,
                                        onCheckedChange = { onlyUpdateIfSelected = it },
                                        isModified = onlyUpdateIfSelected != sub.onlyUpdateIfSelected
                                    )
                                }
                                SectionItem(position = ItemPosition.Bottom) {
                                    TextFieldRow(
                                        label = stringResource(R.string.update_interval_min),
                                        value = if (updateIntervalMinutes == 0) "" else updateIntervalMinutes.toString(),
                                        onValueChange = { updateIntervalMinutes = it.toIntOrNull() ?: 0 },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        isError = updateIntervalMinutes < 20,
                                        isModified = updateIntervalMinutes != sub.updateIntervalMinutes,
                                        supportingText = if (updateIntervalMinutes < 20) stringResource(R.string.update_interval_min_error) else null
                                    )
                                }
                            }
                        }

                        SectionGroup {
                            SectionItem(
                                position = ItemPosition.Single,
                                onClick = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                    showDeleteConfirm.value = true
                                }
                            ) {
                                Text(
                                    text = stringResource(R.string.profile_delete),
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        }
                    }

                    if (showDeleteConfirm.value) {
                        AlertDialog(
                            onDismissRequest = { showDeleteConfirm.value = false },
                            title = { Text(stringResource(R.string.subscription_delete_confirm, sub.name)) },
                            text = { Text(stringResource(R.string.subscription_delete_desc)) },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                        viewModel.deleteSubscription(sub.id)
                                        finish()
                                    }
                                ) {
                                    Text(stringResource(R.string.profile_delete), color = MaterialTheme.colorScheme.error)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showDeleteConfirm.value = false }) {
                                    Text(stringResource(R.string.cancel))
                                }
                            }
                        )
                    }

                    if (showExitDialog.value) {
                        AlertDialog(
                            onDismissRequest = { showExitDialog.value = false },
                            title = { Text(stringResource(R.string.exit_without_saving_title)) },
                            text = { Text(stringResource(R.string.exit_without_saving_desc)) },
                            confirmButton = {
                                TextButton(onClick = { finish() }) {
                                    Text(stringResource(R.string.btn_exit))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showExitDialog.value = false }) {
                                    Text(stringResource(R.string.cancel))
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
