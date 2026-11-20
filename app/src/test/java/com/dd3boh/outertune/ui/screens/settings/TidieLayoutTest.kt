/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import com.dd3boh.outertune.ui.screens.settings.fragments.SampleSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Ti-Die's four layout ideas from his review of the app's texts (19 Nov 2026), as built on tidie-layout-1120: the Living
 * blur sample's refresh button on the cover, Try both named in Quick picks source's line, Count this install's long
 * text behind an (i), and a shorter line when liked songs' downloads pause.
 */
class TidieLayoutTest {
    private val main = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")

    private fun code(path: String) = File(main, "java/com/dd3boh/outertune/$path").readText()

    private fun text(folder: String, name: String): String =
        Regex("""<string name="$name"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(File(main, "res/$folder/strings-ot.xml").readText())!!.groupValues[1]

    @Test
    fun `the sample's next song is never the one playing nor from its album, while another can be`() {
        val songs = listOf(
            SampleSong.Choice("a1", "album-a"), SampleSong.Choice("a2", "album-a"),
            SampleSong.Choice("b1", "album-b"), SampleSong.Choice("c1", null),
        )
        repeat(200) { seed ->
            val next = SampleSong.pick(songs, playingId = "a1", playingAlbumId = "album-a", random = Random(seed))
            assertTrue("$next", next == "b1" || next == "c1")
        }
        // Every other song shares the album: one of them all the same, never the one playing.
        val oneAlbum = listOf(SampleSong.Choice("a1", "album-a"), SampleSong.Choice("a2", "album-a"))
        assertEquals("a2", SampleSong.pick(oneAlbum, "a1", "album-a"))
        // Nothing playing: any song.
        assertTrue(SampleSong.pick(songs, null, null) in songs.map { it.id })
        assertNull(SampleSong.pick(emptyList(), null, null))
        assertNull("the only song is the one playing", SampleSong.pick(listOf(SampleSong.Choice("a1", "album-a")), "a1", "album-a"))
    }

    @Test
    fun `the sample has a refresh button on its cover in place of the button under the words, liked songs first`() {
        val sample = code("ui/screens/settings/fragments/LivingBlurSample.kt")
        assertFalse("the button under the words is gone", "FilledTonalButton" in sample)
        assertTrue(sample, "Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.player_living_sample_play)" in sample)
        assertTrue("on the cover's top left", ".align(Alignment.TopStart)" in sample)
        val pool = sample.substringAfter("val candidates = withContext(Dispatchers.IO) {").substringBefore("}\n")
        assertTrue("liked songs first: $pool", pool.indexOf("likedSongsByCreateDateAsc") in 0 until pool.indexOf("mostPlayedSongs"))
    }

    @Test
    fun `Count this install says one short line, and what is sent behind its (i)`() {
        val frag = code("ui/screens/settings/fragments/UsageCountFrag.kt")
        assertTrue(frag, "explanation = stringResource(R.string.usage_count_enabled_info)" in frag)
        for (folder in listOf("values", "values-fr")) {
            assertTrue("$folder: one sentence under the switch", text(folder, "usage_count_enabled_description").count { it == '.' } == 1)
            val info = text(folder, "usage_count_enabled_info")
            assertTrue("$folder: the (i) says what is never sent", "F-Droid" in info && info.count { it == '.' } in 2..3)
        }
    }

    @Test
    fun `Quick picks source's line says what Try both mixes, and the pause line is two sentences`() {
        assertTrue(text("values", "quick_picks_source_description").endsWith("""\"Try both\" alternates \"Best recommendations\" with YouTube Music\'s picks."""))
        assertTrue(text("values-fr", "quick_picks_source_description").endsWith("« Essayer les deux » alterne « Meilleures recommandations » et les suggestions de YouTube Music."))
        for (folder in listOf("values", "values-fr")) {
            assertEquals("$folder: two sentences", 2, text(folder, "liked_autodownload_blocked").count { it == '.' })
        }
    }
}
