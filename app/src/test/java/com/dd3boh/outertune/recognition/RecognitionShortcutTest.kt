/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The Quick Settings tile and the home screen widget that open recognition, listening. */
class RecognitionShortcutTest {

    @Test
    fun `listening is owed for a fresh tap only`() {
        val action = RecognitionShortcut.ACTION
        assertTrue(RecognitionShortcut.owed(action, hadSavedInstanceState = false, flags = 0))
        // A rotation or a restored process.
        assertFalse(RecognitionShortcut.owed(action, hadSavedInstanceState = true, flags = 0))
        // The task reopened from recents brings the tile's intent back.
        assertFalse(RecognitionShortcut.owed(action, hadSavedInstanceState = false, flags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
        assertFalse(RecognitionShortcut.owed(Intent.ACTION_MAIN, hadSavedInstanceState = false, flags = 0))
        assertFalse(RecognitionShortcut.owed(null, hadSavedInstanceState = false, flags = 0))
    }

    private val manifest = File("src/main/AndroidManifest.xml").readText()

    /** The whole element that declares [name], down to its own closing tag. */
    private fun declaration(name: String): String {
        val at = manifest.indexOf("android:name=\"$name\"")
        assertTrue("$name is declared", at >= 0)
        val start = manifest.lastIndexOf('<', at)
        val tag = manifest.substring(start + 1).takeWhile { !it.isWhitespace() }
        val end = manifest.indexOf("</$tag>", at)
        assertTrue("$name has its closing tag", end > at)
        return manifest.substring(start, end)
    }

    @Test
    fun `the tile is bound by Quick Settings alone and is not a switch`() {
        val tile = declaration(".recognition.RecognitionTileService")
        assertTrue("android:permission=\"android.permission.BIND_QUICK_SETTINGS_TILE\"" in tile)
        assertTrue("android.service.quicksettings.action.QS_TILE" in tile)
        assertTrue("android.service.quicksettings.TOGGLEABLE_TILE" in tile)
        assertTrue("android:label=\"@string/recognise\"" in tile)
    }

    @Test
    fun `the widget is its own, apart from the player widget`() {
        val widget = declaration(".widget.RecognitionWidgetReceiver")
        assertTrue("android:exported=\"false\"" in widget)
        assertTrue("@xml/recognition_widget_info" in widget)
        assertFalse("music_widget_info" in widget)
    }

    @Test
    fun `neither starts the microphone by itself, both open the screen`() {
        // From Android 14 a microphone service cannot start with the app in the background, and
        // the screen is where the permission is asked and the run is seen and stopped.
        for (path in listOf(
            "src/main/java/com/dd3boh/outertune/recognition/RecognitionTileService.kt",
            "src/main/java/com/dd3boh/outertune/widget/RecognitionWidgetReceiver.kt",
        )) {
            val source = File(path).readText()
            assertFalse(path, "RecognitionService" in source.replace("RecognitionShortcut", ""))
            assertTrue(path, "RecognitionShortcut.intent(" in source)
        }
        assertTrue("startListening = true" in File("src/main/java/com/dd3boh/outertune/ui/navigation/AppNavGraph.kt").readText())
    }

    @Test
    fun `the widget's words are in English and in French`() {
        for (folder in listOf("values", "values-fr")) {
            assertTrue(folder, "name=\"recognition_widget_description\"" in File("src/main/res/$folder/strings-ot.xml").readText())
        }
    }
}
