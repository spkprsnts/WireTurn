@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.wireturn.app.R
import com.wireturn.app.ui.theme.ContentAlpha
import com.wireturn.app.viewmodel.UpdateState

/**
 * A shared component to display application update status.
 */
@Composable
fun UpdateBlock(
    state: UpdateState,
    progress: Int,
    modifier: Modifier = Modifier,
    showChangelog: Boolean = true,
    onDownload: (() -> Unit)? = null,
    onInstall: (() -> Unit)? = null,
    onCheck: (() -> Unit)? = null
) {
    val titleText = stringResource(R.string.update_title)

    val supportingText = when (state) {
        is UpdateState.Idle -> stringResource(R.string.update_tap_to_check)
        is UpdateState.Checking -> stringResource(R.string.update_checking)
        is UpdateState.Available -> stringResource(R.string.update_available, state.version)
        is UpdateState.Downloading -> stringResource(R.string.update_downloading_short)
        is UpdateState.ReadyToInstall -> stringResource(R.string.update_ready_desc_short)
        is UpdateState.NoUpdate -> stringResource(R.string.update_no_update)
        is UpdateState.Error -> stringResource(R.string.update_error, state.message)
    }

    // Сохраняем описание обновы, чтобы оно не пропадало во время загрузки или когда всё готово
    var currentChangelog by remember { mutableStateOf("") }
    LaunchedEffect(state) {
        if (state is UpdateState.Available) {
            currentChangelog = state.changelog
        } else if (state is UpdateState.Idle || state is UpdateState.NoUpdate) {
            currentChangelog = ""
        }
    }

    val contentColor = MaterialTheme.colorScheme.onSurface

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LargeLeadingIcon {
                val targetContentColor = MaterialTheme.colorScheme.onSurface

                when (state) {
                    is UpdateState.Checking, is UpdateState.Downloading -> {
                        androidx.compose.material3.LoadingIndicator(
                            modifier = Modifier.size(40.dp),
                            color = targetContentColor
                        )
                    }
                    else -> {
                        Icon(
                            painter = painterResource(
                                when (state) {
                                    is UpdateState.Error -> R.drawable.error_24px
                                    is UpdateState.Available, is UpdateState.ReadyToInstall -> R.drawable.info_24px
                                    else -> R.drawable.sync_24px
                                }
                            ),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = if (state is UpdateState.Error) MaterialTheme.colorScheme.error
                                   else targetContentColor
                        )
                    }
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                RowLabel(text = titleText)
                Spacer(Modifier.height(2.dp))
                SupportingText(
                    text = supportingText,
                    color = if (state is UpdateState.Error) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface
                )
            }

            when (state) {
                is UpdateState.Available -> {
                    if (onDownload != null) {
                        Spacer(Modifier.width(12.dp))
                        TextButton(
                            onClick = { onDownload.invoke() },
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.update_download),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                is UpdateState.Downloading -> {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "$progress%",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp),
                        textAlign = TextAlign.End
                    )
                }
                is UpdateState.ReadyToInstall -> {
                    if (onInstall != null) {
                        Spacer(Modifier.width(12.dp))
                        TextButton(
                            onClick = { onInstall.invoke() },
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.update_install),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                is UpdateState.Idle, is UpdateState.NoUpdate, is UpdateState.Error -> {
                    if (onCheck != null) {
                        Spacer(Modifier.width(12.dp))
                        FilledTonalIconButton(
                            onClick = { onCheck.invoke() }
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.refresh_24px),
                                contentDescription = stringResource(R.string.update_tap_to_check)
                            )
                        }
                    }
                }
                else -> {}
            }
        }

        // Линейный прогресс-бар при скачивании
        AnimatedVisibility(
            visible = state is UpdateState.Downloading,
            enter = expandVertically(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = shrinkVertically(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec())
        ) {
            androidx.compose.material3.LinearWavyProgressIndicator(
                progress = { progress.div(100f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                color = MaterialTheme.colorScheme.primary
            )
        }

        AnimatedVisibility(
            visible = showChangelog && currentChangelog.isNotBlank(),
            enter = expandVertically(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = shrinkVertically(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec())
        ) {
            Text(
                text = MarkdownUtils.parseMarkdown(
                    text = currentChangelog,
                    linkStyle = SpanStyle(
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline
                    )
                ),
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = ContentAlpha.secondary),
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}
