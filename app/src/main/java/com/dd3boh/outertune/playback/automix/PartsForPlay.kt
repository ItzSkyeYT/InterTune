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
import java.io.File
import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * A song's parts lined up with the music the playlist holds of it, for a change saved on parts to
 * play on them: A's held end, B's copy of its opening. The parts were made from the editor's window
 * of the same song ([PartsStore], [EditorCache]); the music played is a decode of its own, which
 * starts where the player's clock says, give or take. So the window is searched near there for the
 * music's own samples, and the parts are used only where the two are the same to the sample:
 * otherwise the four would no longer add up to what plays, and the change stays on bands.
 */
internal object PartsForPlay {

    /**
     * How far either side of the clock's guess the music is looked for: the clock counts what is
     * heard, and Bluetooth headphones hear it a few hundred ms after the stage held it (8 Nov 2026:
     * on a WH-1000XM5 the parts never lined up at 250 ms).
     */
    internal const val SEARCH_MS = 2_000

    /** Samples compared before an offset is believed, and how far one may stray (decoders round alike, not always to the bit). */
    private const val PROBE = 512
    private const val VERIFY_FRAMES = 48_000
    private const val TOLERANCE = 4

    /**
     * [id]'s parts for its [end] window ("tail" or "head") over the [frames] frames of [music],
     * which starts at about [startMs] of the song; null where there are none, or they do not line
     * up with the music to the sample, or do not cover all of it.
     */
    fun lineUp(cacheDir: File, id: String, end: String, music: ShortArray, frames: Int, channels: Int, rate: Int, startMs: Double): List<ShortBuffer>? {
        val (pcmFile, infoFile, _) = EditorCache(cacheDir).files(id, end)
        val info = infoFile.takeIf { it.isFile }?.let { WindowInfo.of(it.readText()) } ?: return null
        if (info.sampleRate != rate || info.channels != channels) return null
        if (pcmFile.length() != info.frames * info.channels * 2) return null
        val parts = PartsStore(File(cacheDir, "mix")).map(id, end, info.frames * channels) ?: return null
        val window = PcmWindow.map(pcmFile, info)
        val guess = ((startMs - info.startMs) * rate / 1000.0).roundToLong()
        val offset = find(window, music, frames, channels, guess, SEARCH_MS * rate / 1000) ?: run {
            android.util.Log.i("AutoMix", "AutoMix: the parts of $end of $id: the music held is not found within ${SEARCH_MS} ms of the clock")
            return null
        }
        android.util.Log.i("AutoMix", "AutoMix: the parts of $end of $id line up, " + "%.0f ms from the clock".format(java.util.Locale.ROOT, (offset - guess) * 1000.0 / rate))
        if (offset < 0 || offset + frames > info.frames) return null
        return parts.map { slice(it, offset, frames, channels) }
    }

    /** The window frame where [music]'s frame 0 is, near [guess], or null where no offset matches. */
    internal fun find(window: PcmWindow, music: ShortArray, frames: Int, channels: Int, guess: Long, radius: Int): Long? {
        if (frames < VERIFY_FRAMES) return null
        // Compared in the middle of the music, past any decoder's start.
        val at = frames / 2 - VERIFY_FRAMES / 2
        val probe = IntArray(PROBE * channels) { music[at * channels + it].toInt() }
        for (d in 0..radius) for (sign in if (d == 0) intArrayOf(1) else intArrayOf(1, -1)) {
            val o = guess + sign * d
            if (o + at < 0 || o + at + VERIFY_FRAMES > window.frames) continue
            if (!matches(window, (o + at).toInt(), probe, channels)) continue
            if (verify(window, music, (o + at).toInt(), at, channels)) return o
        }
        return null
    }

    private fun matches(window: PcmWindow, from: Int, probe: IntArray, channels: Int): Boolean {
        var i = 0
        while (i < probe.size) {
            val f = from + i / channels
            if (abs(window.at(f, i % channels) - probe[i]) > TOLERANCE) return false
            i++
        }
        return true
    }

    private fun verify(window: PcmWindow, music: ShortArray, windowFrom: Int, musicFrom: Int, channels: Int): Boolean {
        var off = 0L
        for (f in 0 until VERIFY_FRAMES) for (c in 0 until channels) {
            if (abs(window.at(windowFrom + f, c) - music[(musicFrom + f) * channels + c]) > TOLERANCE) off++
        }
        // A handful of samples off by more than the tolerance (a decoder's rounding) at most.
        return off <= VERIFY_FRAMES * channels / 10_000
    }

    private fun slice(part: ShortBuffer, offset: Long, frames: Int, channels: Int): ShortBuffer {
        val d = part.duplicate()
        d.position((offset * channels).toInt())
        d.limit(((offset + frames) * channels).toInt())
        return d.slice()
    }
}
