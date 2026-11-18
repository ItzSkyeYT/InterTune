/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.ui.component.ReportOrder
import com.dd3boh.outertune.ui.component.reportOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Report a problem picks the latest session with a problem so that nobody sends a session with nothing in it (#97),
 * but only one of the build installed: a session of an older build went out stamped with the old version, on the
 * new build (15 Nov 2026). Then "Now" stays picked, and the older fault is listed after it, marked.
 */
class ReportOrderTest {

    /** A session: its name, whether something went wrong in it, and the version that wrote it. */
    private data class Log(val name: String, val problem: Boolean, val version: String)

    private val installed = "0.12.11-preview.20261115.1744"
    private val older = "0.12.9-preview.20261111.0021"

    private fun order(vararg logs: Log): ReportOrder<Log> = reportOrder(logs.toList(), { it.problem }, { it.version == installed })

    @Test
    fun `a fault of the build installed comes first and is picked, as it always did`() {
        val o = order(Log("now", false, installed), Log("18:30", false, installed), Log("16:19", true, installed), Log("16:02", true, installed))
        assertEquals(listOf("16:19", "now", "18:30", "16:02"), o.items.map { it.name })
        assertEquals(0, o.marked)
        assertFalse(o.older)
    }

    @Test
    fun `a fault of an older build leaves now first, with the fault listed right after it`() {
        // the testers' case: this run and the one before are of the new build and clean, the fault is of 0.12.9
        val o = order(Log("now", false, installed), Log("15 Nov 12:15", false, installed), Log("14 Nov 23:40", true, older), Log("11 Nov 14:11", false, older))
        assertEquals(listOf("now", "14 Nov 23:40", "15 Nov 12:15", "11 Nov 14:11"), o.items.map { it.name })
        assertEquals(1, o.marked)
        assertTrue(o.older)
        // the older fault is marked and is not the one picked, which is the first: "Now"
        assertEquals("now", o.items[0].name)
        assertEquals(older, o.items[o.marked].version)
    }

    @Test
    fun `a fault of this run is first and not older`() {
        val o = order(Log("now", true, installed), Log("18:30", true, older))
        assertEquals(listOf("now", "18:30"), o.items.map { it.name })
        assertEquals(0, o.marked)
        assertFalse(o.older)
    }

    @Test
    fun `only the newest fault is marked, the build installed or not`() {
        // a newer fault of the build installed beats an older one of an older build, which stays where it was
        val o = order(Log("now", false, installed), Log("12:15", true, installed), Log("14 Nov", true, older))
        assertEquals(listOf("12:15", "now", "14 Nov"), o.items.map { it.name })
        assertEquals(0, o.marked)
        assertFalse(o.older)
        // and the newest fault being of an older build leaves a still older fault of the build installed alone
        val p = order(Log("now", false, installed), Log("14 Nov", true, older), Log("10 Nov", true, installed))
        assertEquals(listOf("now", "14 Nov", "10 Nov"), p.items.map { it.name })
        assertEquals(1, p.marked)
        assertTrue(p.older)
    }

    @Test
    fun `no fault anywhere leaves the order as it was and marks nothing`() {
        val o = order(Log("now", false, installed), Log("18:30", false, older))
        assertEquals(listOf("now", "18:30"), o.items.map { it.name })
        assertEquals(-1, o.marked)
        assertFalse(o.older)
    }

    @Test
    fun `a list of this run alone`() {
        val o = order(Log("now", false, installed))
        assertEquals(listOf("now"), o.items.map { it.name })
        assertEquals(-1, o.marked)
    }

    @Test
    fun `the dialog compares the version with the build installed, and says plainly what the older one is`() {
        val dialog = File("src/main/java/com/dd3boh/outertune/ui/component/ProblemReportDialog.kt").readText()
        assertTrue("{ it.version == BuildConfig.VERSION_NAME }" in dialog)
        assertTrue("c.marked(order.older)" in dialog)
        val label = dialog.substringAfter("if (choice.olderProblem) {").substringBefore("}")
        assertTrue("R.string.problem_report_older_problem" in label)
        assertTrue("MaterialTheme.colorScheme.error" in label)
        for (dir in listOf("values", "values-fr")) {
            val strings = File("src/main/res/$dir/strings-ot.xml").readText()
            assertTrue("$dir has no problem_report_older_problem", """name="problem_report_older_problem"""" in strings)
        }
    }
}
