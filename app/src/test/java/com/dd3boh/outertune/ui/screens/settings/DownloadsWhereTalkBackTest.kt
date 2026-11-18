/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Settings > Downloads, Where downloads go: TalkBack read both choices the same, chosen or not, since the row was only
 * clickable and the dot, with no tap of its own, says nothing (the 0.13 accessibility read, 18 Nov 2026). Each row is
 * now a radio button that is selected or not, the two in one group, as the report dialog's logs are.
 */
class DownloadsWhereTalkBackTest {

    private val simple = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/fragments/DownloadsSimple.kt").readText()

    @Test
    fun `each choice is a radio button that says whether it is the one chosen`() {
        val choice = simple.substringAfter("private fun WhereChoice(").substringBefore("\n}\n")
        assertTrue(choice, ".selectable(selected = selected, onClick = onClick, role = Role.RadioButton)" in choice)
        assertFalse("a plain tap says nothing of the choice", ".clickable(" in choice)
        // the dot stays as it was drawn, with no tap and no voice of its own
        assertTrue(choice, "RadioButton(selected = selected, onClick = null)" in choice)
        // where the tap was, so the row keeps its place, size and ripple
        assertTrue(choice, ".fillMaxWidth()\n            .selectable(" in choice)
        assertTrue(choice, "role = Role.RadioButton)\n            .padding(horizontal = 16.dp, vertical = 12.dp)" in choice)
    }

    @Test
    fun `the two are one group, stacked as the two items were, and the folder's warning stays under them`() {
        val dialog = simple.substringAfter("internal fun WhereDownloadsGoDialog(").substringBefore("\n}\n")
        val group = dialog.substringAfter("Column(Modifier.selectableGroup()) {")
        assertTrue("no group", group != dialog)
        assertEquals(2, Regex("""WhereChoice\(""").findAll(group.substringBefore("if (refused)")).count())
        assertEquals("the two in one item of the list", 1, Regex("""item \{\n\s+Column\(Modifier\.selectableGroup\(\)\)""").findAll(dialog).count())
        assertTrue(group.indexOf("onClick = onInside") in 0 until group.indexOf("onClick = onFolder"))
        assertTrue(dialog.indexOf("if (refused) {") > dialog.lastIndexOf("WhereChoice("))
        // ProblemReportDialog's logs, the way followed here
        val logs = File("src/main/java/com/dd3boh/outertune/ui/component/ProblemReportDialog.kt").readText()
        assertTrue("Column(Modifier.selectableGroup()) {" in logs)
        assertTrue(".selectable(selected = selected, onClick = { onPick(i) }, role = Role.RadioButton)" in logs)
    }
}
