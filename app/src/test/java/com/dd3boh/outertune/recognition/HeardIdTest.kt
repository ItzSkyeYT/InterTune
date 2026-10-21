/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import com.dd3boh.outertune.models.MediaMetadata
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The list of everything heard plays a song only when it is the one Shazam named.
 *
 * Found on 21 Oct 2026: "I Think Of You (Unreleased)" by Sarsh, heard on 18 Oct, was saved with the
 * first song YouTube's search for it returned, "Sarah" by Jasiah, and a tap on the entry played
 * that. The first answer went into the history whatever it was, though the engine had just checked
 * it with [corresponds] and found it was not the song.
 */
class HeardIdTest {

    private fun shazam(title: String, artist: String) =
        Recognised(title = title, artist = artist, artworkUrl = null, isrc = null, shazamKey = "k")

    private fun youtube(id: String, title: String, vararg artists: String) = SongItem(
        id = id,
        title = title,
        artists = artists.map { Artist(name = it, id = null) },
        thumbnail = "",
    )

    /** The library's row of a song: all a tap on the list has to go on. */
    private fun library(id: String, title: String, vararg artists: String) = MediaMetadata(
        id = id,
        title = title,
        artists = artists.map { MediaMetadata.Artist(id = null, name = it) },
        duration = 180,
        genre = null,
    )

    private val sarsh = shazam("I Think Of You (Unreleased)", "Sarsh")

    @Test
    fun `a first answer that is another song is not kept`() {
        assertNull(Heard.idFor(sarsh, youtube("sarah", "Sarah", "Jasiah")))
    }

    @Test
    fun `a first answer that is the song is kept`() {
        assertEquals("sarsh", Heard.idFor(sarsh, youtube("sarsh", "I Think Of You", "Sarsh")))
        assertEquals(
            "weeknd",
            Heard.idFor(shazam("Blinding Lights", "The Weeknd"), youtube("weeknd", "Blinding Lights (Official Video)", "The Weeknd")),
        )
    }

    @Test
    fun `no answer keeps nothing`() {
        assertNull(Heard.idFor(sarsh, null))
    }

    /**
     * A DJ's continuous mix is never added, but an upload that is the mix Shazam named is the
     * recording that was playing, so the list can still play it.
     */
    @Test
    fun `a DJ mix that is the recording heard keeps its id`() {
        val mix = shazam("Nils van Zandt Hitmix 2K16 (Mixed By Joost XXL) [Continious Mix]", "Nils van Zandt")
        assertTrue(isDjMix(mix.title, 0.0))
        assertEquals("mix", Heard.idFor(mix, youtube("mix", "Nils van Zandt Hitmix 2K16 (Mixed By Joost XXL)", "Nils van Zandt")))
    }

    /** The entry as it was found: saved before ids were checked, so it does not say it was. */
    private val found = Heard("I Think Of You (Unreleased)", "Sarsh", videoId = "sarah", at = 0L, checked = false)

    /** That one play put the wrong song in the library, under its own name. */
    @Test
    fun `an old entry the library knows as another song searches instead`() {
        assertNull(found.idToPlay(library("sarah", "Sarah", "Jasiah")))
    }

    @Test
    fun `an old entry the library knows as the song plays it`() {
        val old = Heard("Blinding Lights", "The Weeknd", videoId = "weeknd", at = 0L, checked = false)
        assertEquals("weeknd", old.idToPlay(library("weeknd", "Blinding Lights (Official Video)", "The Weeknd")))
    }

    /** Never played, so nothing on the phone says what the id is: a search costs a tap, the wrong song more. */
    @Test
    fun `an old entry the library has never seen searches`() {
        assertNull(found.idToPlay(null))
        assertNull(Heard("Blinding Lights", "The Weeknd", videoId = "weeknd", at = 0L, checked = false).idToPlay(null))
    }

    /** A row under another id says nothing about this one. */
    @Test
    fun `an old entry is checked against its own song only`() {
        val old = Heard("Blinding Lights", "The Weeknd", videoId = "weeknd", at = 0L, checked = false)
        assertNull(old.idToPlay(library("other", "Blinding Lights", "The Weeknd")))
    }

    @Test
    fun `an entry saved with a checked id plays it, in the library or not`() {
        val entry = Heard("Blinding Lights", "The Weeknd", videoId = "weeknd", at = 0L, checked = true)
        assertEquals("weeknd", entry.idToPlay(null))
        assertEquals("weeknd", entry.idToPlay(library("weeknd", "Blinding Lights", "The Weeknd")))
    }

    @Test
    fun `an entry with no id searches`() {
        assertNull(Heard("Blinding Lights", "The Weeknd", videoId = null, at = 0L, checked = true).idToPlay(null))
        assertNull(Heard("Blinding Lights", "The Weeknd", videoId = null, at = 0L, checked = false).idToPlay(null))
    }

    private val engine = File("src/main/java/com/dd3boh/outertune/recognition/RecognitionEngine.kt").readText()

    /**
     * The engine is not something a test can run, so this reads it, as SpeedRetryTest does: every
     * entry it saves takes its id from [Heard.idFor], and says the id was checked.
     */
    @Test
    fun `the engine saves only an id that was checked`() {
        val saved = Regex("""\bHeard\(""").findAll(engine).map { argumentsOf(engine, it.range.last + 1) }.toList()
        assertEquals("the saves this was written for", 1, saved.size)
        saved.forEach {
            assertTrue(it, "videoId = Heard.idFor(outcome.track, song)" in it)
            assertTrue(it, "checked = true" in it)
        }
    }

    /**
     * YouTube can rank a sped-up or slowed edit of the song first, by the same artist, and it
     * passes [corresponds]. Keep listening adds the recording [pickBest] picks for how the room's
     * copy plays, and the history kept the first answer: a tap on the entry played the other edit.
     */
    @Test
    fun `the history keeps the recording that is added, not the first answer`() {
        val track = shazam("Blinding Lights", "The Weeknd")
        val sped = youtube("sped", "Blinding Lights (Sped Up)", "The Weeknd")
        val original = youtube("original", "Blinding Lights", "The Weeknd")
        assertEquals("the edit passes the check", "sped", Heard.idFor(track, sped))
        assertEquals("original", pickBest(track, listOf(sped, original), PlaybackVariant.ORIGINAL)?.id)

        assertTrue(engine.contains("val kept = outcome.track.shazamKey?.let { confirmed[it] } ?: pickBest(outcome.track, candidates, PlaybackVariant.ORIGINAL)"))
        assertTrue(engine.contains("history.add(heard(kept))"))
        // and once more when a measured speed confirms another recording than the one kept
        val confirming = engine.substringAfter("val chosen = pickBest(outcome.track, candidates, variant) ?: best").substringBefore("return\n")
        assertTrue(confirming, confirming.contains("history.add(heard(chosen))"))
        assertTrue(confirming, confirming.contains("saved.join()"))
    }

    /** And the list plays an entry's video only when [Heard.idToPlay] hands it back. */
    @Test
    fun `the list plays only the id the rule hands back`() {
        val list = File("src/main/java/com/dd3boh/outertune/ui/screens/RecognitionHistoryScreen.kt").readText()
        val played = Regex("""WatchEndpoint\(videoId = (\w+)\)""").findAll(list).map { it.groupValues[1] }.toList()
        assertEquals("the plays this was written for", 1, played.size)
        assertTrue(Regex("""val ${played.single()} = entry\.idToPlay\(known\)""").containsMatchIn(list))
    }

    /** What is between the bracket before [from] and the one that closes it. */
    private fun argumentsOf(text: String, from: Int): String {
        var depth = 1
        var i = from
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        return text.substring(from, i - 1)
    }
}
