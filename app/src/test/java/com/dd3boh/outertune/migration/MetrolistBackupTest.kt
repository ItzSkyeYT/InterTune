/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.migration

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A Metrolist backup opened (MetrolistBackup.unpack): what is kept, what is skipped, what is refused. */
class MetrolistBackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> entries.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
    }.toByteArray()

    @Test
    fun `the new app's backup gives its database and its settings, and nothing else`() {
        val db = ByteArray(70_000) { (it % 251).toByte() }
        val file = zip("backup.json" to """{"formatVersion":2,"platform":"ANDROID","databaseVersion":65}""".toByteArray(),
            "settings.json" to """{"entries":[]}""".toByteArray(), "song.db" to db, "../evil" to byteArrayOf(1))
        val unpacked = MetrolistBackup.unpack(ByteArrayInputStream(file), folder.root)!!
        assertArrayEquals(db, unpacked.database.readBytes())
        assertEquals("""{"entries":[]}""", unpacked.settingsJson)
        assertFalse("a name that climbs out is never written", folder.root.parentFile!!.resolve("evil").exists())
    }

    @Test
    fun `the Android app's backup gives its database and no settings to read`() {
        val unpacked = MetrolistBackup.unpack(ByteArrayInputStream(zip("settings.preferences_pb" to byteArrayOf(1, 2), "song.db" to byteArrayOf(3))), folder.root)!!
        assertArrayEquals(byteArrayOf(3), unpacked.database.readBytes())
        assertNull(unpacked.settingsJson)
    }

    @Test
    fun `a file with no database, and one larger than the cap, are refused`() {
        assertNull(MetrolistBackup.unpack(ByteArrayInputStream(zip("notes.txt" to byteArrayOf(1))), folder.root))
        assertThrows(IOException::class.java) {
            MetrolistBackup.unpack(ByteArrayInputStream(zip("song.db" to ByteArray(2_000))), folder.root, databaseLimit = 1_000)
        }
    }
}
