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
import com.dd3boh.outertune.constants.ListenBrainzTokenKey
import com.dd3boh.outertune.constants.ListenBrainzUserKey
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
        LastFmSessionKey to "lastfm-session", LastFmUsernameKey to "mel",
        ListenBrainzTokenKey to "listenbrainz-token", ListenBrainzUserKey to "mel", other to "kept",
    )

    @Test
    fun `the ListenBrainz token is a sign-in, so it is left out with the others`() {
        // It opens the whole ListenBrainz account (sending and deleting listens), as the cookie opens YouTube's.
        assertTrue(ListenBrainzTokenKey in SignIns.KEYS && ListenBrainzUserKey in SignIns.KEYS)
        assertNull(SignIns.without(prefs)[ListenBrainzTokenKey])
    }

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
    fun `a backup with one account's sign-in leaves the phone signed in to the others`() {
        // A backup made with its sign-ins before 0.13, or any before 4 Nov 2026, holds YouTube's and
        // Last.fm's and never ListenBrainz's: its restore signed the phone out of ListenBrainz.
        val phone = mutablePreferencesOf(
            InnerTubeCookieKey to "SID=phone", AccountNameKey to "Phone",
            LastFmSessionKey to "lastfm-phone", LastFmUsernameKey to "phone-fm",
            ListenBrainzTokenKey to "listenbrainz-phone", ListenBrainzUserKey to "phone-lb",
        )
        val restored = SignIns.keptOver(mutablePreferencesOf(InnerTubeCookieKey to "SID=backup", AccountNameKey to "Backup", other to "x"), phone)
        assertEquals("SID=backup", restored[InnerTubeCookieKey])
        assertEquals("Backup", restored[AccountNameKey])
        assertEquals("lastfm-phone", restored[LastFmSessionKey])
        assertEquals("phone-fm", restored[LastFmUsernameKey])
        assertEquals("listenbrainz-phone", restored[ListenBrainzTokenKey])
        assertEquals("phone-lb", restored[ListenBrainzUserKey])
        assertEquals("x", restored[other])
    }

    @Test
    fun `an account's settings come whole from one side, never its key from one and its name from the other`() {
        assertEquals(listOf(InnerTubeCookieKey, LastFmSessionKey, ListenBrainzTokenKey), SignIns.SERVICES.map { it.first() })
        assertEquals(SignIns.KEYS.toSet(), SignIns.SERVICES.flatten().toSet())
        // The backup's YouTube account without the brand account the phone's uses: that one stays out.
        val phone = mutablePreferencesOf(InnerTubeCookieKey to "SID=phone", DataSyncIdKey to "phone-brand")
        assertNull(SignIns.keptOver(mutablePreferencesOf(InnerTubeCookieKey to "SID=backup"), phone)[DataSyncIdKey])
        // A name the backup kept without its key does not sit beside the phone's key.
        val kept = SignIns.keptOver(
            mutablePreferencesOf(AccountNameKey to "Old name", AccountEmailKey to "old@example.com"),
            mutablePreferencesOf(InnerTubeCookieKey to "SID=phone", AccountNameKey to "Phone"),
        )
        assertEquals("SID=phone", kept[InnerTubeCookieKey])
        assertEquals("Phone", kept[AccountNameKey])
        assertNull(kept[AccountEmailKey])
    }

    @Test
    fun `Last-fm disconnected in the backup does not sign the phone out of it`() {
        // Disconnecting leaves the name blank (LastFmFrag), so a backup made after it holds that.
        val restored = SignIns.keptOver(
            mutablePreferencesOf(LastFmUsernameKey to ""),
            mutablePreferencesOf(LastFmSessionKey to "lastfm-phone", LastFmUsernameKey to "mel"),
        )
        assertEquals("lastfm-phone", restored[LastFmSessionKey])
        assertEquals("mel", restored[LastFmUsernameKey])
        // Signed out on both sides, the backup's settings are left as they are.
        assertEquals("", SignIns.keptOver(mutablePreferencesOf(LastFmUsernameKey to ""), emptyPreferences())[LastFmUsernameKey])
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
