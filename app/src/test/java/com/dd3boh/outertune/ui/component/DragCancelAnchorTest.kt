/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A drag the system calls off (the screen going off mid-drag) drops the sheet to its strip, and
 * the sheet now counts itself collapsed too. A bare snap moved it without telling its anchor, so
 * the queue, down on screen, was kept as left open and came back open after a restart. This reads
 * the source, as ScreenBackHandlerTest does.
 */
class DragCancelAnchorTest {

    @Test
    fun `a cancelled drag records where the sheet lands`() {
        val sheet = File("src/main/java/com/dd3boh/outertune/ui/component/BottomSheet.kt").readText()
        val cancel = sheet.substringAfter("onDragCancel = {", missingDelimiterValue = "").substringBefore("},")
        assertTrue("onDragCancel could not be found", cancel.isNotEmpty())
        assertTrue(cancel, "state.collapse(snap())" in cancel)
        assertFalse(cancel, "snapTo(" in cancel)
    }
}
