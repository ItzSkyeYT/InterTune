/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils.scanners

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The MediaStore scanner took an empty answer for a folder to mean the folder was empty, and
 * disabled every song it had scanned from there: all local songs, when the scan folders were all it
 * was asked about (15 Nov 2026, a folder on an SD card with a .nomedia file in it, which Android
 * does not list). An empty answer for a folder that has songs in the library is one it cannot speak
 * for, so those songs stay.
 */
class MediaStoreAnswerTest {
    private val music = "/storage/emulated/0/Music"
    private val card = "/storage/0000-0000/Ad04a"

    private fun keep(
        folders: List<String?>,
        answered: List<String> = emptyList(),
        inLibrary: List<String> = emptyList(),
        excluded: List<String> = emptyList(),
    ) = MediaStoreAnswer.foldersToKeep(folders, answered, inLibrary, excluded)

    @Test
    fun `a folder MediaStore gave nothing for, where the library has songs, keeps them`() {
        assertEquals(listOf<String?>(card), keep(listOf(card), inLibrary = listOf("$card/a.mp3", "$card/b.mp3")))
    }

    @Test
    fun `a folder MediaStore gave songs for is read as it says`() {
        val answered = listOf("$card/a.mp3")
        // The library may have more there than MediaStore gave: those songs are really gone, and are disabled.
        assertEquals(emptyList<String?>(), keep(listOf(card), answered, inLibrary = listOf("$card/a.mp3", "$card/b.mp3")))
    }

    @Test
    fun `a folder with nothing in it and nothing in the library is only empty`() {
        assertEquals(emptyList<String?>(), keep(listOf(card)))
        // Songs elsewhere are no reason to keep it.
        assertEquals(emptyList<String?>(), keep(listOf(card), inLibrary = listOf("$music/x.mp3")))
    }

    @Test
    fun `among several folders only the one MediaStore is silent about is kept`() {
        val kept = keep(
            listOf(music, card),
            answered = listOf("$music/x.mp3"),
            inLibrary = listOf("$music/x.mp3", "$card/a.mp3"),
        )
        assertEquals(listOf<String?>(card), kept)
    }

    @Test
    fun `a folder whose name only starts the same is another folder`() {
        // Ad04 is not Ad04a: a song in the one says nothing about the other, either way.
        val ad04 = "/storage/0000-0000/Ad04"
        assertEquals(emptyList<String?>(), keep(listOf(ad04), inLibrary = listOf("$card/a.mp3")))
        assertEquals(listOf<String?>(ad04), keep(listOf(ad04), answered = listOf("$card/a.mp3"), inLibrary = listOf("$ad04/b.mp3")))
    }

    @Test
    fun `songs under an excluded folder are meant to go, so they are not a reason to keep`() {
        val bob = "$music/Bob"
        // Everything MediaStore could give for Music is in the excluded Bob, and the library has only Bob's songs there.
        assertEquals(emptyList<String?>(), keep(listOf(music), inLibrary = listOf("$bob/b.mp3"), excluded = listOf(bob)))
        // A song of the library's outside Bob is the one that counts.
        assertEquals(
            listOf<String?>(music),
            keep(listOf(music), inLibrary = listOf("$bob/b.mp3", "$music/x.mp3"), excluded = listOf(bob)),
        )
    }

    @Test
    fun `a slash at the end of a folder makes no difference`() {
        assertEquals(listOf<String?>("$card/"), keep(listOf("$card/"), inLibrary = listOf("$card/a.mp3")))
        assertEquals(emptyList<String?>(), keep(listOf("$card/"), answered = listOf("$card/a.mp3"), inLibrary = listOf("$card/a.mp3")))
    }

    @Test
    fun `a folder whose path is not known keeps every song, as with the other scanners`() {
        // MediaStore was never asked about it. null is what ScanMerge.coveredByUnlistedRoot reads as "covers every song".
        assertEquals(listOf<String?>(null), keep(listOf(null, music), answered = listOf("$music/x.mp3"), inLibrary = listOf("$music/x.mp3")))
        assertEquals(listOf<String?>(null, music), keep(listOf(null, music), inLibrary = listOf("$music/x.mp3")))
    }

    @Test
    fun `no folder, nothing to keep`() {
        assertEquals(emptyList<String?>(), keep(emptyList(), inLibrary = listOf("$music/x.mp3")))
    }

    @Test
    fun `the MediaStore sync decides what to keep before it disables anything`() {
        val scanner = File("src/main/java/com/dd3boh/outertune/utils/scanners/LocalMediaScanner.kt").readText()
        val sync = scanner.substringAfter("suspend fun fullMediaStoreSync(").substringBefore("suspend fun localToRemoteArtist(")
        val asked = sync.indexOf("MediaStoreAnswer.foldersToKeep(")
        val set = sync.indexOf("unlistedRoots = ")
        val disabled = sync.indexOf("disableSongsByPath(")
        assertTrue("fullMediaStoreSync no longer asks MediaStoreAnswer what to keep", asked >= 0)
        assertTrue("what to keep is not handed to the disable pass", set > asked)
        assertTrue("the disable pass runs before what to keep is known", disabled > set)
    }
}
