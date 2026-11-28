/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.LivingColours
import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.PlayerConnection
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.ui.player.LivingBackground
import com.dd3boh.outertune.utils.Toaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Living blur as it will look in the player, small: the real background with a cover standing in it,
 * moving to whatever is playing as it is set. Tuning it by going to the player and back for every
 * change is not tuning. It is set in the whole-screen tuner (LivingBlurTune), from the pencil in its
 * top end corner ([onEdit]; Ti-Die's layout of 28 Nov 2026, in place of a wide button under it).
 *
 * It needs music to move to. With a song playing it uses that. With none it says so, and the refresh
 * button on the cover's corner plays one, or another one when a song plays (Ti-Die's ask of 19 Nov
 * 2026, in place of a button under the words): from the liked songs and what has been played most
 * these three months, so the pool is more than a handful; never the song playing or another from
 * its album; begun a third of the way in, past the intro, where a song has its
 * movement. Not begun by itself: opening a settings screen must not start music.
 */
@Composable
fun LivingBlurSample(
    strength: Float,
    smoothing: Float,
    colours: LivingColours,
    modifier: Modifier = Modifier,
    paused: Boolean = false,
    onEdit: (() -> Unit)? = null,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val context = LocalContext.current
    val database = LocalDatabase.current
    val scope = rememberCoroutineScope()
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val song by playerConnection.mediaMetadata.collectAsState()
    val queueTitle = stringResource(R.string.player_living_sample_queue)
    val coverPx = with(LocalDensity.current) { COVER.roundToPx() }

    // Over the picture as the player has it, so that what is tuned here is what is seen there.
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val scrim = (if (dark) Color.Black else Color.White).copy(alpha = if (dark) 0.3f else 0.45f)

    // As tall as it was, 168 dp, unless its words need more room to stay clear of the pencil (SampleLayout).
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
    ) {
        LivingBackground(
            cover = song?.getThumbnailModel(100, 100),
            tap = playerConnection.service.levelTap,
            playing = isPlaying,
            onScreen = true,
            strength = strength,
            smoothing = smoothing,
            colours = colours,
            modifier = Modifier.matchParentSize(),
            // Under the whole-screen tuner it is out of sight: no frames, and it goes on listening.
            covered = paused,
        )
        Box(
            Modifier
                .matchParentSize()
                .background(scrim)
        )
        SampleLayout(
            cover = {
                Box(Modifier.size(COVER)) {
                    AsyncImage(
                        model = song?.getThumbnailModel(coverPx, coverPx),
                        contentDescription = null,
                        modifier = Modifier
                            .matchParentSize()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                    FilledIconButton(
                        onClick = { scope.launch { playSongToTuneBy(context, database, playerConnection, queueTitle) } },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = BUTTON_ON_PICTURE,
                            contentColor = Color.White,
                        ),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .size(32.dp),
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.player_living_sample_play), modifier = Modifier.size(18.dp))
                    }
                }
            },
            words = {
                Column {
                    if (isPlaying && song != null) {
                        Text(
                            text = song!!.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // The artist under it, as the player and the tuner have them (the line about the sliders went with them).
                        song!!.artists.joinToString { it.name }.takeIf { it.isNotBlank() }?.let { artists ->
                            Text(
                                text = artists,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.player_living_sample_silent),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            },
            edit = { if (onEdit != null) EditButton(onEdit) },
        )
    }
}

/**
 * The cover at the start and the words beside it, both in the middle of the card's height, and the pencil ([edit], where
 * there is one) in its top end corner. The words keep clear of the pencil: never higher than its lower edge and a gap,
 * and as far from the card's bottom, so they stay level with the cover. At the usual text size the card keeps its 168 dp;
 * it grows only where the words need the room (a large text size, a long language).
 */
@Composable
private fun SampleLayout(cover: @Composable () -> Unit, words: @Composable () -> Unit, edit: @Composable () -> Unit) {
    Layout(contents = listOf(cover, words, edit), modifier = Modifier.fillMaxWidth()) { (coverParts, wordsParts, editParts), constraints ->
        val width = constraints.maxWidth
        val side = SAMPLE_SIDE.roundToPx()
        val gap = COVER_WORDS_GAP.roundToPx()
        val inset = EDIT_INSET.roundToPx()
        val coverPart = coverParts.single().measure(Constraints())
        val wordsWidth = (width - 2 * side - coverPart.width - gap).coerceAtLeast(0)
        val wordsPart = wordsParts.single().measure(Constraints(minWidth = wordsWidth, maxWidth = wordsWidth))
        val editPart = editParts.firstOrNull()?.measure(Constraints(maxWidth = (width - 2 * inset).coerceAtLeast(0)))
        val clear = if (editPart == null) 0 else inset + editPart.height + EDIT_GAP.roundToPx()
        // So wide that it reaches over the cover (a long word at a very large text size): the cover keeps clear of it too.
        val overCover = editPart != null && width - inset - editPart.width < side + coverPart.width
        val height = SamplePlan.height(LIVING_SAMPLE_HEIGHT.roundToPx(), coverPart.height, wordsPart.height, clear, overCover)
        layout(width, height) {
            coverPart.placeRelative(side, (height - coverPart.height) / 2)
            wordsPart.placeRelative(side + coverPart.width + gap, (height - wordsPart.height) / 2)
            editPart?.placeRelative(width - inset - editPart.width, inset)
        }
    }
}

/** The sample's arithmetic, apart so that LookAndFeelCleanupTest can hold it to a screen's numbers. */
internal object SamplePlan {
    /**
     * The card's height: [least] (168 dp) as it always was, more where the [words] need it to have [clear] above them, under
     * the pencil, and as much below; never less than the [cover], with [clear] above and below it too where the pencil
     * reaches over it ([overCover]).
     */
    fun height(least: Int, cover: Int, words: Int, clear: Int, overCover: Boolean = false): Int =
        maxOf(least, cover + if (overCover) 2 * clear else 0, words + 2 * clear)
}

/**
 * "Edit" with a pencil, the way into the whole-screen tuner. Made as the refresh button on the cover is: a dark fill over
 * the picture, white on it, 32 dp tall, taller only where its word needs it. No taller in the layout than it is drawn
 * (Material would pad it to 48 dp), so that it sits at its inset in the corner and the words keep clear of what is seen;
 * a touch near it still lands on it. TalkBack reads what it edits, in words the app already has in every language:
 * "Edit, Living blur", then that it is a button. The description is the word's, not the button's: on the button,
 * Compose reads it first and the word after it, "Edit" twice.
 */
@Composable
private fun EditButton(onClick: () -> Unit) {
    val described = "${stringResource(R.string.edit)}, ${stringResource(R.string.player_background_living)}"
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = BUTTON_ON_PICTURE, contentColor = Color.White),
            contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
            modifier = Modifier.heightIn(min = EDIT_HEIGHT),
        ) {
            Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.edit), maxLines = 1, modifier = Modifier.semantics { contentDescription = described })
        }
    }
}

/**
 * Something to tune by: from what has been played most these three months, which is what somebody
 * keeps coming back to, begun a third of the way in, past the intro. The sample and the whole-screen
 * tuner (LivingBlurTune) both offer it.
 */
internal suspend fun playSongToTuneBy(context: Context, database: MusicDatabase, playerConnection: PlayerConnection, queueTitle: String) {
    val candidates = withContext(Dispatchers.IO) {
        (database.likedSongsByCreateDateAsc().first() +
            database.mostPlayedSongs(System.currentTimeMillis() - NINETY_DAYS_MS, limit = 50).first())
            .distinctBy { it.id }
    }
    // Only a song that is playing is left out: a paused one may as well be played again.
    val now = playerConnection.mediaMetadata.value.takeIf { playerConnection.isPlaying.value }
    val chosen = SampleSong.pick(candidates.map { SampleSong.Choice(it.id, it.song.albumId) }, now?.id, now?.album?.id)
    val pick = candidates.firstOrNull { it.id == chosen }
    if (pick == null) {
        withContext(Dispatchers.Main) { Toaster.show(context, R.string.player_living_sample_nothing, Toast.LENGTH_LONG) }
    } else {
        // MENU: asked for by name from a button, like a play from a menu.
        playerConnection.playQueue(
            ListQueue(
                title = queueTitle,
                items = listOf(pick.toMediaMetadata()),
                // a third of the way in, in milliseconds; from the start when the length is not known
                position = pick.song.duration.coerceAtLeast(0) * 1000L / 3,
            ),
            origin = PlayOrigin.MENU,
        )
    }
}

private val COVER = 96.dp
private const val NINETY_DAYS_MS = 90L * 24 * 60 * 60 * 1000

/** The card's height at the usual text size. */
private val LIVING_SAMPLE_HEIGHT = 168.dp

/** From the card's sides to the cover and to the words' end. */
private val SAMPLE_SIDE = 28.dp

/** Between the cover and the words. */
private val COVER_WORDS_GAP = 24.dp

/** The pencil's height at the usual text size, the refresh button's. */
private val EDIT_HEIGHT = 32.dp

/** From the card's top and end to the pencil. */
private val EDIT_INSET = 8.dp

/** Between the pencil's lower edge and the words. */
private val EDIT_GAP = 8.dp

/** The fill of the small buttons over the picture, the refresh and the pencil. */
private val BUTTON_ON_PICTURE = Color.Black.copy(alpha = 0.45f)

/** Which song the sample's refresh button plays next, apart so it can be tested. */
internal object SampleSong {
    data class Choice(val id: String, val albumId: String?)

    /**
     * A song at random that is not the one playing and not from its album, so a press always brings something new;
     * any other song when every one left shares that album; null when there is none at all.
     */
    fun pick(choices: List<Choice>, playingId: String?, playingAlbumId: String?, random: kotlin.random.Random = kotlin.random.Random): String? {
        val others = choices.filter { it.id != playingId }
        val apart = if (playingAlbumId == null) others else others.filter { it.albumId != playingAlbumId }
        return apart.ifEmpty { others }.randomOrNull(random)?.id
    }
}
