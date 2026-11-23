/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Faults made on purpose belong to debug builds and nowhere else. Read from the source, as the
 * other guards are, and tried with the switch off.
 */
class FaultInjectionTest {
    private fun source(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    @After
    fun off() {
        FaultInjection.enabled = true
        FaultInjection.set(FaultInjection.Mode.NONE)
    }

    @Test
    fun `the switch is the build's own debug flag`() {
        assertTrue(source("utils/FaultInjection.kt").contains("internal var enabled: Boolean = BuildConfig.DEBUG"))
    }

    @Test
    fun `every client gets the interceptor only when the switch is on`() {
        for (path in listOf("utils/YTPlayerUtils.kt", "utils/StreamFamily.kt")) {
            val text = source(path)
            val calls = Regex("""FaultInjection\.interceptor\(""").findAll(text).count()
            val guarded = Regex("""if \(FaultInjection\.enabled\) addInterceptor\(FaultInjection\.interceptor\(""").findAll(text).count()
            assertTrue("$path has one", calls > 0)
            assertEquals(path, calls, guarded)
        }
    }

    @Test
    fun `the developer options' row is in a debug build only`() {
        val frag = source("ui/screens/settings/fragments/DeveloperFrag.kt")
        val row = frag.indexOf("FaultInjection.Mode.entries")
        val guard = frag.lastIndexOf("if (BuildConfig.DEBUG) {", row)
        assertTrue(row > 0 && guard > 0)
        assertFalse("the guard is closed before the row", frag.substring(guard, row).contains("\n        }\n"))
    }

    @Test
    fun `a song made gone is the first one fetched once the mode is on, and that one only, until the mode is set again`() {
        assertFalse("not with the mode off", FaultInjection.callsGone("first"))
        FaultInjection.set(FaultInjection.Mode.SONG_GONE)
        assertTrue(FaultInjection.callsGone("first"))
        assertFalse(FaultInjection.callsGone("second"))
        assertTrue("and again, as a song that is gone is", FaultInjection.callsGone("first"))
        FaultInjection.set(FaultInjection.Mode.SONG_GONE)
        assertTrue("set again, the next one fetched", FaultInjection.callsGone("second"))
        FaultInjection.set(FaultInjection.Mode.NONE)
        assertFalse(FaultInjection.callsGone("second"))
        // Asked by the player's resolver, never by a walk for a download or the cast server's own.
        val resolve = source("playback/MusicService.kt").substringAfter("fun resolve(mediaId: String, position: Long, length: Long, ahead: Boolean = false)")
        assertTrue(resolve.contains("if (FaultInjection.callsGone(mediaId)) Result.failure(YTPlayerUtils.SongUnavailable("))
    }

    @Test
    fun `with the switch off nothing can be set and nothing hangs`() {
        FaultInjection.enabled = false
        FaultInjection.set(FaultInjection.Mode.PLAYER_HANGS_VISIONOS)
        assertEquals(FaultInjection.Mode.NONE, FaultInjection.mode)
        assertFalse(FaultInjection.hangsPlayer("VISIONOS", isVisionosItself = true))
        FaultInjection.set(FaultInjection.Mode.SONG_GONE)
        assertFalse("no song is made gone", FaultInjection.callsGone("first"))
    }

    @Test
    fun `only VISIONOS itself is made to hang, not its other identities`() {
        FaultInjection.enabled = true
        FaultInjection.set(FaultInjection.Mode.PLAYER_HANGS_VISIONOS)
        assertTrue(FaultInjection.hangsPlayer("VISIONOS", isVisionosItself = true))
        assertFalse(FaultInjection.hangsPlayer("VISIONOS", isVisionosItself = false))
        assertFalse(FaultInjection.hangsPlayer("IOS", isVisionosItself = false))
    }

    @Test
    fun `the resumption's words, a slow onCreate among them, each read on its own`() {
        val slowCreate = FaultInjection.resumeFaultOf("slowcreate 12000")!!
        assertEquals(12_000L, slowCreate.createDelayMs)
        assertEquals("slowcreate is not slow", 0L, slowCreate.delayMs)
        val both = FaultInjection.resumeFaultOf(" slow 25000  nohold\n")!!
        assertEquals(25_000L, both.delayMs)
        assertEquals(0L, both.createDelayMs)
        assertTrue(both.noHold)
        assertEquals(null, FaultInjection.resumeFaultOf("  \n"))
    }

    @Test
    fun `the songs to set aside, one a line, each with or without "gone", and nothing that is not an id`() {
        assertEquals(
            listOf("dQw4w9WgXcQ" to false, "kJQP7kiw5Fk" to true),
            FaultInjection.setAsideOf("dQw4w9WgXcQ\n  kJQP7kiw5Fk   gone \n\nnot an id!\n"),
        )
        assertEquals(emptyList<Pair<String, Boolean>>(), FaultInjection.setAsideOf("  \n"))
        // Nothing is called gone again but what the file named, and with the switch off nothing at all.
        assertFalse(FaultInjection.goneAgain("dQw4w9WgXcQ"))
        val service = source("playback/MusicService.kt")
        val resolve = service.substringAfter("private val standIns by lazy").substringBefore("wanted = { id ->")
        assertTrue("only the player's own walks: never a download's", "if (FaultInjection.goneAgain(id)) Result.failure(" in resolve)
        assertTrue("applied as the service starts", "FaultInjection.setAside(this).forEach { id ->" in service.substringAfter("override fun onCreate() {"))
    }

    @Test
    fun `a slow onCreate is held before anything else, and only through the fault file`() {
        val service = java.io.File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
        val onCreate = service.substringAfter("override fun onCreate() {").substringBefore("super.onCreate()")
        assertTrue("held first, before media3 or Hilt run", "FaultInjection.resumeFault(this)?.createDelayMs" in onCreate)
        assertEquals("the one sleep before super.onCreate is the fault's", 1, Regex("""Thread\.sleep\(""").findAll(onCreate).count())
    }
}
