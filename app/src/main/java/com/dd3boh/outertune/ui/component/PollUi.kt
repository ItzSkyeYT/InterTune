/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Poll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.dd3boh.outertune.R
import com.dd3boh.outertune.utils.PollChecker

/**
 * The line at the top of Home that says a question is waiting.
 *
 * Quiet on purpose. It is one row, it never blocks anything, and the close button is as easy to hit
 * as the row itself, because a question nobody wants to answer should cost one tap to be rid of.
 */
@Composable
fun PollBanner(
    poll: PollChecker.Poll,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Poll,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = poll.banner,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.poll_banner_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.poll_dismiss),
                )
            }
        }
    }
}

/**
 * The question itself, taking the whole screen.
 *
 * Fullscreen rather than a dialog because a question can carry pictures and several options, and
 * every other dialog in this app caps itself at 560dp, which would make images useless. That cap is
 * right for a yes/no prompt and wrong for this.
 *
 * Nothing here is compulsory. Closing without answering leaves the poll unanswered rather than
 * marking it dealt with, so it can still be found later from settings.
 */
@Composable
fun PollDialog(
    poll: PollChecker.Poll,
    onSubmit: (List<String>) -> Unit,
    onClose: () -> Unit,
) {
    var selected by remember(poll.id) { mutableStateOf(emptySet<String>()) }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.poll_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.poll_close))
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        // Same 720dp cap the settings screens use, so this does not stretch a
                        // question across the full width of a tablet.
                        .widthIn(max = 720.dp)
                        .align(Alignment.CenterHorizontally)
                        .padding(horizontal = 24.dp)
                ) {
                    poll.imageUrl?.let { url ->
                        PollImage(url, Modifier.fillMaxWidth().height(180.dp))
                        Spacer(Modifier.height(20.dp))
                    }

                    Text(
                        text = poll.question,
                        style = MaterialTheme.typography.headlineSmall,
                    )

                    poll.body?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(20.dp))

                    poll.options.forEach { option ->
                        val isOn = option.id in selected
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    selected = when {
                                        // One choice replaces the last, several toggle.
                                        !poll.multiple -> setOf(option.id)
                                        isOn -> selected - option.id
                                        else -> selected + option.id
                                    }
                                }
                                .padding(vertical = 4.dp, horizontal = 4.dp)
                        ) {
                            if (poll.multiple) {
                                Checkbox(checked = isOn, onCheckedChange = null)
                            } else {
                                RadioButton(selected = isOn, onClick = null)
                            }
                            option.imageUrl?.let { url ->
                                Spacer(Modifier.height(0.dp))
                                PollImage(
                                    url,
                                    Modifier
                                        .padding(start = 12.dp)
                                        .height(56.dp)
                                        .widthIn(max = 96.dp)
                                )
                            }
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    // Stated plainly and in the same place every time, because it is the claim the
                    // whole feature rests on. What is actually sent is listed in PollChecker's docs.
                    Text(
                        text = stringResource(R.string.poll_anonymous_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(24.dp))
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 720.dp)
                        .align(Alignment.CenterHorizontally)
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Button(
                        onClick = { onSubmit(selected.toList()) },
                        enabled = selected.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.poll_send))
                    }
                    TextButton(
                        onClick = onClose,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.poll_not_now))
                    }
                }
            }
        }
    }
}

/**
 * A picture from the poll document.
 *
 * These urls are written by hand rather than produced by the app, so unlike every other image in
 * here one of them will eventually be wrong. It gets a placeholder box rather than collapsing the
 * layout, since a broken picture should not take the question down with it.
 */
@Composable
private fun PollImage(url: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
