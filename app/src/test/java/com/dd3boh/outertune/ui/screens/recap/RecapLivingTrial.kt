/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import androidx.compose.ui.graphics.toArgb
import com.dd3boh.outertune.playback.MusicLevels
import com.dd3boh.outertune.recap.AutoFinds
import com.dd3boh.outertune.recap.DayAmount
import com.dd3boh.outertune.recap.FirstSong
import com.dd3boh.outertune.recap.Recap
import com.dd3boh.outertune.recap.RecapArtistShown
import com.dd3boh.outertune.recap.RecapPalette
import com.dd3boh.outertune.recap.RecapShown
import com.dd3boh.outertune.recap.RecapSongShown
import com.dd3boh.outertune.recap.SongAmount
import com.dd3boh.outertune.recap.Streak
import com.dd3boh.outertune.recap.TopArtist
import com.dd3boh.outertune.recap.TopSong
import com.dd3boh.outertune.ui.player.LivingField
import com.dd3boh.outertune.ui.player.LivingMotion
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Not a test: frames of the Living blur, for pictures of where else the Recap could have it (his
 * question of 22 Nov 2026). The picture is moved by the player's own LivingMotion (the cover's main
 * colours, in ribbons, the settings at their defaults) under a steady beat at 120 a minute, and drawn
 * as LivingDiscs draws it: the size the phone draws it at, an eighth of the room, its soft discs and
 * the glow on both edges, then blurred a little as the phone's RenderEffect does. From the folder
 * RECAP_LIVING names, the covers in in/ (square binary PPM), it writes into out/:
 * one-<name>-<s>.ppm, a square of it for number one's window at 0 to 4 s, and one-<name>.txt, that
 * page's colours (CoverLook); card-<s>.ppm, the whole page behind the year card at 0 to 4 s, its three
 * ranges one cover each (card-1 to card-3); turn-<k>.ppm, the whole page turning from turn-a's colours
 * into turn-b's, every 0.4 s from the turn; burst.txt, the burst's outline (PaperShapes). The sheets
 * are put together from these by experiments/features/recap/colours-1122/living/compose.py.
 */
class RecapLivingTrial {
    private val opaque = 0xFF shl 24

    /** Number one's burst on a phone is about a thousand pixels across: its window is drawn at an eighth of that. */
    private val square = 128

    /** A phone of 1080 by 2340, drawn at an eighth. */
    private val pageWide = 135
    private val pageTall = 292
    private val along = LivingField.along(2340f, 1080f, LivingField.FLOW_ACROSS, LivingField.FLOW_MOST_ALONG)

    @Test
    fun frames() {
        val folder = System.getenv("RECAP_LIVING"); assumeTrue("set RECAP_LIVING to run", !folder.isNullOrBlank())
        val input = File(folder!!, "in")
        val out = File(folder, "out").apply { mkdirs() }
        fun mainsOf(name: String) = read(File(input, "$name.ppm")).let { c -> LivingField.mainColours(IntArray(c.pixels.size) { c.pixels[it] or opaque }, c.width, c.height) }
        File(out, "burst.txt").writeText(PaperShapes.of(Paper.BURST).toList().chunked(2).joinToString("\n") { (x, y) -> "$x $y" } + "\n")

        // Number one: a square of the blur for the window in its burst.
        val ones = input.listFiles { f -> f.name.startsWith("one-") && f.extension == "ppm" }!!.map { it.nameWithoutExtension }.sorted()
        for (name in ones) {
            val mains = mainsOf(name)
            File(out, "$name.txt").writeText(lookOf(mains))
            val motion = motion(LivingField.FLOW_ACROSS, LivingField.FLOW_ACROSS, mains)
            play(motion, -2f, listOf(0f, 1f, 2f, 3f, 4f)) { t -> write(field(motion, square, square), File(out, "$name-${t.roundToInt()}.ppm")) }
        }

        // The year card: the whole page, the bass's range in number one's colour, the voice's in number two's, the top's in number three's.
        val three = IntArray(3) { vividOf(mainsOf("card-${it + 1}")) }
        File(out, "card.txt").writeText("mains ${three.joinToString(" ") { hex(it) }}\n")
        val card = motion(LivingField.FLOW_ACROSS, along, three)
        play(card, -2f, listOf(0f, 1f, 2f, 3f, 4f)) { t -> write(field(card, pageWide, pageTall), File(out, "card-${t.roundToInt()}.ppm")) }

        // A countdown step into the next: one cover's colours turning into the next one's (LivingMotion.turnTo, as between two songs).
        val a = mainsOf("turn-a")
        val b = mainsOf("turn-b")
        File(out, "turn.txt").writeText("from ${a.joinToString(" ") { hex(it) }}\ninto ${b.joinToString(" ") { hex(it) }}\n")
        val turn = motion(LivingField.FLOW_ACROSS, along, a)
        play(turn, -2f, listOf(0f)) { write(field(turn, pageWide, pageTall), File(out, "turn-0.ppm")) }
        turn.turnTo(IntArray(turn.count) { b[MusicLevels.BASS] }, glow = b[MusicLevels.BASS], mains = b)
        val shots = listOf(0.4f, 0.8f, 1.2f, 1.6f, 2.0f)
        play(turn, 0f, shots) { t -> write(field(turn, pageWide, pageTall), File(out, "turn-${shots.indexOf(t) + 1}.ppm")) }
    }

    /** The picture the player makes of these three main colours: in ribbons, at once. */
    private fun motion(columns: Int, rows: Int, mains: IntArray) = LivingMotion(columns, rows).apply {
        separate = true
        turnTo(IntArray(count) { mains[MusicLevels.BASS] }, glow = mains[MusicLevels.BASS], mains = mains)
    }

    /**
     * Steps [motion] by the clock from [from] seconds, as LivingMotion.moveTo does (120 steps to the
     * second) with the beat for levels, and hands it to [shot] at each of [shots].
     */
    private fun play(motion: LivingMotion, from: Float, shots: List<Float>, shot: (Float) -> Unit) {
        val rate = LivingMotion.STEPS_A_SECOND
        val due = shots.associateBy { ((it - from) * rate).roundToInt() }
        val end = due.keys.max()
        for (n in 0..end) {
            due[n]?.let(shot)
            if (n < end) motion.step(LivingMotion.STEP_SECONDS, beat(from + n.toFloat() / rate), true)
        }
    }

    /** A steady beat at 120 a minute: a kick on every beat, a voice that comes and goes, hats on the eighths. */
    private fun beat(t: Float): FloatArray {
        val levels = FloatArray(MusicLevels.VALUES)
        val sinceKick = (t + 10.3f) % 0.5f
        val sinceHat = (t + 10.3f) % 0.25f
        levels[MusicLevels.BASS] = 0.15f + 0.8f * exp(-sinceKick / 0.12f)
        levels[MusicLevels.MID] = 0.45f + 0.2f * sin(2f * PI.toFloat() * t / 4f)
        levels[MusicLevels.HIGH] = 0.1f + 0.35f * exp(-sinceHat / 0.05f)
        levels[MusicLevels.PRESENCE] = 0.8f
        return levels
    }

    private fun chroma(argb: Int) = RecapPalette.oklab((argb shr 16) and 0xff, (argb shr 8) and 0xff, argb and 0xff).let { hypot(it[1], it[2]) }

    /** A cover's colour for one range: its main one where that has colour enough (CoverLook.FLOOR), else its most vivid. */
    private fun vividOf(mains: IntArray): Int = mains[0].takeIf { chroma(it) >= CoverLook.FLOOR } ?: mains.maxBy { chroma(it) }

    /** Number one's colours for a cover of these [mains], next to a countdown and a year card in their own papers (CoverLook.plan). */
    private fun lookOf(mains: IntArray): String {
        val slides = slides()
        val at = slides.indexOfFirst { it.kind == SlideKind.NUMBER_ONE } + 1
        val own = paperOf(SlideKind.NUMBER_ONE)
        val looks = CoverLook.plan(slides) { model -> if (model == "cover:one") mains else null }
        val look = looks[pageOf(slides, at, 0)]
        val p = look?.paper ?: own
        val shapes = look?.shapes ?: listOf(RecapInk.Lemon, RecapInk.Pink)
        return listOf(
            "mains ${mains.joinToString(" ") { hex(it) }}",
            "paper ${hex(p.paper.toArgb())}",
            "ink ${hex(p.ink.toArgb())}",
            "ink2 ${hex(p.ink2.toArgb())} ${p.ink2.alpha}",
            "accent ${hex(p.accent.toArgb())}",
            "onaccent ${hex(p.onAccent.toArgb())}",
            "shapes ${shapes.joinToString(" ") { hex(it.toArgb()) }}",
            "own ${look == null || p.paper == own.paper}",
        ).joinToString("\n") + "\n"
    }

    private fun hex(color: Int) = "#%06x".format(color and 0xffffff)

    private fun top(id: String, plays: Int) = TopSong(id, plays, plays * 180_000L, 100, 0, 3, 120, 2, 2, 120, mapOf(7 to plays))

    /** A year whose songs' covers are "cover:<song>", number one's "cover:one", and the top artist's picture "cover:artist". */
    private fun slides(): List<Slide> {
        val r = Recap(
            year = 2026, firstYear = true, sinceDay = 100, lastDay = 223,
            firstSong = FirstSong("first", 100, 691), playedMs = 1L, plays = 9000, songs = 10, artists = 5,
            msByDay = List(124) { 1L }, biggestDay = DayAmount(120, 1L), mostTime = SongAmount("long", 1L, 37),
            msByHour = List(24) { 1L }, peakHour = 18, peakSong = SongAmount("evening", 1L, 9), lateNights = null,
            streak = Streak(64, 150, 213, "streaky", 15),
            topSongs = listOf(top("one", 90), top("two", 87), top("three", 66), top("four", 63), top("five", 62)),
            alsoPlayed = emptyList(), topArtists = listOf(TopArtist("UCa", 236, 1_000L, 4, "one", 90)), newArtists = null,
            auto = AutoFinds(null, 3, 1, 0, listOf(1, 0, 0), SongAmount("found", 1L, 62), 160), recognition = null, rooms = null,
        )
        val ids = listOf("first", "long", "evening", "streaky", "found") + r.topSongs.map { it.songId }
        val songs = ids.associateWith { RecapSongShown(it, it.uppercase(), "Someone", "cover:$it", 200) }
        return planSlides(RecapShown(r, songs, mapOf("UCa" to RecapArtistShown("UCa", "Someone", "cover:artist"))))
    }

    /** The picture as LivingDiscs.field draws it at [wide] by [tall], then blurred a little, as RenderEffect's 2.5 px does. */
    private fun field(motion: LivingMotion, wide: Int, tall: Int): Picture {
        val red = FloatArray(wide * tall)
        val green = FloatArray(wide * tall)
        val blue = FloatArray(wide * tall)
        val under = motion.under
        red.fill((under shr 16 and 0xff).toFloat()); green.fill((under shr 8 and 0xff).toFloat()); blue.fill((under and 0xff).toFloat())
        // LivingDiscs.soft: full in the middle, nothing at the rim, steepest half way.
        fun disc(x: Float, y: Float, halfWidth: Float, halfHeight: Float, color: Int, strength: Float) {
            if (halfWidth <= 0f || halfHeight <= 0f) return
            for (py in max(0, (y - halfHeight).toInt()) until min(tall, (y + halfHeight).toInt() + 1)) {
                for (px in max(0, (x - halfWidth).toInt()) until min(wide, (x + halfWidth).toInt() + 1)) {
                    val nx = (px + 0.5f - x) / halfWidth
                    val ny = (py + 0.5f - y) / halfHeight
                    val inside = max(0f, 1f - (nx * nx + ny * ny))
                    val a = inside * inside * strength.coerceIn(0f, 1f)
                    val at = py * wide + px
                    red[at] += ((color shr 16 and 0xff) - red[at]) * a
                    green[at] += ((color shr 8 and 0xff) - green[at]) * a
                    blue[at] += ((color and 0xff) - blue[at]) * a
                }
            }
        }
        val cellWidth = wide.toFloat() / motion.columns
        val cellHeight = tall.toFloat() / motion.rows
        for (i in 0 until motion.count) {
            val radius = motion.radius(i)
            disc(motion.x(i) * wide, motion.y(i) * tall, radius * cellWidth, radius * cellHeight, motion.color(i), 1f)
        }
        disc(wide / 2f, tall.toFloat(), wide * 0.8f, tall * motion.glowHeight(), motion.glow, motion.glowStrength())
        disc(wide / 2f, 0f, wide * 0.8f, tall * motion.glowHeight() * 0.7f, motion.glow, motion.glowStrength() * 0.8f)
        for (channel in listOf(red, green, blue)) repeat(2) {
            val was = channel.copyOf()
            for (y in 0 until tall) for (x in 0 until wide) {
                var sum = 0f
                var n = 0
                for (dy in -2..2) for (dx in -2..2) {
                    val px = x + dx
                    val py = y + dy
                    if (px in 0 until wide && py in 0 until tall) {
                        sum += was[py * wide + px]
                        n++
                    }
                }
                channel[y * wide + x] = sum / n
            }
        }
        val picture = Picture(wide, tall)
        for (i in 0 until wide * tall) {
            picture.pixels[i] = (red[i].roundToInt().coerceIn(0, 255) shl 16) or (green[i].roundToInt().coerceIn(0, 255) shl 8) or blue[i].roundToInt().coerceIn(0, 255)
        }
        return picture
    }

    /** A picture as plain rows of 0xRRGGBB. */
    private class Picture(val width: Int, val height: Int) {
        val pixels = IntArray(width * height)
    }

    private fun read(file: File): Picture {
        val bytes = file.readBytes()
        var at = 0
        fun word(): String {
            while (bytes[at].toInt().toChar().isWhitespace()) at++
            val from = at
            while (!bytes[at].toInt().toChar().isWhitespace()) at++
            return String(bytes, from, at - from)
        }
        check(word() == "P6") { "${file.name} is not a binary PPM" }
        val width = word().toInt()
        val height = word().toInt()
        check(word() == "255")
        at++
        val picture = Picture(width, height)
        for (n in 0 until width * height) {
            val i = at + n * 3
            picture.pixels[n] = ((bytes[i].toInt() and 0xff) shl 16) or ((bytes[i + 1].toInt() and 0xff) shl 8) or (bytes[i + 2].toInt() and 0xff)
        }
        return picture
    }

    private fun write(picture: Picture, file: File) {
        val head = "P6\n${picture.width} ${picture.height}\n255\n".toByteArray()
        val bytes = ByteArray(head.size + picture.pixels.size * 3)
        head.copyInto(bytes)
        picture.pixels.forEachIndexed { i, p ->
            bytes[head.size + i * 3] = (p shr 16).toByte()
            bytes[head.size + i * 3 + 1] = (p shr 8).toByte()
            bytes[head.size + i * 3 + 2] = p.toByte()
        }
        file.writeBytes(bytes)
    }
}
