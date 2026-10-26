/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #46: the list of queues crashed with "Key ... was already used" while it scrolled to the
 * queue playing. It keys each queue by its id, and the board can be built twice at once on the
 * same shared list (each clears it, then adds the saved queues), which leaves every queue in twice.
 * The board keeps one queue per id, and the list never shows two with the same id.
 */
class QueueIdsStayUniqueTest {
    private val board = File("src/main/java/com/dd3boh/outertune/playback/QueueBoard.kt").readText()
    private val queue = File("src/main/java/com/dd3boh/outertune/ui/player/Queue.kt").readText()

    @Test
    fun `the board keeps one queue per id after loading`() {
        val init = board.substringAfter("    init {").substringBefore("masterIndex = masterQueues.size - 1")
        assertTrue(init.contains("masterQueues.retainAll { seen.add(it.id) }"))
    }

    @Test
    fun `the list of queues shows each id once`() {
        assertTrue(queue.contains("mutableQueues.addAll(qb.getAllQueues().distinctBy { it.id })"))
        assertTrue(!queue.contains("mutableQueues.addAll(qb.getAllQueues())\n"))
    }
}
