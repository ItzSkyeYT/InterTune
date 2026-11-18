/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils.scanners

import com.dd3boh.outertune.utils.scanners.LocalRemoval.Volume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The part of Remove from library and Exclude folder with no Android in it (LocalRemoval): the line Excluded scan
 * paths gets for a file or a folder, that every scanner then leaves it out by the rules it already has, and the list
 * edited without touching the other lines.
 */
class LocalRemovalTest {

    private val phone = listOf(Volume("/storage/emulated/0", "primary"))
    private val withCard = phone + Volume("/storage/1A2B-3C4D", "1A2B-3C4D")

    @Test
    fun `a path gets the document id the folder picker gives it`() {
        assertEquals("primary:Recordings", LocalRemoval.documentId("/storage/emulated/0/Recordings", phone))
        assertEquals("primary:Music/Intertune songe", LocalRemoval.documentId("/storage/emulated/0/Music/Intertune songe/", phone))
        assertEquals(
            "primary:Music/Intertune songe/Rock And Roll All Nite.mp3",
            LocalRemoval.documentId("/storage/emulated/0/Music/Intertune songe/Rock And Roll All Nite.mp3", phone),
        )
        // The Folders tab's paths come with a slash at the end, and sometimes two in a row.
        assertEquals("primary:Music/Kiss", LocalRemoval.documentId("/storage/emulated/0//Music/Kiss//", phone))
        // The root of a volume is "primary:", as the picker has it.
        assertEquals("primary:", LocalRemoval.documentId("/storage/emulated/0", phone))
        // A card, by its id.
        assertEquals("1A2B-3C4D:Music", LocalRemoval.documentId("/storage/1A2B-3C4D/Music", withCard))
    }

    @Test
    fun `a card before Android 11 is known by where it is mounted, as fileFromUri reads it back`() {
        assertEquals("1A2B-3C4D:Music/Kiss", LocalRemoval.documentId("/storage/1A2B-3C4D/Music/Kiss", phone))
        // Not the phone's own storage under another name, nor the app's own files.
        assertNull(LocalRemoval.documentId("/storage/emulated/10/Music", phone))
        assertNull(LocalRemoval.documentId("/storage/self/primary/Music", phone))
        assertNull(LocalRemoval.documentId("/data/user/0/dev.skye.intertune/files/a.mp3", phone))
    }

    @Test
    fun `a volume whose name only starts the same is another volume`() {
        assertEquals("primary:Music", LocalRemoval.documentId("/storage/emulated/0/Music", phone))
        assertNull(LocalRemoval.documentId("/storage/emulated/01/Music", phone))
    }

    @Test
    fun `the address is written as the folder picker writes it`() {
        assertEquals(
            "content://com.android.externalstorage.documents/tree/primary%3AMusic",
            LocalRemoval.treeAddress("primary:Music"),
        )
        assertEquals(
            "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FIntertune%20songe",
            LocalRemoval.treeAddress("primary:Music/Intertune songe"),
        )
        // Uri.encode keeps letters, digits and _-!.~'()* and writes the rest as UTF-8 bytes.
        assertEquals("a_b-c!d.e~f'g(h)i*j", LocalRemoval.encode("a_b-c!d.e~f'g(h)i*j"))
        assertEquals("%C3%A9t%C3%A9%20%2B%25%23%3F%26", LocalRemoval.encode("été +%#?&"))
    }

    @Test
    fun `what the scanners read from the address is the document id again`() {
        for (id in listOf(
            "primary:Music",
            "primary:Music/Intertune songe/Rock And Roll All Nite.mp3",
            "primary:Musique/Été 85/C'est + fort (live) #2.flac",
            "1A2B-3C4D:100% Hits/a&b?.mp3",
            "primary:",
        )) {
            assertEquals(id, FolderNesting.treeDocumentId(LocalRemoval.treeAddress(id)))
        }
    }

    @Test
    fun `the tag scanners leave out a removed file, and every file of an excluded folder, and nothing else`() {
        // getScanFiles compares a found file's document id with each excluded line's, by isSameOrInsideId.
        val removed = FolderNesting.treeDocumentId(LocalRemoval.addressOf("/storage/emulated/0/Music/Kiss/Detroit.mp3", phone)!!)!!
        assertTrue(FolderNesting.isSameOrInsideId("primary:Music/Kiss/Detroit.mp3", removed))
        assertFalse(FolderNesting.isSameOrInsideId("primary:Music/Kiss/Detroit.mp3.bak", removed))
        assertFalse(FolderNesting.isSameOrInsideId("primary:Music/Kiss/Beth.mp3", removed))

        val folder = FolderNesting.treeDocumentId(LocalRemoval.addressOf("/storage/emulated/0/Recordings/", phone)!!)!!
        assertTrue(FolderNesting.isSameOrInsideId("primary:Recordings/memo 1.m4a", folder))
        assertTrue(FolderNesting.isSameOrInsideId("primary:Recordings/Calls/memo 2.m4a", folder))
        assertFalse(FolderNesting.isSameOrInsideId("primary:Recordings2/memo.m4a", folder))
        assertFalse(FolderNesting.isSameOrInsideId("primary:Music/Kiss/Beth.mp3", folder))
    }

    @Test
    fun `the MediaStore scan leaves out a removed file, and every file of an excluded folder, and nothing else`() {
        // fullMediaStoreSync turns each line back into its absolute path and asks isPathExcluded.
        val file = "/storage/emulated/0/Music/Kiss/Detroit.mp3"
        assertTrue(isPathExcluded(file, listOf(file)))
        assertFalse(isPathExcluded("/storage/emulated/0/Music/Kiss/Beth.mp3", listOf(file)))
        val folder = "/storage/emulated/0/Recordings"
        assertTrue(isPathExcluded("/storage/emulated/0/Recordings/memo.m4a", listOf(folder)))
        assertFalse(isPathExcluded("/storage/emulated/0/Recordings2/memo.m4a", listOf(folder)))
    }

    @Test
    fun `a line is added once at the end and taken off alone`() {
        val a = LocalRemoval.treeAddress("primary:Music")
        val b = LocalRemoval.treeAddress("primary:Recordings")
        val c = LocalRemoval.treeAddress("primary:Notifications")
        assertEquals(a, LocalRemoval.withEntry("", a))
        assertEquals("$a\n$b", LocalRemoval.withEntry(a, b))
        assertEquals("$a\n$b", LocalRemoval.withEntry("$a\n$b", b))
        assertEquals(listOf(a, b, c), LocalRemoval.entries("$a\n\n$b\n$c\n"))
        assertEquals("$a\n$c", LocalRemoval.withoutEntry("$a\n$b\n$c", b))
        assertEquals("", LocalRemoval.withoutEntry(b, b))
        assertEquals("$a\n$c", LocalRemoval.withoutEntry("$a\n$c", b))
        // As stringFromUriList and uriListFromString read and write the list: one address a line.
        assertEquals(listOf(a, b), "$a\n$b".split("\n"))
    }

    @Test
    fun `a folder's songs are the ones inside it, and its name is its own`() {
        val folder = "/storage/emulated/0/Recordings/"
        assertTrue(LocalRemoval.under("/storage/emulated/0/Recordings/memo.m4a", folder))
        assertTrue(LocalRemoval.under("/storage/emulated/0/Recordings/Calls/memo.m4a", "/storage/emulated/0/Recordings"))
        assertFalse(LocalRemoval.under("/storage/emulated/0/Recordings2/memo.m4a", folder))
        // The LIKE of the query takes "_" for any letter: the check after it does not.
        assertFalse(LocalRemoval.under("/storage/emulated/0/MyXSongs/a.mp3", "/storage/emulated/0/My_Songs/"))
        assertEquals("Recordings", LocalRemoval.folderName(folder))
        assertEquals("Intertune songe", LocalRemoval.folderName("/storage/emulated/0/Music/Intertune songe"))
    }
}
