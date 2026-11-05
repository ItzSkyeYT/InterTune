/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Add to queue's list of queues numbers them from the newest down. It used one counter outside the
 * list's items, counted down inside them: every time the list drew its items again the count carried
 * on below zero, and the same sheet read "6. Roygbiv Mix" and then "0. Roygbiv Mix", "-1. ..."
 * (seen on a Pixel 5, 31 Oct 2026). A number has to come from the item's own place in the list.
 */
class QueuePickerNumbersTest {

    private val dialog = File("src/main/java/com/dd3boh/outertune/ui/dialog/QueueDialogs.kt").readText()

    @Test
    fun `a queue's number comes from its place in the list, not from a counter the items change`() {
        assertFalse("no counter counted down by the items", Regex("""number\s*=\s*\w+\s*--""").containsMatchIn(dialog))
        assertTrue("the items are numbered by their index", dialog.contains("itemsIndexed(queues)"))
        assertTrue("the newest queue gets the highest number", dialog.contains("number = queues.size - i"))
    }
}
