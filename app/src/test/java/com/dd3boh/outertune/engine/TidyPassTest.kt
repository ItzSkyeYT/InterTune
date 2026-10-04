/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class TidyPassTest {
    private data class Card(val id: String, val title: String, val artist: String?)
    private data class Tile(val name: String)   // an album or artist card: no song to compare

    private fun TidyPass.cards(items: List<Card>, fresh: Boolean = false) = row(items, fresh, { it.id }, { it.title }, { it.artist })

    @Test
    fun `just played songs and their versions leave the fresh row only`() {
        val pass = TidyPass(listOf(PlayedSong("a1", "Africa", "Toto")))
        val picks = listOf(Card("a1", "Africa", "Toto"), Card("a2", "Africa (Live)", "Toto"), Card("h1", "Hold the Line", "Toto"))
        assertEquals(listOf("h1"), pass.cards(picks, fresh = true).map { it.id })
        val later = TidyPass(listOf(PlayedSong("a1", "Africa", "Toto")))
        assertEquals(listOf("a1", "a2", "h1").size - 1, later.cards(picks).size)   // not fresh: only the version rule applies
    }

    @Test
    fun `a song shown once is not shown again below, nor is a version of it`() {
        val pass = TidyPass(emptyList())
        assertEquals(listOf("a1"), pass.cards(listOf(Card("a1", "Africa", "Toto"))).map { it.id })
        assertEquals(listOf("r1"), pass.cards(listOf(Card("a1", "Africa", "Toto"), Card("a3", "Africa - Remastered 2019", "Toto"), Card("r1", "Rosanna", "Toto"))).map { it.id })
    }

    @Test
    fun `the same title by another artist is a different song`() {
        val pass = TidyPass(listOf(PlayedSong("a1", "Africa", "Toto")))
        assertEquals(listOf("d1"), pass.cards(listOf(Card("d1", "Africa", "D'Angelo")), fresh = true).map { it.id })
    }

    @Test
    fun `within one row the first of two versions stays`() {
        val pass = TidyPass(emptyList())
        assertEquals(listOf("a2"), pass.cards(listOf(Card("a2", "Africa (Official Video)", "Toto"), Card("a1", "Africa", "Toto"))).map { it.id })
    }

    @Test
    fun `things that are not songs pass through`() {
        val pass = TidyPass(listOf(PlayedSong("x", "Anything", null)))
        val tiles = listOf(Tile("Toto IV"), Tile("Toto"))
        assertEquals(tiles, pass.row(tiles, freshOnly = true, { null }, { null }, { null }))
    }

    @Test
    fun `the pass is deterministic`() {
        val rows = listOf(Card("a1", "Africa", "Toto"), Card("a2", "Africa (Live)", "Toto"), Card("h1", "Hold the Line", "Toto"))
        val one = TidyPass(emptyList()).cards(rows)
        val two = TidyPass(emptyList()).cards(rows)
        assertEquals(one, two)
        assertEquals(listOf("a1", "h1"), one.map { it.id })
    }

    @Test
    fun `a banned song and its versions, and a banned artist, leave every row`() {
        val pass = TidyPass(emptyList(), bannedSongs = listOf(PlayedSong("a1", "Africa", "Toto")), bannedArtists = setOf("art_x", "rihanna"))
        val row = listOf(Card("a2", "Africa (Live)", "Toto"), Card("h1", "Hold the Line", "Toto"), Card("u1", "Umbrella", "Rihanna"), Card("d1", "Diamonds", "RIHANNA "))
        assertEquals(listOf("h1"), pass.cards(row).map { it.id })
        val byId = TidyPass(emptyList(), bannedArtists = setOf("art_x"))
        assertEquals(emptyList<Card>(), byId.row(listOf(Card("z", "Zed", "Someone")), false, { it.id }, { it.title }, { it.artist }, { "art_x" }))
    }

    @Test
    fun `keys ignore case and spacing around the artist`() {
        assertEquals(versionKey("Africa", "Toto"), versionKey("AFRICA (Remastered)", " toto "))
    }

    @Test
    fun `banning one Artist - Track upload does not ban the artist's other songs`() {
        // Both used to key as "pizza hotline", because the treatment word "OST" after the dash
        // stripped the song's name and left the artist's. A ban never expires, so that one tap
        // hid every one of that artist's songs that happened to be titled this way.
        val pass = TidyPass(emptyList(), bannedSongs = listOf(PlayedSong("p1", "Pizza Hotline - Automata | MOTORSLICE OST", "Pizza Hotline")))
        val row = listOf(
            Card("p2", "Pizza Hotline - Heavy Machine (Boss Theme 1) | MOTORSLICE OST", "Pizza Hotline"),
            Card("p3", "Automata | MOTORSLICE OST", "Pizza Hotline"),
        )
        // The other song stays; the same song without the artist in its title is still the song.
        assertEquals(listOf("p2"), pass.cards(row).map { it.id })
    }

    @Test
    fun `the Again lane may offer back a song heard today`() {
        // The lane exists to bring back something heard well in the last fortnight, and the
        // just-played rule was removing three quarters of its cards before anybody saw them.
        val played = listOf(PlayedSong("a1", "Africa", "Toto"))
        val picks = listOf(Card("a1", "Africa", "Toto"), Card("a2", "Africa (Live)", "Toto"), Card("h1", "Hold the Line", "Toto"))

        val exempt = TidyPass(played).row(picks, true, { it.id }, { it.title }, { it.artist }, { null }, { it.id == "a1" })
        assertEquals(listOf("a1", "h1"), exempt.map { it.id })
    }

    @Test
    fun `an exempt card still claims its version key, so no second cut follows it`() {
        // This is what stops the waiver turning into "Africa" and then "Africa (Live)" together.
        val played = listOf(PlayedSong("a1", "Africa", "Toto"))
        val picks = listOf(Card("a1", "Africa", "Toto"), Card("a2", "Africa (Live)", "Toto"))
        val out = TidyPass(played).row(picks, true, { it.id }, { it.title }, { it.artist }, { null }, { it.id == "a1" })
        assertEquals(listOf("a1"), out.map { it.id })
    }

    @Test
    fun `nothing is exempt by default, so every other row is untouched`() {
        val pass = TidyPass(listOf(PlayedSong("a1", "Africa", "Toto")))
        assertEquals(listOf("h1"), pass.cards(listOf(Card("a1", "Africa", "Toto"), Card("h1", "Hold the Line", "Toto")), fresh = true).map { it.id })
    }

    @Test
    fun `one artist cannot take over a row`() {
        // The complaint was a Quick picks row that came out mostly one kind of music. The engine
        // reads treatments and not genres, so it cannot see the kind; what it can see is that such
        // a run arrives from a handful of artists at once.
        val items = (1..10).map { Triple("id$it", "Song $it", if (it <= 7) "Phonk Guy" else "Someone Else") }
        val kept = TidyPass(emptyList()).row(
            items,
            id = { it.first },
            title = { it.second },
            artist = { it.third },
            maxPerArtist = 2,
        )
        assertEquals(2, kept.count { it.third == "Phonk Guy" })
        // Capped too: three offered, two kept. The cap is a cap, not a handicap for the winner.
        assertEquals(2, kept.count { it.third == "Someone Else" })
    }

    @Test
    fun `a duplicate dropped from the row does not use up its artist's share`() {
        // A YouTube shelf can carry the video and the song upload of one track. The second is
        // dropped as the same song, and it must not cost the artist their second card.
        val items = listOf(
            Triple("v1", "Blinding Lights", "The Weeknd"),
            Triple("s1", "Blinding Lights", "The Weeknd"),
            Triple("s2", "Save Your Tears", "The Weeknd"),
        )
        val kept = TidyPass(emptyList()).row(
            items,
            id = { it.first },
            title = { it.second },
            artist = { it.third },
            maxPerArtist = 2,
        )
        assertEquals(listOf("v1", "s2"), kept.map { it.first })
    }

    @Test
    fun `the ones it keeps are the ones that ranked highest`() {
        // Order is the ranking, so a cap must take from the front rather than at random.
        val items = (1..5).map { Triple("id$it", "Song $it", "One Artist") }
        val kept = TidyPass(emptyList()).row(
            items,
            id = { it.first },
            title = { it.second },
            artist = { it.third },
            maxPerArtist = 2,
        )
        assertEquals(listOf("id1", "id2"), kept.map { it.first })
    }

    @Test
    fun `without a cap nothing is dropped for its artist`() {
        // Every other row is meant to be able to be all one artist: an album, a discography.
        val items = (1..6).map { Triple("id$it", "Song $it", "One Artist") }
        val kept = TidyPass(emptyList()).row(items, id = { it.first }, title = { it.second }, artist = { it.third })
        assertEquals(6, kept.size)
    }

    @Test
    fun `a missing artist never counts against the cap`() {
        // Local files often have no artist at all, and lumping them together as one would thin a
        // row of exactly the songs least likely to sound alike.
        val items = (1..6).map { Triple("id$it", "Song $it", null) }
        val kept = TidyPass(emptyList()).row(
            items,
            id = { it.first },
            title = { it.second },
            artist = { it.third },
            maxPerArtist = 2,
        )
        assertEquals(6, kept.size)
    }

    @Test
    fun `a cap of its own per card lets a lean's lead cards past two, and only them`() {
        // Six by one artist, the first four the lean's own cards: they may hold four, the rest two,
        // and the count is shared, so a lead card after two others still sees the two.
        val items = (1..6).map { Triple("id$it", "Song $it", "One Artist") }
        val lead = setOf("id1", "id2", "id3", "id4")
        val kept = TidyPass(emptyList()).row(items, id = { it.first }, title = { it.second }, artist = { it.third }, maxPerArtist = 2,
            capOf = { if (it.first in lead) 4 else 2 })
        assertEquals(listOf("id1", "id2", "id3", "id4"), kept.map { it.first })
        val mixed = listOf("o1", "o2", "id1", "id2", "id3").map { Triple(it, "Song $it", "One Artist") }
        val keptMixed = TidyPass(emptyList()).row(mixed, id = { it.first }, title = { it.second }, artist = { it.third }, maxPerArtist = 2,
            capOf = { if (it.first.startsWith("id")) 4 else 2 })
        assertEquals(listOf("o1", "o2", "id1", "id2"), keptMixed.map { it.first })
        // With no per-card cap, the row cap holds for everything, as before.
        assertEquals(2, TidyPass(emptyList()).row(items, id = { it.first }, title = { it.second }, artist = { it.third }, maxPerArtist = 2).size)
        // And with no row cap at all, a per-card cap changes nothing: it only refines a cap that is set.
        assertEquals(6, TidyPass(emptyList()).row(items, id = { it.first }, title = { it.second }, artist = { it.third }, capOf = { 1 }).size)
    }
}
