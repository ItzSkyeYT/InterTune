/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The transport bar's glass is drawn behind its buttons (dvd, 13 Nov 2026): the glass library draws a
 * glass's rim after whatever is inside it, so with the glass on the row of buttons itself the rim
 * crossed the play button, which is as tall as the bar, at its top and bottom edges. The glass is a
 * layer of its own, the row's size and in its place, drawn before the row. Read from the source.
 */
class TransportGlassRimTest {
    private val player = File("src/main/java/com/dd3boh/outertune/ui/player/Player.kt").readText()

    /** From the bar's comment to the Row of its buttons. */
    private val bar = player.substringAfter("// The bar's glass is a layer of its own behind the buttons").substringBefore("val shuffleModeEnabled")

    @Test
    fun `the bar's glass is a layer the row's size, drawn before the row of buttons`() {
        assertTrue("the comment that says why is there", bar.isNotEmpty() && bar.length < player.length)
        val layer = bar.indexOf(".matchParentSize()")
        val row = bar.indexOf("Row(")
        assertTrue("the glass layer, then the row", layer in 0 until row)
        // Both kinds of glass, moving and still, are on that layer, before the row.
        assertTrue(bar.indexOf("Modifier.drawBackdrop(") in layer until row)
        assertTrue(bar.indexOf("Modifier.stillGlass { RoundedCornerShape(32.dp) }") in layer until row)
        // The row itself carries no glass, only its width.
        assertTrue(bar.substring(row).contains("modifier = Modifier.fillMaxWidth()"))
    }

    @Test
    fun `no row of the player has the bar's glass as its own modifier any more`() {
        // The old shape: a Row whose modifier chain ends in the glass, so its rim was drawn over its buttons.
        assertFalse(Regex("""Row\(\s*verticalAlignment = Alignment\.CenterVertically,\s*modifier = Modifier\s*\.fillMaxWidth\(\)\s*\.padding\(horizontal = hPadding\)\s*\.then\(""").containsMatchIn(player))
    }
}
