/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Offline, the app's own radio picks songs downloaded into a folder (review of 0.13, F2). A song in a
 * download folder is in neither cache, and the radio asked only the caches: with no network it never
 * picked one, and a radio started from a folder of 200 songs was the song alone. It asks the one rule
 * the adaptive pass asks now, which knows downloads by the downloads' own map.
 */
class OwnRadioFolderDownloadsTest {
    private val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()

    @Test
    fun `one rule says what plays offline, and it knows the downloads`() {
        val rule = service.substringAfter("private fun playsOffline(s: MediaMetadata): Boolean =").substringBefore("\n\n")
        assertTrue(rule.contains("s.isLocal"))
        assertTrue(rule.contains("downloadUtil.isDownloaded(s.id)"))
        assertTrue(rule.contains("downloadCache.holdsWhole(s.id)"))
        assertTrue(rule.contains("playerCache.holdsWhole(s.id)"))
    }

    @Test
    fun `the app's own radio asks that rule for what plays and what is chosen`() {
        val radio = service.substringAfter("private suspend fun ownRadio(seed: MediaMetadata)").substringBefore("\n    }\n")
        assertTrue(radio.contains("val onPhone = { s: MediaMetadata -> playsOffline(s) }"))
        assertTrue(radio.contains("plays = { s -> onPhone(s) ||"))
        assertTrue(radio.contains("chosen = { s -> s.liked || s.inLibrary != null || onPhone(s) }"))
        assertFalse("no rule of its own without the downloads", radio.contains("s.isLocal || downloadCache.holdsWhole"))
    }

    @Test
    fun `the adaptive pass asks the same rule`() {
        assertTrue(service.contains("playsOffline = { s -> playsOffline(s) },"))
    }
}
