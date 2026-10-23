/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.menu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "Links you share" as Share and Settings read it (decided on 23 Oct 2026): nothing is chosen at
 * first, the first share that has a page asks which link to send, and the pick is the setting from
 * then on, so the shares after it go straight through and the row in Settings shows it. Backing
 * out of the question shares nothing and keeps nothing.
 *
 * What the setting makes of each share is ShareLinksTest's. This reads the source for the part
 * Compose draws, as the other tests of screens do.
 */
class ShareLinkChoiceTest {

    private val main = File("src/main/java/com/dd3boh/outertune")
    private fun source(path: String) = File(main, path).readText()
    private val kotlin get() = main.walkTopDown().filter { it.extension == "kt" }
    private val menu = source("ui/menu/ShareLinkMenu.kt")

    @Test
    fun `Share and the row in Settings read the setting with the same start`() {
        val reads = kotlin.flatMap { file ->
            Regex("""rememberEnumPreference\(ShareLinkKindKey, defaultValue = ([\w.]+)\)""").findAll(file.readText()).map { file.name to it.groupValues[1] }
        }.toList()
        assertTrue("$reads", reads.map { it.first }.containsAll(listOf("ShareLinkMenu.kt", "InterfaceFrag.kt")))
        // A reader with a start of its own would show one thing in Settings and do another at Share.
        assertTrue("$reads", reads.all { it.second == "ShareLinks.START" })
    }

    @Test
    fun `the pick in the question is kept before anything goes out, and backing out keeps nothing`() {
        val question = menu.substringAfter("asking?.let { (link, done) ->").substringBefore("\n    }\n")
        assertTrue(question, "ShareLinkChoice(link, onDismiss = { asking = null }) { kind ->" in question)
        val picked = question.substringAfter("{ kind ->")
        val kept = picked.indexOf("chosen = kind")
        assertTrue("the pick is not kept", kept >= 0)
        assertTrue("the menu closes before the pick is kept", kept < picked.indexOf("done()"))
        assertTrue("the share goes before the pick is kept", kept < picked.indexOf("context.shareText(ShareLinks.text(link, kind))"))
        // The setting is written there and nowhere else in Share.
        assertEquals(1, Regex("""\bchosen = """).findAll(menu).count())
    }

    @Test
    fun `the question offers the two links and says where the pick is changed`() {
        val choice = menu.substringAfter("private fun ShareLinkChoice(")
        assertTrue("onPick(ShareLinkKind.YOUTUBE_MUSIC)" in choice)
        assertTrue("onPick(ShareLinkKind.PAGE)" in choice)
        assertFalse("ShareLinkKind.ASK" in choice)
        assertTrue("stringResource(R.string.share_link_choice_kept, stringResource(R.string.grp_library_and_content))" in choice)
    }

    @Test
    fun `the row in Settings offers the two links and says when nothing is picked yet`() {
        val row = source("ui/screens/settings/fragments/InterfaceFrag.kt").substringAfter("fun ColumnScope.ShareLinksFrag()").substringBefore("\n}\n")
        assertTrue(row, "values = ShareLinks.CHOICES," in row)
        assertTrue(row, "ShareLinkKind.ASK -> stringResource(R.string.share_link_not_chosen)" in row)
        // "Ask each time" would promise a question at every share, and the question comes once.
        assertTrue(kotlin.none { "R.string.share_link_ask)" in it.readText() })
    }
}
