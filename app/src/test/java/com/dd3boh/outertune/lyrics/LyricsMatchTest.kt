/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import com.dd3boh.lrclib.models.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the automatic lyrics lookup asks for, and which answer it takes.
 *
 * The titles are the ones YouTube Music gave on 26 Sep 2026, and the candidates are shaped like what
 * LRCLIB and KuGou returned for them that morning.
 */
class LyricsMatchTest {

    private fun query(title: String, vararg artists: String, duration: Int) =
        LyricsQuery(id = "x", title = title, artists = artists.toList(), duration = duration)

    private fun track(title: String, artist: String, duration: Double?, synced: String? = "[00:01.00]line", plain: String? = "line") =
        Track(id = 1, trackName = title, artistName = artist, duration = duration, plainLyrics = plain, syncedLyrics = synced)

    @Test
    fun `version tags that are not part of the name come off`() {
        assertEquals("Bailando", LyricsMatch.cleanTitle("Bailando (Video Edit)"))
        assertEquals("Samba de Janeiro", LyricsMatch.cleanTitle("Samba de Janeiro (Radio Edit)"))
        assertEquals("Samba de Janeiro", LyricsMatch.cleanTitle("Samba de Janeiro - Radio Edit"))
        assertEquals("Rhythm Is a Dancer", LyricsMatch.cleanTitle("Rhythm Is a Dancer (7\" Edit)"))
        assertEquals("Rhythm Is A Dancer", LyricsMatch.cleanTitle("Rhythm Is A Dancer (7'' Edit)"))
        assertEquals("Rhythm Is a Dancer", LyricsMatch.cleanTitle("Rhythm Is a Dancer (7″ edit)"))
        assertEquals("Rhythm Is A Dancer", LyricsMatch.cleanTitle("Rhythm Is A Dancer (7’’ Edit)"))
        assertEquals("Song", LyricsMatch.cleanTitle("Song (Edit)"))
        assertEquals("Song", LyricsMatch.cleanTitle("Song (Official Video)"))
        assertEquals("Song", LyricsMatch.cleanTitle("Song (Official Audio)"))
        assertEquals("Song", LyricsMatch.cleanTitle("Song (Lyrics)"))
        assertEquals("Song", LyricsMatch.cleanTitle("Song [Official Music Video]"))
        assertEquals("Song", LyricsMatch.cleanTitle("Song (Official Lyric Video)"))
        assertEquals("Song", LyricsMatch.cleanTitle("Song (Visualizer)"))
        assertEquals("Song", LyricsMatch.cleanTitle("Song [HD]"))
        assertEquals("Song", LyricsMatch.cleanTitle("Song - Remastered 2011"))
        assertEquals("Song", LyricsMatch.cleanTitle("Song (2011 Remaster)"))
        assertEquals("Song", LyricsMatch.cleanTitle("Song (Single Version)"))
        assertEquals("Song", LyricsMatch.cleanTitle("Song (feat. Someone)"))
        assertEquals("Song", LyricsMatch.cleanTitle("Song feat. Someone"))
        assertEquals("Song", LyricsMatch.cleanTitle("Song (with Someone)"))
        assertEquals("Song", LyricsMatch.cleanTitle("Song (Official Video) [HD]"))
        assertEquals("Macarena (Bayside Boys Remix)", LyricsMatch.cleanTitle("Macarena (Bayside Boys Remix) (Remasterizado)"))
        // En and em dashes, written as escapes so the source holds neither.
        assertEquals("Song", LyricsMatch.cleanTitle("Song \u2013 Radio Edit"))
        assertEquals("Song", LyricsMatch.cleanTitle("Song \u2014 Official Video"))
    }

    @Test
    fun `a bracket that is part of the name stays`() {
        assertEquals("(It Goes Like) Nanana", LyricsMatch.cleanTitle("(It Goes Like) Nanana (Edit)"))
        assertEquals("(I Can't Get No) Satisfaction", LyricsMatch.cleanTitle("(I Can't Get No) Satisfaction"))
    }

    @Test
    fun `a different recording keeps what makes it different`() {
        assertEquals("DAYS LATER FUNK - SPED UP", LyricsMatch.cleanTitle("DAYS LATER FUNK - SPED UP"))
        assertEquals("Song (Sped Up)", LyricsMatch.cleanTitle("Song (Sped Up)"))
        assertEquals("Song (Slowed + Reverb)", LyricsMatch.cleanTitle("Song (Slowed + Reverb)"))
        assertEquals("Song (Nightcore)", LyricsMatch.cleanTitle("Song (Nightcore)"))
        assertEquals("Song (Live)", LyricsMatch.cleanTitle("Song (Live)"))
        assertEquals("Song (Acoustic)", LyricsMatch.cleanTitle("Song (Acoustic)"))
        assertEquals("Song (Extended Mix)", LyricsMatch.cleanTitle("Song (Extended Mix)"))
    }

    @Test
    fun `a title that is nothing but a tag is left alone`() {
        assertEquals("Edit", LyricsMatch.cleanTitle("Edit"))
        assertEquals("(Edit)", LyricsMatch.cleanTitle("(Edit)"))
    }

    @Test
    fun `the artist in front of a video title is dropped`() {
        assertEquals("Bailando", query("Paradisio - Bailando (Official Video)", "Paradisio", duration = 230).searchTitle)
        assertEquals("Bailando", query("Bailando", "Paradisio", duration = 230).searchTitle)
        assertEquals("Bailando", query("Paradisio \u2014 Bailando", "Paradisio", duration = 230).searchTitle)
        // Only an artist of the song counts, not any "Something - " in front.
        assertEquals("Sabotage - Remix", query("Sabotage - Remix", "Beastie Boys", duration = 180).searchTitle)
    }

    @Test
    fun `the same song is recognised however it is written`() {
        assertTrue(LyricsMatch.titleMatches("Bailando (Video Edit)", "Bailando - Video Edit"))
        assertTrue(LyricsMatch.titleMatches("Bailando (Video Edit)", "Bailando"))
        assertTrue(LyricsMatch.titleMatches("(It Goes Like) Nanana (Edit)", "It Goes Like Nanana"))
        assertTrue(LyricsMatch.titleMatches("All That She Wants", "All That She Want's"))
        assertTrue(LyricsMatch.titleMatches("Rhythm Is a Dancer (7\" Edit)", "Rhythm Is A Dancer (7_ Edit)"))
        assertTrue(LyricsMatch.titleMatches("Sí Señor", "si senor"))
        // A remix name is left to the duration to settle, since LRCLIB often files the famous mix
        // under the plain title.
        assertTrue(LyricsMatch.titleMatches("Macarena (Bayside Boys Remix) (Remasterizado)", "Macarena"))
    }

    @Test
    fun `a different song or a different kind of recording is not the same song`() {
        assertFalse(LyricsMatch.titleMatches("DAYS LATER FUNK - SPED UP", "DAYS LATER FUNK"))
        assertFalse(LyricsMatch.titleMatches("DAYS LATER FUNK - SPED UP", "Careless Whisper - DocterS/Sped Up"))
        assertFalse(LyricsMatch.titleMatches("Song (Live)", "Song"))
        assertFalse(LyricsMatch.titleMatches("Song (Acoustic)", "Song"))
        assertFalse(LyricsMatch.titleMatches("Song", "Song (Nightcore)"))
        assertFalse(LyricsMatch.titleMatches("Bailando", "Bailando (Spanish Version)"))
        assertFalse(LyricsMatch.titleMatches("Bailando", "Bailamos"))
    }

    @Test
    fun `artists match across spelling, accents and lists`() {
        assertTrue(LyricsMatch.artistMatches(listOf("Los Del Rio"), "Los del Río"))
        assertTrue(LyricsMatch.artistMatches(listOf("SNAP!"), "Snap!"))
        assertTrue(LyricsMatch.artistMatches(listOf("Ace of Base"), "Ace Of Base"))
        assertTrue(LyricsMatch.artistMatches(listOf("Paradisio"), "Paradisio、Marisa"))
        assertTrue(LyricsMatch.artistMatches(listOf("Paradisio"), "Paradisio, Marisa"))
        assertTrue(LyricsMatch.artistMatches(listOf("Paradisio", "Marisa"), "Paradisio"))
        assertTrue(LyricsMatch.artistMatches(listOf("Earth, Wind & Fire"), "Earth, Wind & Fire"))
        assertTrue(LyricsMatch.artistMatches(listOf("Ace of Base"), "Ace Of Base (爱司基地)"))
    }

    @Test
    fun `a song with no artist is judged on title and length alone`() {
        assertTrue(LyricsMatch.artistMatches(emptyList(), "Anyone"))
        assertEquals(listOf(""), query("Bailando", duration = 230).artistVariants)
        val tracks = listOf(track("Bailando", "Paradisio", 229.0, synced = "[00:01.00]timed"))
        assertEquals("[00:01.00]timed", LyricsMatch.chooseLrcLib(tracks, query("Bailando", duration = 230)))
    }

    @Test
    fun `a different artist is not taken`() {
        assertFalse(LyricsMatch.artistMatches(listOf("Paradisio"), "Enrique Iglesias、Descemer Bueno、Gente de Zona"))
        assertFalse(LyricsMatch.artistMatches(listOf("Pink"), "Pink Floyd"))
        assertFalse(LyricsMatch.artistMatches(listOf("LXNSTED"), "DocterS"))
    }

    @Test
    fun `synced lyrics come from the closest length, not the first in the list`() {
        val tracks = listOf(
            track("Bailando (Video Edit)", "Paradisio", 226.0, synced = "[00:01.00]226"),
            track("Bailando (Video Edit)", "Paradisio", 4.0, synced = "[00:01.00]4"),
            track("Bailando (Video Edit)", "Paradisio", 229.04, synced = "[00:01.00]229"),
            track("Bailando (Video Edit)", "Paradisio", 230.0, synced = "[00:01.00]230"),
        )
        assertEquals("[00:01.00]230", LyricsMatch.chooseLrcLib(tracks, query("Bailando (Video Edit)", "Paradisio", duration = 230)))
    }

    @Test
    fun `nothing within a few seconds means no synced lyrics`() {
        val tracks = listOf(track("Bailando", "Paradisio", 200.8), track("Bailando", "Paradisio", 246.0))
        val found = LyricsMatch.chooseLrcLib(tracks, query("Bailando", "Paradisio", duration = 230))
        assertNull(found)
    }

    @Test
    fun `a sped up version does not get the original's timings`() {
        val original = listOf(track("DAYS LATER FUNK", "LXNSTED", 95.0))
        assertNull(LyricsMatch.chooseLrcLib(original, query("DAYS LATER FUNK - SPED UP", "LXNSTED", duration = 74)))
        val spedUp = listOf(track("DAYS LATER FUNK - SPED UP", "LXNSTED", 74.0, synced = "[00:01.00]fast"))
        assertEquals("[00:01.00]fast", LyricsMatch.chooseLrcLib(spedUp, query("DAYS LATER FUNK - SPED UP", "LXNSTED", duration = 74)))
    }

    @Test
    fun `a different song of the same length is not taken`() {
        val tracks = listOf(track("Bailando (Spanish Version)", "Enrique Iglesias", 242.0))
        assertNull(LyricsMatch.chooseLrcLib(tracks, query("Bailando", "Paradisio", duration = 242)))
        val other = listOf(track("Careless Whisper - DocterS/Sped Up", "DocterS", 74.0))
        assertNull(LyricsMatch.chooseLrcLib(other, query("DAYS LATER FUNK - SPED UP", "LXNSTED", duration = 74)))
    }

    @Test
    fun `plain words are taken when only they fit the length`() {
        val tracks = listOf(
            track("Samba de Janeiro", "Bellini", 168.0, synced = null, plain = "plain words"),
            track("Samba de Janeiro", "Bellini", 180.0, synced = "[00:01.00]too far"),
        )
        assertEquals("plain words", LyricsMatch.chooseLrcLib(tracks, query("Samba de Janeiro (Radio Edit)", "Bellini", duration = 169)))
    }

    @Test
    fun `plain words are not taken from a very different length`() {
        val tracks = listOf(track("Macarena", "Los Del Rio", 313.0, synced = null, plain = "long mix"))
        assertNull(LyricsMatch.chooseLrcLib(tracks, query("Macarena", "Los Del Rio", duration = 228)))
    }

    @Test
    fun `with no length known only plain words are given`() {
        val tracks = listOf(
            track("Bailando", "Enrique Iglesias", 242.0, synced = "[00:01.00]other", plain = "other"),
            track("Bailando", "Paradisio", 229.0, synced = "[00:01.00]timed", plain = "words"),
        )
        assertEquals("words", LyricsMatch.chooseLrcLib(tracks, query("Bailando (Video Edit)", "Paradisio", duration = -1)))
    }

    @Test
    fun `instrumental entries are skipped`() {
        val tracks = listOf(Track(id = 1, trackName = "Song", artistName = "A", duration = 100.0, plainLyrics = null, syncedLyrics = null, instrumental = true))
        assertNull(LyricsMatch.chooseLrcLib(tracks, query("Song", "A", duration = 100)))
    }

    @Test
    fun `ranking keeps the closest first and drops the rest`() {
        data class C(val t: String, val a: String, val d: Double?)
        val items = listOf(C("Song", "A", 231.9), C("Song", "A", 229.0), C("Song", "A", 230.0), C("Song", "B", 230.0), C("Song", "A", null))
        val ranked = LyricsMatch.ranked(items, query("Song", "A", duration = 230), LyricsMatch.SYNCED_TOLERANCE_SEC, { it.t }, { it.a }, { it.d })
        assertEquals(listOf(230.0, 229.0, 231.9), ranked.map { it.d })
    }

    @Test
    fun `synced text is told from plain text`() {
        assertTrue(LyricsMatch.isSynced("[00:29.59]Si señor\n[00:31.60]Efectos especiales"))
        assertTrue(LyricsMatch.isSynced("[ti:Song]\n[00:01.000]line"))
        assertFalse(LyricsMatch.isSynced("Si señor\nEfectos especiales"))
        assertFalse(LyricsMatch.isSynced("[Chorus]\nSi señor"))
    }

    @Test
    fun `the first artist alone is tried after the whole list`() {
        assertEquals(listOf("Paradisio, Marisa", "Paradisio"), query("Bailando", "Paradisio", "Marisa", duration = 230).artistVariants)
        assertEquals(listOf("Paradisio"), query("Bailando", "Paradisio", duration = 230).artistVariants)
    }

    // The names below are how KuGou answered on 26 Sep 2026 when asked in the script on the left.

    @Test
    fun `Traditional and Simplified Chinese are the same name`() {
        assertTrue(LyricsMatch.titleMatches("告白氣球", "告白气球"))
        assertTrue(LyricsMatch.titleMatches("說好的幸福呢", "说好的幸福呢"))
        assertTrue(LyricsMatch.artistMatches(listOf("周杰倫"), "周杰伦"))
        assertTrue(LyricsMatch.artistMatches(listOf("薛之謙"), "薛之谦"))
        assertFalse(LyricsMatch.titleMatches("演員", "认真的雪"))
        assertFalse(LyricsMatch.artistMatches(listOf("周杰倫"), "薛之谦"))
    }

    @Test
    fun `an artist in another script is left to the title and length`() {
        assertTrue(LyricsMatch.artistMatches(listOf("Kenshi Yonezu"), "米津玄師"))
        assertTrue(LyricsMatch.artistMatches(listOf("Jay Chou"), "周杰伦"))
        // Written in both, one half is enough.
        assertTrue(LyricsMatch.artistMatches(listOf("G.E.M."), "G.E.M.邓紫棋"))
        assertTrue(LyricsMatch.artistMatches(listOf("G.E.M."), "华晨宇、G.E.M.邓紫棋"))
        assertTrue(LyricsMatch.artistMatches(listOf("G.E.M. 鄧紫棋"), "邓紫棋"))
        assertTrue(LyricsMatch.artistMatches(listOf("BTS"), "BTS (防弹少年团)"))
        // Two names in the same script are still compared.
        assertFalse(LyricsMatch.artistMatches(listOf("G.E.M."), "Pink Floyd"))
        assertFalse(LyricsMatch.artistMatches(listOf("米津玄師"), "周杰伦"))
        // Digits alone are no script, so they do not count as one that differs.
        assertFalse(LyricsMatch.artistMatches(listOf("112"), "Boyz II Men"))
    }

    @Test
    fun `a title in another script is not taken for the song`() {
        assertFalse(LyricsMatch.titleMatches("Racing Into The Night", "夜に駆ける"))
        assertFalse(LyricsMatch.titleMatches("Racing Into The Night", "たぶん"))
    }

    @Test
    fun `a concert recording marked only in Chinese is not the studio song`() {
        assertFalse(LyricsMatch.titleMatches("小幸運", "小幸运 (2015如果巡回演唱会高雄站)"))
        assertFalse(LyricsMatch.titleMatches("演員", "演员 (2021成都超乐音乐节现场)"))
        assertTrue(LyricsMatch.titleMatches("演員 (現場)", "演员 (现场)"))
    }

    @Test
    fun `titles that only start the same are different songs`() {
        assertFalse(LyricsMatch.titleMatches("MONTAGEM - XONADA", "MONTAGEM - CORAL"))
        assertFalse(LyricsMatch.titleMatches("MONTAGEM - XONADA (Slowed)", "MONTAGEM - CORAL (Slowed)"))
        assertFalse(LyricsMatch.titleMatches("Song (Part 1)", "Song (Part 2)"))
        assertFalse(LyricsMatch.titleMatches("Les Misérables - I Dreamed a Dream", "Les Misérables - On My Own"))
        // One addition the other title lacks is still the same song, as before.
        assertTrue(LyricsMatch.titleMatches("Macarena (Bayside Boys Remix)", "Macarena"))
        assertTrue(LyricsMatch.titleMatches("Macarena", "Macarena (Bayside Boys Remix)"))
        assertTrue(LyricsMatch.titleMatches("Macarena (Bayside Boys Remix) (Remasterizado)", "Macarena (Bayside Boys remix)"))
    }

    @Test
    fun `a featured artist comes off without what follows`() {
        assertEquals("Song (Live)", LyricsMatch.cleanTitle("Song feat. Someone (Live)"))
        assertEquals("Song - Live", LyricsMatch.cleanTitle("Song feat. Someone - Live"))
        assertEquals("Song", LyricsMatch.cleanTitle("Song feat. Someone (Official Video)"))
        assertEquals("Song", LyricsMatch.cleanTitle("Song ft. Someone & Other"))
        assertFalse(LyricsMatch.titleMatches("Song feat. Someone (Live)", "Song"))
        assertTrue(LyricsMatch.titleMatches("Song feat. Someone (Live)", "Song (Live)"))
    }

    @Test
    fun `folding Chinese twice changes nothing more, and Latin text not at all`() {
        for (c in '㐀'..'鿿') {
            val once = HanFold.fold(c.toString())
            assertEquals("U+%04X".format(c.code), once, HanFold.fold(once))
        }
        assertEquals("Bailando (Video Edit)", HanFold.fold("Bailando (Video Edit)"))
        assertEquals("夜に駆ける", HanFold.fold("夜に駆ける"))
    }
}
