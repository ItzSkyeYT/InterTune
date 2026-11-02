/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * When an album asks for a radio, and from which song.
 *
 * A listener asked on 2 Nov 2026 how to turn on infinite mode: after an album the app "doesn't play
 * new songs; it just stops". Auto load more songs only ever carried on a radio or a mix, the queues
 * that hold the seed of their next page. Endless playback is for albums and radios, by his word of
 * that day, so these hold the decision for an album and hold every other list to ending where it
 * ends.
 */
class EndlessQueueTest {

    private fun song(id: String, artist: String = "Artist", local: Boolean = false) = MediaMetadata(
        id = id,
        title = "Song $id",
        artists = listOf(MediaMetadata.Artist(id = null, name = artist)),
        duration = 200,
        genre = null,
        isLocal = local,
    )

    /** An album as the album page plays it: one artist, the tracks in order. */
    private fun album(tracks: Int) = (1..tracks).map { song("T$it", "Band") }

    /** A playlist: songs of many artists. */
    private fun playlist(songs: Int) = (1..songs).map { song("P$it", "Artist $it") }

    /** The files on the phone, which have no YouTube id (SongEntity.generateSongId). */
    private fun files(count: Int) = (1..count).map { song("LSfile$it", "Local", local = true) }

    /**
     * What the service asks at a song change with [queue] in the player at [playing]. Everything
     * is as it is by default: an album, the setting on, repeat off, online, no radio of its own.
     */
    private fun ask(
        queue: List<MediaMetadata>,
        playing: Int,
        origin: PlayOrigin = PlayOrigin.ALBUM,
        hasNextPage: Boolean = false,
        autoLoadMore: Boolean = true,
        repeatOff: Boolean = true,
        online: Boolean = true,
        run: String = "run:1",
        asked: EndlessQueue.Asked? = null,
        inPlayer: Int = queue.size,
        playingId: String? = queue.getOrNull(playing)?.id,
    ) = EndlessQueue.seed(
        origin = origin.code,
        queue = queue,
        playing = playing,
        playingId = playingId,
        inPlayer = inPlayer,
        run = run,
        hasNextPage = hasNextPage,
        autoLoadMore = autoLoadMore,
        repeatOff = repeatOff,
        online = online,
        asked = asked,
    )

    // An album carries on

    @Test
    fun `an album five from its end asks for a radio from its last song`() {
        val tracks = album(12)

        // The song playing is counted: the 8th of 12 has five left, itself and four more.
        assertEquals("T12", ask(tracks, playing = 7))
        assertEquals("T12", ask(tracks, playing = 11))
    }

    @Test
    fun `an album with more than five left asks for nothing yet`() {
        val tracks = album(12)

        assertNull(ask(tracks, playing = 0))
        assertNull(ask(tracks, playing = 6))
    }

    @Test
    fun `a short album asks as soon as it starts`() {
        assertEquals("T4", ask(album(4), playing = 0))
    }

    @Test
    fun `a single is an album of one track`() {
        assertEquals("T1", ask(album(1), playing = 0))
    }

    @Test
    fun `a shuffled album asks from the last song in the order it plays, not the order it was saved`() {
        // A shuffled album is played by shuffle order, which the board hands over already sorted.
        val inPlayOrder = listOf("T9", "T2", "T11", "T5", "T1", "T12", "T7", "T3").map { song(it, "Band") }

        assertEquals("T3", ask(inPlayOrder, playing = 3))
    }

    @Test
    fun `an album from YouTube Music that ends in the radio the page added asks from the last song of it`() {
        // YouTubeAlbumRadio: the album, then what YouTube's own radio added past it.
        val queue = album(10) + (1..14).map { song("Y$it", "Someone else") }

        assertNull(ask(queue, playing = 3))
        assertEquals("Y14", ask(queue, playing = 19))
    }

    // Files on the phone have no radio

    @Test
    fun `an album of local files asks for nothing`() {
        assertNull(ask(files(8), playing = 6))
        assertNull(ask(files(1), playing = 0))
    }

    @Test
    fun `an album that mixes the two asks from the last song that has a YouTube id`() {
        // Three songs from YouTube and two files after them.
        val queue = listOf(song("A"), song("B"), song("C")) + files(2)

        assertEquals("C", ask(queue, playing = 0))
    }

    @Test
    fun `local files in front change nothing`() {
        val queue = files(3) + listOf(song("A"), song("B"))

        assertEquals("B", ask(queue, playing = 2))
    }

    // Everything but an album ends where it ends

    @Test
    fun `a playlist stops at its end, his own or somebody else's`() {
        // The same list, five from its end, as an album would ask.
        val songs = playlist(40)

        assertNull(ask(songs, playing = 35, origin = PlayOrigin.PLAYLIST))
        assertNull(ask(songs, playing = 39, origin = PlayOrigin.PLAYLIST))
    }

    @Test
    fun `the liked songs and the library stop at their end`() {
        // Liked songs start as a playlist, the library's song list and folders as the library.
        val songs = playlist(30)

        assertNull(ask(songs, playing = 28, origin = PlayOrigin.PLAYLIST))
        assertNull(ask(songs, playing = 28, origin = PlayOrigin.LIBRARY))
    }

    @Test
    fun `search results stop at their end`() {
        assertNull(ask(playlist(10), playing = 8, origin = PlayOrigin.SEARCH))
    }

    @Test
    fun `the history stops at its end`() {
        assertNull(ask(playlist(25), playing = 23, origin = PlayOrigin.HISTORY))
    }

    @Test
    fun `local songs stop at their end`() {
        assertNull(ask(files(12), playing = 10, origin = PlayOrigin.LOCAL_FILES))
        assertNull(ask(files(12), playing = 10, origin = PlayOrigin.LIBRARY))
    }

    @Test
    fun `a queue made by hand stops at its end`() {
        // Add to queue and Create queue start a queue as QUEUE, a song's own Play as MENU.
        assertNull(ask(playlist(6), playing = 4, origin = PlayOrigin.QUEUE))
        assertNull(ask(listOf(song("A")), playing = 0, origin = PlayOrigin.MENU))
    }

    @Test
    fun `an artist's songs and the rows on Home stop at their end`() {
        assertNull(ask(playlist(20), playing = 18, origin = PlayOrigin.ARTIST))
        assertNull(ask(playlist(20), playing = 18, origin = PlayOrigin.HOME_ROW))
        assertNull(ask(playlist(20), playing = 18, origin = PlayOrigin.QUICK_PICKS))
    }

    @Test
    fun `a queue that does not say where it began stops at its end`() {
        // Saved before origins were kept, or started by a caller nobody tagged.
        assertNull(ask(album(12), playing = 11, origin = PlayOrigin.UNKNOWN))
    }

    @Test
    fun `an album started from the car or another app is not an album to the app`() {
        // A list handed over through onSetMediaItems says EXTERNAL whatever it holds.
        assertNull(ask(album(12), playing = 11, origin = PlayOrigin.EXTERNAL))
    }

    @Test
    fun `of all the places a queue can begin, only an album asks`() {
        // Whatever is added to PlayOrigin later ends where it ends until it is made to ask.
        val asking = PlayOrigin.entries.filter { ask(album(12), playing = 11, origin = it) != null }

        assertEquals(listOf(PlayOrigin.ALBUM), asking)
    }

    // What stops an album

    @Test
    fun `repeat on asks for nothing, since the queue has no end`() {
        // Repeat all goes round and repeat one stays on the song. Neither reaches the end.
        assertNull(ask(album(12), playing = 11, repeatOff = false))
    }

    @Test
    fun `with Auto load more songs off an album stops as it always did`() {
        assertNull(ask(album(12), playing = 11, autoLoadMore = false))
    }

    @Test
    fun `a radio is left to the path it has`() {
        // Its queue holds the seed of its next page, and the service asks for that page itself,
        // whatever the queue says it began as.
        assertNull(ask(playlist(30), playing = 27, origin = PlayOrigin.RADIO, hasNextPage = true))
        assertNull(ask(album(12), playing = 11, origin = PlayOrigin.ALBUM, hasNextPage = true))
        // And a radio that has no seed yet is not an album either.
        assertNull(ask(playlist(30), playing = 27, origin = PlayOrigin.RADIO))
    }

    @Test
    fun `offline asks for nothing, and what was not asked is not spent`() {
        val tracks = album(12)

        assertNull(ask(tracks, playing = 10, online = false))
        // Back online at the next song, the same album asks.
        assertEquals("T12", ask(tracks, playing = 11, online = true))
    }

    @Test
    fun `a queue with nothing in it asks for nothing`() {
        assertNull(ask(emptyList(), playing = 0))
        assertNull(ask(emptyList(), playing = -1))
    }

    @Test
    fun `a player that holds another list is not asked for`() {
        val tracks = album(12)

        // A list a car or another app put in the player is not the queue the board calls current.
        assertNull(ask(tracks, playing = 11, inPlayer = 30))
        assertNull(ask(tracks, playing = 11, playingId = "somewhere else"))
        assertNull(ask(tracks, playing = 11, playingId = null))
        assertNull(ask(tracks, playing = 12))
    }

    // Once per end of a queue

    @Test
    fun `an album asks once for each end it has`() {
        val tracks = album(12)
        val first = ask(tracks, playing = 7)
        assertEquals("T12", first)

        val asked = EndlessQueue.Asked(run = "run:1", seed = first!!)
        // Every song change after it, to the end, in the same run.
        for (playing in 8..11) assertNull("song $playing", ask(tracks, playing = playing, asked = asked))
    }

    @Test
    fun `songs added by hand give the album a new end, which is asked for once more`() {
        val asked = EndlessQueue.Asked(run = "run:1", seed = "T12")
        val longer = album(12) + song("Z")

        assertEquals("Z", ask(longer, playing = 11, asked = asked))
    }

    @Test
    fun `playing the album again is a new run, asked for from the start`() {
        val tracks = album(12)
        val before = EndlessQueue.Asked(run = "run:1", seed = "T12")

        assertNull(ask(tracks, playing = 8, run = "run:1", asked = before))
        // The same album tapped again: the board gives the queue a new run.
        assertEquals("T12", ask(tracks, playing = 8, run = "run:2", asked = before))
    }

    // The album stays the listener's

    @Test
    fun `the songs the album held stay the listener's, so Add to queue goes after them and before the radio`() {
        // The service marks the album's songs as put in by hand when its radio joins, in the run
        // that is playing (MusicService.noteByHand), and a radio puts Add to queue's songs after
        // the listener's own.
        val tracks = album(12).map { it.id }
        val radio = listOf("R1", "R2", "R3")
        val heard = AdaptiveQueue.Heard()
        heard.note(7L, "T8", "Song T8", listOf("Band"), runId = 1L)
        heard.putInByHand(7L, 1L, tracks, radio = true)
        val queue = tracks + radio

        // Playing the 8th track: the other four are ahead, and the new song goes after them.
        assertEquals(12, AddToQueue.place(queue, playing = 7, radio = true, byHand = heard.byHandIn(7L, 1L)))
        // Left unmarked, it would have gone in front of the end of the album.
        assertEquals(8, AddToQueue.place(queue, playing = 7, radio = true, byHand = emptySet()))
        // And no pass looks at them: they are what the run says it put in.
        assertEquals(tracks.toSet(), heard.byHand.toSet())
    }

    @Test
    fun `what was asked in a queue is told apart from the queue by its run`() {
        // Runs of the same queue and of another queue never share an ask.
        assertEquals("run:77", AdaptiveQueue.runKey(queue = 5L, runId = 77L))
        assertEquals("queue:5", AdaptiveQueue.runKey(queue = 5L, runId = 0L))
    }
}
