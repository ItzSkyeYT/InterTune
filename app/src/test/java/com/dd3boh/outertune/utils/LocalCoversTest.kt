/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A music file on the phone with no picture of its own takes its folder's cover (LocalCovers, his pick
 * for listeners of their own files, 22 Nov 2026), in the app's screens and for the system's player.
 */
class LocalCoversTest {
    @Test
    fun `the folder's cover is found by the names players use, the best first, in any case`() {
        assertEquals("cover.jpg", LocalCovers.best(listOf("01 Song.mp3", "folder.jpg", "cover.jpg", "back.jpg")))
        assertEquals("Folder.PNG", LocalCovers.best(listOf("01 Song.flac", "Folder.PNG", "booklet.jpg")))
        assertEquals("front.jpg", LocalCovers.best(listOf("front.jpg", "AlbumArt.jpg", "album.png")))
        assertEquals("a jpg before a png of the same name", "cover.jpg", LocalCovers.best(listOf("cover.png", "cover.jpg")))
        assertEquals("AlbumArt.jpeg", LocalCovers.best(listOf("AlbumArt.jpeg", "scan01.jpg")))
    }

    @Test
    fun `other images, other files and hidden names are not a cover`() {
        assertNull(LocalCovers.best(listOf("back.jpg", "scan01.jpg", "cd.png", "01 Song.mp3", "cover.txt", ".jpg", "cover")))
        assertNull(LocalCovers.best(emptyList()))
    }

    private val sources = File("src/main/java/com/dd3boh/outertune")

    @Test
    fun `the app's screens and the system's player both fall back to it, after the file's own picture`() {
        val coil = File(sources, "utils/CoilBitmapLoader.kt").readText()
        val own = coil.indexOf("mData.embeddedPicture")
        val folder = coil.indexOf("?: LocalCovers.of(context, data.path,")
        val placeholder = coil.indexOf("?: drawPlaceholder(context)\n")
        assertTrue(own in 0 until folder)
        assertTrue(folder in 0 until placeholder)
        assertTrue("the retriever is let go", "runCatching { mData.release() }" in coil)
        val session = File(sources, "utils/SessionBitmapLoader.kt").readText()
        assertTrue("?: return LocalCovers.of(context, path, artPx)" in session)
        // From Android 13 the app sees no image file: the system's thumbnail of the song, which needs no new permission.
        val covers = File(sources, "utils/LocalCovers.kt").readText()
        assertTrue("contentResolver.loadThumbnail(" in covers)
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue("no permission for images", "READ_MEDIA_IMAGES" !in manifest)
    }
}
