/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A download folder saved that the app cannot open says so, and choosing a folder offers to move
 * the downloads still inside the app (11 Nov 2026). On his phone the saved folder's permission was
 * gone, most likely with a restored backup: every start logged "Not a valid directory" and said
 * nothing, 360 songs stayed inside the app, and the move after each download failed without a
 * word. Read from the source, as the other wiring tests are.
 */
class DownloadFolderSaysSoTest {
    private val dirManager = File("src/main/java/com/dd3boh/outertune/playback/downloadManager/DirectoryMangerOt.kt").readText()
    private val util = File("src/main/java/com/dd3boh/outertune/playback/DownloadUtil.kt").readText()
    private val frag = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/fragments/DownloadsFrag.kt").readText()
    private val en = File("src/main/res/values/strings-ot.xml").readText()
    private val fr = File("src/main/res/values-fr/strings-ot.xml").readText()

    private val doInit = dirManager.substringAfter("fun doInit(").substringBefore("\n    }\n")

    @Test
    fun `the start of the download manager sets where the folder stands, opened or not`() {
        val tried = doInit.substringBefore("} catch (e: Exception) {")
        val caught = doInit.substringAfter("} catch (e: Exception) {")
        assertTrue(tried.contains("_folderState.value = DownloadFolderState.of(pathSaved = dir.toString().isNotBlank(), opened = true)"))
        assertTrue(caught.contains("_folderState.value = DownloadFolderState.of(pathSaved = dir.toString().isNotBlank(), opened = false)"))
    }

    @Test
    fun `the failure no longer ends in a TODO that tells nobody`() {
        val caught = doInit.substringAfter("} catch (e: Exception) {")
        assertFalse(caught.contains("TODO"))
        assertFalse(caught.contains("snackbar for failed uri"))
    }

    @Test
    fun `the state is made before the init that sets it, and a change of folder runs the init again`() {
        assertTrue(dirManager.indexOf("private val _folderState") in 0 until dirManager.indexOf("init {"))
        val cd = util.substringAfter("fun cd() {").substringBefore("\n    }\n")
        assertTrue(cd.contains("localMgr.doInit("))
    }

    @Test
    fun `a download that cannot be moved says so once a start, the way the wait for Wi-Fi is said`() {
        val idle = util.substringAfter("override fun onIdle(downloadManager: DownloadManager) {").substringBefore("override fun onDownloadChanged(")
        assertTrue(idle.indexOf("notifyIfFolderCannotOpen()") in 0 until idle.indexOf("migrateDownloads(quiet = true, only = just)"))
        val notice = util.substringAfter("private fun notifyIfFolderCannotOpen() {").substringBefore("\n    }\n")
        assertTrue(notice.contains("localMgr.folderState.value != DownloadFolderState.CANNOT_OPEN"))
        assertTrue("once a start", notice.contains("folderNoticeGiven.compareAndSet(false, true)"))
        assertTrue(notice.contains("CoroutineScope(Dispatchers.Main.immediate).launch"))
        assertTrue(notice.contains("R.string.dl_folder_cannot_open_notice"))
    }

    @Test
    fun `the folder's row says it cannot open the folder, in the error colour`() {
        val row = frag.substringAfter("settingRow(R.string.dl_main_path_title)").substringBefore("showDlPathDialog = true")
        assertTrue(row.contains("folderState == DownloadFolderState.CANNOT_OPEN"))
        assertTrue(row.contains("R.string.dl_folder_cannot_open"))
        assertTrue(row.contains("MaterialTheme.colorScheme.error"))
        assertTrue(frag.contains("downloadUtil.localMgr.folderState.collectAsState()"))
    }

    @Test
    fun `choosing a folder that opens asks to move the downloads still inside the app`() {
        val confirm = frag.substringAfter("onConfirm = {").substringBefore("onReset = {")
        assertTrue(confirm.contains("downloadUtil.cd()"))
        assertTrue(confirm.indexOf("downloadUtil.cd()") < confirm.indexOf("downloadUtil.downloadsInsideApp()"))
        assertTrue(confirm.contains("folderState.value == DownloadFolderState.OPEN && inside > 0"))
        assertTrue(confirm.contains("moveHereCount = inside"))
        val question = frag.substringAfter("if (moveHereCount > 0) {").substringBefore("if (showMigrationDialog) {")
        assertTrue(question.contains("R.string.dl_move_here_title"))
        assertTrue(question.contains("pluralStringResource(R.plurals.dl_move_here_text, moveHereCount, moveHereCount)"))
        assertTrue(question.contains("R.string.dl_move_here_not_now"))
        assertTrue("Move is the existing move", question.contains("downloadUtil.startMigrate()"))
    }

    @Test
    fun `the count is the scan's, the finished entries of media3's index`() {
        val count = util.substringAfter("fun downloadsInsideApp(): Int {").substringBefore("\n    }\n")
        assertTrue(count.contains("downloadManager.downloadIndex.getDownloads()"))
        assertTrue(count.contains("completedDownloadTime(download.state, download.updateTimeMs) != null"))
    }

    @Test
    fun `the same folder chosen again takes its permission again`() {
        val picker = frag.substringAfter("ActivityResultContracts.OpenDocumentTree()").substringBefore("tempFilePath = uri")
        assertFalse(picker.contains("return@rememberLauncherForActivityResult"))
        assertTrue(picker.contains("takePersistableUriPermission(uri, takeFlags)"))
    }

    private fun plural(xml: String, name: String): Map<String, String> =
        Regex("""<item quantity="(\w+)">([^<]*)</item>""").findAll(
            xml.substringAfter("<plurals name=\"$name\"").substringBefore("</plurals>")
        ).associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun `the texts are there in English and French, the question's in plurals`() {
        for (name in listOf("dl_folder_cannot_open", "dl_folder_cannot_open_notice", "dl_move_here_title", "dl_move_here_move", "dl_move_here_not_now")) {
            assertTrue(name, en.contains("<string name=\"$name\" translatable=\"false\">"))
            assertTrue(name, fr.contains("<string name=\"$name\">"))
        }
        assertEquals(
            mapOf(
                "one" to "%d song is still inside the app. Move it to this folder now?",
                "other" to "%d songs are still inside the app. Move them to this folder now?",
            ),
            plural(en, "dl_move_here_text"),
        )
        val french = plural(fr, "dl_move_here_text")
        assertEquals("%d titre est encore dans l\\'application. Le déplacer dans ce dossier maintenant ?", french["one"])
        assertEquals("%d titres sont encore dans l\\'application. Les déplacer dans ce dossier maintenant ?", french["other"])
        assertEquals(french["other"], french["many"])
    }
}
