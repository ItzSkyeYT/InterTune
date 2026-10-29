/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.car.app.notification.CarPendingIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android Auto's settings entry goes through AutoSettingsLink. A real head unit refused the
 * settings screen on 24 Oct 2026 ("Intent target not a valid settings template"), and the car
 * library's own receiver threw that refusal on the main thread, which killed the app and the music.
 */
class AutoSettingsLinkTest {

    private fun source(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    @Test
    fun `the car's settings entry is the guarded link, not the library's own`() {
        val callback = source("playback/MediaLibrarySessionCallback.kt")
        assertTrue(callback.contains("AutoSettingsLink.forCar(context)"))
        assertFalse(callback.contains("CarPendingIntent.getCarApp("))
    }

    @Test
    fun `the receiver catches what the car throws back`() {
        val link = source("playback/AutoSettingsLink.kt")
        val receiver = link.substringAfter("class AutoSettingsReceiver")
        assertTrue(Regex("try \\{\\s*super\\.onReceive\\(context, intent\\)\\s*\\} catch \\(e: RuntimeException\\)").containsMatchIn(receiver))
        assertTrue(link.contains(".setClass(context, AutoSettingsReceiver::class.java)"))
    }

    @Test
    fun `the screen goes under the library's own key`() {
        // The library's receiver reads the screen from this extra; a newer library that renamed it
        // would leave every tap on the cog doing nothing.
        val key = CarPendingIntent::class.java.getDeclaredField("COMPONENT_EXTRA_KEY").apply { isAccessible = true }.get(null)
        assertEquals(key, AutoSettingsLink.COMPONENT_KEY)
    }

    @Test
    fun `the receiver is declared with the car's screens and only the app reaches it`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val entry = manifest.substringAfter("android:name=\".playback.AutoSettingsReceiver\"", "").substringBefore("/>")
        assertTrue("AutoSettingsReceiver is declared", entry.isNotEmpty())
        assertTrue("android:enabled=\"\${carScreens}\"" in entry)
        assertTrue("android:exported=\"false\"" in entry)
    }
}
