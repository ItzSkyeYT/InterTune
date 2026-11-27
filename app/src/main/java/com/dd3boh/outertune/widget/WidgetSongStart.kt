/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import android.content.Context
import android.content.Intent
import com.dd3boh.outertune.WidgetPlaybackKind
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.MusicService
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.playback.queues.Queue
import com.dd3boh.outertune.playback.queues.YouTubeQueue
import com.dd3boh.outertune.widgetPlaybackKind
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A song tapped on the widget, played by the player's service without the app coming up (27 Nov 2026: a tap on a
 * song opened the app every time, running or not, while Up next and the buttons never did).
 *
 * The tap's callback hands the song over here and starts the service with the number it was given, and the service
 * takes the song back by that number ([take]). The song never rides in the intent: the service is exported for
 * media3's controllers, and a start from anywhere else names no song this process handed over, so it plays nothing.
 *
 * What plays is what the app plays for the same tap ([metadata], [play]), which MainActivity's playFromWidget asks
 * here too: the library's own song where it has it, alone when it is a file on the phone, with its radio otherwise.
 */
internal object WidgetSongStart {
    /** The service's action for a song handed over here (MusicService.onStartCommand). */
    const val ACTION = "com.dd3boh.outertune.widget.PLAY_SONG_IN_PLAYER"

    private const val EXTRA_TAP = "widget_song_tap"

    private val taps = AtomicLong()
    private val handed = ConcurrentHashMap<Long, WidgetSong>()

    /** Keeps [song] for the service, and gives the number it is taken back by. */
    fun hand(song: WidgetSong): Long = taps.incrementAndGet().also { handed[it] = song }

    /** The song handed over as [tap], once: null for a number never given, or given and taken already. */
    fun take(tap: Long): WidgetSong? = handed.remove(tap)

    /** The service's start for the song handed over as [tap]. */
    fun intent(context: Context, tap: Long): Intent =
        Intent(context, MusicService::class.java).setAction(ACTION).putExtra(EXTRA_TAP, tap)

    /** The song [intent] was started for, taken: null for a start that names none handed over here. */
    fun take(intent: Intent): WidgetSong? =
        runCatching { intent.getLongExtra(EXTRA_TAP, 0L) }.getOrDefault(0L).takeIf { it > 0L }?.let(::take)

    /** The song as the player is to have it: the library's own, [known], or else made from what the widget drew. */
    fun metadata(known: MediaMetadata?, song: WidgetSong): MediaMetadata = known ?: MediaMetadata(
        id = song.id,
        title = song.title,
        artists = listOf(MediaMetadata.Artist(null, song.artist)),
        duration = song.durationSec,
        thumbnailUrl = song.thumbnailUrl,
        genre = null,
    )

    /** What a song tapped on the widget starts: the queue, and whether it is a radio. */
    class Play(val queue: Queue, val isRadio: Boolean)

    /** The song alone when it is a file on the phone (a local id is no YouTube video), and its radio otherwise. */
    fun play(metadata: MediaMetadata): Play = when (widgetPlaybackKind(metadata)) {
        WidgetPlaybackKind.LOCAL -> Play(ListQueue(title = metadata.title, items = listOf(metadata)), isRadio = false)
        WidgetPlaybackKind.RADIO -> Play(YouTubeQueue.radio(metadata), isRadio = true)
    }
}
