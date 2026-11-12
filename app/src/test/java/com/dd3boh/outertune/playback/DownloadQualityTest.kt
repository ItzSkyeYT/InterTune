/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.constants.AudioQuality
import com.dd3boh.outertune.constants.DownloadQuality
import com.dd3boh.outertune.extensions.toEnum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Downloads in their own quality (third in the 0.13 poll, Unreleased.DOWNLOAD_QUALITY): the quality a
 * download asks for is a plain function of the Download quality setting, the Audio quality setting and
 * the flag, so it is tested without a download, a cache or a database.
 */
class DownloadQualityTest {

    @Test
    fun `Same as playback asks for the playback quality, whatever that is, as every download did before`() {
        for (playback in AudioQuality.entries) {
            assertEquals(playback, qualityForDownload(DownloadQuality.SAME, playback, offered = true))
        }
    }

    @Test
    fun `a quality of its own is asked for whatever playback is set to`() {
        for (playback in AudioQuality.entries) {
            assertEquals("Highest over $playback", AudioQuality.MAX, qualityForDownload(DownloadQuality.MAX, playback, offered = true))
            assertEquals("High over $playback", AudioQuality.HIGH, qualityForDownload(DownloadQuality.HIGH, playback, offered = true))
            assertEquals("Low over $playback", AudioQuality.LOW, qualityForDownload(DownloadQuality.LOW, playback, offered = true))
        }
    }

    @Test
    fun `without the flag a download asks for the playback quality, whatever is stored`() {
        for (setting in DownloadQuality.entries) for (playback in AudioQuality.entries) {
            assertEquals("$setting over $playback", playback, qualityForDownload(setting, playback, offered = false))
        }
    }

    @Test
    fun `the choices are Same as playback, Highest, High and Low in that order, and never Auto`() {
        assertEquals(listOf("SAME", "MAX", "HIGH", "LOW"), DownloadQuality.entries.map { it.name })
        // Auto follows the connection a download happens to run on, which may be Wi-Fi hours after it was asked for.
        assertFalse(DownloadQuality.entries.any { it.tier == AudioQuality.AUTO })
        assertNull(DownloadQuality.SAME.tier)
        // The tiers are the playback ones by name, so each reads the label the Audio quality row gives it.
        for (own in DownloadQuality.entries.filter { it != DownloadQuality.SAME }) assertEquals(own.name, own.tier!!.name)
    }

    @Test
    fun `nobody who never touched the setting is changed, and a stored name that is no choice reads as Same as playback`() {
        val default = DownloadQuality.SAME
        assertEquals(default, (null as String?).toEnum(default))
        assertEquals(default, "".toEnum(default))
        assertEquals(default, "AUTO".toEnum(default))
        assertEquals(default, "LOSSLESS".toEnum(default))
        for (choice in DownloadQuality.entries) assertEquals(choice, choice.name.toEnum(default))
    }

    // ---- what the resolver does with it: the rank rule of the cached copy, and the tier the download is recorded at

    private fun staleForDownload(cached: AudioQuality, setting: DownloadQuality, playback: AudioQuality, metered: Boolean = false) =
        isStaleQualityTier(cacheTier(cached, metered), qualityForDownload(setting, playback, offered = true), metered)

    @Test
    fun `a download at Highest does not take the copy cached at Low, however playback is set`() {
        for (playback in AudioQuality.entries) {
            assertTrue("over $playback", staleForDownload(AudioQuality.LOW, DownloadQuality.MAX, playback))
        }
        // And the other tiers of the song cache, which is what the shortcut asks about.
        assertTrue(staleForDownload(AudioQuality.AUTO, DownloadQuality.MAX, AudioQuality.LOW, metered = true))
        assertTrue(staleForDownload(AudioQuality.HIGH, DownloadQuality.MAX, AudioQuality.LOW))
    }

    @Test
    fun `a download at Low takes the better copy that is cached, however playback is set`() {
        for (cached in listOf(AudioQuality.LOW, AudioQuality.HIGH, AudioQuality.MAX, AudioQuality.AUTO)) {
            for (playback in AudioQuality.entries) {
                assertFalse("$cached copy over $playback", staleForDownload(cached, DownloadQuality.LOW, playback))
            }
        }
    }

    @Test
    fun `Same as playback judges a cached copy as a download always did`() {
        for (cached in AudioQuality.entries) for (playback in AudioQuality.entries) for (metered in listOf(true, false)) {
            assertEquals(
                "$cached copy, playback $playback, metered $metered",
                isStaleQualityTier(cacheTier(cached, metered), playback, metered),
                staleForDownload(cached, DownloadQuality.SAME, playback, metered),
            )
        }
    }

    @Test
    fun `the copy is recorded at the tier the download asked for, and Auto only where it was Auto`() {
        assertEquals("MAX", cacheTier(qualityForDownload(DownloadQuality.MAX, AudioQuality.LOW, offered = true), metered = true))
        assertEquals("HIGH", cacheTier(qualityForDownload(DownloadQuality.HIGH, AudioQuality.AUTO, offered = true), metered = false))
        assertEquals("LOW", cacheTier(qualityForDownload(DownloadQuality.LOW, AudioQuality.MAX, offered = true), metered = false))
        // Same as playback under Auto records the network too, as the player does.
        assertEquals("AUTO_METERED", cacheTier(qualityForDownload(DownloadQuality.SAME, AudioQuality.AUTO, offered = true), metered = true))
        assertEquals("AUTO_UNMETERED", cacheTier(qualityForDownload(DownloadQuality.SAME, AudioQuality.AUTO, offered = true), metered = false))
        // A download of its own is never recorded as Auto.
        for (setting in DownloadQuality.entries.filter { it != DownloadQuality.SAME }) for (playback in AudioQuality.entries) for (metered in listOf(true, false)) {
            assertFalse(cacheTier(qualityForDownload(setting, playback, offered = true), metered).startsWith("AUTO"))
        }
    }
}
