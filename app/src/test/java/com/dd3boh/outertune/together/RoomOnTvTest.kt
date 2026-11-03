/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the TV's room card says of a room: its name, how many are in it, and who. */
class RoomOnTvTest {
    private fun member(name: String, owner: Boolean = false, leader: Boolean = false) = TogetherSession.Member("key-$name", name, owner, leader)

    private val title = { owner: String? -> if (owner == null) "Listening room" else "$owner's room" }

    @Test
    fun `an owner is first in its own room, then the others as they were heard`() {
        val names = RoomOnTv.namesOf("Mel", iOwn = true, others = listOf(member("Ann"), member("Jo")))
        assertEquals(listOf("Mel", "Ann", "Jo"), names)
    }

    @Test
    fun `a guest lists the owner first, and itself last`() {
        val names = RoomOnTv.namesOf("Jo", iOwn = false, others = listOf(member("Ann"), member("Mel", owner = true)))
        assertEquals(listOf("Mel", "Ann", "Jo"), names)
    }

    @Test
    fun `the room is called after its owner, and has a plain name while the owner is not known`() {
        assertEquals("Mel", RoomOnTv.ownerName("Mel", iOwn = true, others = emptyList()))
        assertEquals("Mel", RoomOnTv.ownerName("Jo", iOwn = false, others = listOf(member("Ann"), member("Mel", owner = true))))
        assertNull(RoomOnTv.ownerName("Jo", iOwn = false, others = listOf(member("Ann"))))
        assertNull(RoomOnTv.ownerName("Jo", iOwn = false, others = listOf(member("  ", owner = true))))
    }

    @Test
    fun `the card counts everyone, this phone included, and carries the code`() {
        val status = TogetherSession.Status(members = listOf(member("Ann"), member("Jo")))
        val card = RoomOnTv.card("Mel", iOwn = true, status = status, qr = "data:image/png;base64,AAAA", title = title)
        assertEquals("Mel's room", card.name)
        assertEquals(3, card.count)
        assertEquals(listOf("M", "A", "J"), card.people)
        assertEquals("data:image/png;base64,AAAA", card.qr)
    }

    @Test
    fun `alone in the room, the card says one is in it`() {
        val card = RoomOnTv.card("Mel", iOwn = true, status = TogetherSession.Status(), qr = null, title = title)
        assertEquals(1, card.count)
        assertEquals(listOf("M"), card.people)
        assertNull(card.qr)
    }
}
