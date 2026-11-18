/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * One tap on Inside the app no longer takes every song in the download folder out of Downloads at
 * the next start (review of 0.13, F3; his rule: an update never removes what people had). The folder
 * is kept as one of the extra folders the app reads, and those are read with no main folder too.
 */
class InsideKeepsFolderTest {
    private val frag = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/fragments/DownloadsFrag.kt").readText()
    private val manager = File("src/main/java/com/dd3boh/outertune/playback/downloadManager/DirectoryMangerOt.kt").readText()

    private val music = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FInterTune"
    private val other = "content://com.android.externalstorage.documents/tree/primary%3ADownload"

    @Test
    fun `the folder joins the extra folders once, after the ones there`() {
        assertEquals(listOf(other, music), InsideKeepsFolder.extras(music, listOf(other)))
        assertEquals(listOf(music, other), InsideKeepsFolder.extras(music, listOf(music, other)))
        assertEquals(listOf(music), InsideKeepsFolder.extras(music, emptyList()))
        assertEquals(listOf(other), InsideKeepsFolder.extras("", listOf(other)))
    }

    @Test
    fun `Inside the app keeps the folder before it unsets it`() {
        val inside = frag.substringAfter("onInside = {").substringBefore("onFolder = {")
        val keep = inside.indexOf("onDlPathExtraChange(stringFromUriList(kept.map { it.toUri() }))")
        assertTrue(keep >= 0)
        assertTrue(keep < inside.indexOf("onDownloadPathChange(\"\")"))
        assertTrue(inside.contains("InsideKeepsFolder.extras("))
    }

    @Test
    fun `with no main folder the extra folders are still read`() {
        val init = manager.substringAfter("fun doInit(").substringBefore("\n    }\n")
        val blank = init.substringAfter("if (dir.toString().isBlank()) {").substringBefore("        try {")
        assertTrue(blank.contains("openExtras(context, extraDirs)"))
        assertTrue(blank.contains("allDirs = opened"))
        assertTrue(blank.contains("_folderState.value = DownloadFolderState.of(pathSaved = false, opened = false)"))
        assertTrue(blank.contains("return"))
    }
}
