/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import android.util.Log
import android.view.WindowManager

/** Recognition's, so that a report sent after a dropped tap is filed under it (ReportSettings.areaOfTag). */
private const val TAG = "RecognitionTile"

/**
 * Runs [show], a tile's TileService.showDialog, and gives false instead of Android's refusal when the panel's
 * window is gone (#257, #260).
 *
 * A question over the panel goes on a window Quick Settings lends the tile for that one tap, and takes back as
 * soon as its panel closes. A tap the app got to after that, a busy moment on a slow phone with the panel
 * swiped shut in between, asked for a window that was no longer there: "Unable to add window -- token ... is
 * not valid", and Android closed the app, music and all. Quick Settings drops a tap it can no longer hand over
 * in that state, and from Android 14 nothing can be opened from the tile once the window is taken back, so the
 * tap is dropped here too: the tile asks again on the next tap, with the panel open.
 *
 * @return whether the question is on screen.
 */
internal fun unlessPanelGone(show: () -> Unit): Boolean = try {
    show()
    true
} catch (e: WindowManager.BadTokenException) {
    Log.w(TAG, "Quick Settings took its window back before the tile's question could show", e)
    false
}
