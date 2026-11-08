/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.parts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.log10

/**
 * Real editor windows from the S25 Ultra (8 Nov 2026) split here with the phone's model file, held
 * to split_windows.py's parts of them on the PC. The windows and the PC's parts stay where they
 * are, in experiments/features/automix/backups/s25u-debug-20261108-0020 (never copied: they are
 * his songs); without them or the model this is skipped. One window by default, all four with
 * PARTS_WINDOWS=all in the environment.
 *
 * Each part is the PC's but for one 16-bit step at a few samples in a thousand, and the four add
 * up to the window as closely as the PC's do.
 */
class WindowSplitterPcTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `real windows split here as the PC split them`() {
        val backup = backupDir
        assumeTrue("no windows: experiments/features/automix/backups/$BACKUP", backup != null)
        val modelFile = File(backup!!.parentFile.parentFile, "parts/models/scnet-int8.ort")
        assumeTrue("no model at $modelFile", modelFile.isFile)
        val all = File(backup, "windows").listFiles { f -> f.name.endsWith(".pcm") }!!.sortedBy { it.name }
        val windows = if (System.getenv("PARTS_WINDOWS") == "all") all else all.filter { it.name.startsWith(DEFAULT) }
        assertTrue(windows.isNotEmpty())
        OrtSpecModel(OrtSpecModel.environment(), modelFile.path, OrtSpecModel.Options(threads = THREADS)).use { model ->
            for (pcm in windows) check(pcm, File(backup, "parts"), model)
        }
    }

    private fun check(pcm: File, pcParts: File, model: OrtSpecModel) {
        val info = File(pcm.path.removeSuffix(".pcm") + ".info").readLines().filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        val rate = info.getValue("rate").toInt()
        val channels = info.getValue("channels").toInt()
        val frames = info.getValue("frames").toLong()
        val window = map(pcm)
        assertEquals(frames * channels, window.limit().toLong())
        val stem = pcm.name.removeSuffix(".pcm")
        val out = folder.newFolder()
        val targets = WindowSplitter.PART_NAMES.map { File(out, "$stem.$it.pcm") }
        val seen = ArrayList<Double>()
        val splitter = WindowSplitter(model)
        val started = System.nanoTime()
        assertTrue(splitter.split(window, rate, channels, targets, { seen += it }))
        val seconds = (System.nanoTime() - started) / 1e9
        report(
            "%s: %.1f s at %d Hz, %d segments, split in %.1f s (model %.1f s)",
            stem.take(8) + stem.substring(stem.lastIndexOf('-')), frames.toDouble() / rate, rate, splitter.segments, seconds, splitter.modelNanos / 1e9,
        )
        assertEquals(splitter.segments + 1, seen.size)
        assertEquals(1.0, seen.last(), 0.0)

        val mine = targets.map { read(it) }
        val pc = WindowSplitter.PART_NAMES.map { read(File(pcParts, "$stem.$it.pcm")) }
        val samples = window.limit()
        for (p in mine) assertEquals(samples, p.size)
        for ((k, name) in WindowSplitter.PART_NAMES.withIndex()) {
            val (db, differ, worst) = compare(pc[k], mine[k])
            report("  %s: %.1f dB from the PC's, %d samples a step off (%.3f%%), worst %d steps", name, db, differ, 100.0 * differ / samples, worst)
            assertTrue("$name $db dB", db > 85)
            assertTrue("$name $differ samples off", differ < samples / 100)
            assertTrue("$name $worst steps", worst <= 2)
        }
        // How the four add up to the window, as parts.json puts it: the error's level in dB of full scale.
        val sumHere = sumErrorDb(window, mine)
        val sumPc = sumErrorDb(window, pc)
        report("  the four against the window: %.1f dB here, %.1f dB on the PC", sumHere, sumPc)
        assertEquals(sumPc, sumHere, 1.0)
    }

    private fun sumErrorDb(window: ShortBuffer, parts: List<ShortArray>): Double {
        var e = 0.0
        for (i in 0 until window.limit()) {
            val d = (window.get(i) - parts.sumOf { it[i].toInt() }) / 32768.0
            e += d * d
        }
        return 20 * log10(kotlin.math.sqrt(e / window.limit()) + 1e-12)
    }

    /** dB of [want] over the difference, the samples that differ, and the largest difference, in 16-bit steps. */
    private fun compare(want: ShortArray, got: ShortArray): Triple<Double, Int, Int> {
        var signal = 0.0
        var error = 0.0
        var differ = 0
        var worst = 0
        for (i in want.indices) {
            val d = got[i] - want[i]
            signal += want[i].toDouble() * want[i]
            error += d.toDouble() * d
            if (d != 0) differ++
            worst = maxOf(worst, abs(d))
        }
        return Triple(if (error == 0.0) Double.POSITIVE_INFINITY else 10 * log10(signal / error), differ, worst)
    }

    private fun map(file: File): ShortBuffer = RandomAccessFile(file, "r").use { raf ->
        raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    }

    private fun read(file: File): ShortArray {
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(b.remaining()).also { b.get(it) }
    }

    private fun report(format: String, vararg args: Any) = println("window splitter against the PC: " + String.format(format, *args))

    private companion object {
        const val BACKUP = "s25u-debug-20261108-0020"

        /** A 60 s opening at 48 kHz: eight segments, about half a minute here. */
        const val DEFAULT = "e09adf69"

        const val THREADS = 4

        val backupDir: File? by lazy {
            var at: File? = File("").absoluteFile
            while (at != null) {
                val candidate = File(at, "experiments/features/automix/backups/$BACKUP")
                if (File(candidate, "windows").isDirectory && File(candidate, "parts").isDirectory) return@lazy candidate
                at = at.parentFile
            }
            null
        }
    }
}
