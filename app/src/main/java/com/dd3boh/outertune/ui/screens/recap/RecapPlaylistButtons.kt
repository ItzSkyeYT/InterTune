/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.recap.RecapPlaylist
import com.dd3boh.outertune.recap.RecapShown
import com.dd3boh.outertune.utils.mayPushToYouTube
import com.dd3boh.outertune.viewmodels.RecapViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Where Save as a playlist has got to. */
internal sealed interface SaveStep {
    /** Not tapped yet in this Recap: it says Save as a playlist, and a playlist from an earlier tap is updated by it. */
    data object Idle : SaveStep

    data object Saving : SaveStep

    /** Saved, in the playlist [id]. */
    data class Saved(val id: String) : SaveStep
}

/** Where the second, separate choice, the copy of the playlist on YouTube Music, has got to. */
internal sealed interface CopyStep {
    /** Not offered: nothing saved yet, or nothing in the list that YouTube Music could have. */
    data object Hidden : CopyStep

    data object Offer : CopyStep

    /** [done] of the [of] songs asked of YouTube Music so far. */
    data class Adding(val done: Int, val of: Int) : CopyStep

    /** On YouTube Music: [added] of the playlist's [of] songs, or [of] 0 where it was put there before and the count is not known. */
    data class Added(val added: Int, val of: Int) : CopyStep
}

/**
 * What the year card's two playlist buttons are doing. Save as a playlist is one tap and writes on
 * the phone alone (RecapViewModel.savePlaylist); once it has, and only where the account may be
 * changed ([copyMayBeOffered]), a second line offers the copy on YouTube Music, which is made only
 * by a tap of its own and only once (RecapViewModel.putOnYouTubeMusic). The story waits while
 * either is going on ([busy]).
 */
@Stable
internal class PlaylistFlow(
    private val shown: RecapShown,
    private val name: String,
    private val viewModel: RecapViewModel,
    private val scope: CoroutineScope,
    /** Signed in, with sync set to read and write: the one rule for changing the YouTube Music account (mayPushToYouTube). */
    val copyMayBeOffered: Boolean,
) {
    private val year = shown.recap.year

    var save by mutableStateOf<SaveStep>(SaveStep.Idle)
        private set

    var copy by mutableStateOf<CopyStep>(CopyStep.Hidden)
        private set

    private var saved: RecapPlaylist.Saved? = null

    /**
     * Something is being written or sent: the story holds where it is, as it does while the card is
     * being shared. Derived, so that the story is not drawn again for each song the copy gets through.
     */
    val busy: Boolean by derivedStateOf { save is SaveStep.Saving || copy is CopyStep.Adding }

    fun onSave() {
        if (save is SaveStep.Saving) return
        save = SaveStep.Saving
        scope.launch {
            val result = viewModel.savePlaylist(shown, name)
            if (result == null) {
                save = SaveStep.Idle
                viewModel.say(R.string.recap_playlist_failed)
                return@launch
            }
            saved = result
            save = SaveStep.Saved(result.id)
            // The second choice follows the first tap and is never made for the listener.
            if (copyMayBeOffered && copy == CopyStep.Hidden && result.songs.any { !it.local }) {
                copy = if (viewModel.onYouTubeMusic(year)) CopyStep.Added(0, 0) else CopyStep.Offer
            }
        }
    }

    fun onCopy() {
        val list = saved ?: return
        if (copy != CopyStep.Offer) return
        copy = CopyStep.Adding(0, list.songs.count { !it.local })
        viewModel.putOnYouTubeMusic(
            year, name, list,
            progress = { done, of -> copy = CopyStep.Adding(done, of) },
            done = { result ->
                copy = when (result) {
                    is RecapPlaylist.Pushed.Done -> CopyStep.Added(result.added, result.of)
                    RecapPlaylist.Pushed.Failed -> CopyStep.Offer
                }
            },
        )
    }
}

@Composable
internal fun rememberPlaylistFlow(shown: RecapShown, name: String, viewModel: RecapViewModel): PlaylistFlow {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(shown, name, viewModel) { PlaylistFlow(shown, name, viewModel, scope, copyMayBeOffered = context.mayPushToYouTube()) }
}

/**
 * The two rows under Share this: Save as a playlist, which says Saved with a way to open the
 * playlist once it has, and, only where the account may be changed, the quiet line that offers the
 * copy on YouTube Music. Their room is kept from the start (RecapFit.belowCard), so nothing above
 * them moves when they change.
 */
@Composable
internal fun ColumnScope.PlaylistRows(s: SlideScope, flow: PlaylistFlow, onOpen: (String) -> Unit) {
    val c = s.paper
    val saved = flow.save as? SaveStep.Saved
    Box(
        Modifier
            .align(Alignment.CenterHorizontally)
            .padding(top = RecapFit.SAVE_GAP)
            .enter(s, Entrance.POP, 3.4f)
            .height(RecapFit.PILL),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = saved != null, animationSpec = tween(if (s.still) 0 else 180), label = "playlist") { isSaved ->
            val done = flow.save as? SaveStep.Saved
            if (isSaved && done != null) {
                SavedPill(c, onOpen = { onOpen(done.id) })
            } else {
                RecapPill(
                    Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.recap_playlist_save), c,
                    busy = flow.save is SaveStep.Saving, onClick = flow::onSave,
                )
            }
        }
    }
    if (flow.copyMayBeOffered) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = RecapFit.COPY_GAP)
                .height(RecapFit.COPY),
            contentAlignment = Alignment.Center,
        ) { CopyLine(s, flow.copy, flow::onCopy) }
    }
}

/**
 * The button after the tap: it says Saved, and beside the word, on the same pill, a part of its own
 * that opens the playlist.
 */
@Composable
private fun SavedPill(c: SlidePaper, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(23.dp)
    val openDescribed = stringResource(R.string.recap_playlist_open_described)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(RecapFit.PILL)
            .clip(shape)
            .background(c.ink.copy(alpha = 0.14f))
            .border(2.dp, c.ink, shape),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 16.dp, end = 14.dp),
        ) {
            Icon(Icons.Rounded.Check, null, tint = c.ink, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.recap_playlist_saved), style = RecapType.button, color = c.ink, maxLines = 1, softWrap = false)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxHeight()
                .background(c.accent)
                .recapClick(onOpen)
                .semantics { contentDescription = openDescribed }
                .padding(horizontal = 18.dp),
        ) {
            Text(stringResource(R.string.recap_playlist_open), style = RecapType.button, color = c.onAccent, maxLines = 1, softWrap = false)
        }
    }
}

/** The quiet line under the pill: the offer, how far the copy has got, and that it is there. */
@Composable
private fun CopyLine(s: SlideScope, step: CopyStep, onClick: () -> Unit) {
    val c = s.paper
    val w = s.words
    when (step) {
        CopyStep.Hidden -> Unit
        CopyStep.Offer -> {
            val shape = RoundedCornerShape(RecapFit.COPY / 2)
            Text(
                stringResource(R.string.recap_playlist_copy), style = RecapType.quiet.copy(fontWeight = FontWeight.Bold), color = c.ink, maxLines = 1, softWrap = false,
                modifier = Modifier
                    .height(RecapFit.COPY)
                    .clip(shape)
                    .border(1.5.dp, c.ink.copy(alpha = 0.6f), shape)
                    .recapClick(onClick)
                    .padding(horizontal = 16.dp)
                    .wrapCentre(),
            )
        }
        is CopyStep.Adding -> Text(
            stringResource(R.string.recap_playlist_copying, w.number(step.done), w.number(step.of)),
            style = RecapType.quiet, color = c.ink2, maxLines = 1, softWrap = false,
        )
        is CopyStep.Added -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Rounded.Check, null, tint = c.ink2, modifier = Modifier.size(16.dp))
            val whole = step.of <= 0 || step.added >= step.of
            Text(
                if (whole) stringResource(R.string.recap_playlist_copied) else stringResource(R.string.recap_playlist_copied_some, w.number(step.added), w.number(step.of)),
                style = RecapType.quiet, color = c.ink2, maxLines = 1, softWrap = false,
            )
        }
    }
}
