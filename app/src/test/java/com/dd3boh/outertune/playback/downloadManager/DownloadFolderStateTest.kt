/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the download folder stands (11 Nov 2026): not set, open, or saved and not opened. The last
 * one is what his phone had for days, a folder whose permission was gone, and said nothing.
 */
class DownloadFolderStateTest {

    @Test
    fun `no folder saved is not set, whatever opened`() {
        assertEquals(DownloadFolderState.NOT_SET, DownloadFolderState.of(pathSaved = false, opened = false))
        assertEquals(DownloadFolderState.NOT_SET, DownloadFolderState.of(pathSaved = false, opened = true))
    }

    @Test
    fun `a folder saved that opened is open`() {
        assertEquals(DownloadFolderState.OPEN, DownloadFolderState.of(pathSaved = true, opened = true))
    }

    @Test
    fun `a folder saved that did not open cannot be opened`() {
        assertEquals(DownloadFolderState.CANNOT_OPEN, DownloadFolderState.of(pathSaved = true, opened = false))
    }
}
