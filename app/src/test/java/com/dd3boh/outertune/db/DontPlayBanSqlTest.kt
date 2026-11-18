/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import com.dd3boh.outertune.db.entities.RecommendationExclusion
import com.dd3boh.outertune.viewmodels.ExclusionsViewModel
import com.dd3boh.outertune.viewmodels.ExclusionsViewModel.Companion.REASON_BAN
import com.dd3boh.outertune.viewmodels.ExclusionsViewModel.Companion.REASON_DONT_PLAY
import com.dd3boh.outertune.viewmodels.ExclusionsViewModel.Companion.REASON_DONT_PLAY_BANNED
import com.dd3boh.outertune.viewmodels.ExclusionsViewModel.Companion.REASON_SNOOZE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection

/**
 * Don't play this song set on a song banned from the rows (Not this song), run against the exported schema as on the
 * device. A song has one row of Exclusions in all, so Don't play wrote over the ban, and lifting it deleted the row:
 * the song was back in Quick picks and the other rows, which the listener had asked never to see (review of 18 Nov 2026).
 */
class DontPlayBanSqlTest {
    private lateinit var db: Connection

    @Before
    fun open() {
        db = SchemaDb.open()
        exec("INSERT INTO song(id, title, duration, liked) VALUES ('af1', 'Africa', 200, 0)")
        exec("INSERT INTO song(id, title, duration, liked) VALUES ('ro2', 'Rosanna', 200, 0)")
    }

    @After
    fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    /** What Room's insertExclusion (REPLACE on the unique kind and target) does with a row. */
    private fun write(row: RecommendationExclusion) = db.prepareStatement(
        "INSERT OR REPLACE INTO recommendation_exclusion (kind, targetId, label, reason, createdAt, expiresAt) VALUES (?, ?, ?, ?, ?, ?)"
    ).use { st ->
        st.setInt(1, row.kind); st.setString(2, row.targetId); st.setString(3, row.label); st.setInt(4, row.reason)
        st.setLong(5, row.createdAt); st.setObject(6, row.expiresAt)
        st.executeUpdate()
    }

    /** The song's row now, as ListenDao.exclusion reads it. */
    private fun there(id: String): RecommendationExclusion? = db.createStatement().use { st ->
        st.executeQuery("SELECT * FROM recommendation_exclusion WHERE kind = 1 AND targetId = '$id' LIMIT 1").use { rs ->
            if (!rs.next()) null else RecommendationExclusion(
                id = rs.getLong("id"), kind = rs.getInt("kind"), targetId = rs.getString("targetId"), label = rs.getString("label"),
                reason = rs.getInt("reason"), createdAt = rs.getLong("createdAt"), expiresAt = rs.getObject("expiresAt")?.let { (it as Number).toLong() },
            )
        }
    }

    /** What toggleDontPlay writes: the row it makes from the one there. */
    private fun dontPlay(id: String, at: Long) = write(ExclusionsViewModel.dontPlay(id, id, at, there(id)))

    /** ListenDao.liftDontPlay: its two statements, in one transaction there. */
    private fun lift(id: String) {
        exec(RecommendationSql.LIFT_DONT_PLAY.replace(":songId", "'$id'"))
        exec(RecommendationSql.LIFT_DONT_PLAY_BANNED.replace(":songId", "'$id'"))
    }

    private fun dontPlaySongs(): List<String> = db.createStatement().use { st ->
        st.executeQuery(RecommendationSql.DONT_PLAY_SONGS).use { rs -> buildList { while (rs.next()) add(rs.getString("id")) } }
    }

    @Test
    fun `Don't play set over Not this song keeps the ban, and lifted, the ban is back`() {
        write(RecommendationExclusion(kind = 1, targetId = "af1", label = "Africa", reason = REASON_BAN, createdAt = 1_000L))
        dontPlay("af1", at = 2_000L)
        assertEquals(REASON_DONT_PLAY_BANNED, there("af1")?.reason)
        assertEquals("not played anywhere while it is set", listOf("af1"), dontPlaySongs())

        lift("af1")
        assertEquals(emptyList<String>(), dontPlaySongs())
        assertEquals("out of the rows still, as Not this song asked", REASON_BAN, there("af1")?.reason)
        assertNull(there("af1")?.expiresAt)
    }

    @Test
    fun `Don't play on its own goes when lifted, and a ban beside it on another song stays`() {
        write(RecommendationExclusion(kind = 1, targetId = "ro2", label = "Rosanna", reason = REASON_BAN, createdAt = 1_000L))
        dontPlay("af1", at = 2_000L)
        assertEquals(REASON_DONT_PLAY, there("af1")?.reason)
        lift("af1")
        assertNull(there("af1"))
        assertEquals(REASON_BAN, there("ro2")?.reason)
        // Lifting a song that is only banned changes nothing.
        lift("ro2")
        assertEquals(REASON_BAN, there("ro2")?.reason)
    }

    @Test
    fun `a snooze is not made a ban, and Don't play twice keeps what it kept`() {
        write(RecommendationExclusion(kind = 1, targetId = "af1", label = "Africa", reason = REASON_SNOOZE, createdAt = 1_000L, expiresAt = 9_000L))
        dontPlay("af1", at = 2_000L)
        assertEquals(REASON_DONT_PLAY, there("af1")?.reason)
        lift("af1")
        assertNull(there("af1"))

        write(RecommendationExclusion(kind = 1, targetId = "af1", label = "Africa", reason = REASON_BAN, createdAt = 3_000L))
        dontPlay("af1", at = 4_000L)
        dontPlay("af1", at = 5_000L)
        assertEquals(REASON_DONT_PLAY_BANNED, there("af1")?.reason)
    }

    @Test
    fun `everywhere Don't play is read, set over a ban counts the same`() {
        assertTrue(ExclusionsViewModel.isDontPlay(REASON_DONT_PLAY))
        assertTrue(ExclusionsViewModel.isDontPlay(REASON_DONT_PLAY_BANNED))
        assertFalse(ExclusionsViewModel.isDontPlay(REASON_BAN))
        // Not this song from a card never takes its place, and Don't play may.
        assertFalse(ExclusionsViewModel.mayReplace(REASON_DONT_PLAY_BANNED, REASON_BAN))
        assertTrue(ExclusionsViewModel.mayReplace(REASON_BAN, REASON_DONT_PLAY))
        val sources = File("src/main/java/com/dd3boh/outertune")
        fun code(path: String) = File(sources, path).readText()
        assertTrue("ExclusionsViewModel.isDontPlay(it.reason)" in code("playback/QueueFit.kt"))
        assertTrue("dontPlayOn && active.any { ExclusionsViewModel.isDontPlay(it.reason) }" in code("ui/screens/settings/ExclusionsSettings.kt"))
        assertTrue("ExclusionsViewModel.REASON_DONT_PLAY, ExclusionsViewModel.REASON_DONT_PLAY_BANNED ->" in code("ui/screens/settings/ExclusionsSettings.kt"))
        // Lifted from Exclusions too, the ban is back rather than the row gone.
        assertTrue("if (exclusion.reason == REASON_DONT_PLAY_BANNED) insertExclusion(exclusion.copy(reason = REASON_BAN))" in code("viewmodels/ExclusionsViewModel.kt"))
        // The menu reads the row there inside the transaction it writes in.
        assertTrue("exclusion(ExclusionsViewModel.KIND_SONG, song.id)" in code("ui/menu/DontPlaySong.kt").substringAfter("database.transaction {"))
    }
}
