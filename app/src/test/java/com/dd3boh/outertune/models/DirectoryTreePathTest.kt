/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** The path a folder's top bar shows under its name read "//storage/emulated/0/Music". */
class DirectoryTreePathTest {

    private fun song(path: String) = Song(
        SongEntity(id = "LS" + path.substringAfterLast('/'), title = path.substringAfterLast('/'), isLocal = true, localPath = path),
        artists = emptyList(),
    )

    @Test
    fun `a folder's own screen shows the folder it is in with single slashes`() {
        // As refreshLocal builds it: the whole path as the name, and no parent.
        val folder = DirectoryTree("/storage/emulated/0/Music/Issue39", CulmSongs(0))
        assertEquals("/storage/emulated/0/Music/Issue39", folder.getFullPath())
        assertEquals("/storage/emulated/0/Music", folder.parentPathLine())
    }

    @Test
    fun `a subfolder made from the songs in it has the same path`() {
        val music = DirectoryTree("/storage/emulated/0/Music", CulmSongs(0))
        music.insert("Issue39/One.mp3", song("/storage/emulated/0/Music/Issue39/One.mp3"))
        val issue39 = music.subdirs.single()
        assertEquals("/storage/emulated/0/Music/Issue39", issue39.getFullPath())
        assertEquals("/storage/emulated/0/Music", issue39.parentPathLine())
    }

    @Test
    fun `the top of storage has no line under its name`() {
        assertEquals("", DirectoryTree("/storage", CulmSongs(0)).parentPathLine())
        assertEquals("", DirectoryTree("/storage/", CulmSongs(0)).parentPathLine())
    }
}
