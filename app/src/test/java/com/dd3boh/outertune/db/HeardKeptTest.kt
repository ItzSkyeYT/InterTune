/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import com.dd3boh.outertune.engine.EngineSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Discover's heard set is read once and kept until a table it comes from changes (KeptUntilChanged,
 * MusicDatabase.heardKept): about 0.5 to 1 s saved at each Home build or Discover pick on a big
 * library, and never a song heard since offered as new, since any change to those tables drops it.
 */
class HeardKeptTest {

    private class Value(val n: Int)

    private class Watch {
        var calls = 0
        var onChange: (() -> Unit)? = null
        val watch: (() -> Unit) -> Unit = { calls++; onChange = it }
    }

    @Test
    fun `read once, then kept`() {
        val w = Watch()
        val kept = KeptUntilChanged<Value>(w.watch)
        var reads = 0
        val first = kept.get { Value(++reads) }
        val second = kept.get { Value(++reads) }
        assertSame(first, second)
        assertEquals(1, reads)
        assertEquals("the tables are watched once", 1, w.calls)
    }

    @Test
    fun `a change drops what is kept`() {
        val w = Watch()
        val kept = KeptUntilChanged<Value>(w.watch)
        var reads = 0
        kept.get { Value(++reads) }
        w.onChange!!()
        val after = kept.get { Value(++reads) }
        assertEquals(2, after.n)
        assertSame(after, kept.get { Value(++reads) })
        assertEquals(2, reads)
    }

    @Test
    fun `a change during a read leaves that read unkept`() {
        val w = Watch()
        val kept = KeptUntilChanged<Value>(w.watch)
        var reads = 0
        val during = kept.get { w.onChange!!(); Value(++reads) }
        assertEquals(1, during.n)
        val next = kept.get { Value(++reads) }
        assertEquals("read again: the first may have been taken before the change", 2, next.n)
        assertSame(next, kept.get { Value(++reads) })
    }

    @Test
    fun `without a watch nothing is kept`() {
        val kept = KeptUntilChanged<Value> { throw IllegalArgumentException("no such table") }
        var reads = 0
        kept.get { Value(++reads) }
        kept.get { Value(++reads) }
        assertEquals("each get reads, as before the keeping", 2, reads)
    }

    @Test
    fun `every table the query reads is watched`() {
        val read = Regex("""\b(?:FROM|JOIN)\s+(\w+)""", RegexOption.IGNORE_CASE)
            .findAll(EngineSql.KNOWN_SONGS).map { it.groupValues[1] }.toSet()
        assertEquals(read, EngineSql.KNOWN_SONGS_TABLES.toSet())
    }

    @Test
    fun `the database watches them through Room's tracker, and Discover reads through it`() {
        val sources = File("src/main/java/com/dd3boh/outertune")
        val db = File(sources, "db/MusicDatabase.kt").readText()
        assertTrue("invalidationTracker.addObserver(" in db)
        assertTrue("InvalidationTracker.Observer(com.dd3boh.outertune.engine.EngineSql.KNOWN_SONGS_TABLES)" in db)
        val loader = File(sources, "engine/EngineLoader.kt").readText()
        assertTrue("fun known(database: MusicDatabase): HeardSet = database.heardKept.get { HeardSet.of(database.knownSongs()) }" in loader)
    }
}
