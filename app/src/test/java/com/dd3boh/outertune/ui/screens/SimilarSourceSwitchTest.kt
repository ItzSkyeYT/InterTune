/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens

import com.dd3boh.outertune.constants.SimilarSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The switch on the Last.fm card, in setup and in the catch-up screen, must never silently turn a
 * stored "Last.fm only" choice into "Both". It can only show on or off, so what it writes when
 * turned back on has to come from whatever was really stored before, not a fixed value.
 */
class SimilarSourceSwitchTest {

    @Test
    fun `turning it off always writes YouTube`() {
        assertEquals(SimilarSource.YOUTUBE, similarSourceForSwitch(useLastFm = false, restoreTo = SimilarSource.LASTFM))
        assertEquals(SimilarSource.YOUTUBE, similarSourceForSwitch(useLastFm = false, restoreTo = SimilarSource.BOTH))
    }

    @Test
    fun `turning it back on restores a Last-fm-only choice instead of downgrading to Both`() {
        assertEquals(SimilarSource.LASTFM, similarSourceForSwitch(useLastFm = true, restoreTo = SimilarSource.LASTFM))
    }

    @Test
    fun `turning it back on keeps Both when that is what was stored`() {
        assertEquals(SimilarSource.BOTH, similarSourceForSwitch(useLastFm = true, restoreTo = SimilarSource.BOTH))
    }

    @Test
    fun `a first yes with nothing stored yet still means Both`() {
        // Never asked: both preferences are unset, so SimilarSources.stored reads as YOUTUBE, and
        // nextRestoreTo must leave the untouched default (BOTH) alone rather than overwrite it.
        val restoreTo = nextRestoreTo(previous = SimilarSource.BOTH, stored = null, oldSwitch = null)
        assertEquals(SimilarSource.BOTH, similarSourceForSwitch(useLastFm = true, restoreTo = restoreTo))
    }

    @Test
    fun `Last-fm stored, switch off then on gives Last-fm back, not Both`() {
        var restoreTo = SimilarSource.BOTH
        restoreTo = nextRestoreTo(restoreTo, stored = "LASTFM", oldSwitch = null)
        val offValue = similarSourceForSwitch(useLastFm = false, restoreTo = restoreTo)
        assertEquals(SimilarSource.YOUTUBE, offValue)

        // The switch's own off write feeds back in as `stored`; nextRestoreTo must not let that
        // erase the Last.fm-only choice it is tracking.
        restoreTo = nextRestoreTo(restoreTo, stored = offValue.name, oldSwitch = null)
        assertEquals(SimilarSource.LASTFM, similarSourceForSwitch(useLastFm = true, restoreTo = restoreTo))
    }

    @Test
    fun `the old switch alone, with no new key, also comes back as Last-fm after off then on`() {
        var restoreTo = SimilarSource.BOTH
        // The old boolean switch was on and the new key was never written: SimilarSources.stored
        // reads this as LASTFM.
        restoreTo = nextRestoreTo(restoreTo, stored = null, oldSwitch = true)
        val offValue = similarSourceForSwitch(useLastFm = false, restoreTo = restoreTo)
        assertEquals(SimilarSource.YOUTUBE, offValue)

        restoreTo = nextRestoreTo(restoreTo, stored = offValue.name, oldSwitch = true)
        assertEquals(SimilarSource.LASTFM, similarSourceForSwitch(useLastFm = true, restoreTo = restoreTo))
    }
}
