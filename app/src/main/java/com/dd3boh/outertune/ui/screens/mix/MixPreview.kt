/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.edit.TransitionSpec
import kotlinx.coroutines.flow.StateFlow

/**
 * What the editor hears: the change as it will play, from a bar of the canvas, looping over the
 * canvas while [loop] is on. The editor hands it every edit as a whole spec ([update]); the newest
 * is rendered and heard without a restart (EnginePreview).
 *
 * Positions are bars of the change, as on the canvas. [position] is what is being heard, for the
 * playhead, asked on each frame drawn while it plays.
 */
interface MixPreview {
    val playing: StateFlow<Boolean>

    /** The bar being heard now; meaningful while [playing]. */
    fun position(): Double

    /** The spec to play and the stretch of bars to loop over: every block reads the newest. */
    fun update(spec: TransitionSpec, from: Double, to: Double)

    fun play(fromU: Double)

    fun stop()

    var loop: Boolean

    /** A song alone with a click on each beat and a higher one on each 1 (Fix the beat), or null for the change. */
    fun click(side: Side?)

    /** Plays only [id] of its song, the lane's own level kept (Listen alone); null for everything. */
    fun solo(id: LaneId?)

    fun release()
}
