/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.pill

import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.SongPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Auto did between two songs the last time they were heard, so a pill can name it after one
 * listen (DESIGN.md 2.4: a pair says Auto until it has been heard or prepared).
 */
class HeardChangesTest {

    private val ab = SongPair("a", "b")
    private val bc = SongPair("b", "c")

    @Test
    fun `a change heard is named on the pill the next time`() {
        val heard = HeardChanges()
        assertNull(heard.auto(ab))
        assertTrue(heard.put(ab, AutoChoice.Mixed(Preset.FADE, PillLength.bars(8))))
        assertEquals(AutoChoice.Mixed(Preset.FADE, PillLength.bars(8)), heard.auto(ab))
        val pill = Pills.of(PillSong("a", null, 200_000), PillSong("b", null, 200_000), null, null, heard)
        assertEquals(Pill.Move(Preset.FADE, PillLength.bars(8)), pill)
    }

    @Test
    fun `only what belongs to the pair is kept, not a moment's reason`() {
        val heard = HeardChanges()
        assertTrue(heard.put(ab, AutoChoice.Plain(PlainReason.NO_FIT)))
        assertTrue(heard.put(bc, AutoChoice.Plain(PlainReason.FORMATS)))
        // Casting, a sleep timer, a read that came late: true of that moment only.
        for (reason in listOf(PlainReason.CASTING, PlainReason.SLEEP_TIMER, PlainReason.NOT_READY, PlainReason.NO_TIME, PlainReason.FROM_NEXT)) {
            assertFalse(reason.name, heard.put(SongPair("x", "y"), AutoChoice.Plain(reason)))
        }
        assertNull(heard.auto(SongPair("x", "y")))
        // A later mix of a pair that went plain once replaces it.
        assertTrue(heard.put(ab, AutoChoice.Mixed(Preset.BLEND, PillLength.seconds(6.0))))
        assertEquals(AutoChoice.Mixed(Preset.BLEND, PillLength.seconds(6.0)), heard.auto(ab))
    }

    @Test
    fun `the oldest pairs go first once it is full`() {
        val heard = HeardChanges(max = 2)
        heard.put(ab, AutoChoice.Mixed(Preset.CUT, PillLength.NONE))
        heard.put(bc, AutoChoice.Mixed(Preset.RISE, PillLength.seconds(10.0)))
        heard.put(SongPair("c", "d"), AutoChoice.Mixed(Preset.FADE, PillLength.bars(4)))
        assertNull(heard.auto(ab))
        assertEquals(2, heard.size)
    }

    @Test
    fun `it is kept as JSON, and a file it cannot read is an empty memory`() {
        val heard = HeardChanges()
        heard.put(ab, AutoChoice.Mixed(Preset.FADE, PillLength.bars(8)))
        heard.put(bc, AutoChoice.Plain(PlainReason.NO_FIT))
        heard.put(SongPair("c", "d"), AutoChoice.Mixed(Preset.BLEND, PillLength.seconds(6.4)))
        val back = HeardChanges.decode(heard.encode())
        assertEquals(heard.auto(ab), back.auto(ab))
        assertEquals(heard.auto(bc), back.auto(bc))
        assertEquals(AutoChoice.Mixed(Preset.BLEND, PillLength.seconds(6.0)), back.auto(SongPair("c", "d")))
        assertEquals(0, HeardChanges.decode("not json").size)
        assertEquals(0, HeardChanges.decode("""{"v":99,"pairs":[]}""").size)
        // An entry this build cannot read is left out; the rest stay.
        val mixed = HeardChanges.decode("""{"v":1,"pairs":[{"from":"a","to":"b","preset":"nope","bars":8,"seconds":0},{"from":"b","to":"c","plain":"no_fit"}]}""")
        assertNull(mixed.auto(ab))
        assertEquals(AutoChoice.Plain(PlainReason.NO_FIT), mixed.auto(bc))
    }
}
