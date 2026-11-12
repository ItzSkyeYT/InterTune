/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.playback.downloadManager.MoveNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * While the downloads kept inside the app are moved into the folder (Migrate, and the move offered
 * once a folder is chosen), the phone shows how far the move is in a notification, and how it
 * ended (12 Nov 2026, "a notification when transferring"). Until then the move said nothing: its
 * toast every 20 songs sat where the count is 1, 11, 21 and never came.
 */
class DownloadMoveNoticeTest {
    private val util = File("src/main/java/com/dd3boh/outertune/playback/DownloadUtil.kt").readText()
    private val notice = File("src/main/java/com/dd3boh/outertune/playback/downloadManager/MoveNotice.kt").readText()
    private val en = File("src/main/res/values/strings-ot.xml").readText()
    private val fr = File("src/main/res/values-fr/strings-ot.xml").readText()

    private val migrate = util.substringAfter("suspend fun migrateDownloads(").substringBefore("private suspend fun saveSongFile(")

    @Test
    fun `the move asked for in Settings shows the notification, the quiet move after a download does not`() {
        assertTrue(migrate.contains("if (!quiet && toMigrate.isNotEmpty() && MoveNotice.canShow(context)) {\n                moveNotice = MoveNotice(context, toMigrate.size)"))
        assertTrue("each song, before it is moved", migrate.indexOf("moveNotice?.starting(runs)") in 0 until migrate.indexOf("openFromCache(downloadCache, s.key)"))
        assertTrue("the same move for both ways in", util.contains("CoroutineScope(dlCoroutine).launch { migrateDownloads() }"))
    }

    @Test
    fun `it ends by saying it is done or how many stay, even when the move fails halfway`() {
        assertTrue(migrate.contains("FolderMove.MOVED -> movedCount++"))
        assertTrue(migrate.substringAfter("} finally {").contains("moveNotice?.finish(movedCount)"))
        val finish = notice.substringAfter("fun finish(moved: Int) {").substringBefore("\n    }\n")
        assertTrue(finish.contains("manager.cancel(ConvertNotice.ONGOING_ID)"))
        assertTrue(finish.contains("val left = stayInside(total, moved)"))
        assertTrue(finish.contains("R.string.dl_move_notice_done"))
        assertTrue(finish.contains("getQuantityString(R.plurals.dl_move_notice_failed, left, left)"))
    }

    @Test
    fun `the songs that stay are those that did not reach the folder`() {
        assertEquals(0, MoveNotice.stayInside(total = 360, moved = 360))
        assertEquals(3, MoveNotice.stayInside(total = 360, moved = 357))
        assertEquals(1, MoveNotice.stayInside(total = 1, moved = 0))
        assertEquals("never below nothing", 0, MoveNotice.stayInside(total = 2, moved = 3))
    }

    @Test
    fun `one way of telling, the toast only where the notification cannot show`() {
        assertTrue(migrate.contains("if (MoveNotice.toastDue(runs, quiet, noticeShown = moveNotice != null)) {"))
        assertFalse("with the notification up", MoveNotice.toastDue(done = 20, quiet = false, noticeShown = true))
        assertFalse("the quiet move after a download", MoveNotice.toastDue(done = 20, quiet = true, noticeShown = false))
        assertTrue(MoveNotice.toastDue(done = 20, quiet = false, noticeShown = false))
        assertEquals(
            "every 20 songs of a move of 360, counted after the song's turn began",
            (20..360 step 20).toList(),
            (1..360).filter { MoveNotice.toastDue(it, quiet = false, noticeShown = false) },
        )
        // Where it sat until 12 Nov 2026 the count was 1, 11, 21..., never a multiple of 20.
        val everyTen = migrate.substringAfter("if (runs++ % 10 == 0) {").substringBefore("\n                }\n")
        assertFalse(everyTen.contains("Toaster.show"))
    }

    @Test
    fun `the notification is the downloads' own, quiet, beside the conversion's, and opens the downloads`() {
        val starting = notice.substringAfter("fun starting(index: Int) {").substringBefore("\n    }\n")
        assertTrue(starting.contains("NotificationCompat.Builder(context, ExoDownloadService.CHANNEL_ID)"))
        assertTrue(starting.contains("context.getString(R.string.dl_move_notice_title)"))
        assertTrue(starting.contains("context.getString(R.string.dl_move_notice_text, index + 1, total)"))
        assertTrue(starting.contains(".setProgress(total, index, false)"))
        assertTrue(starting.contains(".setOngoing(true)"))
        assertTrue(starting.contains(".setSilent(true)"))
        assertTrue(starting.contains("DownloadsShortcut.pendingIntent(context)"))
        assertTrue("one line in the shade with a conversion", starting.contains("ConvertNotice.post(manager, ConvertNotice.ONGOING_ID, builder.build())"))
        assertTrue("a line a killed app left up goes at the next start", util.contains("ConvertNotice.clearStale(context)"))
    }

    @Test
    fun `it is made only where it can be seen`() {
        val canShow = notice.substringAfter("fun canShow(context: Context): Boolean {").substringBefore("\n        }\n")
        assertTrue(canShow.contains("if (!manager.areNotificationsEnabled()) return false"))
        assertTrue(canShow.contains("channel.importance != NotificationManagerCompat.IMPORTANCE_NONE"))
    }

    private fun plural(xml: String, name: String): Map<String, String> =
        Regex("""<item quantity="(\w+)">([^<]*)</item>""").findAll(
            xml.substringAfter("<plurals name=\"$name\"").substringBefore("</plurals>")
        ).associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun `the words are there in English and French`() {
        assertTrue(en.contains("<string name=\"dl_move_notice_title\" translatable=\"false\">Moving downloads</string>"))
        assertTrue(en.contains("<string name=\"dl_move_notice_text\" translatable=\"false\">%1\$d of %2\$d</string>"))
        assertTrue(en.contains("<string name=\"dl_move_notice_done\" translatable=\"false\">Downloads moved to your folder</string>"))
        assertTrue(fr.contains("<string name=\"dl_move_notice_title\">Déplacement des téléchargements</string>"))
        assertTrue(fr.contains("<string name=\"dl_move_notice_text\">%1\$d sur %2\$d</string>"))
        assertTrue(fr.contains("<string name=\"dl_move_notice_done\">Téléchargements déplacés dans votre dossier</string>"))
        assertEquals(
            mapOf(
                "one" to "%d song could not be moved and stays inside the app",
                "other" to "%d songs could not be moved and stay inside the app",
            ),
            plural(en, "dl_move_notice_failed"),
        )
        val french = plural(fr, "dl_move_notice_failed")
        assertEquals("%d titre n\\'a pas pu être déplacé et reste dans l\\'application", french["one"])
        assertEquals("%d titres n\\'ont pas pu être déplacés et restent dans l\\'application", french["other"])
        assertEquals(french["other"], french["many"])
    }
}
