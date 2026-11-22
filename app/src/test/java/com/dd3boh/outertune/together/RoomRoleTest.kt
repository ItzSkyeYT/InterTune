package com.dd3boh.outertune.together

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomRoleTest {
    @Test
    fun whoeverIsInControlSaysSo() {
        assertEquals(RoomRole.IN_CONTROL, RoomRole.of(owner = true, leading = true, hostAway = false, waitingForHost = false))
        // A guest handed control: the same line.
        assertEquals(RoomRole.IN_CONTROL, RoomRole.of(owner = false, leading = true, hostAway = false, waitingForHost = false))
    }

    @Test
    fun theOwnerWhoGaveControlAwayCanTakeItBack() {
        assertEquals(RoomRole.OWNER_GAVE, RoomRole.of(owner = true, leading = false, hostAway = false, waitingForHost = false))
    }

    @Test
    fun aGuestAddsSongsAndPausesForItself() {
        assertEquals(RoomRole.GUEST, RoomRole.of(owner = false, leading = false, hostAway = false, waitingForHost = false))
    }

    @Test
    fun nothingToSayWhileTheHostIsAwayOrNotHeardYet() {
        assertNull(RoomRole.of(owner = false, leading = false, hostAway = true, waitingForHost = false))
        assertNull(RoomRole.of(owner = false, leading = false, hostAway = false, waitingForHost = true))
    }
}
