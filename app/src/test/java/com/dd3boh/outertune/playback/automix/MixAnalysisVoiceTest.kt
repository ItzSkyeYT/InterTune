/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import com.dd3boh.outertune.mix.RegionAnalysis
import com.dd3boh.outertune.mix.RegionKind
import com.dd3boh.outertune.mix.auto.SongEnd
import com.dd3boh.outertune.mix.auto.SongEndJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Auto keeps two voices apart from its guess of where each song sings, made with the rest of the
 * analysis. The pills plan from the ends the analysis cache keeps, so the cache keeps the guess with
 * them; an end kept before it was (version 1) is read again from the music, never planned without it.
 */
class MixAnalysisVoiceTest {

    @get:Rule
    val dir = TemporaryFolder()

    private val rate = 44_100

    /** A tone with a slow swell and a little noise, 20 s: enough for the analysis to make an end of. */
    private fun song(seconds: Double): ShortArray {
        val random = Random(3)
        val frames = (seconds * rate).toInt()
        return ShortArray(frames * 2) { i ->
            val t = (i / 2).toDouble() / rate
            val v = 0.3 * sin(2 * PI * 220.0 * t) * (0.8 + 0.2 * sin(2 * PI * 0.25 * t)) + 0.01 * (random.nextDouble() - 0.5)
            (v * 32767).toInt().toShort()
        }
    }

    private fun end(): SongEnd {
        val region = RegionAnalysis.analyse(song(20.0), rate, 2, RegionKind.HEAD)
        return SongEnd.of(region, 0.0, 180_000.0)
    }

    @Test
    fun `an end the analysis makes says where its song sings, step by step`() {
        val e = end()
        val voice = assertNotNullAnd(e.voice)
        assertEquals(e.levelDb.size, voice.size)
        assertTrue(voice.all { it in 0f..1f })
    }

    @Test
    fun `the cache keeps where a song sings`() {
        val cache = MixAnalysisCache(dir.newFolder("analysis"))
        val e = end()
        assertTrue(cache.put("song", e))
        val back = assertNotNullAnd(cache.get("song", RegionKind.HEAD))
        val kept = assertNotNullAnd(back.voice)
        val voice = e.voice!!
        assertEquals(voice.size, kept.size)
        for (i in voice.indices) assertEquals(voice[i].toDouble(), kept[i].toDouble(), 0.006)
    }

    @Test
    fun `an end kept before the voice was is read again, not planned without it`() {
        val folder = dir.newFolder("old")
        val cache = MixAnalysisCache(folder)
        val e = end()
        assertTrue(cache.put("song", e))
        val f = File(folder, sha1("song") + "-head.json")
        assertTrue(f.isFile)
        f.writeText(f.readText().replace("\"v\":${SongEndJson.VERSION}", "\"v\":1"))
        assertNull(cache.get("song", RegionKind.HEAD))
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun <T> assertNotNullAnd(v: T?): T {
        assertNotNull(v)
        return v!!
    }
}
