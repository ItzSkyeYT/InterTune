/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import com.dd3boh.outertune.ui.screens.mix.EditorCache
import com.dd3boh.outertune.ui.screens.mix.PartsStore
import com.dd3boh.outertune.ui.screens.mix.PcmWindow
import com.dd3boh.outertune.ui.screens.mix.WindowInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.random.Random

/**
 * A change saved on parts plays on them only where the music held is found in the editor's window
 * to the sample, near where the player's clock puts it; anything else stays on bands.
 */
class PartsForPlayTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val rate = 48_000
    private val channels = 2
    private val windowFrames = 20 * rate

    private val song = Random(7).let { r -> ShortArray(windowFrames * channels) { (r.nextInt(-12_000, 12_000)).toShort() } }
    private val window = PcmWindow(WindowInfo(rate, channels, 100_000.0, windowFrames.toLong(), 300_000.0), ShortBuffer.wrap(song))

    private fun musicFrom(frame: Int, frames: Int) = song.copyOfRange(frame * channels, (frame + frames) * channels)

    @Test
    fun `the music is found to the frame, the clock's guess off either way`() {
        val frames = 6 * rate
        for ((truth, guess) in listOf(123_457 to 123_457L, 123_457 to 123_000L, 300_001 to 305_000L)) {
            assertEquals(truth.toLong(), PartsForPlay.find(window, musicFrom(truth, frames), frames, channels, guess, rate / 4))
        }
    }

    @Test
    fun `saved changes and Auto's both play on parts where the parts line up, on bands otherwise`() {
        val controller = java.io.File("src/main/java/com/dd3boh/outertune/playback/automix/AutomixController.kt").readText()
        assertTrue("a change saved on bands plays on parts too", "val spec = if (parts != null) Presets.onParts(saved) else Presets.onBands(saved)" in controller)
        assertTrue("LaneMixer(p).render(ring, copy, attenuationA, attenuationB, parts?.first, parts?.second)" in controller)
        assertTrue("val played = onParts ?: render" in controller)
        assertTrue("stage.offer(held, played.heldPlan(), played.outgoing, played.incoming, replaces = true, ring = ring)" in controller)
    }

    @Test
    fun `a song started with AutoMix on counts as joining until the sink takes it`() {
        val controller = java.io.File("src/main/java/com/dd3boh/outertune/playback/automix/AutomixController.kt").readText()
        val transition = controller.substringAfter("override fun onMediaItemTransition(").substringBefore("\n    }\n")
        assertTrue(transition, "joining = stage.wanted && !stage.activeForStream" in transition)
        val still = controller.substringAfter("private fun stillJoining(): Boolean =").substringBefore("\n\n")
        assertTrue("a song still loading is still joining", "player.playbackState == Player.STATE_BUFFERING" in still)
    }

    @Test
    fun `music heard on Bluetooth, a few hundred ms after it was held, is still found`() {
        // 8 Nov 2026: on a WH-1000XM5 the parts never lined up while the search reached 250 ms.
        val frames = 6 * rate
        val radius = PartsForPlay.SEARCH_MS * rate / 1000
        for (lateMs in listOf(400, -400, 900)) {
            val truth = 300_000
            val guess = truth - lateMs * rate / 1000L
            assertEquals("$lateMs ms", truth.toLong(), PartsForPlay.find(window, musicFrom(truth, frames), frames, channels, guess, radius))
        }
    }

    @Test
    fun `a decoder's rounding is let through, other music is not`() {
        val frames = 6 * rate
        val rounded = musicFrom(200_000, frames).also { m -> for (i in m.indices step 7) m[i] = (m[i] + 1).toShort() }
        assertEquals(200_000L, PartsForPlay.find(window, rounded, frames, channels, 199_900, rate / 4))
        val other = Random(8).let { r -> ShortArray(frames * channels) { (r.nextInt(-12_000, 12_000)).toShort() } }
        assertNull(PartsForPlay.find(window, other, frames, channels, 200_000, rate / 4))
        // Too far from the guess: not searched for.
        assertNull(PartsForPlay.find(window, musicFrom(200_000, frames), frames, channels, 200_000L + rate, rate / 4))
    }

    @Test
    fun `parts a change plays on are kept as fresh as when the editor opens them`() {
        // The editor's cache keeps the newest windows split into parts where room runs short
        // (EditorCache.prune): a pair played often but not opened stays among them.
        val cacheDir = tmp.root
        val cache = EditorCache(cacheDir)
        val (pcm, info, bands) = cache.files("song", "tail")
        cache.dir.mkdirs()
        val bytes = ByteBuffer.allocate(song.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { it.asShortBuffer().put(song) }.array()
        pcm.writeBytes(bytes)
        info.writeText(window.info.toText())
        bands.writeBytes(ByteArray(16))
        val parts = PartsStore(File(cacheDir, "mix")).files("song", "tail").onEach { it.parentFile!!.mkdirs(); it.writeBytes(bytes) }
        val files = listOf(pcm, info, bands) + parts
        val hourAgo = System.currentTimeMillis() - 60 * 60_000
        files.forEach { it.setLastModified(hourAgo) }
        val frames = 6 * rate
        val at = 300_000
        val lined = PartsForPlay.lineUp(cacheDir, "song", "tail", musicFrom(at, frames), frames, channels, rate, window.info.startMs + at * 1000.0 / rate)
        assertNotNull(lined)
        assertEquals(4, lined!!.size)
        assertTrue(files.all { it.lastModified() > hourAgo + 30 * 60_000 })
    }
}
