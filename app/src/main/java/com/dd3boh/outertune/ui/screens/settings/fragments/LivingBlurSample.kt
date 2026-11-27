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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
 * change is not tuning. It is set in the whole-screen tuner (LivingBlurTune), from the button under it.
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

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(168.dp)
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .matchParentSize()
                .padding(horizontal = 28.dp)
        ) {
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
                        containerColor = Color.Black.copy(alpha = 0.45f),
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
            Spacer(Modifier.width(24.dp))
            Column(modifier = Modifier.weight(1f)) {
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
