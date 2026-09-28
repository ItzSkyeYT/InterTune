/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class DownloadScanRescanTest {

    private fun t(epochSecond: Long) = Instant.ofEpochSecond(epochSecond).atZone(ZoneOffset.UTC).toLocalDateTime()

    // mergeRescanResult

    @Test
    fun `a song that finished mid-walk is kept, not dropped by the stale snapshot`() {
        val snapshot = emptyMap<String, LocalDateTime>() // the walk started before it finished
        val live = mapOf("finished" to t(100)) // the listener recorded it in the meantime
        val merged = mergeRescanResult(snapshot, live, touchedIds = setOf("finished"))
        assertEquals(t(100), merged["finished"])
    }

    @Test
    fun `a song removed mid-walk (Clear all downloads included) is not put back with its old date`() {
        val snapshot = mapOf("removed" to t(50)) // the stale snapshot still has the old row
        val live = emptyMap<String, LocalDateTime>() // the listener already dropped it
        val merged = mergeRescanResult(snapshot, live, touchedIds = setOf("removed"))
        assertTrue(!merged.containsKey("removed"))
    }

    @Test
    fun `an id the listener never touched keeps the snapshot's value`() {
        val snapshot = mapOf("untouched" to t(10))
        val live = mapOf("untouched" to t(20)) // some unrelated later value, must not leak in
        val merged = mergeRescanResult(snapshot, live, touchedIds = emptySet())
        assertEquals(t(10), merged["untouched"])
    }

    // applyRescanResult

    @Test
    fun `a listener change landing while the merge reads the touched set is not overwritten`() {
        val downloads = MutableStateFlow<Map<String, LocalDateTime>>(mapOf("z" to t(1)))
        // Insertion-ordered, so the first read of the set sees "other" alone.
        val backing = linkedSetOf("other")
        var raced = false
        // The first read of the set stands in for the listener landing between the merge's read
        // and its write: it records "z" and moves its value on, as the listener does.
        val touched = object : AbstractMutableSet<String>() {
            override val size get() = backing.size
            override fun add(element: String) = backing.add(element)
            override fun iterator(): MutableIterator<String> {
                if (!raced) {
                    raced = true
                    backing.add("z")
                    downloads.update { it + ("z" to t(200)) }
                }
                return backing.iterator()
            }
        }

        applyRescanResult(downloads, mapOf("z" to t(1)), touched)

        assertEquals(t(200), downloads.value["z"])
        assertTrue(backing.isEmpty())
    }
}
