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
 * Issue #34: the finger moves the thumb and the thumb moves the list, one way only. The old
 * scrollbar (LazyColumnScrollbar 2.2.0) worked the list's position out again from its layout on
 * every touch event and launched a scroll for each, a jump to the top of a row and then a scroll
 * on from there, and under our top padding its sums fed back on themselves. This reads the
 * source, comments left out, as ScreenBackHandlerTest does; ScrollbarGeometryTest and
 * ScrollbarOddRowsTest run the arithmetic.
 */
class ScrollbarFollowsFingerTest {

    private val scrollBar = File("src/main/java/com/dd3boh/outertune/ui/component/ScrollBar.kt").readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")

    /** The inside of the first block opened with [open] after [start], counted by brackets. */
    private fun block(start: String, open: Char = '{', close: Char = '}'): String {
        val at = scrollBar.indexOf(start)
        if (at < 0) return ""
        val from = scrollBar.indexOf(open, at)
        if (from < 0) return ""
        var depth = 0
        for (i in from until scrollBar.length) {
            when (scrollBar[i]) {
                open -> depth++
                close -> if (--depth == 0) return scrollBar.substring(from + 1, i)
            }
        }
        return ""
    }

    @Test
    fun `the scrollbar is our own and the library is gone`() {
        assertFalse("ScrollBar.kt still uses the library", "my.nanihadesuka" in scrollBar)
        assertFalse("the version catalog still lists it", "lazycolumnscrollbar" in File("../gradle/libs.versions.toml").readText())
        assertFalse("the app still depends on it", "lazycolumnscrollbar" in File("build.gradle.kts").readText())
    }

    @Test
    fun `a touch event moves the thumb and nothing else`() {
        val onDelta = block("rememberDraggableState")
        val moveBy = block("fun moveBy(")
        assertTrue("the drag callback could not be found", onDelta.isNotBlank() && moveBy.isNotBlank())
        for (body in listOf(onDelta, moveBy)) {
            assertFalse("a scroll is launched for each touch event", "launch" in body)
            assertFalse("the thumb is worked out again from the list while held", "layoutInfo" in body || "snapshot()" in body)
            assertFalse("the touch event scrolls the list itself", "scrollBy" in body || "scrollToItem" in body)
        }
    }

    @Test
    fun `one loop per press scrolls the list and never reads its position back`() {
        val follow = block("suspend fun follow(")
        assertTrue("the loop that scrolls the list could not be found", follow.isNotBlank())
        for (read in listOf("layoutInfo", "snapshot()", "firstVisibleItem")) {
            assertFalse("the loop reads the list's position ($read)", read in follow)
        }
        assertFalse("the loop launches more", "launch" in follow)
        assertFalse(
            "a jump to the top of a row, to be scrolled on from there",
            Regex("""scrollToItem\([^()]*,\s*0\s*\)""").containsMatchIn(scrollBar)
        )
    }

    @Test
    fun `while held, the thumb is drawn where the finger put it`() {
        assertTrue("the thumb is not drawn from the held position first", Regex("""offset\s*\{[^}]*held\?\.top\s*\?:""").containsMatchIn(scrollBar))
    }

    @Test
    fun `only the thumb takes a touch, so the rows keep the edge of the screen`() {
        // Before, a strip the height of the list took every touch along the edge, over the outer
        // part of each row's menu button and drag handle.
        val drags = Regex("""\.draggable\(""").findAll(scrollBar).toList()
        assertTrue("the drag could not be found", drags.isNotEmpty())
        for (drag in drags) {
            val chainStart = scrollBar.lastIndexOf("Box(", drag.range.first)
            val chain = scrollBar.substring(chainStart, drag.range.first)
            assertTrue("the drag is not on the thumb: $chain", "offset" in chain && "fillMaxHeight(thumbFrac)" in chain)
            assertFalse("the drag takes a strip the height of the list", "fillMaxHeight()" in chain)
        }
    }
}
