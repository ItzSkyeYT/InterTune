/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * His screen, 1 Nov 2026: Search lyrics' results opened on top of the search dialog, which stayed
 * drawn around them with its title field covered, and a long title made its field wider than the
 * artists'. The search dialog steps aside while its results are up and comes back as typed.
 */
class LyricsSearchDialogTest {
    private val menu = File("src/main/java/com/dd3boh/outertune/ui/menu/LyricsMenu.kt").readText()

    @Test
    fun `the search dialog is not drawn under its results`() {
        assertTrue("if (showSearchDialog && !showSearchResultDialog) {" in menu)
        // What was typed lives outside the dialog, so it is still there when the dialog comes back.
        val fields = menu.substringBefore("if (showSearchDialog && !showSearchResultDialog) {")
        assertTrue("rememberSaveable(showSearchDialog, stateSaver = TextFieldValue.Saver)" in fields)
    }

    @Test
    fun `both fields are as wide as the dialog`() {
        val dialog = menu.substringAfter("if (showSearchDialog && !showSearchResultDialog) {").substringBefore("if (showSearchResultDialog) {")
        val fields = dialog.split("OutlinedTextField(").drop(1)
        assertEquals(2, fields.size)
        for (field in fields) assertTrue(field.substringBefore("\n            )").contains("modifier = Modifier.fillMaxWidth()"))
    }
}
