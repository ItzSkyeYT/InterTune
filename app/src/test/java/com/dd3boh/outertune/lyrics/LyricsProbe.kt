/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.WatchEndpoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assume.assumeTrue

/**
 * The automatic lyrics lookup run for real, against YouTube Music, LRCLIB and KuGou.
 *
 * Each song's title, artists and length are read from YouTube Music's queue entry for it, as the
 * player gets them, then looked up twice: with that length, which is what the app has once the
 * stream is resolved, and with none, which is what a search tap starts with and what the lookup
 * falls back to if the length never arrives. Makes real requests, so it only runs when asked:
 *
 *     LYRICS_PROBE=1 ./gradlew :app:testCoreDebugUnitTest --tests "*LyricsProbe*" -i
 *
 * [kugouNames] asks KuGou alone about songs with Chinese and Japanese names.
 */
class LyricsProbe {

    private class Song(val title: String, val artist: String, val videoId: String? = null)

    // Pinned by id, because YouTube Music's search did not return the same upload twice in a row on
    // 26 Sep 2026. The ids are what it returned first for title and artist that morning. Without an
    // id, a song is searched for.
    private val songs = listOf(
        // The one from the report of 26 Sep 2026, by the id he played.
        Song("Bailando (Video Edit)", "Paradisio", "G223WQ6vSII"),
        Song("Samba de Janeiro (Radio Edit)", "Bellini", "drx71eKybAg"),
        Song("Samba De Janeiro", "Bellini", "qcsgPTxr-NI"),
        Song("(It Goes Like) Nanana (Edit)", "Peggy Gou", "XOsY3W3dlI0"),
        Song("Rhythm Is a Dancer (7\" Edit)", "SNAP!", "P-IHYOpI3gc"),
        Song("Macarena", "Los Del Rio", "Z7EsuR5I8SE"),
        Song("Macarena (Bayside Boys Remix) (Remasterizado)", "Los Del Rio", "ulMRK4grORk"),
        Song("All That She Wants", "Ace of Base", "FpkawF5GWMI"),
        // Phonk, very likely without words, and the old lookup gave it another song's.
        Song("DAYS LATER FUNK - SPED UP", "LXNSTED", "BNwzqYVvS9g"),
    )

    private val providers = listOf(YouTubeSubtitleLyricsProvider, LrcLibLyricsProvider, KuGouLyricsProvider, YouTubeLyricsProvider)

    /** Notes what each provider answered, so the output says who found what. */
    private class Noting(private val inner: LyricsProvider, private val notes: MutableList<String>) : LyricsProvider by inner {
        override suspend fun getLyrics(query: LyricsQuery): Result<String> = inner.getLyrics(query).also { r ->
            notes += "${inner.name}: " + r.fold(
                { if (LyricsMatch.isSynced(it)) "synced" else "plain" },
                { (it.message?.lineSequence()?.first() ?: it.javaClass.simpleName).take(60) },
            )
        }
    }

    private suspend fun resolve(song: Song): SongItem? {
        val id = song.videoId ?: YouTube.search("${song.title} ${song.artist}", YouTube.SearchFilter.FILTER_SONG).getOrNull()
            ?.items?.filterIsInstance<SongItem>()?.firstOrNull()?.id ?: return null
        val next = YouTube.next(WatchEndpoint(videoId = id)).getOrNull() ?: return null
        return next.items.firstOrNull { it.id == id }
    }

    private suspend fun lookUp(query: LyricsQuery): Pair<String?, List<String>> {
        val notes = mutableListOf<String>()
        val found = LyricsLookup.firstFound(providers.map { Noting(it, notes) }, query) { _, _ -> }
        return found to notes
    }

    private fun describe(found: String?, notes: List<String>, ms: Long): String {
        val what = when {
            found == null -> "nothing"
            LyricsMatch.isSynced(found) -> "synced: " + found.lines().filter { it.isNotBlank() }.take(2).joinToString(" / ")
            else -> "plain: " + found.lines().filter { it.isNotBlank() }.take(2).joinToString(" / ")
        }
        return "$what  [${notes.joinToString("; ")}] ${ms} ms"
    }

    @Test
    fun probe() = runBlocking {
        assumeTrue("set LYRICS_PROBE=1 to run", System.getenv("LYRICS_PROBE") == "1")
        val results = LinkedHashMap<String, Pair<String?, String?>>()
        for (song in songs) {
            val item = resolve(song)
            if (item == null) {
                println("LYRICS ${song.title} / ${song.artist}: not found on YouTube Music")
                continue
            }
            val artists = item.artists.map { it.name }
            println("LYRICS '${song.title}' by ${song.artist} -> ${item.id} '${item.title}' by ${artists.joinToString()}, ${item.duration} s, album ${item.album?.name}")
            val known = LyricsQuery(item.id, item.title, artists, item.album?.name, item.duration ?: -1)
            var t = System.currentTimeMillis()
            val (withLength, notesA) = lookUp(known)
            println("LYRICS   at ${known.duration} s: ${describe(withLength, notesA, System.currentTimeMillis() - t)}")
            t = System.currentTimeMillis()
            val (without, notesB) = lookUp(known.copy(duration = -1))
            println("LYRICS   no length: ${describe(without, notesB, System.currentTimeMillis() - t)}")
            results[song.title] = withLength to without

            // LRCLIB answers first for most songs, so KuGou's own matching is shown on its own too.
            t = System.currentTimeMillis()
            val kugou = KuGouLyricsProvider.getLyrics(known)
            println("LYRICS   KuGou alone at ${known.duration} s: ${describe(kugou.getOrNull(), listOfNotNull(kugou.exceptionOrNull()?.message), System.currentTimeMillis() - t)}")

            if (song.title == "Macarena") {
                // A sped up copy of it, a fifth shorter, must not get the original's timings.
                val sped = known.copy(title = "${item.title} (Sped Up)", duration = (known.duration * 0.8).toInt())
                t = System.currentTimeMillis()
                val (spedFound, notesC) = lookUp(sped)
                println("LYRICS   '${sped.title}' at ${sped.duration} s: ${describe(spedFound, notesC, System.currentTimeMillis() - t)}")
                assertTrue("a sped up copy got timed lyrics", spedFound == null || !LyricsMatch.isSynced(spedFound))
            }
            delay(500)
        }

        val bailando = results["Bailando (Video Edit)"]
        assertNotNull("Bailando was not resolved on YouTube Music", bailando)
        assertTrue("Bailando had no timed lyrics", bailando!!.first?.let(LyricsMatch::isSynced) == true)
        val phonk = results["DAYS LATER FUNK - SPED UP"]
        assertNotNull("the phonk track was not resolved on YouTube Music", phonk)
        assertNull("the phonk track got lyrics at its length", phonk!!.first)
        assertNull("the phonk track got lyrics with no length", phonk.second)
    }

    /**
     * KuGou alone, asked in the scripts a player can hold: Traditional Chinese, and artists written in
     * Latin letters, which KuGou answers in Simplified Chinese and in the artist's own script.
     */
    @Test
    fun kugouNames() = runBlocking {
        assumeTrue("set LYRICS_PROBE=1 to run", System.getenv("LYRICS_PROBE") == "1")
        val timed = listOf(
            LyricsQuery("x", "告白氣球", listOf("周杰倫"), duration = 215),
            LyricsQuery("x", "晴天", listOf("周杰倫"), duration = 269),
            LyricsQuery("x", "告白氣球", listOf("Jay Chou"), duration = 215),
            LyricsQuery("x", "光年之外", listOf("G.E.M."), duration = 235),
            LyricsQuery("x", "Lemon", listOf("Kenshi Yonezu"), duration = 255),
        )
        for (query in timed) {
            val t = System.currentTimeMillis()
            val found = KuGouLyricsProvider.getLyrics(query)
            println("LYRICS KuGou '${query.title}' by ${query.artist} at ${query.duration} s: ${describe(found.getOrNull(), listOfNotNull(found.exceptionOrNull()?.message), System.currentTimeMillis() - t)}")
            assertTrue("KuGou had no timed lyrics for ${query.title} by ${query.artist}", found.getOrNull()?.let(LyricsMatch::isSynced) == true)
            delay(500)
        }
        // Listed only under its Japanese title, next to another YOASOBI song three seconds shorter.
        val english = LyricsQuery("x", "Racing Into The Night", listOf("YOASOBI"), duration = 261)
        val found = KuGouLyricsProvider.getLyrics(english)
        println("LYRICS KuGou '${english.title}' by ${english.artist}: ${describe(found.getOrNull(), listOfNotNull(found.exceptionOrNull()?.message), 0)}")
        assertNull("KuGou guessed at a title in another script", found.getOrNull())
    }
}
