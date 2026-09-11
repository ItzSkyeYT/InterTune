/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentlyShownTest {
    private val pool = listOf("a", "b", "c", "d", "e", "f")

    @Test
    fun `a row that has shown nothing keeps the pool's order`() {
        assertEquals(pool, RecentlyShown().order("row", pool) { it })
    }

    @Test
    fun `what was shown goes last, the longest ago first, and the rest keep their order`() {
        val memory = RecentlyShown()
        memory.note("row", listOf("a", "b"))
        memory.note("row", listOf("c"))
        assertEquals(listOf("d", "e", "f", "a", "b", "c"), memory.order("row", pool) { it })
    }

    @Test
    fun `a pool no bigger than the row cycles through itself`() {
        val memory = RecentlyShown()
        val small = listOf("a", "b", "c")
        memory.note("row", small)
        assertEquals(small, memory.order("row", small) { it })
        memory.note("row", listOf("b"))
        assertEquals(listOf("a", "c", "b"), memory.order("row", small) { it })
    }

    @Test
    fun `only the last few fillings are remembered`() {
        val memory = RecentlyShown(rowsKept = 2)
        memory.note("row", listOf("a"))
        memory.note("row", listOf("b"))
        memory.note("row", listOf("c"))
        assertEquals(listOf("a", "d", "e", "f", "b", "c"), memory.order("row", pool) { it })
    }

    @Test
    fun `rows do not share a memory`() {
        val memory = RecentlyShown()
        memory.note("one", listOf("a"))
        assertEquals(pool, memory.order("two", pool) { it })
    }
}
