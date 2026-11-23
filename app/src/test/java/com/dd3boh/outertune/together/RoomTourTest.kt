/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import com.dd3boh.outertune.ui.dialog.RoomTourPage
import com.dd3boh.outertune.ui.dialog.roomTourDue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * How rooms work (RoomTour): a little tour (Ti-Die, #testers 22 Nov 2026: "a LITTLE tutorial"), shown by itself once,
 * the first time a phone is in a room however it got there, skippable at once, and there again from the room's sheet
 * and Settings > Listen together. Its words are short, in English and French.
 */
class RoomTourTest {

    private fun main(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    private fun strings(folder: String): Map<String, String> =
        Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(File("src/main/res/$folder/strings-ot.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }

    @Test
    fun `it opens by itself in a room, and only the once`() {
        assertTrue(roomTourDue(inRoom = true, seen = null))
        assertTrue(roomTourDue(inRoom = true, seen = false))
        assertFalse(roomTourDue(inRoom = true, seen = true))
        assertFalse(roomTourDue(inRoom = false, seen = null))
        // Marked as seen as it opens, so that it never opens by itself again, even if the app goes before it is closed.
        val first = main("ui/dialog/RoomTour.kt").substringAfter("fun RoomTourOnFirstRoom(").substringBefore("\n}\n")
        val marked = first.indexOf("context.dataStore.edit { it[TogetherTourSeenKey] = true }")
        assertTrue(first, marked >= 0 && marked < first.indexOf("showing = true"))
        // Wherever the app is, as a proposal is: a room joined from a link or a QR code never opens the sheet.
        assertTrue("RoomTourOnFirstRoom(playerConnection)" in main("MainActivity.kt"))
    }

    @Test
    fun `it can be skipped at once, and a stray tap beside it does not close it`() {
        val dialog = main("ui/dialog/RoomTour.kt").substringAfter("fun RoomTourDialog(").substringBefore("\n/** One page")
        assertTrue("dismissOnClickOutside = false" in dialog)
        assertTrue("TextButton(\n                        onClick = onDismiss,\n                        enabled = !last," in dialog)
        assertTrue("R.string.together_tour_skip" in dialog)
        // The last page's tick closes it, and goes on to the walk over the room's sheet when one is on screen (RoomWalk).
        val done = dialog.substringAfter("if (last) {").substringBefore("} else")
        assertTrue("onDismiss()" in done)
        assertTrue("if (RoomWalkAsk.areas > 0) RoomWalkAsk.wanted = true" in done)
    }

    @Test
    fun `it is there any time, in the room's sheet in or out of a room, and in Settings`() {
        val sheet = main("ui/dialog/TogetherDialog.kt")
        assertTrue(sheet.substringAfter("private fun StartPage(").substringBefore("private fun RoomPage(").contains("HowRoomsWork("))
        assertTrue(sheet.substringAfter("private fun RoomPage(").substringBefore("\n/** Two phones").contains("HowRoomsWork("))
        val settings = main("ui/screens/settings/ListenTogetherSettings.kt")
        assertTrue("Modifier.settingRow(R.string.together_tour_entry)" in settings)
        assertTrue("if (touring) RoomTourDialog(onDismiss = { touring = false })" in settings)
        assertTrue("SettingEntry(\"settings/together\", R.string.together_tour_entry, there = togetherAvailable)" in main("ui/screens/settings/SettingsIndex.kt"))
    }

    @Test
    fun `a little tour, in short words, in English and French`() {
        assertTrue(RoomTourPage.entries.size in 3..4)
        for (folder in listOf("values", "values-fr")) {
            val texts = strings(folder)
            for (name in listOf("together_tour_entry", "together_tour_skip")) assertTrue("$folder $name", name in texts)
            for (page in RoomTourPage.entries) {
                val title = texts.entries.single { it.key == nameOf(page.title, page) }.value
                val body = texts.entries.single { it.key == nameOf(page.body, page) }.value
                assertFalse("$folder $title", title.endsWith("."))
                val sentences = Regex("[.!?](?=\\s|$)").findAll(body).count()
                assertTrue("$folder: ${page.name} has $sentences sentences", sentences in 1..3)
                assertTrue("$folder: ${page.name} is long", body.length <= 200)
                for (text in listOf(title, body)) assertFalse("$folder: no em dash in $text", '—' in text)
            }
        }
    }

    /** The name of a page's string, from the names its enum gives in RoomTour.kt. */
    private fun nameOf(id: Int, page: RoomTourPage): String {
        val line = main("ui/dialog/RoomTour.kt").lines().single { it.trim().startsWith("${page.name}(RoomTourScene.") }
        val names = Regex("R\\.string\\.(\\w+)").findAll(line).map { it.groupValues[1] }.toList()
        return if (id == page.title) names[0] else names[1]
    }
}
