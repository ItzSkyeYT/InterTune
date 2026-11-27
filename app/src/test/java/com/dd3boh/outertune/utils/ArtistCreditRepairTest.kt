/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.db.StoredCredit
import com.dd3boh.outertune.models.ArtistCredit
import com.dd3boh.outertune.models.MediaMetadata
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The pass that puts back, once, the artists of the kept songs whose credit was stored as one name: what it asks
 * YouTube about, what it does with the answer, and where it carries on when it could not ask.
 */
class ArtistCreditRepairTest {

    private class Marks(var place: String? = null, var done: Boolean = false) : ArtistCreditRepair.Marks {
        override fun at() = place
        override fun setAt(id: String) {
            place = id
        }
        override suspend fun done() = done
        override suspend fun setDone() {
            done = true
        }
    }

    private val hoax = MediaMetadata.Artist("UC0-C_jjGdhQ4x9pmMSEZLcg", "Hoax")
    private val soWhat = MediaMetadata.Artist(null, "So What")
    private val dynamite = MediaMetadata.Artist("UCEMEp1vS0srzZBre3-J9JbQ", "Dynamite MC")

    /** The stored songs, in id order, as DatabaseDao.creditsToAsk pages them. */
    private val stored = listOf(
        StoredCredit("RuFu8i6JLhE", "Hoax, So What, & Dynamite MC"),
        StoredCredit("milkTalk001", "Milk Talk"),
        StoredCredit("simonAnd001", "Simon & Garfunkel"),
    ).sortedBy { it.songId }

    private fun page(after: String, limit: Int) = stored.filter { it.songId > after }.take(limit)

    /** YouTube Music now: the three named one by one, Simon & Garfunkel one artist as ever. */
    private val youtube = mapOf(
        "RuFu8i6JLhE" to listOf(hoax, soWhat, dynamite),
        "milkTalk001" to listOf(MediaMetadata.Artist(null, "Milk Talk")),
        "simonAnd001" to listOf(MediaMetadata.Artist("UCsimon", "Simon & Garfunkel")),
    )

    @Test
    fun `his song gets its three artists, and only names that may be several are asked about`() = runBlocking {
        val marks = Marks()
        val asked = mutableListOf<List<String>>()
        val split = mutableMapOf<String, List<MediaMetadata.Artist>>()
        val fixed = ArtistCreditRepair.run(
            marks,
            page = ::page,
            ask = { ids -> asked += ids; youtube.filterKeys { it in ids } },
            split = { id, artists ->
                val name = stored.first { it.songId == id }.name
                ArtistCredit.splits("LAhoaxComb", name, false, artists.map { it.name }).also { if (it) split[id] = artists }
            },
            pauseMs = 0,
        )
        assertEquals(1, fixed)
        assertEquals(mapOf("RuFu8i6JLhE" to listOf(hoax, soWhat, dynamite)), split)
        assertEquals(listOf(listOf("RuFu8i6JLhE", "simonAnd001")), asked)
        assertTrue(marks.done)
    }

    @Test
    fun `with no answer from YouTube the pass is not done, and the next launch carries on from the last page done`() = runBlocking {
        val marks = Marks()
        var answering = false
        val asked = mutableListOf<String>()
        fun ask(ids: List<String>): Map<String, List<MediaMetadata.Artist>>? {
            if (!answering) return null
            asked += ids
            return youtube.filterKeys { it in ids }
        }
        val first = ArtistCreditRepair.run(marks, page = ::page, ask = { ask(it) }, split = { _, _ -> true }, pauseMs = 0)
        assertEquals(0, first)
        assertFalse(marks.done)
        assertEquals(null, marks.place)

        answering = true
        val second = ArtistCreditRepair.run(marks, page = ::page, ask = { ask(it) }, split = { id, _ -> id == "RuFu8i6JLhE" }, pauseMs = 0)
        assertEquals(1, second)
        assertTrue(marks.done)
        assertEquals(listOf("RuFu8i6JLhE", "simonAnd001"), asked)
    }

    @Test
    fun `a pass that is done is not run again`() = runBlocking {
        var asked = false
        val fixed = ArtistCreditRepair.run(Marks(done = true), page = ::page, ask = { asked = true; emptyMap() }, split = { _, _ -> true }, pauseMs = 0)
        assertEquals(0, fixed)
        assertFalse(asked)
    }

    @Test
    fun `it runs after the scan at launch, and the player is told when songs changed`() {
        val code = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()
        val after = code.substringAfter("TrackTagReread.runOnce(this@MainActivity, database)").take(600)
        assertTrue(
            "MainActivity no longer runs ArtistCreditRepair after the scan",
            Regex("""if \(ArtistCreditRepair\.runOnce\(this@MainActivity, database\) > 0\) \{\s*playerConnection\?\.service\?\.refreshQueuesAfterScan\(\)""")
                .containsMatchIn(after),
        )
        // The song shown before Play, read from the saved queue at the start, is shown again with its artists.
        assertTrue("the song shown before Play is not shown again", "playerConnection?.storedQueueEdited()" in after)
    }
}
