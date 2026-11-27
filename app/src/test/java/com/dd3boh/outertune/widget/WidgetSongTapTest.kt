/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import androidx.glance.action.actionParametersOf
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.MediaButtonStart
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.playback.queues.YouTubeQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A song tapped on the Shelf widget plays without the app coming up, running or not, as Up next and the buttons
 * always did (27 Nov 2026: it opened the app on the song, every time). The app opens on the song as before only where
 * the player cannot be started on it, and what plays is what the app plays for the same tap.
 */
class WidgetSongTapTest {
    private val widget = File("src/main/java/com/dd3boh/outertune/widget/MusicWidget.kt").readText()
    private val actions = File("src/main/java/com/dd3boh/outertune/widget/WidgetActions.kt").readText()
    private val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
    private val main = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()

    private fun body(source: String, start: String, end: String = "\n    }\n"): String {
        assertTrue("not found: $start", start in source)
        return source.substringAfter(start).substringBefore(end)
    }

    private val song = WidgetSong(
        id = "FTQbiNvZqaY",
        title = "A song",
        artist = "An artist",
        thumbnailUrl = "https://i.ytimg.com/vi/FTQbiNvZqaY/hqdefault.jpg",
        durationSec = 214,
    )

    @Test
    fun `every song on the widget plays where it is tapped, and the rest still opens the app`() {
        assertFalse("no song opens the app any more", "actionStartActivity(WidgetCommands.playIntent(" in widget)
        assertTrue("private fun playOnTap(song: WidgetSong): Action = actionRunCallback<PlaySongAction>(WidgetCommands.songParameters(song))" in widget)
        // Big cover's song where the list is alone, the mosaic's covers, and the rows of Crate and Records.
        assertEquals(3, Regex("""\.clickable\(playOnTap\(song\)\)|first\?\.let \{ playOnTap\(it\) }""").findAll(widget).count())
        val bigCover = body(widget, "private fun BigCoverLook(", "private fun MosaicCover(")
        assertTrue("with nothing in the list, the big cover opens the app", "val tap = first?.let { playOnTap(it) } ?: openApp()" in bigCover)
        assertTrue(".clickable(playOnTap(song))" in body(widget, "private fun MosaicCover(", "\n}\n"))
        val rows = body(widget, "private fun RowsLook(", "private fun Panes(")
        assertTrue(rows, ".clickable(playOnTap(song))" in rows)
        assertTrue("what is playing opens the app, as before", "Picture(l, bitmap, pic, l.song?.title, GlanceModifier.clickable(openApp()))" in rows)
        // Everything else that opens the app opens it as before, through the one action that does.
        assertEquals(1, Regex("""actionStartActivity\(""").findAll(widget).count())
        assertTrue("private fun openApp(): Action = actionStartActivity(WidgetCommands.appIntent(LocalContext.current))" in widget)
        // Up next with no player, or with a queue that has moved on: the song plays the same way, the app left alone.
        val upNext = body(actions, "suspend fun playUpNext(context: Context, songId: String) {")
        assertTrue(upNext, "playSong(context, song)" in upNext)
        assertFalse(upNext, "startActivity(playIntent(" in upNext)
    }

    @Test
    fun `a tap carries its song, so the song drawn is the song that plays`() {
        assertEquals(song, WidgetCommands.songOf(WidgetCommands.songParameters(song)))
        val bare = song.copy(thumbnailUrl = null, durationSec = 0)
        assertEquals(bare, WidgetCommands.songOf(WidgetCommands.songParameters(bare)))
        assertNull("a tap that names no song opens the app", WidgetCommands.songOf(actionParametersOf()))
        val action = body(actions, "class PlaySongAction : ActionCallback {", "\n}\n")
        assertTrue(action, "val song = WidgetCommands.songOf(parameters) ?: return WidgetCommands.openApp(context)" in action)
        assertTrue(action, "WidgetCommands.playSong(context, song)" in action)
    }

    @Test
    fun `a tap is never nothing, the app opens on the song where the player cannot play it`() {
        val calls = mutableListOf<String>()
        songTap(needsTheApp = false, start = { calls += "player"; true }, openOnSong = { calls += "app" })
        assertEquals("the player alone", listOf("player"), calls)
        calls.clear()
        songTap(needsTheApp = false, start = { calls += "player"; false }, openOnSong = { calls += "app" })
        assertEquals("Android would not start it: the app, as before", listOf("player", "app"), calls)
        calls.clear()
        songTap(needsTheApp = true, start = { calls += "player"; true }, openOnSong = { calls += "app" })
        assertEquals("a listening room's question is the app's", listOf("app"), calls)
        // Refused with an exception, or dropped without one (null), the start is a refusal.
        val start = body(actions, "private fun startSong(context: Context, song: WidgetSong): Boolean {")
        assertTrue(start, "context.startForegroundService(intent) else context.startService(intent)" in start)
        assertTrue(start, ".getOrNull() != null" in start)
        assertTrue("and leaves no song behind", "if (!started) WidgetSongStart.take(tap)" in start)
        assertTrue("needsTheApp = MusicService.alive()?.songTapNeedsTheApp() == true" in actions)
        assertTrue("fun songTapNeedsTheApp(): Boolean = together.value?.let { it.following && !it.status.value.ownWay } == true" in service)
    }

    @Test
    fun `the service plays only a song this process handed over, and once`() {
        val tap = WidgetSongStart.hand(song)
        assertEquals(song, WidgetSongStart.take(tap))
        assertNull("taken once", WidgetSongStart.take(tap))
        assertNull("a number never given, as another app's start would carry: nothing plays", WidgetSongStart.take(tap + 1_000))
        assertNotEquals("two taps, two numbers", WidgetSongStart.hand(song), WidgetSongStart.hand(song))
    }

    @Test
    fun `what plays is what the app plays for the same tap`() {
        val made = WidgetSongStart.metadata(null, song)
        assertEquals(song.id, made.id)
        assertEquals(song.title, made.title)
        assertEquals(listOf("An artist"), made.artists.map { it.name })
        assertEquals(214, made.duration)
        assertEquals(song.thumbnailUrl, made.thumbnailUrl)
        assertNull(made.genre)
        assertFalse(made.isLocal)
        val file = MediaMetadata(id = "LSabcdefgh", title = "A file", artists = listOf(MediaMetadata.Artist(null, "Me")), duration = 90, genre = null, isLocal = true)
        assertSame("the library's own song where it has it", file, WidgetSongStart.metadata(file, song))

        val radio = WidgetSongStart.play(made)
        assertTrue(radio.isRadio)
        assertTrue(radio.queue is YouTubeQueue)
        assertSame("the song tapped is in the player at once, its radio after it", made, radio.queue.preloadItem)
        val alone = WidgetSongStart.play(file)
        assertFalse("a file on the phone has no radio", alone.isRadio)
        val list = alone.queue as ListQueue
        assertEquals(listOf("LSabcdefgh"), list.items.map { it.id })
        assertEquals("A file", list.title)

        // The app plays a tap it is opened on the same way.
        val fromApp = body(main, "private fun playFromWidget(", "\n}\n")
        assertTrue(fromApp, "val play = WidgetSongStart.play(WidgetSongStart.metadata(known?.toMediaMetadata(), song))" in fromApp)
        assertTrue(fromApp, "connection.playQueue(play.queue, isRadio = play.isRadio, origin = PlayOrigin.WIDGET)" in fromApp)
        // And so does the service, with the app's defaults (PlayerConnection.playQueue replaces).
        val played = body(service, "private fun playWidgetSong(intent: Intent, flags: Int) {")
        assertTrue(played, "val play = WidgetSongStart.play(WidgetSongStart.metadata(known?.toMediaMetadata(), song))" in played)
        assertTrue(played, "playQueue(play.queue, replace = true, isRadio = play.isRadio, origin = PlayOrigin.WIDGET)" in played)
        assertTrue("replace: Boolean = true," in File("src/main/java/com/dd3boh/outertune/playback/PlayerConnection.kt").readText())
    }

    @Test
    fun `the service is started as the play button starts it, and its start is looked at as a play press`() {
        // Handed to Android with the moment it was handed (MediaButtonStart.sinceStart), with startForegroundService.
        val start = body(actions, "private fun startSong(context: Context, song: WidgetSong): Boolean {")
        assertTrue(start, "val intent = MediaButtonStart.markHanded(WidgetSongStart.intent(context, tap))" in start)
        // Where the service receives it: after the media button's own look, before the first start is counted.
        val command = body(service, "override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {")
        val press = command.indexOf("holdForegroundForMediaButton(MediaButtonStart.keyOf(intent), MediaButtonStart.handedAtOf(intent, flags))")
        val tap = command.indexOf("else if (intent.action == WidgetSongStart.ACTION) playWidgetSong(intent, flags)")
        assertTrue(command, press >= 0 && tap > press && tap < command.indexOf("firstStart = false"))
        // The guard first, whatever comes after: Android waits on this start whether a song comes of it or not.
        val played = body(service, "private fun playWidgetSong(intent: Intent, flags: Int) {")
        val look = played.indexOf("holdForegroundForMediaButton(KeyEvent.KEYCODE_MEDIA_PLAY, MediaButtonStart.handedAtOf(intent, flags))")
        assertTrue(played, look >= 0 && look < played.indexOf("val song = WidgetSongStart.take(intent) ?: return"))
        assertTrue("a play press, which the guard looks at again, a song in the player or not",
            MediaButtonStart.startsPlayback(android.view.KeyEvent.KEYCODE_MEDIA_PLAY))
        // media3's notification follows the player of a start that is no media button.
        assertTrue(played, "if (::mediaSession.isInitialized && !isSessionAdded(mediaSession)) addSession(mediaSession)" in played)
        // A listening room this phone follows has the app ask what it always asks.
        val room = played.indexOf("if (songTapNeedsTheApp()) {")
        assertTrue(played, room >= 0 && room < played.indexOf("playQueue(") && "WidgetCommands.openOnSong(this@MusicService, song)" in played)
    }

    @Test
    fun `a start given up before its song is in opens the app on the song`() {
        val giveUp = body(service, "private fun giveUpMediaButtonStart(why: String) {")
        val keep = giveUp.indexOf("player.mediaItemCount > 0) return")
        val app = giveUp.indexOf("WidgetCommands.openOnSong(this, song)")
        assertTrue(giveUp, keep >= 0 && app > keep && app < giveUp.indexOf("stopSelf()"))
        // Only while the start it was handed over for is waited on: forgotten once the guard has seen to it.
        val played = body(service, "private fun playWidgetSong(intent: Intent, flags: Int) {")
        assertTrue(played, "widgetSongOwed = song.takeIf { mediaButtonPending }" in played)
        val guard = service.substringAfter("private val mediaButtonGuard = object : Runnable {").substringBefore("\n    }\n")
        assertTrue(guard, "if (!mediaButtonPending) widgetSongOwed = null" in guard)
        assertTrue("widgetSongOwed = null" in body(service, "private fun meetMediaButtonStartBeforeStop()"))
    }
}
