/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.sabr

import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import com.dd3boh.outertune.sabr.SabrFile
import com.metrolist.innertubex.sabr.SabrChunk
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The clock SABR is paced by runs whenever a SABR read paced by the player is open, in every
 * build, and sleeps otherwise. Fed only in debug builds, a release would have streamed SABR
 * unpaced, which YouTube held "pending" until the library gave the stream up (16 Oct 2026); fed
 * always, it woke four times a second through every song of an ordinary day.
 */
class SabrClockTest {

    @After
    fun nothingLeftOpen() {
        SabrClock.forgetReads()
        SabrStarts.forget(SONG)
    }

    /** Only carried by the exceptions, never read: made without its constructor, which wants a Uri. */
    private val spec: DataSpec = run {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, DataSpec::class.java) as DataSpec
    }

    private val init = ByteArray(100) { it.toByte() }
    private val segment = ByteArray(400) { (it % 7).toByte() }

    private fun file() = SabrFile(
        stream = { flowOf(SabrChunk(init, 0, true, null, null, null), SabrChunk(segment, 100, false, 0, 0, 10_000)) },
        contentLength = 500,
        durationMs = 10_000,
    )

    private fun start(forDownload: Boolean) =
        SabrStarts.put(SabrStarts.Start(SONG, 251, LMT, file(), expiresAtMs = Long.MAX_VALUE, pacedByPlayer = !forDownload))

    @Test
    fun `the clock runs while a read paced by the player is open, and sleeps after the last one closes`() {
        assertFalse("asleep while nothing is read over SABR", SabrClock.reading.value)
        SabrClock.opened()
        assertTrue(SabrClock.reading.value)
        // The next song's read, opened ahead beside it.
        SabrClock.opened()
        SabrClock.closed()
        assertTrue("one is still open", SabrClock.reading.value)
        SabrClock.closed()
        assertFalse(SabrClock.reading.value)
        // One close too many never leaves the count below none.
        SabrClock.closed()
        SabrClock.opened()
        assertTrue(SabrClock.reading.value)
        SabrClock.closed()
        assertFalse(SabrClock.reading.value)
    }

    @Test
    fun `a read of the player's start keeps the clock running from its open to its close, a download's never`() {
        val played = SabrRead()
        played.open(start(forDownload = false), 0, C.LENGTH_UNSET.toLong(), spec)
        assertTrue("open", SabrClock.reading.value)
        val buffer = ByteArray(1_000)
        assertEquals(100, played.read(buffer, 0, buffer.size))
        assertTrue("reading", SabrClock.reading.value)
        played.close()
        assertFalse("closed", SabrClock.reading.value)
        played.close()
        assertFalse("closed twice is closed once", SabrClock.reading.value)

        // A download goes at its own clock (SabrOver.downloadClock), and the player's sleeps through it.
        val downloaded = SabrRead()
        downloaded.open(start(forDownload = true), 0, C.LENGTH_UNSET.toLong(), spec)
        assertFalse(SabrClock.reading.value)
        downloaded.close()
    }

    @Test
    fun `a read whose start was forgotten never wakes the clock`() {
        val read = SabrRead()
        try {
            read.open(SabrStarts.addressOf(SONG, 251, LMT), 0, C.LENGTH_UNSET.toLong(), spec)
            fail("a start that is not kept cannot be read")
        } catch (expected: java.io.IOException) {
        }
        assertFalse(SabrClock.reading.value)
        read.close()
        SabrClock.opened()
        assertTrue("its close took nothing from another read", SabrClock.reading.value)
    }

    @Test
    fun `the player's start and a download's of the same song are kept apart`() {
        // Kept under one address, the second replaced the first: the player's next open read the
        // download's start, at four times the player's pace and with the player's clock asleep.
        assertNotEquals(SabrStarts.addressOf(SONG, 251, LMT), SabrStarts.addressOf(SONG, 251, LMT, forDownload = true))
        val played = start(forDownload = false)
        val downloaded = start(forDownload = true)
        assertTrue(SabrStarts.forAddress(played)!!.pacedByPlayer)
        assertFalse(SabrStarts.forAddress(downloaded)!!.pacedByPlayer)
    }

    private val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()

    /** The block [head] opens, from [head] to its closing brace: the first `{` in [head] is the block's. */
    private fun block(text: String, head: String, from: Int = text.indexOf(head)): IntRange {
        assertTrue("`$head` is not in MusicService", from >= 0)
        val open = text.indexOf('{', from)
        assertTrue("`$head` opens no block", open in from until from + head.length)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return from..i
            }
        }
        fail("`$head` is never closed")
        error("unreachable")
    }

    /** Every block MusicService runs only in some builds: a debug or preview build, or under a flag. */
    private fun conditionalBlocks(): List<IntRange> =
        Regex("""if \((?:BuildConfig\.DEBUG|Unreleased\.)[^\n]*\{\n""").findAll(service).map { block(service, it.value.trimEnd('\n'), it.range.first) }.toList()

    @Test
    fun `the clock follows the open reads in every build, never a debug build's switch`() {
        val head = "SabrClock.reading.collectLatest(scope) { reading ->"
        val clock = block(service, head)
        val body = service.substring(clock)
        assertTrue(body.contains("SabrClock.update(activePlayer.currentMediaItem?.mediaId, activePlayer.currentPosition)"))
        assertEquals("the clock is set in one place", 1, Regex("""SabrClock\.update\(""").findAll(service).count())
        assertTrue("it sleeps with no read open", body.contains("if (!reading) return@collectLatest"))
        // Paused or buffering too: after a seek the player waits at the new place for bytes, and
        // a clock left at the old place had the stream wait for the player.
        assertTrue(body.contains("delay(if (activePlayer.isPlaying || activePlayer.playbackState == Player.STATE_BUFFERING) 250 else 1000)"))
        for (only in conditionalBlocks()) {
            assertFalse("the clock sits inside `${service.substring(only).lineSequence().first()}`", clock.first in only)
        }
    }

    @Test
    fun `the developer's switches stay in a debug build's block, and the clock is not among them`() {
        val switches = service.substring(block(service, "if (BuildConfig.DEBUG && Unreleased.SABR) {"))
        assertTrue(switches.contains("YTPlayerUtils.sabrOnly = it"))
        assertTrue(switches.contains("YTPlayerUtils.everyDirectRefused = it"))
        assertFalse(switches.contains("SabrClock"))
    }

    private companion object {
        const val SONG = "CLOCKSONG01"
        const val LMT = 1_700_000_000_000_000L
    }
}
