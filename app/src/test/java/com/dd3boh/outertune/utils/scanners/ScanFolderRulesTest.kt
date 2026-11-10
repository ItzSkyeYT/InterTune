/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils.scanners

import com.dd3boh.outertune.utils.scanners.ScanFolderRules.Conflict
import com.dd3boh.outertune.utils.scanners.ScanFolderRules.Role
import com.dd3boh.outertune.utils.scanners.ScanFolderRules.Way
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which folders cannot be scan folders, and why. Issue 122: a download folder at Download/InterTune,
 * then Music and Music/Artistes refused with "This folder holds your download folder (.../Music/Artistes)".
 * The download folder is nowhere near Music. The folder in the way can be an extra import folder,
 * chosen in the dialog that Storage and downloads > Advanced > Configure extra import locations
 * opens, which was titled "Scan paths", and the refusal called it the download folder as well
 * (reproduced on the emulator with the released 0.12.9).
 *
 * The addresses below are the ones the folder picker handed out on the emulator when the report's
 * steps were followed (Android 17, the same picker GrapheneOS ships), as the app stored them in
 * dlPath and dlExtraPath.
 */
class ScanFolderRulesTest {

    private val downloadFolder = "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FInterTune"
    private val importFolder = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FArtistes"
    private val music = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
    private val artistes = importFolder

    private fun storage(id: String) =
        "content://com.android.externalstorage.documents/tree/" +
            java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")

    @Test
    fun `Music and Music-Artistes are accepted beside the download folder in the report`() {
        assertNull(ScanFolderRules.conflictOf(music, listOf(downloadFolder), emptyList()))
        assertNull(ScanFolderRules.conflictOf(artistes, listOf(downloadFolder), emptyList()))
    }

    @Test
    fun `nothing is refused when nothing is set`() {
        assertNull(ScanFolderRules.conflictOf(music, emptyList(), emptyList()))
    }

    @Test
    fun `with Music-Artistes as an extra import folder, Music is refused and the import folder is named as one`() {
        // what made the report: the download folder is fine, the import folder is what holds Music
        val conflict = ScanFolderRules.conflictOf(music, listOf(downloadFolder), listOf(importFolder))
        assertEquals(Conflict(importFolder, Role.IMPORT, Way.HOLDS), conflict)
    }

    @Test
    fun `with Music-Artistes as an extra import folder, Music-Artistes is refused as that folder`() {
        assertEquals(
            Conflict(importFolder, Role.IMPORT, Way.HOLDS),
            ScanFolderRules.conflictOf(artistes, listOf(downloadFolder), listOf(importFolder))
        )
    }

    @Test
    fun `a folder beside an extra import folder is accepted, and so is one whose name only begins like Music`() {
        val imports = listOf(importFolder)
        assertNull(ScanFolderRules.conflictOf(storage("primary:Music/Other"), listOf(downloadFolder), imports))
        assertNull(ScanFolderRules.conflictOf(storage("primary:Music2"), listOf(downloadFolder), imports))
        assertNull(ScanFolderRules.conflictOf(storage("primary:MusicVideos"), listOf(downloadFolder), imports))
    }

    @Test
    fun `a folder inside an extra import folder is refused as inside it`() {
        assertEquals(
            Conflict(importFolder, Role.IMPORT, Way.INSIDE),
            ScanFolderRules.conflictOf(storage("primary:Music/Artistes/Singles"), emptyList(), listOf(importFolder))
        )
    }

    @Test
    fun `a download folder inside Music-Artistes is what holds Music, and it is named as the download folder`() {
        val download = storage("primary:Music/Artistes/InterTune")
        assertEquals(Conflict(download, Role.DOWNLOAD, Way.HOLDS), ScanFolderRules.conflictOf(music, listOf(download), emptyList()))
        assertEquals(Conflict(download, Role.DOWNLOAD, Way.HOLDS), ScanFolderRules.conflictOf(artistes, listOf(download), emptyList()))
    }

    @Test
    fun `the download folder itself, or one inside it, cannot be a scan folder`() {
        assertEquals(
            Conflict(downloadFolder, Role.DOWNLOAD, Way.HOLDS),
            ScanFolderRules.conflictOf(downloadFolder, listOf(downloadFolder), emptyList())
        )
        assertEquals(
            Conflict(downloadFolder, Role.DOWNLOAD, Way.INSIDE),
            ScanFolderRules.conflictOf(storage("primary:Download/InterTune/Albums"), listOf(downloadFolder), emptyList())
        )
    }

    @Test
    fun `when more than one folder is in the way the download folder is named first`() {
        val download = storage("primary:Music/Artistes")
        val imports = listOf(storage("primary:Music/Artistes/Singles"))
        // Music holds both
        assertEquals(Conflict(download, Role.DOWNLOAD, Way.HOLDS), ScanFolderRules.conflictOf(music, listOf(download), imports))
        // and Music/Artistes/Singles/2026 sits inside both
        assertEquals(
            Conflict(download, Role.DOWNLOAD, Way.INSIDE),
            ScanFolderRules.conflictOf(storage("primary:Music/Artistes/Singles/2026"), listOf(download), imports)
        )
    }

    @Test
    fun `holding a folder is named before sitting inside another`() {
        // Music/Artistes holds the import folder Music/Artistes/Singles, and sits inside the download folder Music
        val imports = listOf(storage("primary:Music/Artistes/Singles"))
        assertEquals(Conflict(imports[0], Role.IMPORT, Way.HOLDS), ScanFolderRules.conflictOf(artistes, listOf(music), imports))
    }
}
