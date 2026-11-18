/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #181 (0.12.11, a Xiaomi on Android 12): the player's service could not start, "Session ID must
 * be unique": media3 found the session of an earlier start of the service still registered in the
 * process and refused a second with the same id. One left behind is released before the new one.
 */
class LeftSessionTest {

    private val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()

    @Test
    fun `a session an earlier start left behind is released before the new one is built`() {
        val release = service.indexOf("leftSession?.get()?.let { left ->")
        val build = service.indexOf("mediaSession = MediaLibrarySession.Builder(this, sessionPlayer, mediaLibrarySessionCallback)")
        val kept = service.indexOf("leftSession = WeakReference(mediaSession)")
        assertTrue(release in 0 until build && build < kept)
        assertTrue("never a throw over it", "runCatching { left.release() }" in service.substring(release, build))
    }

    @Test
    fun `the service forgets the session it releases itself`() {
        val destroy = service.substringAfter("override fun onDestroy() {").substringBefore("\n    }\n")
        val release = destroy.indexOf("mediaSession.release()")
        val forget = destroy.indexOf("if (leftSession?.get() === mediaSession) leftSession = null")
        assertTrue(destroy, release in 0 until forget)
        assertTrue("held weakly, so a released session is never kept alive", "private var leftSession: WeakReference<MediaLibrarySession>? = null" in service)
    }
}
