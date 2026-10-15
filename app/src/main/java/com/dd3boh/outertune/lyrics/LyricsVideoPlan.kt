/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import kotlin.math.max
import kotlin.math.min

/**
 * Lines of synced lyrics as a short video: the song plays from just before the first line to the
 * end of the last, the lines scroll by as they are sung, and sound and picture fade out quickly
 * after. This says which stretch of the song that is, which lines are in it, and where everything
 * stands at each moment; LyricsVideo makes the video. No Android in here, so it is tested.
 */
object LyricsVideoPlan {
    /** The most of a song a video takes: what a story or a status holds. */
    const val LONGEST_MS = 60_000L

    /** It starts this long before the first line, so that the voice does not come in on the first frame. */
    const val LEAD_MS = 600L

    /**
     * The last line is held for as long as it is sung and no longer than this from its start. A
     * line without an end of its own ends where the next one starts, which can be a solo away.
     */
    const val LAST_LINE_MS = 6_000L

    /** The sound comes up over this at the start, which keeps the first sample from clicking. */
    const val FADE_IN_MS = 120L

    /** Sound and picture fade out together over this, after the last line. */
    const val FADE_OUT_MS = 1_000L

    /** A line moves up into the place of the line being sung over this, from when it starts. */
    const val SCROLL_MS = 450L

    /** A line lights up when it starts, and goes back down when the next one does, over this. */
    const val LIGHT_MS = 250L

    /** How bright a line is that is not being sung, against 1 for the one that is. */
    const val DIM = 0.42f

    const val FRAMES_A_SECOND = 30

    /**
     * The video's size: two thirds of the share card's 1080 x 1920 each way, which is drawn as
     * it is and scaled down. Chats shrink what they are sent anyway; this one is small to begin
     * with, and quicker to make.
     */
    const val OUT_WIDTH = 720
    const val OUT_HEIGHT = 1280

    /**
     * Bits a second for the picture and for the sound, kept low so a video is quick to send: a
     * minute, the longest there is, comes to about 9 MB, under the 10 MB Discord takes without
     * paying. Encoders held to a rate still overshoot it a little (1.31 Mbit/s for 1.2 asked).
     */
    const val PICTURE_BITRATE = 1_000_000
    const val SOUND_BITRATE = 128_000

    /** About how big a video [durationMs] long comes out, in bytes. Encoders aim at the bitrate; most land under it. */
    fun bytes(durationMs: Long): Long = (PICTURE_BITRATE + SOUND_BITRATE).toLong() * durationMs / 8_000

    /** A line as the lyrics view has it: its text, when it starts and ends in the song, and whether it is the line before it in another language. */
    data class Timed(val text: String, val startMs: Long, val endMs: Long, val translated: Boolean = false)

    /** A line of the video: when it starts, from the start of the video, and the lyrics' translation of it where they have one. */
    data class Line(val text: String, val startMs: Long, val translation: String? = null)

    /** The stretch of the song from [startMs] to [endMs], and the lines sung in it. */
    data class Clip(val startMs: Long, val endMs: Long, val lines: List<Line>) {
        val durationMs get() = endMs - startMs
    }

    /**
     * The video of the lines picked at [picked] of [lines]: every line from the first picked to
     * the last, since the song sings the ones between them too, and the stretch of the song they
     * take, which ends no later than the song does ([songMs], where it is known). Null when there
     * is nothing sung in it, or when it would be longer than [LONGEST_MS].
     */
    fun clip(lines: List<Timed>, picked: Collection<Int>, songMs: Long? = null): Clip? {
        var first = picked.filter { it in lines.indices }.minOrNull() ?: return null
        var last = picked.filter { it in lines.indices }.max()
        // A translation goes with the line it translates, on either side.
        while (first > 0 && lines[first].translated) first--
        while (last + 1 < lines.size && lines[last + 1].translated) last++

        val sung = ArrayList<Timed>()
        val translations = ArrayList<String?>()
        for (i in first..last) {
            val line = lines[i]
            val text = line.text.trim()
            if (text.isEmpty()) continue
            if (line.translated && sung.isNotEmpty()) {
                val at = translations.lastIndex
                translations[at] = translations[at]?.let { "$it\n$text" } ?: text
            } else {
                sung += line
                translations += null
            }
        }
        val opening = sung.firstOrNull() ?: return null
        val closing = sung.last()

        val start = max(0L, opening.startMs - LEAD_MS)
        // What the lyrics say is the end of the last line, unless they have no end for it that
        // follows its start; held no longer than LAST_LINE_MS either way.
        val stops = if (closing.endMs > closing.startMs) closing.endMs else Long.MAX_VALUE
        var end = min(stops, closing.startMs + LAST_LINE_MS) + FADE_OUT_MS
        if (songMs != null && songMs > 0) end = min(end, songMs)
        if (end <= start || end - start > LONGEST_MS) return null

        return Clip(start, end, sung.mapIndexed { i, line -> Line(line.text.trim(), line.startMs - start, translations[i]) })
    }

    /** Whether the lines picked at [picked] of [lines] make a video ([clip]). */
    fun fits(lines: List<Timed>, picked: Collection<Int>, songMs: Long? = null): Boolean = clip(lines, picked, songMs) != null

    /** The line being sung at [ms] into [clip]: the last to have started, or -1 before the first has. */
    fun sung(clip: Clip, ms: Long): Int = clip.lines.indexOfLast { it.startMs <= ms }

    /**
     * How far the column of lines has moved up at [ms] into [clip], where the top of each line in
     * it is at [tops]. The line being sung stands where the first stands before it starts: each
     * line is brought up there over [SCROLL_MS] from when it starts. A line that starts before the
     * last one has got there takes over from wherever the column is, so it never jumps.
     */
    fun scroll(clip: Clip, tops: FloatArray, ms: Long): Float {
        if (tops.isEmpty()) return 0f
        val now = min(sung(clip, ms), tops.lastIndex)
        if (now <= 0) return tops[0]
        // Where the column was as each line started, on its way to the line before.
        var from = tops[0]
        for (k in 1 until now) {
            from += (tops[k] - from) * eased((clip.lines[k + 1].startMs - clip.lines[k].startMs).toFloat() / SCROLL_MS)
        }
        return from + (tops[now] - from) * eased((ms - clip.lines[now].startMs).toFloat() / SCROLL_MS)
    }

    /**
     * How bright line [i] of [clip] is at [ms]: [DIM], up to 1 while it is sung, and back down once
     * the next line starts. The first is lit from the first frame, which is what a chat or a feed
     * shows of the video before it plays.
     */
    fun light(clip: Clip, i: Int, ms: Long): Float {
        val line = clip.lines.getOrNull(i) ?: return DIM
        val up = if (i == 0) 1f else ((ms - line.startMs).toFloat() / LIGHT_MS).coerceIn(0f, 1f)
        val next = clip.lines.getOrNull(i + 1)?.startMs
        val down = if (next == null) 0f else ((ms - next).toFloat() / LIGHT_MS).coerceIn(0f, 1f)
        return DIM + (1f - DIM) * up * (1f - down)
    }

    /**
     * How much of the sound is heard at [ms] into a video [durationMs] long: coming up over
     * [FADE_IN_MS] at the start, going down over [FADE_OUT_MS] at the end.
     */
    fun sound(ms: Double, durationMs: Long): Float =
        eased((ms / FADE_IN_MS).toFloat()) * fadingOut(ms, durationMs)

    /**
     * How much of the picture shows at [ms]: all of it until the fade at the end. It does not come
     * up from black at the start, since the first frame is what a chat or a feed shows of a video
     * before it plays.
     */
    fun picture(ms: Long, durationMs: Long): Float = fadingOut(ms.toDouble(), durationMs)

    /** How many frames a video [durationMs] long has. */
    fun frames(durationMs: Long): Int = ((durationMs * FRAMES_A_SECOND + 999) / 1000).toInt()

    private fun fadingOut(ms: Double, durationMs: Long): Float = eased(((durationMs - ms) / FADE_OUT_MS).toFloat())

    /** 0 to 1, slow at both ends: what every fade and every move here follows. */
    private fun eased(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3 - 2 * x)
    }
}
