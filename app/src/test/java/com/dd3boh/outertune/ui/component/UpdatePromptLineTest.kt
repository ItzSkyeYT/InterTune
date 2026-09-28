/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * On F-Droid installs the update prompt's button goes to F-Droid, not to a download or an
 * install, so its first line must never talk about either. The dialog's title already names the
 * version and the F-Droid note already says where it comes from, so there is no line at all here,
 * regardless of download state.
 */
class UpdatePromptLineTest {

    @Test
    fun `an F-Droid install gets no first line, regardless of download state`() {
        assertNull(updatePromptLine(fromFdroid = true, ready = true, needsPermission = true))
        assertNull(updatePromptLine(fromFdroid = true, ready = false, needsPermission = false))
    }

    @Test
    fun `elsewhere, downloaded but not yet allowed to install asks for permission`() {
        assertEquals(
            UpdatePromptLine.NEEDS_PERMISSION,
            updatePromptLine(fromFdroid = false, ready = true, needsPermission = true),
        )
    }

    @Test
    fun `elsewhere, downloaded and permitted already says ready`() {
        assertEquals(
            UpdatePromptLine.READY,
            updatePromptLine(fromFdroid = false, ready = true, needsPermission = false),
        )
    }

    @Test
    fun `elsewhere, not yet downloaded says it will be`() {
        assertEquals(
            UpdatePromptLine.WILL_DOWNLOAD,
            updatePromptLine(fromFdroid = false, ready = false, needsPermission = false),
        )
    }
}
