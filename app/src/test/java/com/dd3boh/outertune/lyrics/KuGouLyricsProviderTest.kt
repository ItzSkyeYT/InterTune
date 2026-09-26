/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import com.zionhuang.kugou.models.SearchLyricsResponse
import com.zionhuang.kugou.models.SearchSongResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which of KuGou's answers the lookup takes. The names and lengths are what KuGou listed on 26 Sep 2026
 * for each query; its lyrics are stood in for by a line naming the entry.
 */
class KuGouLyricsProviderTest {

    private fun query(title: String, vararg artists: String, duration: Int) =
        LyricsQuery(id = "x", title = title, artists = artists.toList(), duration = duration)

    private class Listed(val name: String, val singer: String, val seconds: Int)

    /** KuGou as a list of songs, each with lyrics of its own, and a lyrics search by name. */
    private class Fake(songs: List<Listed>, private val byName: List<Listed> = emptyList()) : KuGouLyricsProvider.Requests {
        private val listed = songs.mapIndexed { i, s -> SearchSongResponse.Data.Info(duration = s.seconds, hash = "h$i", songname = s.name, singername = s.singer) }
        val asked = mutableListOf<String>()

        private fun candidate(id: Long, s: Listed) =
            SearchLyricsResponse.Candidate(id = id, productFrom = "", duration = s.seconds * 1000L, accesskey = "", song = s.name, singer = s.singer)

        override suspend fun songs(title: String, artist: String) = listed.also { asked += "songs $title / $artist" }
        override suspend fun candidatesForHash(hash: String): List<SearchLyricsResponse.Candidate> {
            asked += "hash $hash"
            val i = hash.removePrefix("h").toInt()
            return listOf(candidate(i.toLong(), Listed(listed[i].songname, listed[i].singername, listed[i].duration)))
        }
        override suspend fun candidatesForKeyword(title: String, artist: String, duration: Int) =
            byName.mapIndexed { i, s -> candidate(100L + i, s) }.also { asked += "name $title / $artist" }
        override suspend fun download(candidate: SearchLyricsResponse.Candidate) = "[00:01.00]${candidate.song} ${candidate.duration / 1000}"
    }

    @Test
    fun `a Traditional Chinese query takes KuGou's Simplified answer`() = runBlocking {
        val kugou = Fake(listOf(Listed("告白气球", "周杰伦", 215), Listed("告白气球 (3D环绕版)", "周杰伦", 209), Listed("晴天", "周杰伦", 269), Listed("告白气球 (Live)", "周杰伦", 212)))
        assertEquals("[00:01.00]告白气球 215", KuGouLyricsProvider.lookUp(query("告白氣球", "周杰倫", duration = 215), kugou).getOrNull())
    }

    @Test
    fun `a romanised artist takes KuGou's own name for them`() = runBlocking {
        val kugou = Fake(listOf(Listed("Lemon", "米津玄師", 255), Listed("Lemon (Live)", "米津玄師", 166), Listed("LOSER", "米津玄師", 243)))
        assertEquals("[00:01.00]Lemon 255", KuGouLyricsProvider.lookUp(query("Lemon", "Kenshi Yonezu", duration = 255), kugou).getOrNull())
        val jay = Fake(listOf(Listed("告白气球", "周杰伦", 215)))
        assertEquals("[00:01.00]告白气球 215", KuGouLyricsProvider.lookUp(query("告白氣球", "Jay Chou", duration = 215), jay).getOrNull())
    }

    @Test
    fun `the same artist's other song of about the same length is not taken`() = runBlocking {
        // KuGou lists 认真的雪 two seconds from 演员; without 演员 itself in the list, nothing fits.
        val kugou = Fake(listOf(Listed("演员 (Live)", "薛之谦", 257), Listed("认真的雪", "薛之谦", 259)), byName = listOf(Listed("认真的雪", "薛之谦", 259)))
        val found = KuGouLyricsProvider.lookUp(query("演員", "薛之謙", duration = 261), kugou)
        assertNull(found.getOrNull())
        assertFalse(kugou.asked.any { it.startsWith("hash") })
    }

    @Test
    fun `a title in another script is not guessed at`() = runBlocking {
        // たぶん is another YOASOBI song, three seconds off; 夜に駆ける is the song, but nothing says so.
        val kugou = Fake(listOf(Listed("群青", "YOASOBI", 248), Listed("たぶん", "YOASOBI", 258), Listed("夜に駆ける", "YOASOBI", 261)))
        assertNull(KuGouLyricsProvider.lookUp(query("Racing Into The Night", "YOASOBI", duration = 261), kugou).getOrNull())
    }

    @Test
    fun `a concert recording marked only in Chinese is passed over for the studio song`() = runBlocking {
        val kugou = Fake(listOf(Listed("小幸运", "田馥甄", 263), Listed("小幸运 (2015如果巡回演唱会高雄站)", "田馥甄", 266)))
        assertEquals("[00:01.00]小幸运 263", KuGouLyricsProvider.lookUp(query("小幸運", "田馥甄", duration = 265), kugou).getOrNull())
    }

    @Test
    fun `the lyrics search by name is used when no listed song fits`() = runBlocking {
        val kugou = Fake(listOf(Listed("光年之外 (DJ版)", "G.E.M.邓紫棋", 306)), byName = listOf(Listed("光年之外", "邓紫棋", 235)))
        assertEquals("[00:01.00]光年之外 235", KuGouLyricsProvider.lookUp(query("光年之外", "G.E.M. 鄧紫棋", duration = 235), kugou).getOrNull())
    }

    @Test
    fun `with no length KuGou is not asked`() = runBlocking {
        val kugou = Fake(listOf(Listed("告白气球", "周杰伦", 215)))
        val found = KuGouLyricsProvider.lookUp(query("告白氣球", "周杰倫", duration = -1), kugou)
        assertTrue(found.isFailure)
        assertEquals(emptyList<String>(), kugou.asked)
    }
}
