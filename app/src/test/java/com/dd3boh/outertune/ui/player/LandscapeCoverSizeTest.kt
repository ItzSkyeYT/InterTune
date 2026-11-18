/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.constants.PlayerHorizontalPadding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * On its side the player's cover filled all the height it was given, a good deal more than it takes
 * upright, where it has a 32dp margin on either side. It is now held to the size it has upright,
 * and nothing else in the landscape player moves for it.
 */
class LandscapeCoverSizeTest {

    private val player = File("src/main/java/com/dd3boh/outertune/ui/player/Player.kt").readText()
    private val thumbnail = File("src/main/java/com/dd3boh/outertune/ui/player/Thumbnail.kt").readText()

    /** The two-pane landscape branch of the player, from its condition to the tablet's branch after it. */
    private val landscapeBranch = player
        .substringAfter("orientation == Configuration.ORIENTATION_LANDSCAPE && !tabMode && wideScreen) {")
        .substringBefore("Two panes on a tablet")

    @Test
    fun `the cover is the size it has upright`() {
        // 411dp across upright, the cover is 347dp: the width less 32dp of margin on each side
        assertEquals(347f, landscapeCoverSide(411.dp).value, 0.01f)
        assertEquals(PlayerHorizontalPadding, 32.dp)
    }

    @Test
    fun `it follows the short side of the screen, as the upright cover follows the width`() {
        var short = 320
        while (short < 600) {
            assertEquals(short - 64f, landscapeCoverSide(short.dp).value, 0.01f)
            short += 8
        }
    }

    @Test
    fun `a window too small for any cover gives none rather than a negative size`() {
        assertEquals(0f, landscapeCoverSide(40.dp).value, 0.01f)
    }

    @Test
    fun `both ways the artwork is drawn on its side are held to it`() {
        // the plain cover and the strip of covers that swipes to skip
        assertEquals(2, Regex("""maxSide = coverSide""").findAll(landscapeBranch).count())
        assertTrue("coverSide = landscapeCoverSide(" in landscapeBranch)
    }

    @Test
    fun `the thumbnail keeps a cover to its limit, and to the room it has if that is less`() {
        assertTrue(Regex("""maxSide: Dp = Dp\.Unspecified""").containsMatchIn(thumbnail))
        // the limit goes on the box the square is drawn in, so the picture asked for is the size drawn
        val box = thumbnail.substringAfter("BoxWithConstraints(").substringBefore(") {")
        assertTrue(".sizeIn(maxWidth = maxSide, maxHeight = maxSide)" in box)
    }

    @Test
    fun `it keeps the margin it has upright at its left, as it has above and below`() {
        // 32dp when held to a size of its own; the 8dp that hugged the edge stays for the covers that are not
        assertTrue(Regex("""padding\(horizontal = if \(isLandscape && maxSide\.isUnspecified\) 8\.dp else PlayerHorizontalPadding\)""").containsMatchIn(thumbnail))
    }

    @Test
    fun `no control is moved for it`() {
        // the controls keep their column: the same width, and the cover still takes the rest of the row
        assertTrue(".width(controlsWidth)" in landscapeBranch)
        assertTrue(Regex("""landscapeControlsWidth\(\s+available = maxWidth,\s+playButton = playButtonSize,""").containsMatchIn(landscapeBranch))
        assertTrue(".weight(1f)" in landscapeBranch)
    }
}
