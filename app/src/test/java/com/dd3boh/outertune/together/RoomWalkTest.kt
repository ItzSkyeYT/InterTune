/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import com.dd3boh.outertune.ui.dialog.WalkStop
import com.dd3boh.outertune.ui.dialog.walkStops
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The walk over the room's sheet after the tour's pages (RoomWalk): a stop for each part of the sheet drawn for this
 * phone's role, in the sheet's order, and none for a part that is not there.
 */
class RoomWalkTest {

    private fun main(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    @Test
    fun `only the parts drawn get a stop, in order`() {
        val tops = mapOf(WalkStop.ROLE to 10f, WalkStop.INVITE to 40f, WalkStop.PLAYING to 200f, WalkStop.PROPOSAL to 300f, WalkStop.PEOPLE to 300f, WalkStop.LEAVE to 600f)
        // No proposal waiting: its marks meet. Not the host: no settings marks at all.
        val feet = mapOf(WalkStop.ROLE to 30f, WalkStop.INVITE to 180f, WalkStop.PLAYING to 300f, WalkStop.PROPOSAL to 300f, WalkStop.PEOPLE to 420f, WalkStop.LEAVE to 650f)
        assertEquals(listOf(WalkStop.ROLE, WalkStop.INVITE, WalkStop.PLAYING, WalkStop.PEOPLE, WalkStop.LEAVE), walkStops(tops, feet))
    }

    @Test
    fun `every part of the room's page is marked from its top to its foot`() {
        val page = main("ui/dialog/TogetherDialog.kt").substringAfter("private fun RoomPage(").substringBefore("\n/** Two phones")
        for (stop in WalkStop.entries) {
            val from = page.indexOf("WalkFrom(WalkStop.${stop.name}")
            val to = page.indexOf("WalkTo(WalkStop.${stop.name})")
            assertTrue("$stop is not marked", from >= 0 && to > from)
        }
        // In the sheet's order, so the walk goes down the sheet.
        val order = WalkStop.entries.map { page.indexOf("WalkFrom(WalkStop.${it.name}") }
        assertEquals(order.sorted(), order)
        assertTrue("RoomWalkArea(owner = room.owner)" in main("ui/dialog/TogetherDialog.kt"))
    }

    @Test
    fun `Done on the tour goes on to the walk, Skip does not`() {
        val tour = main("ui/dialog/RoomTour.kt")
        assertTrue("if (RoomWalkAsk.areas > 0) RoomWalkAsk.wanted = true" in tour)
        val skip = tour.substringAfter("TextButton(\n                        onClick = onDismiss,").substringBefore("}")
        assertTrue("RoomWalkAsk" !in skip)
        assertTrue("BackHandler { walk.end() }" in main("ui/dialog/RoomWalk.kt"))
    }
}
