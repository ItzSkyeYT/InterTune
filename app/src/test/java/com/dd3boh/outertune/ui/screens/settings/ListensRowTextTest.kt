/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Listens row on How it's doing gives two numbers: every listen, and the ones that count as a
 * play (ListenDao.countedListenCount, past Minimum playback duration). It called the second one
 * "in your History", and Recent listens marked the others "not in History". That was true while
 * History held only counted plays. History has since listed any play heard for two seconds
 * (HistoryRule), so the page was telling people their short listens were missing from a list that
 * shows them.
 */
class ListensRowTextTest {
    private fun file(folder: String) = File("src/main/res/$folder/strings-ot.xml").readText()

    private fun entry(text: String, tag: String, name: String) =
        Regex("<$tag name=\"$name\"[^>]*>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(text)!!.groupValues[1]

    @Test
    fun `the row and the marker speak of plays that count, not of History`() {
        for ((folder, history, counted) in listOf(Triple("values", "History", "counted"), Triple("values-fr", "historique", "compt"))) {
            val text = file(folder)
            val row = entry(text, "plurals", "recommendations_listens")
            val marker = entry(text, "string", "recommendations_recent_not_counted")
            assertFalse("$folder row: $row", history in row)
            assertFalse("$folder marker: $marker", history in marker)
            assertTrue("$folder row: $row", counted in row)
            assertTrue("$folder marker: $marker", counted in marker)
        }
    }

    @Test
    fun `the line under the row does not say what puts a listen in History`() {
        // It said Minimum playback duration did. The page that setting lives on has history in its
        // name, so the claim is looked for, not the word.
        assertFalse("into History" in entry(file("values"), "string", "recommendations_listens_description"))
        assertFalse("entre dans" in entry(file("values-fr"), "string", "recommendations_listens_description"))
    }
}
