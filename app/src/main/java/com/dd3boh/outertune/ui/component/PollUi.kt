/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Poll
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.dd3boh.outertune.R
import com.dd3boh.outertune.utils.PollChecker

/**
 * The line at the top of Home that says a question is waiting.
 *
 * Tinted rather than another grey card, because it is a one-off invitation sitting in a page of
 * album art and needs to read as different without shouting. It never blocks anything, and the
 * close button is as easy to hit as the row itself: a question nobody wants should cost one tap.
 */
@Composable
fun PollBanner(
    poll: PollChecker.Poll,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Poll,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondary,
                    modifier = Modifier.size(22.dp),
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp)
            ) {
                Text(
                    text = poll.banner,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.poll_banner_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                )
            }

            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.poll_dismiss),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
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
 * Two panes when there is room, one when there is not. A single centred column on a tablet in
 * landscape left most of the screen empty and pushed the answers below the fold, which is the worst
 * of both: nothing to look at, and the part you came to use out of sight. Side by side, the picture
 * and the question sit next to the answers and the whole thing fits without scrolling.
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
    // Saveable, and a list rather than a set, so that rotating the tablet does not quietly throw
    // away what somebody had already picked. A set is not something a Bundle can hold.
    var selected by rememberSaveable(poll.id) { mutableStateOf(listOf<String>()) }

    val onToggle: (PollChecker.Option) -> Unit = { option ->
        selected = when {
            // One choice replaces the last, several toggle.
            !poll.multiple -> listOf(option.id)
            option.id in selected -> selected - option.id
            else -> selected + option.id
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
            ) {
                // Enough for two readable columns rather than two cramped ones. Below this a phone,
                // or a tablet held upright, still gets the single column.
                val wide = maxWidth >= 720.dp

                Column(Modifier.fillMaxSize()) {
                    PollTopBar(onClose)

                    if (wide) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(32.dp),
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                PollQuestion(poll, heroHeight = 260.dp)
                                Spacer(Modifier.height(24.dp))
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    PollOptions(poll, selected, onToggle)
                                }
                                PollFooter(
                                    enabled = selected.isNotEmpty(),
                                    onSubmit = { onSubmit(selected) },
                                    onClose = onClose,
                                )
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                                .widthIn(max = 720.dp)
                                .align(Alignment.CenterHorizontally)
                                .padding(horizontal = 24.dp)
                        ) {
                            PollQuestion(poll, heroHeight = 200.dp)
                            Spacer(Modifier.height(24.dp))
                            PollOptions(poll, selected, onToggle)
                            Spacer(Modifier.height(16.dp))
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 720.dp)
                                .align(Alignment.CenterHorizontally)
                                .padding(horizontal = 24.dp)
                        ) {
                            PollFooter(
                                enabled = selected.isNotEmpty(),
                                onSubmit = { onSubmit(selected) },
                                onClose = onClose,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PollTopBar(onClose: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)
    ) {
        Text(
            text = stringResource(R.string.poll_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose) {
            Icon(Icons.Rounded.Close, stringResource(R.string.poll_close))
        }
    }
}

@Composable
private fun PollQuestion(poll: PollChecker.Poll, heroHeight: androidx.compose.ui.unit.Dp) {
    poll.imageUrl?.let { url ->
        PollImage(
            url = url,
            scrim = true,
            modifier = Modifier
                .fillMaxWidth()
                .height(heroHeight),
        )
        Spacer(Modifier.height(24.dp))
    }

    Text(
        text = poll.question,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )

    poll.body?.let {
        Spacer(Modifier.height(10.dp))
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PollOptions(
    poll: PollChecker.Poll,
    selected: List<String>,
    onToggle: (PollChecker.Option) -> Unit,
) {
    poll.options.forEach { option ->
        PollOption(
            option = option,
            selected = option.id in selected,
            multiple = poll.multiple,
            onClick = { onToggle(option) },
        )
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun PollFooter(
    enabled: Boolean,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.padding(top = 8.dp, bottom = 8.dp)) {
        // Outside the scrolling area on purpose. This is the claim the whole feature rests on, and
        // a long question with several options pushed it below the fold, which meant the one thing
        // somebody should read before answering was the one thing they might never see.
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(14.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.VisibilityOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.poll_anonymous_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = onSubmit,
            enabled = enabled,
            shape = RoundedCornerShape(16.dp),
            contentPadding = ButtonDefaults.ContentPadding,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text(
                text = stringResource(R.string.poll_send),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        TextButton(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.poll_not_now))
        }
    }
}

/**
 * One answer, as a card that fills in when chosen.
 *
 * A card rather than a bare radio row because the whole thing is then the target, which matters on
 * a phone, and because a filled card makes what you have picked obvious at a glance in a way a
 * small dot at the edge of the screen does not.
 */
@Composable
private fun PollOption(
    option: PollChecker.Option,
    selected: Boolean,
    multiple: Boolean,
    onClick: () -> Unit,
) {
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        label = "pollOptionContainer",
    )
    val borderColour by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        label = "pollOptionBorder",
    )
    val borderWidth by animateDpAsState(
        if (selected) 2.dp else 0.dp,
        label = "pollOptionBorderWidth",
    )

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        border = BorderStroke(borderWidth, borderColour),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            option.imageUrl?.let { url ->
                PollImage(
                    url = url,
                    modifier = Modifier
                        .size(56.dp)
                        .padding(end = 0.dp),
                )
                Spacer(Modifier.size(14.dp))
            }

            Text(
                text = option.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )

            // Trailing rather than leading: the label is what people read, and the state indicator
            // reads better as the answer to it than as a bullet in front of it.
            if (multiple) {
                Checkbox(checked = selected, onCheckedChange = null)
            } else {
                RadioButton(selected = selected, onClick = null)
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
private fun PollImage(
    url: String,
    modifier: Modifier = Modifier,
    scrim: Boolean = false,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(if (scrim) 20.dp else 12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // Keeps a bright photo from fighting the text that follows it.
        if (scrim) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.55f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.28f),
                        )
                    )
            )
        }
    }
}
