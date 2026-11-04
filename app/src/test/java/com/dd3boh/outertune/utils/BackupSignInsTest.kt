/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dd3boh.outertune.constants.AccountChannelHandleKey
import com.dd3boh.outertune.constants.AccountEmailKey
import com.dd3boh.outertune.constants.AccountNameKey
import com.dd3boh.outertune.constants.DataSyncIdKey
import com.dd3boh.outertune.constants.InnerTubeCookieKey
import com.dd3boh.outertune.constants.LastFmSessionKey
import com.dd3boh.outertune.constants.LastFmUsernameKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A backup file leaves the account sign-ins out unless they are asked for, since anyone with the
 * file could use them, and a restore keeps the sign-ins already on the phone (his yes, 4 Nov 2026).
 */
class BackupSignInsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val other = stringPreferencesKey("someSetting")
    private val prefs = mutablePreferencesOf(
        InnerTubeCookieKey to "SID=secret", DataSyncIdKey to "sync", AccountNameKey to "Mel",
        AccountEmailKey to "mel@example.com", AccountChannelHandleKey to "@mel",
        LastFmSessionKey to "lastfm-session", LastFmUsernameKey to "mel", other to "kept",
    )

    @Test
    fun `a backup's settings leave every sign-in out and keep the rest`() {
        val out = SignIns.without(prefs)
        SignIns.KEYS.forEach { assertNull(it.name, out[it]) }
        assertEquals("kept", out[other])
    }

    @Test
    fun `a restore keeps the phone's sign-ins when the backup has none, and takes the backup's when it has them`() {
        val phone = mutablePreferencesOf(InnerTubeCookieKey to "SID=phone", LastFmSessionKey to "lastfm-phone")
        val restored = SignIns.keptOver(mutablePreferencesOf(other to "from the backup"), phone)
        assertEquals("SID=phone", restored[InnerTubeCookieKey])
        assertEquals("lastfm-phone", restored[LastFmSessionKey])
        assertEquals("from the backup", restored[other])
        assertEquals("SID=secret", SignIns.keptOver(prefs, phone)[InnerTubeCookieKey])
        assertNull(SignIns.keptOver(mutablePreferencesOf(other to "x"), emptyPreferences())[InnerTubeCookieKey])
    }

    @Test
    fun `the settings go through DataStore and back whole, and leave no file behind`() = runBlocking {
        val back = SettingsFile.decode(SettingsFile.encode(prefs, tmp.root), tmp.root)
        assertEquals(prefs.asMap(), back.asMap())
        val stripped = SettingsFile.decode(SettingsFile.encode(SignIns.without(prefs), tmp.root), tmp.root)
        assertNull(stripped[InnerTubeCookieKey])
        assertEquals("kept", stripped[other])
        assertEquals("no copies left in the folder", 0, tmp.root.listFiles()!!.size)
    }

    @Test
    fun `a backup takes no sign-ins unless asked, and the scheduled one never asks`() {
        assertFalse(BackupParts().signIns)
        val auto = File("src/main/java/com/dd3boh/outertune/utils/AutoBackup.kt").readText()
        assertTrue(auto.contains("BackupWriter.write(context, database, stream)"))
        assertFalse(auto.contains("signIns = true"))
        val writer = File("src/main/java/com/dd3boh/outertune/utils/BackupWriter.kt").readText()
        assertTrue("the settings in a backup go through SignIns.without unless asked", writer.contains("if (parts.signIns) it else SignIns.without(it)"))
    }
}
