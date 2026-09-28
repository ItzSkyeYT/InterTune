/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils.scanners

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fullMediaStoreSync's own exclude check: MediaStore hands back absolute file paths, never tree
 * addresses, so this compares two absolute paths instead of FolderNesting's tree-address ids. The
 * old check compared an absolute path with a tree address's own path (a "/tree/..." string), so it
 * never matched a real file.
 */
class MediaStoreExclusionTest {

    @Test
    fun `a file under an excluded folder is caught`() {
        assertTrue(
            isPathExcluded(
                "/storage/emulated/0/Music/WhatsApp Audio/x.opus",
                listOf("/storage/emulated/0/Music/WhatsApp Audio")
            )
        )
    }

    @Test
    fun `a folder whose name only starts the same is not caught`() {
        assertFalse(isPathExcluded("/storage/emulated/0/Music2/x.opus", listOf("/storage/emulated/0/Music")))
        assertFalse(isPathExcluded("/storage/emulated/0/MusicVideos/x.opus", listOf("/storage/emulated/0/Music")))
    }

    @Test
    fun `nothing is excluded when no folder converted to an absolute path`() {
        assertFalse(isPathExcluded("/storage/emulated/0/Music/x.opus", emptyList()))
    }

    @Test
    fun `a file that is itself the excluded path is caught`() {
        assertTrue(isPathExcluded("/storage/emulated/0/Music", listOf("/storage/emulated/0/Music")))
    }
}
