/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A Move asked for by hand says when the song playing moves later (review of the branch, R2). The
 * song the player has open is left for later (HeldWhilePlaying), and the notification said
 * "Downloads moved to your folder" all the same, though that song was still inside the app.
 */
class MoveHeldSaidTest {
    private val util = File("src/main/java/com/dd3boh/outertune/playback/DownloadUtil.kt").readText()
    private val notice = File("src/main/java/com/dd3boh/outertune/playback/downloadManager/MoveNotice.kt").readText()
    private val en = File("src/main/res/values/strings-ot.xml").readText()
    private val fr = File("src/main/res/values-fr/strings-ot.xml").readText()

    @Test
    fun `done only when nothing stays inside the app and nothing moves later`() {
        assertEquals(MoveNotice.Ending.DONE, MoveNotice.ending(left = 0, held = 0))
        assertEquals(MoveNotice.Ending.NOT_ALL, MoveNotice.ending(left = 0, held = 1))
        assertEquals(MoveNotice.Ending.NOT_ALL, MoveNotice.ending(left = 2, held = 0))
        assertEquals(MoveNotice.Ending.NOT_ALL, MoveNotice.ending(left = 2, held = 1))
    }

    @Test
    fun `the notification's end says the songs that come after, beside the ones that stay`() {
        val finish = notice.substringAfter("fun finish(moved: Int, held: Int = 0) {").substringBefore("\n    }\n")
        assertTrue(finish.contains("getQuantityString(R.plurals.dl_move_notice_held, held, held)"))
        assertTrue(finish.contains("if (ending(left, held) == Ending.DONE) R.string.dl_move_notice_done else R.string.dl_move_notice_title"))
    }

    @Test
    fun `the move counts the songs it leaves for later and says them, in a toast where no notification shows`() {
        val migrate = util.substringAfter("suspend fun migrateDownloads(").substringBefore("    private suspend fun saveSongFile(")
        assertTrue(migrate.contains("held = completed.size - movable.size"))
        val end = migrate.substringAfter("} finally {")
        assertTrue(end.contains("moveNotice?.finish(movedCount, held)"))
        assertTrue(end.contains("if (!quiet && held > 0 && moveNotice == null) {"))
        assertTrue(end.contains("getQuantityString(R.plurals.dl_move_notice_held, held, held)"))
    }

    private fun plural(xml: String, name: String): Map<String, String> =
        Regex("""<item quantity="(\w+)">([^<]*)</item>""").findAll(
            xml.substringAfter("<plurals name=\"$name\" translatable=\"false\">").substringBefore("</plurals>")
        ).associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun `the words, held from translators with the other new ones, short enough for a toast`() {
        val english = plural(en, "dl_move_notice_held")
        assertEquals(mapOf("one" to "%d song moves once it stops playing", "other" to "%d songs move once they stop playing"), english)
        val french = plural(fr, "dl_move_notice_held")
        assertEquals("%d titre sera déplacé dès qu\\'il ne sera plus en lecture", french["one"])
        assertEquals("%d titres seront déplacés dès qu\\'ils ne seront plus en lecture", french["other"])
        assertEquals(french["other"], french["many"])
        for (text in english.values + french.values) {
            assertTrue(text, text.replace("\\'", "'").length <= 70)
            assertFalse(text, text.contains('—'))
        }
    }
}
