/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.utils.PlaylistCovers.CoverSource
import com.dd3boh.outertune.utils.PlaylistCovers.Option
import com.dd3boh.outertune.utils.PlaylistCovers.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.abs

/**
 * What a playlist's cover is made of (PlaylistCovers): the size a photo is kept at, the files it
 * is kept in, and the choices the sheet offers. The photo's own decoding needs a phone and is seen
 * on one; everything it is made of is here.
 */
class PlaylistCoversTest {

    @get:Rule
    val folder = TemporaryFolder()

    // ---- the size of a kept photo ----

    @Test
    fun `a photo's longer side is at most 1024 and its shape is kept`() {
        assertEquals(1024 to 768, PlaylistCovers.fit(4000, 3000))
        assertEquals(768 to 1024, PlaylistCovers.fit(3000, 4000))
        assertEquals(1024 to 1024, PlaylistCovers.fit(5000, 5000))
        assertEquals(1024 to 576, PlaylistCovers.fit(1920, 1080))
        assertEquals(1024 to 256, PlaylistCovers.fit(8000, 2000))
        // Just over, which is where an off-by-one would show.
        assertEquals(1024 to 1024, PlaylistCovers.fit(1025, 1025))
        assertEquals(1024 to 1023, PlaylistCovers.fit(1025, 1024))
    }

    @Test
    fun `a photo is never made larger`() {
        assertEquals(500 to 300, PlaylistCovers.fit(500, 300))
        assertEquals(1024 to 1024, PlaylistCovers.fit(1024, 1024))
        assertEquals(1024 to 10, PlaylistCovers.fit(1024, 10))
        assertEquals(1 to 1, PlaylistCovers.fit(1, 1))
    }

    @Test
    fun `a very thin photo keeps a pixel and a picture with no size is refused`() {
        assertEquals(1 to 1024, PlaylistCovers.fit(1, 5000))
        assertEquals(1024 to 1, PlaylistCovers.fit(100000, 20))
        assertNull(PlaylistCovers.fit(0, 100))
        assertNull(PlaylistCovers.fit(100, 0))
        assertNull(PlaylistCovers.fit(-1, -1))
    }

    @Test
    fun `the shape stays within a pixel for any size`() {
        for ((w, h) in listOf(4032 to 3024, 3024 to 4032, 6000 to 4000, 1280 to 720, 2048 to 1365, 4999 to 3333, 1500 to 1499, 12000 to 5)) {
            val (fw, fh) = PlaylistCovers.fit(w, h)!!
            assertEquals("$w x $h: the longer side", 1024, maxOf(fw, fh))
            // The shorter side is what the shape gives, to the nearest pixel.
            val exact = minOf(w, h) * 1024.0 / maxOf(w, h)
            assertTrue("$w x $h became $fw x $fh", abs(minOf(fw, fh) - exact) <= 0.5 || minOf(fw, fh) == 1)
        }
    }

    @Test
    fun `a photo is read at a size that still leaves its longer side at 1024 or more`() {
        assertEquals(1, PlaylistCovers.sampleSize(1500, 1000))
        assertEquals(1, PlaylistCovers.sampleSize(2047, 1000))
        assertEquals(2, PlaylistCovers.sampleSize(2048, 1000))
        assertEquals(2, PlaylistCovers.sampleSize(4000, 3000))
        assertEquals(4, PlaylistCovers.sampleSize(8000, 6000))
        // The longer side counts, not the shorter: a panorama is not read whole.
        assertEquals(16, PlaylistCovers.sampleSize(20000, 4000))
        for ((w, h) in listOf(4000 to 3000, 8000 to 6000, 20000 to 4000, 9000 to 12000, 3000 to 1000)) {
            val n = PlaylistCovers.sampleSize(w, h)
            assertTrue("$w x $h at 1/$n", maxOf(w, h) / n >= 1024)
            assertTrue("$w x $h at 1/$n is not the largest step", maxOf(w, h) / (n * 2) < 1024)
        }
    }

    @Test
    fun `the turn of each EXIF orientation`() {
        assertEquals(Turn(0, false), PlaylistCovers.turnFor(1))
        assertEquals(Turn(0, true), PlaylistCovers.turnFor(2))
        assertEquals(Turn(180, false), PlaylistCovers.turnFor(3))
        assertEquals(Turn(180, true), PlaylistCovers.turnFor(4))
        assertEquals(Turn(90, true), PlaylistCovers.turnFor(5))
        assertEquals(Turn(90, false), PlaylistCovers.turnFor(6))
        assertEquals(Turn(270, true), PlaylistCovers.turnFor(7))
        assertEquals(Turn(270, false), PlaylistCovers.turnFor(8))
        // A photo with none, or with a value nobody defined, stands as it is.
        assertEquals(Turn(0, false), PlaylistCovers.turnFor(0))
        assertEquals(Turn(0, false), PlaylistCovers.turnFor(9))
    }

    // ---- what is stored ----

    @Test
    fun `a kept photo is stored as its folder and its name`() {
        val name = PlaylistCovers.fileName("LPabcdefgh", 1730500000000L)
        assertEquals("LPabcdefgh-1730500000000.jpg", name)
        assertEquals("playlist-covers/LPabcdefgh-1730500000000.jpg", PlaylistCovers.stored(name))
        assertTrue(PlaylistCovers.isPhoto(PlaylistCovers.stored(name)))
    }

    @Test
    fun `only a kept photo is a photo`() {
        assertFalse(PlaylistCovers.isPhoto(null))
        assertFalse(PlaylistCovers.isPhoto(""))
        assertFalse(PlaylistCovers.isPhoto("https://lh3.googleusercontent.com/cover=w544-h544-l90-rj"))
        assertFalse(PlaylistCovers.isPhoto("/storage/emulated/0/Music/Album/cover.jpg"))
        assertFalse(PlaylistCovers.isPhoto("/data/user/0/dev.skye.intertune/files/playlist-covers/LPx-1.jpg"))
    }

    @Test
    fun `a stored photo is found in the app's folder by its name alone`() {
        val base = folder.root
        assertEquals(File(base, "playlist-covers/LPx-1.jpg"), PlaylistCovers.fileOf(base, "playlist-covers/LPx-1.jpg"))
        // A value can never lead out of the folder.
        assertEquals(File(base, "playlist-covers/passwd"), PlaylistCovers.fileOf(base, "playlist-covers/../../etc/passwd"))
        assertNull(PlaylistCovers.fileOf(base, "playlist-covers/"))
        assertNull(PlaylistCovers.fileOf(base, null))
        assertNull(PlaylistCovers.fileOf(base, "https://i.ytimg.com/vi/x/hqdefault.jpg"))
    }

    // ---- the files ----

    @Test
    fun `a photo is kept under its name with no half file left`() {
        val base = folder.root
        val name = PlaylistCovers.keep(base, "LPone", 5L) { it.write(byteArrayOf(1, 2, 3)); true }
        assertEquals("LPone-5.jpg", name)
        assertTrue(File(base, "playlist-covers/LPone-5.jpg").readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(listOf("LPone-5.jpg"), File(base, "playlist-covers").list()!!.toList())
    }

    @Test
    fun `a photo that could not be written keeps nothing`() {
        val base = folder.root
        assertNull(PlaylistCovers.keep(base, "LPone", 6L) { false })
        assertNull(PlaylistCovers.keep(base, "LPone", 7L) { it.write(byteArrayOf(1)); throw java.io.IOException("disk full") })
        assertEquals(emptyList<String>(), File(base, "playlist-covers").list()!!.toList())
        assertEquals(emptyList<File>(), PlaylistCovers.files(base))
    }

    @Test
    fun `forgetting a playlist's photos leaves another playlist's alone`() {
        val base = folder.root
        File(base, "playlist-covers").mkdirs()
        for (name in listOf("LPone-1.jpg", "LPone-2.jpg", "LPone-3.jpg.part", "LPonetwo-1.jpg", "LPtwo-1.jpg")) {
            File(base, "playlist-covers/$name").writeBytes(byteArrayOf(9))
        }
        PlaylistCovers.forget(base, "LPone", except = "LPone-2.jpg")
        assertEquals(listOf("LPone-2.jpg", "LPonetwo-1.jpg", "LPtwo-1.jpg"), PlaylistCovers.files(base).map { it.name })
        PlaylistCovers.forget(base, "LPone")
        assertEquals(listOf("LPonetwo-1.jpg", "LPtwo-1.jpg"), PlaylistCovers.files(base).map { it.name })
        // What a cut write left of the playlist's went with them.
        assertFalse(File(base, "playlist-covers/LPone-3.jpg.part").exists())
    }

    @Test
    fun `forgetting in a folder that is not there is nothing to do`() {
        PlaylistCovers.forget(folder.root, "LPnone")
        assertEquals(emptyList<File>(), PlaylistCovers.files(folder.root))
    }

    @Test
    fun `the files of a backup are the photos and not what a cut write left`() {
        val base = folder.root
        File(base, "playlist-covers").mkdirs()
        File(base, "playlist-covers/LPa-1.jpg").writeBytes(byteArrayOf(1))
        File(base, "playlist-covers/LPb-2.jpg").writeBytes(byteArrayOf(2))
        File(base, "playlist-covers/LPb-3.jpg.part").writeBytes(byteArrayOf(3))
        assertEquals(listOf("LPa-1.jpg", "LPb-2.jpg"), PlaylistCovers.files(base).map { it.name })
    }

    // ---- in a backup ----

    private fun zipOf(base: File): ByteArray = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { PlaylistCovers.addTo(it, base) } }.toByteArray()

    private fun stageAll(zip: ByteArray, staging: File): List<String> {
        val staged = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { input ->
            var entry = input.nextEntry
            while (entry != null) {
                if (PlaylistCovers.stage(staging, entry.name, input)) staged += entry.name
                entry = input.nextEntry
            }
        }
        return staged
    }

    @Test
    fun `photos go into a backup and come back from it`() {
        val phone = folder.newFolder("phone")
        PlaylistCovers.keep(phone, "LPa", 1L) { it.write(byteArrayOf(1, 2, 3)); true }
        PlaylistCovers.keep(phone, "LPb", 2L) { it.write(byteArrayOf(4, 5)); true }
        File(phone, "playlist-covers/LPb-3.jpg.part").writeBytes(byteArrayOf(7))
        val zip = zipOf(phone)

        val other = folder.newFolder("other")
        PlaylistCovers.keep(other, "LPold", 3L) { it.write(byteArrayOf(9)); true }
        val staging = File(folder.root, "staging")
        assertEquals(listOf("playlist-covers/LPa-1.jpg", "playlist-covers/LPb-2.jpg"), stageAll(zip, staging))
        PlaylistCovers.place(staging, other)

        // The restored database names these and no others.
        assertEquals(listOf("LPa-1.jpg", "LPb-2.jpg"), PlaylistCovers.files(other).map { it.name })
        assertTrue(File(other, "playlist-covers/LPa-1.jpg").readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        assertTrue(File(other, "playlist-covers/LPb-2.jpg").readBytes().contentEquals(byteArrayOf(4, 5)))
        assertFalse(staging.exists())
    }

    @Test
    fun `a library with no photos adds nothing to a backup, and a backup with none changes nothing`() {
        assertEquals(0, zipOf(folder.newFolder("empty")).let { ZipInputStream(ByteArrayInputStream(it)).use { zin -> generateSequence { zin.nextEntry }.count() } })
        val other = folder.newFolder("other")
        PlaylistCovers.keep(other, "LPmine", 4L) { it.write(byteArrayOf(1)); true }
        PlaylistCovers.place(File(folder.root, "nothing-staged"), other)
        assertEquals(listOf("LPmine-4.jpg"), PlaylistCovers.files(other).map { it.name })
    }

    @Test
    fun `only a plain jpg in the folder is a photo of a backup`() {
        assertEquals("LPa-1.jpg", PlaylistCovers.backedUpName("playlist-covers/LPa-1.jpg"))
        for (entry in listOf("playlist-covers/../evil.jpg", "../evil.jpg", "playlist-covers/sub/a.jpg", "playlist-covers/a.txt", "playlist-covers/", "song.db", "settings.preferences_pb", "/playlist-covers/a.jpg")) {
            assertNull(entry, PlaylistCovers.backedUpName(entry))
        }
        val staging = File(folder.root, "staging")
        assertFalse(PlaylistCovers.stage(staging, "playlist-covers/../evil.jpg", ByteArrayInputStream(byteArrayOf(1))))
        assertFalse(File(folder.root, "evil.jpg").exists())
        assertEquals(emptyList<String>(), staging.list().orEmpty().toList())
    }

    // ---- what is drawn ----

    @Test
    fun `a kept photo is drawn from its file`() {
        val base = folder.root
        val name = PlaylistCovers.keep(base, "LPone", 8L) { true }!!
        assertEquals(File(base, "playlist-covers/$name"), PlaylistCovers.model(base, PlaylistCovers.stored(name), "https://example.com/first.jpg", 300))
    }

    @Test
    fun `a photo that is gone is drawn as the first song's cover, as if none was chosen`() {
        val base = folder.root
        assertEquals("https://example.com/first.jpg", PlaylistCovers.model(base, "playlist-covers/LPgone-1.jpg", "https://example.com/first.jpg", 300))
        assertNull(PlaylistCovers.model(base, "playlist-covers/LPgone-1.jpg", null, 300))
    }

    @Test
    fun `a cover as stored is drawn as it was before, and with none the first song's`() {
        val base = folder.root
        assertEquals("https://example.com/chosen.jpg", PlaylistCovers.model(base, "https://example.com/chosen.jpg", "https://example.com/first.jpg", 300))
        assertEquals("https://example.com/first.jpg", PlaylistCovers.model(base, null, "https://example.com/first.jpg", 300))
        assertNull(PlaylistCovers.model(base, null, null, 300))
        // A song on the phone is drawn by its own path, as the song's row draws it.
        assertEquals(LocalArtworkPath("/storage/emulated/0/Music/a.flac", 300, 300), PlaylistCovers.model(base, "/storage/emulated/0/Music/a.flac", null, 300))
    }

    // ---- what the sheet offers ----

    @Test
    fun `a song's cover is what its own row shows`() {
        assertEquals("https://example.com/a.jpg", PlaylistCovers.coverOf("https://example.com/a.jpg", false, null))
        // A song of YouTube's has no file to fall back on.
        assertNull(PlaylistCovers.coverOf(null, false, "/storage/emulated/0/Music/a.flac"))
        assertEquals("/storage/emulated/0/Music/cover.jpg", PlaylistCovers.coverOf("/storage/emulated/0/Music/cover.jpg", true, "/storage/emulated/0/Music/a.flac"))
        assertEquals("/storage/emulated/0/Music/a.flac", PlaylistCovers.coverOf(null, true, "/storage/emulated/0/Music/a.flac"))
        assertNull(PlaylistCovers.coverOf("", false, null))
        assertNull(PlaylistCovers.coverOf(null, true, null))
    }

    @Test
    fun `the strip lists each picture once, in the playlist's order, under the first song that has it`() {
        val options = PlaylistCovers.options(
            listOf(
                CoverSource("a", "Midnight City"),
                CoverSource(null, "No cover"),
                CoverSource("b", "Latch"),
                CoverSource("a", "Midnight City (Live)"),
                CoverSource("c", "Solar System"),
                CoverSource("b", "Latch (Remix)"),
            )
        )
        assertEquals(listOf(Option("a", "Midnight City"), Option("b", "Latch"), Option("c", "Solar System")), options)
    }

    @Test
    fun `a playlist with no cover to pick from offers no strip`() {
        assertEquals(emptyList<Option>(), PlaylistCovers.options(emptyList()))
        assertEquals(emptyList<Option>(), PlaylistCovers.options(listOf(CoverSource(null, "a"), CoverSource(null, "b"))))
    }

    // ---- who may choose ----

    @Test
    fun `only a playlist of one's own, and only with the flag on`() {
        assertTrue(PlaylistCovers.canChoose(browseId = null, enabled = true))
        // A playlist from YouTube keeps YouTube's picture, which its sync writes into the same field.
        assertFalse(PlaylistCovers.canChoose(browseId = "PLabc123", enabled = true))
        assertFalse(PlaylistCovers.canChoose(browseId = null, enabled = false))
        assertFalse(PlaylistCovers.canChoose(browseId = "PLabc123", enabled = false))
    }

    @Test
    fun `the limits are the ones that were asked for`() {
        assertEquals(1024, PlaylistCovers.MAX_SIDE)
        assertEquals(90, PlaylistCovers.QUALITY)
        assertEquals("playlist-covers", PlaylistCovers.DIR)
    }
}
