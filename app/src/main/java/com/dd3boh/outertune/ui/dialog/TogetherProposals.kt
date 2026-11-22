/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.dialog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.dd3boh.outertune.R
import com.dd3boh.outertune.playback.PlayerConnection
import com.dd3boh.outertune.together.TogetherSession
import kotlinx.coroutines.delay

/**
 * A guest's song proposed to this phone while it is in control of a room: a card at the top of the app, on every screen,
 * until Play it or Not now. It was a dialog, and a tap beside it or Back threw the proposal away for good: in #testers on
 * 22 Nov 2026 dvd, in control, was typing when it came up, tapped beside its buttons and never knew what it was. The card
 * closes on neither, takes no tap meant for the screen under it, and its buttons wait a moment after it comes
 * ([PROPOSAL_TAP_GUARD_MS]). Under the room's sheet, the sheet shows it instead.
 */
@Composable
fun TogetherProposalCard(connection: PlayerConnection?, modifier: Modifier = Modifier) {
    val room = connection?.service?.together?.collectAsState()?.value
    val status = room?.status?.collectAsState()?.value
    val waiting = status?.takeIf { it.leading }?.proposals?.firstOrNull()
    // The last one shown, so that the card leaves with its song still on it.
    var shown by remember { mutableStateOf(waiting) }
    if (waiting != null) shown = waiting
    AnimatedVisibility(
        visible = waiting != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .widthIn(max = 560.dp)
            .fillMaxWidth(),
    ) {
        shown?.let { proposal ->
            ProposalCard(
                proposal = proposal,
                onPlay = { room?.acceptProposal() },
                onNotNow = { room?.dismissProposal() },
                container = floatingCardColor(),
                elevation = 6.dp,
            )
        }
    }
}

/**
 * One proposal: who, their song, how many come after it from their queue (Play it puts their queue in place of the
 * room's), and the two answers. At the top of the app and in the room's sheet.
 */
@Composable
internal fun ProposalCard(
    proposal: TogetherSession.Proposal,
    onPlay: () -> Unit,
    onNotNow: () -> Unit,
    container: Color,
    elevation: Dp,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    // The buttons wait a moment after a proposal comes: a tap already on its way to what was under it lands on nothing.
    var armed by remember(proposal) { mutableStateOf(false) }
    LaunchedEffect(proposal) {
        delay(PROPOSAL_TAP_GUARD_MS)
        armed = true
    }
    val first = proposal.songs.first()
    val more = proposal.songs.size - 1
    Surface(shape = RoundedCornerShape(24.dp), color = container, shadowElevation = elevation, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 14.dp, end = 10.dp, bottom = 6.dp)) {
            // Said by TalkBack as it comes.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            ) {
                Icon(Icons.Rounded.Group, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.together_proposal_title, proposal.by),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 6.dp)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    val cover = first.thumbnail.ifBlank { null }
                    if (cover != null) {
                        AsyncImage(model = cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                    } else {
                        Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(first.title.ifBlank { first.id }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (first.artist.isNotBlank()) {
                        Text(
                            first.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (more > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    pluralStringResource(R.plurals.together_proposal_more, more, more),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = { if (armed) onNotNow() }) { Text(stringResource(R.string.together_proposal_later)) }
                Spacer(Modifier.width(4.dp))
                FilledTonalButton(onClick = {
                    if (armed) {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        onPlay()
                    }
                }) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.together_proposal_play))
                }
            }
        }
    }
}

/** A floating card's fill, a few tones off the page as the app's floating bars are (topBarSurfaceColor), never glass. */
@Composable
private fun floatingCardColor(): Color {
    val scheme = MaterialTheme.colorScheme
    return if (scheme.surface.luminance() < 0.5f) scheme.surfaceContainerHigh else scheme.surfaceContainerHighest
}

/** How long a proposal's buttons ignore taps after it comes: one already on its way meant something else. */
internal const val PROPOSAL_TAP_GUARD_MS = 600L
