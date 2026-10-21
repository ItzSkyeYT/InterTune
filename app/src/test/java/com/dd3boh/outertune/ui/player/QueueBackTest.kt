/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import com.dd3boh.outertune.ui.component.collapsedAnchor
import com.dd3boh.outertune.ui.component.dismissedAnchor
import com.dd3boh.outertune.ui.component.expandedAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Back from the queue (issue #30). With "Back from the queue closes the player" off, which it is
 * unless turned on, Back from the queue goes to the song view as it always has. On, it closes the
 * whole player and leaves the queue open in it, and where the queue was left outlives the app.
 */
class QueueBackTest {

    /** compact, tabletPane: an ordinary phone, upright or on its side, a small window, a tablet on its side. */
    private val layouts = listOf(false to false, true to false, false to true)

    @Test
    fun `with the switch off Back from the queue goes to the song view, in every layout`() {
        for ((compact, tabletPane) in layouts) {
            assertEquals(QueueBack.SONG_VIEW, queueBack(closesPlayer = false, compact = compact, tabletPane = tabletPane))
        }
    }

    @Test
    fun `with it on Back from the queue closes the whole player`() {
        assertEquals(QueueBack.CLOSE_PLAYER, queueBack(closesPlayer = true, compact = false, tabletPane = false))
    }

    @Test
    fun `a selection is left first, then a search, whatever the switch says`() {
        for (on in listOf(false, true)) {
            for ((compact, tabletPane) in layouts) {
                assertEquals(QueueBack.LEAVE_SELECTION, queueBack(on, compact, tabletPane, selecting = true, searching = true))
                assertEquals(QueueBack.LEAVE_SELECTION, queueBack(on, compact, tabletPane, selecting = true))
                assertEquals(QueueBack.LEAVE_SEARCH, queueBack(on, compact, tabletPane, searching = true))
            }
        }
    }

    @Test
    fun `a small window keeps Back from the queue going to the song view`() {
        // Its queue's bar has no controls: Back is how a keypad phone's keys get to the player's.
        assertEquals(QueueBack.SONG_VIEW, queueBack(closesPlayer = true, compact = true, tabletPane = false))
    }

    @Test
    fun `so does a tablet on its side, whose queue is beside the player all the time`() {
        assertEquals(QueueBack.SONG_VIEW, queueBack(closesPlayer = true, compact = false, tabletPane = true))
    }

    @Test
    fun `the queue starts down, unless it was left open with the switch on`() {
        assertEquals(collapsedAnchor, queueSheetStartAnchor(closesPlayer = false, leftOpen = false))
        assertEquals(
            "stored while the switch was on, it counts for nothing once the switch is off",
            collapsedAnchor,
            queueSheetStartAnchor(closesPlayer = false, leftOpen = true),
        )
        assertEquals(collapsedAnchor, queueSheetStartAnchor(closesPlayer = true, leftOpen = false))
        assertEquals(expandedAnchor, queueSheetStartAnchor(closesPlayer = true, leftOpen = true))
    }

    @Test
    fun `where the queue was left is stored only with the switch on`() {
        for (anchor in listOf(expandedAnchor, collapsedAnchor, dismissedAnchor)) {
            assertNull("switch off, anchor $anchor", queueLeftOpenToStore(closesPlayer = false, anchor = anchor))
        }
        assertEquals(true, queueLeftOpenToStore(closesPlayer = true, anchor = expandedAnchor))
        assertEquals(false, queueLeftOpenToStore(closesPlayer = true, anchor = collapsedAnchor))
        assertEquals(false, queueLeftOpenToStore(closesPlayer = true, anchor = dismissedAnchor))
    }

    // ---- the code that uses them, read as ReleasedFlagsTest reads it ----

    private val main = File("src/main/java/com/dd3boh/outertune")
    private fun source(path: String) = File(main, path).readText()
    private fun filesNaming(name: String): Set<String> =
        main.walkTopDown().filter { it.extension == "kt" && name in it.readText() }.map { it.name }.toSet()

    @Test
    fun `the switch is off unless it is turned on`() {
        assertEquals(setOf("PreferenceKeys.kt", "Player.kt", "PlayerButtonsFrag.kt"), filesNaming("QueueBackClosesPlayerKey"))
        val read = Regex("""rememberPreference\(\s*QueueBackClosesPlayerKey\s*,\s*defaultValue\s*=\s*(\w+)\s*\)""")
        for (path in listOf("ui/player/Player.kt", "ui/screens/settings/fragments/PlayerButtonsFrag.kt")) {
            val defaults = read.findAll(source(path)).map { it.groupValues[1] }.toList()
            assertEquals("$path reads the switch once, off unless set", listOf("false"), defaults)
        }
    }

    @Test
    fun `where the queue was left is written in one place, and only through queueLeftOpenToStore`() {
        assertEquals(setOf("PreferenceKeys.kt", "Player.kt"), filesNaming("QueueLeftOpenKey"))
        val player = source("ui/player/Player.kt")
        assertEquals("writes of queueLeftOpen", 1, Regex("""\bqueueLeftOpen\s*=(?!=)""").findAll(player).count())
        assertTrue(
            player.contains(
                "queueLeftOpenToStore(queueBackClosesPlayer, queueSheetState.anchor)?.let { open ->\n" +
                    "            if (open != queueLeftOpen) queueLeftOpen = open"
            )
        )
        assertTrue(player.contains("initialAnchor = queueSheetStartAnchor(queueBackClosesPlayer, queueLeftOpen)"))
    }

    @Test
    fun `the queue's Back moves the player only where queueBack says so`() {
        assertTrue(
            source("ui/player/Player.kt").contains(
                "backClosesPlayer = queueBack(queueBackClosesPlayer, compact = queueLayout.compactSheet, tabletPane = tabletTwoPane) == QueueBack.CLOSE_PLAYER,"
            )
        )
        assertTrue(source("ui/player/Queue.kt").contains("backMoves = if (backClosesPlayer) playerBottomSheetState else state,"))
    }

    @Test
    fun `a selection and a search in the queue keep their own Back, ahead of the sheet's`() {
        val queue = source("ui/player/Queue.kt")
        // In this order, as queueBack has it: a selection first, then a search.
        assertTrue(
            queue.contains(
                "if (inSelectMode) {\n        BackHandler(onBack = onExitSelectionMode)\n    } else if (isSearching) {\n        BackHandler {"
            )
        )
        // Inside the sheet: its content is put in place after the sheet's own handler, and the
        // handler put in place last goes first.
        val sheet = queue.substringAfter("fun QueueSheet(").substringBefore("\nfun QueueScreen(")
        assertTrue(sheet.contains("QueueContent("))
    }

    @Test
    fun `its words are in English and in French`() {
        val english = File("src/main/res/values/strings-ot.xml").readText()
        val french = File("src/main/res/values-fr/strings-ot.xml").readText()
        for (name in listOf("queue_back_closes_player", "queue_back_closes_player_description")) {
            assertTrue("$name has no English", "<string name=\"$name\">" in english)
            assertTrue("$name has no French", "<string name=\"$name\">" in french)
        }
    }
}
