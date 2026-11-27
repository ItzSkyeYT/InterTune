/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.action.mutableActionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import androidx.media3.common.C
import androidx.media3.common.Player
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.extensions.metadata
import com.dd3boh.outertune.extensions.toggleShuffleMode
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.MediaButtonStart
import com.dd3boh.outertune.playback.MusicService
import com.dd3boh.outertune.playback.ResumingMediaButtonReceiver
import com.dd3boh.outertune.playback.turnLike
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The buttons on the widget.
 *
 * A press has to work whether or not the app is running, which is the whole difficulty of a media
 * widget. While the service is alive the key goes straight to it. While it is not, the same key
 * goes to the media button receiver, which starts the service and asks it to resume the queue it
 * was on: the identical path a headset button or a car takes, already implemented here as
 * onPlaybackResumption. Nothing new decides what to play. With no queue saved there is nothing to
 * resume, and play opens the app instead.
 */
object WidgetCommands {
    private const val TAG = "WidgetCommands"

    suspend fun mediaKey(context: Context, keyCode: Int) {
        val event = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, event)
        // Only play and pause can start playback, and only playback calls startForeground, so only
        // they may go through startForegroundService. Next and previous went that way too: sent to
        // a service still alive after the paused foreground tail had ended, they left Android
        // waiting for a startForeground that never came, which it answers by crashing the app.
        // media3's own notification buttons follow the same rule.
        val startsPlayback = keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
        val sent = runCatching {
            if (MusicService.isRunning) {
                intent.component = ComponentName(context, MusicService::class.java)
                // With the moment it is handed to Android, which the player's guard counts from
                // (MediaButtonStart.sinceStart), and not from the app's start: a player that a
                // screen brought up moments before is not a cold start.
                if (startsPlayback) ContextCompat.startForegroundService(context, MediaButtonStart.markHanded(intent))
                // Refused in the background once the service has left the foreground; the catch
                // below then opens the app instead.
                else context.startService(intent)
                true
            } else if (startsPlayback && ResumingMediaButtonReceiver.hasSavedQueue(context)) {
                // Cold: the receiver starts the service and media3 waits for the session before it
                // delivers the key, so the queue is back by the time play means anything.
                intent.component = ComponentName(context, ResumingMediaButtonReceiver::class.java)
                context.sendBroadcast(intent)
                true
            } else {
                // The receiver drops every key but play from a cold start, so next and previous did
                // nothing at all with the app closed. The app can do them. So can it play with no
                // queue saved, where the receiver now starts nothing rather than a service that
                // Android would kill the app over.
                false
            }
        }.onFailure { Log.w(TAG, "Could not send $keyCode to the player", it) }.getOrDefault(false)

        // Whatever went wrong, the tap must not be silence: the app can always do it.
        if (!sent) openApp(context)
    }

    /** One tap of the heart at a time, so two quick taps are two turns of the like, never two reads of the same one. */
    private val likeTaps = Mutex()

    /**
     * The heart: the song the widget shows is liked, or its like taken back, as the player's heart
     * does it. With the player's service alive the tap goes to it, naming the song, so a like from
     * here reaches the queue the way one from the app does; the service then fills or empties every
     * heart, this one included, from the like as stored. With no service there is no queue to tell
     * and nobody to redraw the widget: the song's row is turned over by the function the service
     * uses, and the widget is drawn with the result here.
     */
    suspend fun toggleLike(context: Context) = likeTaps.withLock {
        val running = MusicService.alive()
        likeTap(
            shown = WidgetStore.read(context).nowPlaying?.id,
            service = running?.let { service -> { id: String -> service.toggleLike(id) } },
            stored = { id ->
                withContext(Dispatchers.IO) {
                    runCatching {
                        val app = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
                        app.database().turnLike(id, app.downloadUtil())?.liked
                    }.onFailure { Log.w(TAG, "Could not like the song from the widget", it) }.getOrNull()
                }
            },
            drawn = { id, liked -> WidgetStore.setLiked(context, id, liked) },
        )
    }

    /**
     * Shuffle, as the player's own button does it: the player's flag turned over, which the service
     * answers by shuffling the queue or putting it back. With no service there is no queue to
     * shuffle, and the tap opens the app instead, as next and previous do then.
     */
    suspend fun toggleShuffle(context: Context) {
        val service = MusicService.alive()
        val done = service != null && withContext(Dispatchers.Main) {
            runCatching { service.activePlayer.toggleShuffleMode() }
                .onFailure { Log.w(TAG, "Could not shuffle from the widget", it) }.isSuccess
        }
        if (!done) openApp(context)
    }

    /** The song an Up next row is for. */
    val UpNextSong = ActionParameters.Key<String>("widget_up_next_song")

    /**
     * A song of Up next tapped: the queue plays on from it, as a tap on it in the queue does. With no
     * service, or a queue that has moved on and no longer has it next, the song is played the way a
     * Quick pick on the widget plays it ([playSong]), so the tap is never silence.
     */
    suspend fun playUpNext(context: Context, songId: String) {
        val service = MusicService.alive()
        val played = service != null && withContext(Dispatchers.Main) {
            runCatching { service.activePlayer.playOnFrom(songId) }
                .onFailure { Log.w(TAG, "Could not play from Up next", it) }.getOrDefault(false)
        }
        if (played) return
        val song = WidgetStore.read(context).upNext.firstOrNull { it.id == songId }
        if (song == null) {
            openApp(context)
            return
        }
        playSong(context, song)
    }

    /**
     * A song of the widget's lists tapped: it plays where the player is, and the app stays where it was, running or
     * not, as Up next and the buttons have always done. What plays is what the app plays for the same tap
     * ([WidgetSongStart]).
     *
     * The service is started as the play button starts it ([mediaKey]), with startForegroundService, which Android
     * allows from a tap on a widget on Android 12 and later: the tap is the listener's own doing, and the launcher
     * that sends it is in front. Android then waits for the player's notification, and the service looks at the start
     * as at a play press (MediaButtonStart), so the wait is met whatever comes of the song. Where Android refuses the
     * start, and on a phone that follows a listening room, where a song played asks first whether to leave the room
     * (MusicService.songTapNeedsTheApp), the app opens on the song as the tap always did: never nothing.
     */
    fun playSong(context: Context, song: WidgetSong) = songTap(
        needsTheApp = MusicService.alive()?.songTapNeedsTheApp() == true,
        start = { startSong(context, song) },
        openOnSong = { openOnSong(context, song) },
    )

    /** The service started on [song], which it takes from [WidgetSongStart]: false where Android would not start it. */
    private fun startSong(context: Context, song: WidgetSong): Boolean {
        val tap = WidgetSongStart.hand(song)
        val intent = MediaButtonStart.markHanded(WidgetSongStart.intent(context, tap))
        val started = runCatching {
            // Not ContextCompat's, which drops the answer: an app the listener has restricted in the background
            // gets null for a start Android drops without a word, and the tap then has to go to the app.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }.onFailure { Log.w(TAG, "Could not start the player on the song", it) }.getOrNull() != null
        if (!started) WidgetSongStart.take(tap)
        return started
    }

    /** The app opened on [song], which it plays as it always has for a song tapped on the widget. */
    fun openOnSong(context: Context, song: WidgetSong) {
        runCatching { context.startActivity(playIntent(context, song)) }
            .onFailure { Log.w(TAG, "Could not open the app on the song", it) }
    }

    /** What a tap on a song of a list carries: what [playIntent] gave the app, so the tap needs no lookup. */
    private val SongId = ActionParameters.Key<String>("widget_tap_song_id")
    private val SongTitle = ActionParameters.Key<String>("widget_tap_song_title")
    private val SongArtist = ActionParameters.Key<String>("widget_tap_song_artist")
    private val SongThumbnail = ActionParameters.Key<String>("widget_tap_song_thumbnail")
    private val SongDuration = ActionParameters.Key<Int>("widget_tap_song_duration")

    /** [song] as the parameters of its tap ([PlaySongAction]). */
    fun songParameters(song: WidgetSong): ActionParameters = mutableActionParametersOf(
        SongId to song.id,
        SongTitle to song.title,
        SongArtist to song.artist,
        SongDuration to song.durationSec,
    ).apply { song.thumbnailUrl?.let { set(SongThumbnail, it) } }

    /** The song a tap's [parameters] are for ([songParameters]), null when they name none. */
    fun songOf(parameters: ActionParameters): WidgetSong? {
        val id = parameters[SongId]?.takeIf { it.isNotEmpty() } ?: return null
        return WidgetSong(
            id = id,
            title = parameters[SongTitle].orEmpty(),
            artist = parameters[SongArtist].orEmpty(),
            thumbnailUrl = parameters[SongThumbnail],
            durationSec = parameters[SongDuration] ?: 0,
        )
    }

    fun openApp(context: Context) = runCatching {
        context.startActivity(appIntent(context))
    }.onFailure { Log.w(TAG, "Could not open the app", it) }.getOrDefault(Unit)

    fun appIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * The app opened on a song of the widget, which it plays, exactly as tapping the card does: where the song cannot
     * be played without the app ([openOnSong]). A tap on a song used to open the app this way every time.
     */
    fun playIntent(context: Context, song: WidgetSong): Intent =
        appIntent(context)
            .setAction(ACTION_PLAY_SONG)
            .putExtra(EXTRA_SONG_ID, song.id)
            .putExtra(EXTRA_SONG_TITLE, song.title)
            .putExtra(EXTRA_SONG_ARTIST, song.artist)
            .putExtra(EXTRA_SONG_THUMBNAIL, song.thumbnailUrl)
            .putExtra(EXTRA_SONG_DURATION, song.durationSec)
            // A distinct data uri per song, or the launcher hands every row the first song's
            // intent: PendingIntents that differ only by their extras are the same PendingIntent.
            .setData(android.net.Uri.parse("intertune://widget/song/${song.id}"))

    const val ACTION_PLAY_SONG = "com.dd3boh.outertune.widget.PLAY_SONG"
    const val EXTRA_SONG_ID = "widget_song_id"
    const val EXTRA_SONG_TITLE = "widget_song_title"
    const val EXTRA_SONG_ARTIST = "widget_song_artist"
    const val EXTRA_SONG_THUMBNAIL = "widget_song_thumbnail"
    const val EXTRA_SONG_DURATION = "widget_song_duration"
}

class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        // The start of a tap's way to the redrawn widget (WidgetStore.DRAW_TAG).
        Log.d(WidgetStore.DRAW_TAG, "Tapped play or pause")
        WidgetCommands.mediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    }
}

class NextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Log.d(WidgetStore.DRAW_TAG, "Tapped next")
        WidgetCommands.mediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
    }
}

class PreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetCommands.mediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }
}

class LikeAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetCommands.toggleLike(context)
    }
}

class ShuffleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetCommands.toggleShuffle(context)
    }
}

class PlayUpNextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[WidgetCommands.UpNextSong] ?: return
        WidgetCommands.playUpNext(context, id)
    }
}

/** A song of one of the widget's lists, played without the app coming up ([WidgetCommands.playSong]). */
class PlaySongAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Log.d(WidgetStore.DRAW_TAG, "Tapped a song")
        val song = WidgetCommands.songOf(parameters) ?: return WidgetCommands.openApp(context)
        WidgetCommands.playSong(context, song)
    }
}

/**
 * What a tap on a song does, apart from Android so a test can drive it: the app opens on the song where it [needsTheApp],
 * and where the player's service could not be [start]ed on it. Never nothing.
 */
internal fun songTap(needsTheApp: Boolean, start: () -> Boolean, openOnSong: () -> Unit) {
    if (needsTheApp || !start()) openOnSong()
}

/** The next [count] songs of the queue after the one playing, in the order the player will play them. */
fun Player.upNext(count: Int): List<MediaMetadata> {
    val timeline = currentTimeline
    if (timeline.isEmpty || count <= 0) return emptyList()
    val songs = ArrayList<MediaMetadata>(count)
    var index = currentMediaItemIndex
    while (songs.size < count) {
        index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffleModeEnabled)
        if (index == C.INDEX_UNSET) break
        getMediaItemAt(index).metadata?.let { songs += it }
    }
    return songs
}

/** Plays the queue on from the first [songId] after the song playing: true when it was there. */
internal fun Player.playOnFrom(songId: String): Boolean {
    val at = nextIndexOf(songId, currentMediaItemIndex, mediaItemCount, { getMediaItemAt(it).mediaId }) { index ->
        currentTimeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffleModeEnabled)
    } ?: return false
    seekToDefaultPosition(at)
    play()
    return true
}

/**
 * Where [songId] is next in a queue of [count] songs, after [current]: following [next] from one
 * song to the one after it, as the player plays them, and never round past the end. Null when the
 * queue does not have it coming.
 */
internal fun nextIndexOf(songId: String, current: Int, count: Int, idAt: (Int) -> String, next: (Int) -> Int): Int? {
    var index = current
    repeat(count) {
        index = next(index)
        if (index == C.INDEX_UNSET || index !in 0 until count) return null
        if (idAt(index) == songId) return index
    }
    return null
}

/**
 * What a tap on the heart does, apart from Android so a test can drive it. The song the widget
 * shows, [shown], goes to the running [service] when there is one, which draws the heart itself;
 * otherwise its [stored] like is turned over, giving the like as it now is (null when the song is
 * not stored), and the widget is [drawn] with it.
 */
internal suspend fun likeTap(
    shown: String?,
    service: ((String) -> Unit)?,
    stored: suspend (String) -> Boolean?,
    drawn: suspend (String, Boolean) -> Unit,
) {
    val id = shown ?: return
    if (service != null) {
        service(id)
        return
    }
    val liked = stored(id) ?: return
    drawn(id, liked)
}
