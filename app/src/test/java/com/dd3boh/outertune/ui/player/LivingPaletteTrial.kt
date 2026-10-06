/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import com.dd3boh.outertune.playback.MusicLevels
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Not a test: sheets to look at. For every cover in the folder LIVING_COVERS names (square
 * pictures of any size, as binary PPM, since a unit test here has no java.awt to read a PNG
 * with), a row: the cover, the three main colours picked from it (the bass's on
 * top, then the voice's, then the top's), and the picture they make on a phone held upright, drawn
 * the way LivingBackground draws it and at half strength over a dark screen as with Liquid glass
 * on. Left to right: at rest, on a kick, with a voice, with cymbals, with all three at once, and
 * last the same kick in the picture coloured from the cover itself, to compare.
 *
 * Writes _sheet-1.ppm and so on into that folder, three covers to a sheet.
 */
class LivingPaletteTrial {

    private val columns = LivingField.ACROSS
    private val rows = 7
    private val wide = 132
    private val tall = 286

    // The cover in the upright player, in parts of the screen: 32 dp gutters, under the status bar.
    private val coverLeft = 0.0835f
    private val coverTop = 0.097f
    private val coverSide = 0.833f

    @Test
    fun sheets() {
        val folder = System.getenv("LIVING_COVERS"); assumeTrue("set LIVING_COVERS to run", !folder.isNullOrBlank())
        val covers = File(folder!!).listFiles { f -> f.extension == "ppm" && !f.name.startsWith("_") }!!.sortedBy { it.name }
        covers.chunked(3).forEachIndexed { at, three ->
            val gap = 10
            val row = tall + gap
            val sheet = Picture(tall + 70 + 6 * (wide + gap) + gap, three.size * row + gap)
            sheet.pixels.fill(0x202020)
            three.forEachIndexed { line, file ->
                val cover = read(file)
                val pixels = IntArray(cover.pixels.size) { cover.pixels[it] or (0xff shl 24) }
                val mains = LivingField.mainColours(pixels, cover.width, cover.height)
                println("${file.name}: bass ${hex(mains[0])} voice ${hex(mains[1])} top ${hex(mains[2])}")
                val top = gap + line * row
                paste(sheet, scaled(cover, tall, tall), gap, top)
                for (band in 0 until 3) for (y in 0 until tall / 3 - 4) for (x in 0 until 54) sheet.setRGB(gap + tall + 8 + x, top + band * (tall / 3) + y, mains[band] and 0xffffff)
                val states = listOf(
                    floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 0.1f, 0.1f), floatArrayOf(0.1f, 1f, 0.1f),
                    floatArrayOf(0.1f, 0.1f, 1f), floatArrayOf(1f, 1f, 1f),
                )
                states.forEachIndexed { i, levels ->
                    val motion = motion(separate = true, LivingField.mainPatches(mains, columns, rows), mains[MusicLevels.BASS], levels)
                    paste(sheet, picture(motion, cover), gap + tall + 70 + i * (wide + gap), top)
                }
                val asCover = motion(separate = false, LivingField.patches(pixels, cover.width, cover.height, columns, rows), null, floatArrayOf(1f, 0.1f, 0.1f))
                paste(sheet, picture(asCover, cover), gap + tall + 70 + 5 * (wide + gap), top)
            }
            write(sheet, File(folder, "_sheet-${at + 1}.ppm"))
        }
    }

    private fun hex(color: Int) = "#%06x".format(color and 0xffffff)

    /** The picture after half a second of quiet and then a tenth of a second of [levels]. */
    private fun motion(separate: Boolean, patches: IntArray, glow: Int?, levels: FloatArray) = LivingMotion(columns, rows).apply {
        this.separate = separate
        coverThere = true
        coverAt(coverLeft, coverTop, coverLeft + coverSide, coverTop + coverSide * wide / tall)
        turnTo(patches, glow)
        val quiet = floatArrayOf(0.05f, 0.05f, 0.05f)
        repeat(30) { step(1f / 60, quiet, true) }
        if (levels.any { it > 0f }) repeat(6) { step(1f / 60, levels, true) }
    }

    private fun picture(motion: LivingMotion, cover: Picture): Picture {
        val red = FloatArray(wide * tall)
        val green = FloatArray(wide * tall)
        val blue = FloatArray(wide * tall)
        fun fill(color: Int) {
            red.fill((color shr 16 and 0xff).toFloat()); green.fill((color shr 8 and 0xff).toFloat()); blue.fill((color and 0xff).toFloat())
        }
        // LivingBackground's Look.disc: full in the middle, nothing at the rim, steepest half way
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
        fill(motion.under)
        val cellWidth = wide.toFloat() / motion.columns
        val cellHeight = tall.toFloat() / motion.rows
        for (i in motion.order) {
            val radius = motion.radius(i)
            disc(motion.x(i) * wide, motion.y(i) * tall, radius * cellWidth, radius * cellHeight, motion.color(i), 1f)
        }
        disc(wide / 2f, tall.toFloat(), wide * 0.8f, tall * motion.glowHeight(), motion.glow, motion.glowStrength())
        disc(wide / 2f, 0f, wide * 0.8f, tall * motion.glowHeight() * 0.7f, motion.glow, motion.glowStrength() * 0.8f)
        val left = coverLeft * wide
        val top = coverTop * tall
        val side = coverSide * wide
        for (i in motion.aura.indices) {
            val point = motion.aura[i]
            val radius = motion.auraRadius(i) * side
            disc(left + point.x * side, top + point.y * side, radius, radius, motion.auraColor(i), motion.auraStrength(i))
        }
        val out = Picture(wide, tall)
        // Liquid glass on: the picture at half strength over the dark theme's surface
        for (i in 0 until wide * tall) {
            val r = (red[i] * 0.5f + 0x12 * 0.5f).toInt().coerceIn(0, 255)
            val g = (green[i] * 0.5f + 0x12 * 0.5f).toInt().coerceIn(0, 255)
            val b = (blue[i] * 0.5f + 0x14 * 0.5f).toInt().coerceIn(0, 255)
            out.setRGB(i % wide, i / wide, (r shl 16) or (g shl 8) or b)
        }
        paste(out, scaled(cover, side.toInt(), side.toInt()), left.toInt(), top.toInt())
        return out
    }

    /** A picture as plain rows of 0xRRGGBB. */
    private class Picture(val width: Int, val height: Int, val pixels: IntArray = IntArray(width * height)) {
        fun getRGB(x: Int, y: Int) = pixels[y * width + x]
        fun setRGB(x: Int, y: Int, color: Int) {
            pixels[y * width + x] = color and 0xffffff
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
        return Picture(width, height, IntArray(width * height) {
            val i = at + it * 3
            ((bytes[i].toInt() and 0xff) shl 16) or ((bytes[i + 1].toInt() and 0xff) shl 8) or (bytes[i + 2].toInt() and 0xff)
        })
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
        for (y in 0 until height) for (x in 0 until width) out.setRGB(x, y, image.getRGB(x * image.width / width, y * image.height / height))
        return out
    }

    private fun paste(onto: Picture, image: Picture, left: Int, top: Int) {
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (left + x in 0 until onto.width && top + y in 0 until onto.height) onto.setRGB(left + x, top + y, image.getRGB(x, y))
        }
    }
}
