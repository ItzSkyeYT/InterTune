/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.models.MediaMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The heart on the widget (issue #45). A tap likes the song the widget shows, or takes the like
 * back, exactly as the player's heart does; the heart on the widget, in the player and in the
 * notification then all show the like as stored, wherever it was pressed.
 *
 * [likeTap] is what a tap does, [likesToDraw] is how the widget follows the stored like, and the
 * snapshot functions say which song a heart belongs to. The last test reads the source to see that
 * the real tap and the service are wired to them.
 */
class WidgetLikeTest {

    private class Tap {
        val sent = mutableListOf<String>()
        val turned = mutableListOf<String>()
        val drawn = mutableListOf<Pair<String, Boolean>>()
    }

    private fun tap(shown: String?, serviceAlive: Boolean, storedAfter: Boolean? = true): Tap = Tap().also { t ->
        val service: (String) -> Unit = { id -> t.sent += id }
        runBlocking {
            likeTap(
                shown = shown,
                service = if (serviceAlive) service else null,
                stored = { id -> t.turned += id; storedAfter },
                drawn = { id, liked -> t.drawn += id to liked },
            )
        }
    }

    @Test
    fun `with the player's service alive, the tap goes through it, for the song the widget shows`() {
        val t = tap("abc", serviceAlive = true)
        // The song on the widget, named: after a cold start the player holds none, and the
        // service's own song would be nothing at all.
        assertEquals(listOf("abc"), t.sent)
        assertTrue("the row is the service's to turn over", t.turned.isEmpty())
        // The service follows the stored like and redraws the widget itself.
        assertTrue(t.drawn.isEmpty())
    }

    @Test
    fun `with no service, the song's stored like is turned over and the widget drawn with it`() {
        val t = tap("abc", serviceAlive = false, storedAfter = true)
        assertTrue(t.sent.isEmpty())
        assertEquals(listOf("abc"), t.turned)
        assertEquals(listOf("abc" to true), t.drawn)
        // Taken back: drawn empty again.
        assertEquals(listOf("abc" to false), tap("abc", serviceAlive = false, storedAfter = false).drawn)
    }

    @Test
    fun `a widget with nothing on it, or a song that is not stored, is left alone`() {
        for (alive in listOf(true, false)) {
            val t = tap(null, serviceAlive = alive)
            assertTrue(t.sent.isEmpty() && t.turned.isEmpty() && t.drawn.isEmpty())
        }
        val missing = tap("gone", serviceAlive = false, storedAfter = null)
        assertEquals(listOf("gone"), missing.turned)
        assertTrue(missing.drawn.isEmpty())
    }

    /** Lets every coroutine of the single-threaded test loop run until it waits again. */
    private suspend fun settle() = repeat(20) { yield() }

    @Test
    fun `the heart follows the stored like of the song the widget shows, and of no other`() = runBlocking {
        val shown = MutableStateFlow<String?>(null)
        val stored = mapOf("a" to MutableStateFlow<Boolean?>(false), "b" to MutableStateFlow<Boolean?>(true))
        val seen = mutableListOf<Pair<String, Boolean>>()
        val following = launch { likesToDraw(shown) { stored.getValue(it) }.collect { seen += it } }
        settle()
        assertTrue("nothing on the widget, nothing to draw", seen.isEmpty())

        shown.value = "a"
        settle()
        assertEquals(listOf("a" to false), seen)
        // Liked in the player, the notification, a menu or the widget: the widget follows.
        stored.getValue("a").value = true
        settle()
        assertEquals(listOf("a" to false, "a" to true), seen)
        // The same like again is nothing new to draw.
        stored.getValue("a").value = true
        settle()
        assertEquals(2, seen.size)

        // The next song: its own like, and the last song's no longer counts.
        shown.value = "b"
        settle()
        stored.getValue("a").value = false
        settle()
        assertEquals(listOf("a" to false, "a" to true, "b" to true), seen)
        // A song that is not stored has no like to follow.
        stored.getValue("b").value = null
        settle()
        assertEquals(3, seen.size)
        following.cancel()
    }

    private val song = MediaMetadata(id = "a", title = "Rosanna", artists = emptyList(), duration = 302, genre = null)

    private fun row(id: String, liked: Boolean) = SongEntity(id = id, title = "Rosanna", localPath = null, liked = liked)

    @Test
    fun `a new song's heart starts from its own row once the player has it, from the song itself until then`() {
        assertTrue(likeGuess(song, row("a", liked = true)))
        assertFalse(likeGuess(song.copy(liked = true), row("a", liked = false)))
        // The row the player still holds is the last song's for a moment: it lends this one nothing.
        assertFalse(likeGuess(song, row("z", liked = true)))
        assertTrue(likeGuess(song.copy(liked = true), row("z", liked = false)))
        assertTrue(likeGuess(song.copy(liked = true), null))
        assertFalse(likeGuess(null, row("a", liked = true)))
    }

    @Test
    fun `a heart belongs to its song, which keeps it when written again while the next song brings its own`() {
        val old = WidgetSnapshot(nowPlaying = WidgetSong("a", "Rosanna", "Toto"), isLiked = true)
        // A play or a pause rewrites the same song: the like the follower drew stays.
        assertTrue(likedFor(old, "a", guess = false))
        assertFalse(likedFor(old, "b", guess = false))
        assertTrue(likedFor(old.copy(isLiked = false), "b", guess = true))

        assertEquals(old.copy(isLiked = false), old.withLike("a", liked = false))
        // Already drawn so: nothing to write. Moved on to another song: not this heart to change.
        assertNull(old.withLike("a", liked = true))
        assertNull(old.withLike("b", liked = false))
        assertNull(WidgetSnapshot().withLike("a", liked = true))
    }

    /**
     * Glance keeps no more than ten children in a Row or a Column and drops the rest without a
     * word. A line of song has four before its buttons, so the buttons stand in a Row of their own:
     * drawn straight into the line, the heart made eleven and next disappeared, the other buttons
     * sliding along into its place (seen on the emulator, 25 Oct 2026). At most eight are in the
     * buttons' own Row: the heart and its gap, previous, play and next with theirs, and a blank.
     */
    @Test
    fun `the buttons stand in a Row of their own, so Glance never drops one past its tenth child`() {
        val widget = File("src/main/java/com/dd3boh/outertune/widget/MusicWidget.kt").readText()
        val controls = widget.substringAfter("private fun Controls(").substringBefore("/** The heart:")
        val body = controls.substring(controls.indexOf("{\n") + 2).trim()
        val row = body.indexOf("Row(")
        assertTrue("the buttons' Row", row >= 0)
        assertTrue("only a val before it", body.substring(0, row).lines().all { it.isBlank() || it.trim().startsWith("val ") })
        val open = body.indexOf('{', row)
        var depth = 0
        var close = -1
        for (i in open until body.length) {
            when (body[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) { close = i; break }
            }
        }
        assertEquals("nothing drawn after the Row but the end of the function", "}", body.substring(close + 1).trim())
    }

    @Test
    fun `the tap reaches the service's own like, and with no service the same function the service uses`() {
        val actions = File("src/main/java/com/dd3boh/outertune/widget/WidgetActions.kt").readText()
        val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
        assertTrue("the tap asks for the running service", "MusicService.alive()" in actions)
        assertTrue("the tap goes through the service's toggleLike", Regex("""\.toggleLike\(\s*id\s*\)""").containsMatchIn(actions))
        assertTrue("with no service the tap turns the like over as the service does", "turnLike(" in actions)
        assertTrue("the service turns a like over with the same function", "turnLike(" in service)
        assertTrue("the service keeps the widget's heart on the stored like", "WidgetStore.followLikes(" in service)
        assertTrue("the heart's button runs the like action", "actionRunCallback<LikeAction>()" in File("src/main/java/com/dd3boh/outertune/widget/MusicWidget.kt").readText())
        // A widget that appears, and every widget after the update that brings the heart, draws
        // the like as stored: an empty heart over a liked song would take the like away when tapped.
        val hydrate = File("src/main/java/com/dd3boh/outertune/widget/WidgetStore.kt").readText()
            .substringAfter("suspend fun hydrate(").substringBefore("private suspend fun fromLibrary(")
        assertTrue("hydrate reads the stored like", "readOrNull { songRow(now.id) }" in hydrate)
    }

    /** The switch's words are agreed (25 Oct 2026): open to translators, with their French beside them. */
    @Test
    fun `the switch's words are open to translators, with their French beside them`() {
        fun line(folder: String) = File("src/main/res/$folder/strings-ot.xml").readLines().single { "name=\"widget_show_like\"" in it }
        assertFalse("still held from translators", "translatable=\"false\"" in line("values"))
        assertTrue("no French", Regex(""">[^<]+</string>""").containsMatchIn(line("values-fr")))
        assertFalse("a long dash", '\u2014' in line("values") || '\u2014' in line("values-fr"))
    }
}
