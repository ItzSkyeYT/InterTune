/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The trail of what was done before a problem (ReportTrail): bounded, nothing personal in it, and the
 * times shown from the problem. His guards of 2 Nov 2026: no song title or id, no search words, no
 * account name or email; times relative to the problem.
 */
class ReportTrailTest {
    private var now = 1_000_000_000L

    private fun trail(capacity: Int = ReportTrail.CAPACITY, keepMs: Long = ReportTrail.KEEP_MS) =
        ReportTrail(capacity, keepMs) { now }.also { it.enabled = true }

    private fun ReportTrail.at(secondsAgo: Long, step: ReportTrail.() -> Unit) {
        val real = now
        now = real - secondsAgo * 1000
        step()
        now = real
    }

    @Test
    fun `a trail that is off keeps nothing`() {
        val off = ReportTrail { now }
        off.screen("settings/player")
        off.songStarted(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, false, false, false)
        assertTrue(off.steps().isEmpty())
        assertNull(off.view(null))
    }

    @Test
    fun `it holds the most recent steps and no more`() {
        val t = trail(capacity = 5)
        repeat(12) {
            now += 10_000
            t.setting("skip silence", if (it % 2 == 0) "on" else "off")
            t.screen(if (it % 2 == 0) "settings/player" else "settings/storage")
        }
        assertEquals(5, t.steps().size)
        assertTrue("the newest is kept", t.steps().last().text.startsWith("opened"))
    }

    @Test
    fun `the same screen again is not a step`() {
        // Turning the phone makes the activity anew and the screen is reported again: nothing was opened.
        val t = trail()
        t.screen("settings/about")
        now += 20_000
        t.screen("settings/about")
        now += 5_000
        t.songStarted(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, false, false, false)
        t.screen("settings/about")
        now += 5_000
        t.screen("home")
        t.screen("settings/about")
        assertEquals(
            listOf("opened settings/about", "song started (the last one ended)", "opened home", "opened settings/about"),
            t.steps().map { it.text },
        )
    }

    @Test
    fun `steps older than the keeping time go`() {
        val t = trail(keepMs = 60_000)
        t.screen("settings/player")
        now += 30_000
        t.paused()
        now += 45_000
        t.resumed()
        // The first is 75 s old by now.
        assertEquals(listOf("paused", "resumed"), t.steps().map { it.text })
    }

    @Test
    fun `the same step told twice at once is one, a seek dragged is one with its count`() {
        val t = trail()
        t.network(ReportTrail.Network.LOST)
        now += 200
        t.network(ReportTrail.Network.LOST)
        assertEquals(1, t.steps().size)
        now += 5_000
        t.seeked(10_000)
        now += 500
        t.seeked(20_000)
        now += 500
        t.seeked(72_000)
        val seeks = t.steps().filter { it.text.startsWith("seeked") }
        assertEquals(1, seeks.size)
        assertEquals("seeked to 1:12 (x3)", seeks.single().toString())
    }

    @Test
    fun `times are shown from the problem, before it with a minus and after it with a plus`() {
        assertEquals("0:00", ReportTrail.relative(0))
        assertEquals("-0:08", ReportTrail.relative(-8_000))
        assertEquals("-2:41", ReportTrail.relative(-161_000))
        assertEquals("+0:05", ReportTrail.relative(5_000))
        // To the second, rounded.
        assertEquals("-0:02", ReportTrail.relative(-1_600))
        assertEquals("0:00", ReportTrail.relative(-400))
    }

    @Test
    fun `positions in a song read as minutes and seconds`() {
        assertEquals("0:00", ReportTrail.clockText(-5))
        assertEquals("1:12", ReportTrail.clockText(72_000))
        assertEquals("1:02:05", ReportTrail.clockText(3_725_000))
    }

    @Test
    fun `around a fault the steps before and just after are listed with the fault in between`() {
        val t = trail()
        val fault = now
        t.at(200) { screen("settings/player") }
        t.at(120) { setting("skip silence", "on") }
        t.at(60) { songStarted(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK, chosen = true, wentBack = false, afterError = false) }
        t.at(8) { playbackError("ERROR_CODE_IO_BAD_HTTP_STATUS", 403) }
        t.at(-5) { network(ReportTrail.Network.LOST) }
        val view = t.view(faultAt = fault, now = fault + 20_000)!!
        assertEquals(ReportTrail.HEADER_FAULT, view.header)
        assertEquals(
            listOf(
                "-3:20 opened settings/player",
                "-2:00 set skip silence to on",
                "-1:00 song started (picked)",
                "-0:08 playback error: IO_BAD_HTTP_STATUS 403",
                "0:00 >>> here",
                "+0:05 network lost",
            ),
            view.lines,
        )
    }

    @Test
    fun `with no fault the steps are those before the report`() {
        val t = trail()
        t.at(30) { paused("another app took the sound") }
        val view = t.view(faultAt = null)!!
        assertEquals(ReportTrail.HEADER_NOW, view.header)
        assertEquals(listOf("-0:30 paused: another app took the sound", "0:00 >>> now"), view.lines)
    }

    @Test
    fun `a fault older than the steps kept falls back to the steps before the report`() {
        val t = trail()
        t.at(30) { resumed() }
        val oldFault = now - 3_600_000
        val view = t.view(faultAt = oldFault)!!
        assertEquals("the header says the steps are not those of the fault", ReportTrail.HEADER_NOW, view.header)
        assertEquals("-0:30 resumed", view.lines.first())
    }

    @Test
    fun `a crash is the moment of the report, and says so`() {
        val t = trail()
        t.at(12) { screen("settings/player/equalizer") }
        val view = t.view(null, crash = true)!!
        assertEquals(ReportTrail.HEADER_CRASH, view.header)
        assertEquals(listOf("-0:12 opened settings/player/equalizer", "0:00 >>> here"), view.lines)
    }

    @Test
    fun `nothing in the window is no block at all`() {
        val t = trail()
        t.at(3_000) { resumed() }
        assertNull(t.view(null))
    }

    @Test
    fun `the report shows a few more than a dozen steps before the fault at most, and says how many it left out`() {
        val t = trail()
        for (i in 1..20) t.at((21 - i) * 5L) { setting("skip silence", if (i % 2 == 0) "on" else "off") }
        val view = t.view(null)!!
        assertEquals("(6 steps left out)", view.lines.first())
        assertEquals(ReportTrail.MAX_BEFORE, view.lines.count { it.contains(" set skip silence") })
        assertEquals(14, ReportTrail.MAX_BEFORE)
    }

    @Test
    fun `what tells most is kept when there are too many, settings and songs and errors before screens and the network and pauses`() {
        val t = trail()
        // Twelve screens and network steps, then what was changed, then more of the same.
        t.at(230) { setting("skip silence", "on") }
        t.at(225) { setting("audio quality", "high") }
        for (i in 0 until 10) t.at(200L - i * 10) { screen(if (i % 2 == 0) "settings/player" else "settings/storage") }
        t.at(90) { network(ReportTrail.Network.LOST) }
        t.at(80) { songStarted(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK, false, false, false) }
        t.at(60) { network(ReportTrail.Network.BACK) }
        t.at(50) { playbackError("ERROR_CODE_IO_BAD_HTTP_STATUS", 403) }
        t.at(30) { screen("settings/about") }
        val lines = t.view(null)!!.lines
        assertTrue("what was changed is kept: $lines", lines.any { it.endsWith("set skip silence to on") } && lines.any { it.endsWith("set audio quality to high") })
        assertTrue(lines.any { it.endsWith("song started (skipped)") } && lines.any { it.endsWith("playback error: IO_BAD_HTTP_STATUS 403") })
        // The screens were the first to go, the oldest first, and the one opened last stays.
        assertTrue(lines.last { it.contains("opened") }.endsWith("opened settings/about"))
        assertTrue("(", lines.first().startsWith("("))
        assertEquals(ReportTrail.MAX_BEFORE, lines.count { it.startsWith("-") })
        // What is kept is in its order.
        val times = lines.filter { it.startsWith("-") }.map { it.substringBefore(' ') }
        assertEquals(times, times.sortedByDescending { it.removePrefix("-") })
    }

    @Test
    fun `songs are told by how they started and never which`() {
        fun how(reason: Int, chosen: Boolean = false, back: Boolean = false, error: Boolean = false) =
            ReportTrail.songHow(reason, chosen, back, error)
        assertEquals("picked", how(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, chosen = true))
        assertEquals("the last one ended", how(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO))
        assertEquals("skipped", how(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK))
        assertEquals("went back", how(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK, back = true))
        assertEquals("skipped past an error", how(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK, error = true))
        assertEquals("repeated", how(Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT))
        assertEquals("new queue", how(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED))
        val t = trail()
        t.songStarted(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, chosen = false, wentBack = false, afterError = false, casting = true)
        assertEquals("song started (the last one ended, casting)", t.steps().single().text)
    }

    @Test
    fun `a screen is its route's pattern, never what fills the pattern in`() {
        assertEquals("settings/player", ReportTrail.screenName("settings/player"))
        assertEquals("album", ReportTrail.screenName("album/{albumId}?song={song}"))
        assertEquals("artist/songs", ReportTrail.screenName("artist/{artistId}/songs"))
        assertEquals("search", ReportTrail.screenName("search/{query}?filter={filter}"))
        assertEquals("folders", ReportTrail.screenName("folders/{path}"))
        assertEquals("online_playlist", ReportTrail.screenName("online_playlist/{playlistId}"))
        // Handed a route with its arguments filled in by mistake, it still names no id, folder or search.
        assertEquals("album", ReportTrail.screenName("album/MPREb_x1Y2z3A4b5C"))
        assertEquals("search", ReportTrail.screenName("search/Daft%20Punk?filter=songs"))
        assertEquals("folders", ReportTrail.screenName("folders/Music/Mel's Phone"))
        assertNull(ReportTrail.screenName(null))
        assertNull(ReportTrail.screenName("{only}/{arguments}"))
    }

    @Test
    fun `every way in is scrubbed and cut, so what is handed over cannot grow into a log`() {
        val t = trail()
        t.setting("proxy", "on, http, 192.168.1.20:8080 on /storage/emulated/0/Music/Mel's Phone/")
        val text = t.steps().single().text
        assertFalse("192.168.1.20" in text)
        assertFalse("Mel's Phone" in text)
        t.setting("x", "y".repeat(500))
        assertTrue(t.steps().last().text.length <= 90)
    }

    @Test
    fun `no step the app can write holds a title, an id, a search, a name or an address`() {
        val t = trail()
        // Everything the feeds call, with the kind of values they hand over.
        t.screen("album/{albumId}?song={song}")
        t.screen("search/{query}")
        t.screen("artist/{artistId}")
        t.setting("audio quality", "high")
        t.signedIn(true)
        t.songStarted(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK, true, false, false)
        t.seeked(61_000)
        t.paused(); t.paused("the headphones or the speaker went away"); t.resumed()
        t.playbackError("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED")
        t.playbackError("ERROR_CODE_IO_BAD_HTTP_STATUS", 403)
        t.gaveUp(3)
        t.downloadFailed()
        t.waited(7_200)
        t.network(ReportTrail.Network.LOST); t.network(ReportTrail.Network.BACK); t.networkChangedTo("mobile data")
        t.app(ReportTrail.App.IN_BACKGROUND)
        val everything = t.steps().joinToString("\n") { it.text }
        for (personal in listOf("{", "}", "@", "http", "://", "MPREb", "dQw4w9WgXcQ", "Daft", "title", "query")) {
            assertFalse("$personal is in $everything", personal in everything)
        }
        assertTrue(everything.lines().all { it.length <= 90 })
    }

    @Test
    fun `a wait for data is a step only when it was long`() {
        val t = trail()
        t.waited(900)
        assertTrue(t.steps().isEmpty())
        t.waited(7_200)
        assertEquals("waited 7 s for data", t.steps().single().text)
        assertEquals("2 min 5 s", ReportTrail.waitText(125_000))
    }

    @Test
    fun `the time of a log line becomes a moment, yesterday's when it is ahead of the clock`() {
        val zone = TimeZone.getTimeZone("Europe/Paris")
        fun at(h: Int, m: Int, s: Int): Long = Calendar.getInstance(zone).apply {
            clear(); set(2026, Calendar.NOVEMBER, 2, h, m, s)
        }.timeInMillis
        val clock = at(15, 30, 0)
        assertEquals(at(15, 29, 40) + 345, ReportTrail.faultTime("15:29:40.345", clock, zone))
        assertEquals(at(15, 29, 40), ReportTrail.faultTime("15:29:40", clock, zone))
        // Written at 23:58, read at 00:10 the next day.
        val after = at(0, 10, 0) + 24 * 3_600_000L
        assertEquals(at(23, 58, 0), ReportTrail.faultTime("23:58:00.000", after, zone))
        assertNull(ReportTrail.faultTime("not a time", clock, zone))
        assertNotNull(ReportTrail.faultTime(" 15:29:40.5 ", clock, zone))
    }
}
