/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import com.dd3boh.outertune.viewmodels.DataAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every button on Your data says on its own line what it did: Working while it runs, then what came of it, or that it
 * failed. Rebuild weights lost that line with the hint under it when Settings dropped the lines that only repeated a
 * row's name (24 Nov 2026), and a rebuild then ran, finished or failed without a word.
 *
 * This reads the source, as the other tests of these screens do: the screens are drawn by Compose.
 */
class YourDataLinesTest {

    private val page = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/RecommendationsDataSettings.kt").readText()

    @Test
    fun `every button shows what it did on its line`() {
        for (action in DataAction.entries) {
            val line = Regex("""description = results\[DataAction\.${action.name}]\?\.let \{ resultText\(it\) }""")
            assertTrue("${action.name} has no line for what it did", line.containsMatchIn(page))
        }
    }

    @Test
    fun `Rebuild weights has no line until it is used`() {
        // The hint went for good on 24 Nov 2026: only what reports a state came back.
        assertFalse("recommendations_rebuild_weights_description" in page)
        val row = page.substringAfter("Modifier.settingRow(R.string.recommendations_rebuild_weights)").substringBefore("onClick =")
        assertTrue(row, "description = results[DataAction.REBUILD]?.let { resultText(it) },\n" in row)
    }
}
