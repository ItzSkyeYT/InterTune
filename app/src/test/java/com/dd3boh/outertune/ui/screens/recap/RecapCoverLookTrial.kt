/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.dd3boh.outertune.recap.AutoFinds
import com.dd3boh.outertune.recap.DayAmount
import com.dd3boh.outertune.recap.FirstSong
import com.dd3boh.outertune.recap.Recap
import com.dd3boh.outertune.recap.RecapArtistShown
import com.dd3boh.outertune.recap.RecapShown
import com.dd3boh.outertune.recap.RecapSongShown
import com.dd3boh.outertune.recap.SongAmount
import com.dd3boh.outertune.recap.Streak
import com.dd3boh.outertune.recap.TopArtist
import com.dd3boh.outertune.recap.TopSong
import com.dd3boh.outertune.ui.player.LivingField
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Not a test: sheets to look at, as LivingPaletteTrial's. For every cover in the folder RECAP_COVERS
 * names (square binary PPM: a unit test here has no java.awt to read a PNG with), a row: the cover,
 * its three main colours (LivingField.mainColours, the main one first), and the two pages it gives
 * where a slide wears its cover's colours (CoverLook), drawn plainly: the paper, the words as bars in
 * their ink (the quieter ones fainter), the accent, the cover stuck on, the paper shapes in their
 * colours. Left, number one (the top artists' page takes the same look); right, a countdown step,
 * whose words are always the ink. Writes _looks-1.ppm and so on, four covers to a sheet, and
 * _story.ppm: the story from the streak to the year's card with the first six covers in their
 * places (the top artist, the countdown from five to two, number one), the design's own papers on
 * top and the covers' colours under them.
 */
class RecapCoverLookTrial {
    private val pageW = 120
    private val pageH = 212
    private val gap = 10
    private val opaque = 0xFF shl 24

    @Test
    fun sheets() {
        val folder = System.getenv("RECAP_COVERS"); assumeTrue("set RECAP_COVERS to run", !folder.isNullOrBlank())
        val files = File(folder!!).listFiles { f -> f.extension == "ppm" && !f.name.startsWith("_") }!!.sortedBy { it.name }
        val covers = files.map { read(it) }
        val mains = covers.map { c -> LivingField.mainColours(IntArray(c.pixels.size) { c.pixels[it] or opaque }, c.width, c.height) }
        files.indices.chunked(4).forEachIndexed { at, rows ->
            val sheet = Picture(gap + 150 + gap + 2 * (pageW + gap), gap + rows.size * (pageH + gap))
            sheet.pixels.fill(0x202020)
            rows.forEachIndexed { line, i ->
                val top = gap + line * (pageH + gap)
                paste(sheet, scaled(covers[i], 150, 150), gap, top)
                for (k in 0 until 3) sheet.rect(gap + k * 50, top + 160, 50, 40, Color(mains[i][k] or opaque))
                val one = CoverLook.of(mains[i], emptyList())
                val countdown = CoverLook.of(mains[i], emptyList(), RecapInk.Ink)
                println("${files[i].name}: main ${mains[i].joinToString(" ") { hex(it) }}")
                println("  number one: ${say(one)}")
                println("  countdown:  ${say(countdown)}")
                paste(sheet, page(SlideKind.NUMBER_ONE, 1, one.paper, one.shapes, covers[i]), gap + 150 + gap, top)
                paste(sheet, page(SlideKind.COUNTDOWN, 2, countdown.paper, countdown.shapes, covers[i]), gap + 150 + gap + pageW + gap, top)
            }
            write(sheet, File(folder, "_looks-${at + 1}.ppm"))
        }
        if (covers.size >= 6) story(covers.take(6), mains.take(6), File(folder, "_story.ppm"))
    }

    /** The covers' places in the story, by the name in their model: "cover:<name>". */
    private val order = listOf("artist", "five", "four", "three", "two", "one")

    private fun placeOf(model: Any?): Int? = (model as? String)?.removePrefix("cover:")?.let { order.indexOf(it) }?.takeIf { it >= 0 }

    private fun story(covers: List<Picture>, mains: List<IntArray>, file: File) {
        val slides = slides()
        val looks = CoverLook.plan(slides) { model -> placeOf(model)?.let { mains[it] } }
        val from = slides.indexOfFirst { it.kind == SlideKind.STREAK }
        val to = slides.indexOfFirst { it.kind == SlideKind.CARD }
        val pages = (from..to).flatMap { s -> (0 until slides[s].steps).map { step -> s to step } }
        val sheet = Picture(gap + pages.size * (pageW + gap), gap + 2 * (pageH + gap))
        sheet.pixels.fill(0x202020)
        pages.forEachIndexed { n, (s, step) ->
            val slide = slides[s]
            val cover = placeOf(slide.cover(step))?.let { covers[it] }
            val rank = if (slide.kind == SlideKind.COUNTDOWN) slide.steps + 1 - step else 1
            val design = Choreography.of(slide.kind, step).first().set[ActorId.A]?.c.orEmpty()
            val own = paperOf(slide.kind, step)
            val look = looks[pageOf(slides, s + 1, step)]
            paste(sheet, page(slide.kind, rank, own, design, cover), gap + n * (pageW + gap), gap)
            paste(sheet, page(slide.kind, rank, look?.paper ?: own, look?.shapes ?: design, cover), gap + n * (pageW + gap), gap + pageH + gap)
            println("${slide.kind} $step: ${look?.let { say(it) } ?: "its own paper"}")
        }
        write(sheet, file)
    }

    private fun top(id: String, plays: Int) = TopSong(id, plays, plays * 180_000L, 100, 0, 3, 120, 2, 2, 120, mapOf(7 to plays))

    /** A year whose songs' covers are "cover:<song>", and the top artist's picture "cover:artist". */
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

    /** A page drawn plainly, as it rests: its paper, its shapes, the cover stuck on, its words as bars. */
    private fun page(kind: SlideKind, rank: Int, c: SlidePaper, shapes: List<Color>, cover: Picture?): Picture {
        val p = Picture(pageW, pageH)
        p.rect(0, 0, pageW, pageH, c.paper)
        val s0 = shapes.getOrElse(0) { RecapInk.Cream }
        val s1 = shapes.getOrElse(1) { s0 }
        when (kind) {
            SlideKind.COUNTDOWN -> {
                p.disc(34f, 66f, 30f, s0)
                p.disc(34f, 66f, 18f, s1)
                p.disc(110f, 12f, 7f, s0)
                p.rect(92, 198, 26, 8, s1)
                p.rect(10, 8, 40, 4, c.ink2)
                // The numeral, always the ink, with its white shadow.
                p.digit(rank, 17, 33, 8, RecapInk.White)
                p.digit(rank, 14, 30, 8, RecapInk.Ink)
                sticker(p, cover, 64, 26, 46)
                p.rect(10, 92, 84, 9, c.ink)
                p.rect(10, 104, 54, 9, c.ink)
                p.rect(10, 118, 44, 5, c.ink2)
                p.rect(10, 128, 18, 10, c.ink)
                p.rect(31, 131, 18, 7, c.ink)
                for (place in 1..5) {
                    val y = 148 + (place - 1) * 11
                    when {
                        place == rank -> {
                            p.rect(6, y - 1, 108, 10, RecapInk.Ink)
                            p.rect(12, y + 2, 60, 4, RecapInk.Cream)
                        }
                        place < rank -> {
                            p.rect(12, y + 1, 8, 8, Color(0xFF9A9A9A))
                            p.rect(24, y + 3, 40, 4, RecapInk.Ink.copy(alpha = 0.2f))
                        }
                        else -> {
                            p.rect(12, y + 1, 8, 8, Color(0xFFDDDAD3))
                            p.rect(24, y + 2, 56, 5, c.ink)
                            p.rect(88, y + 3, 20, 3, c.ink2)
                        }
                    }
                }
                p.rect(10, 204, 40, 3, c.ink2)
            }
            SlideKind.NUMBER_ONE, SlideKind.ARTISTS -> {
                p.disc(60f, 62f, 50f, s0)
                p.disc(60f, 62f, 39f, s1)
                p.disc(60f, 62f, 28f, s0)
                p.disc(14f, 112f, 7f, s1)
                p.rect(96, 196, 22, 8, s0)
                p.rect(10, 8, 46, 4, c.ink2)
                sticker(p, cover, 32, 34, 56)
                p.rect(10, 112, 88, 10, c.ink)
                p.rect(10, 125, 52, 10, c.ink)
                p.rect(10, 140, 40, 5, c.ink2)
                // The plays in the accent, and Share this on the accent.
                p.rect(10, 152, 40, 22, c.accent)
                p.rect(54, 164, 20, 8, c.ink)
                p.rect(80, 156, 32, 14, c.accent)
                p.rect(86, 161, 20, 4, c.onAccent)
                p.rect(10, 184, 98, 5, c.ink)
                p.rect(10, 193, 70, 5, c.ink)
            }
            else -> {
                p.disc(96f, 40f, 22f, s0)
                p.disc(20f, 180f, 14f, s1)
                p.rect(10, 8, 46, 4, c.ink2)
                p.rect(10, 70, 70, 26, c.ink)
                p.rect(10, 104, 90, 8, c.ink)
                p.rect(10, 116, 60, 8, c.ink)
                p.rect(10, 132, 80, 5, c.ink2)
            }
        }
        return p
    }

    private fun sticker(p: Picture, cover: Picture?, x: Int, y: Int, side: Int) {
        p.rect(x - 3, y - 3, side + 6, side + 6, RecapInk.White)
        if (cover == null) p.rect(x, y, side, side, Color(0xFFDDDAD3)) else paste(p, scaled(cover, side, side), x, y)
    }

    private fun say(look: CoverLook.Look): String {
        val p = look.paper
        val words = if (p.ink == RecapInk.Ink) "ink" else "cream"
        val ratio = "%.1f".format(java.util.Locale.ROOT, CoverLook.contrast(p.ink, p.paper))
        return "paper ${hex(p.paper.toArgb())}, words in $words at $ratio, accent ${hex(p.accent.toArgb())}, shapes ${look.shapes.joinToString(" ") { hex(it.toArgb()) }}"
    }

    private fun hex(color: Int) = "#%06x".format(color and 0xffffff)

    /** A picture as plain rows of 0xRRGGBB. */
    private class Picture(val width: Int, val height: Int) {
        val pixels = IntArray(width * height)

        fun get(x: Int, y: Int) = pixels[y * width + x]

        fun put(x: Int, y: Int, rgb: Int) {
            if (x in 0 until width && y in 0 until height) pixels[y * width + x] = rgb and 0xffffff
        }

        /** [c] laid over what is there, as see-through as its alpha. */
        fun dab(x: Int, y: Int, c: Color) {
            if (x !in 0 until width || y !in 0 until height) return
            val under = pixels[y * width + x]
            val a = c.alpha
            fun mix(shift: Int, v: Float) = (((under shr shift) and 0xff) * (1f - a) + v * 255f * a).toInt().coerceIn(0, 255)
            pixels[y * width + x] = (mix(16, c.red) shl 16) or (mix(8, c.green) shl 8) or mix(0, c.blue)
        }

        fun rect(x: Int, y: Int, w: Int, h: Int, c: Color) {
            for (j in y until y + h) for (i in x until x + w) dab(i, j, c)
        }

        fun disc(cx: Float, cy: Float, r: Float, c: Color) {
            for (j in (cy - r).toInt()..(cy + r).toInt()) for (i in (cx - r).toInt()..(cx + r).toInt()) {
                val dx = i + 0.5f - cx
                val dy = j + 0.5f - cy
                if (dx * dx + dy * dy <= r * r) dab(i, j, c)
            }
        }

        /** The digit [n] in a five by seven grid, each cell [scale] pixels. */
        fun digit(n: Int, x: Int, y: Int, scale: Int, c: Color) {
            val rows = DIGITS[n] ?: return
            rows.forEachIndexed { j, row -> row.forEachIndexed { i, ch -> if (ch == '#') rect(x + i * scale, y + j * scale, scale, scale, c) } }
        }

        companion object {
            val DIGITS = mapOf(
                1 to listOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
                2 to listOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
                3 to listOf("####.", "....#", "....#", ".###.", "....#", "....#", "####."),
                4 to listOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
                5 to listOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
            )
        }
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

    private fun scaled(image: Picture, width: Int, height: Int): Picture {
        val out = Picture(width, height)
        for (y in 0 until height) for (x in 0 until width) out.put(x, y, image.get(x * image.width / width, y * image.height / height))
        return out
    }

    private fun paste(onto: Picture, image: Picture, left: Int, top: Int) {
        for (y in 0 until image.height) for (x in 0 until image.width) onto.put(left + x, top + y, image.get(x, y))
    }
}
