/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.theme

import com.dd3boh.outertune.constants.DarkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #213 (Ti-Die, 0.12.11, Pixel 11, 16 Nov 2026): opening the app from its widget and closing it back,
 * the launcher painted a white border round the widget in the dark theme. It paints the app's task
 * colour there, which came from the manifest's light theme whatever the app's theme was.
 */
class WindowThemeTest {
    private fun res(path: String) = File("src/main/res/$path").readText()

    @Test
    fun `the splash takes the theme picked in the app, or the phone's`() {
        assertEquals(WindowTheme.Start.PHONE, WindowTheme.start(DarkMode.AUTO, alwaysDark = false))
        assertEquals(WindowTheme.Start.DARK, WindowTheme.start(DarkMode.ON, alwaysDark = false))
        assertEquals(WindowTheme.Start.LIGHT, WindowTheme.start(DarkMode.OFF, alwaysDark = false))
        // Battery use's Saver is always dark, whatever was picked.
        for (mode in DarkMode.entries) assertEquals(WindowTheme.Start.DARK, WindowTheme.start(mode, alwaysDark = true))
    }

    @Test
    fun `a task colour is always opaque, as Android asks`() {
        assertEquals(0xFF121212.toInt(), WindowTheme.opaque(0x00121212))
        assertEquals(0xFF000000.toInt(), WindowTheme.opaque(0x80000000.toInt()))
        assertEquals(0xFFFFFFFF.toInt(), WindowTheme.opaque(0xFFFFFFFF.toInt()))
    }

    @Test
    fun `the manifest's theme is dark when the phone is, for the splash and the task`() {
        val styles = res("values/styles.xml")
        val app = styles.substringAfter("<style name=\"Theme.OuterTune\"").substringBefore("</style>")
        assertTrue(app.contains("<item name=\"android:windowBackground\">@color/window_background</item>"))
        // The task's colour, which the launcher paints round the app, comes from colorBackground.
        assertTrue(app.contains("<item name=\"android:colorBackground\">@color/window_background</item>"))
        assertTrue(res("values/colors_window.xml").contains("<color name=\"window_background\">@color/window_background_light</color>"))
        assertTrue(res("values-night/colors_window.xml").contains("<color name=\"window_background\">@color/window_background_dark</color>"))
        // The two splashes the app can pick keep their own colour whatever the phone's mode.
        val light = styles.substringAfter("<style name=\"Theme.OuterTune.StartLight\">").substringBefore("</style>")
        val dark = styles.substringAfter("<style name=\"Theme.OuterTune.StartDark\">").substringBefore("</style>")
        assertTrue(light.contains("@color/window_background_light") && !light.contains("@color/window_background<"))
        assertTrue(dark.contains("@color/window_background_dark"))
    }

    @Test
    fun `the window and the task take the pages' colour, and the splash the theme picked`() {
        val main = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()
        assertTrue(main.contains("val pagesSurface = MaterialTheme.colorScheme.surface"))
        assertTrue(main.contains("WindowTheme.paint(this@MainActivity, pagesSurface.toArgb())"))
        assertTrue(main.contains("WindowTheme.start(darkTheme, alwaysDark = chosenBatteryMode == BatteryMode.SAVER)"))
        assertTrue(main.contains("withContext(Dispatchers.IO) { WindowTheme.startWith(this@MainActivity, start) }"))
        // Before anything is drawn, the window takes the theme picked in the app, on every Android.
        val create = main.substringAfter("override fun onCreate(savedInstanceState: Bundle?) {").substringBefore("super.onCreate(savedInstanceState)")
        assertTrue(create.contains("WindowTheme.startTheme(this)?.let { setTheme(it) }"))
        val window = File("src/main/java/com/dd3boh/outertune/ui/theme/WindowTheme.kt").readText()
        assertTrue(window.contains("Start.DARK.name -> R.style.Theme_OuterTune_StartDark"))
        assertTrue(window.contains("Start.LIGHT.name -> R.style.Theme_OuterTune_StartLight"))
        assertTrue(window.contains("setBackgroundColor(colour)"))
        assertTrue(window.contains("activity.window.setBackgroundDrawable(ColorDrawable(colour))"))
    }

    @Test
    fun `the theme the window starts in stays on the phone, out of the cloud backup and out of a transfer`() {
        // It notes what this phone's splash was told. Carried to a new phone with the settings, it
        // said the splash there was told already, and the cold start followed the phone's dark mode
        // instead of the theme picked in the app.
        val window = File("src/main/java/com/dd3boh/outertune/ui/theme/WindowTheme.kt").readText()
        val prefs = Regex("""private const val PREFS = "([a-z_]+)"""").find(window)!!.groupValues[1]
        assertTrue("getSharedPreferences(PREFS, Context.MODE_PRIVATE)" in window)
        val kept = Regex("""<exclude\s+domain="sharedpref"\s+path="${Regex.escape("$prefs.xml")}"\s*/>""")
        val rules = res("xml/data_extraction_rules.xml")
        assertTrue("cloud backup", kept.containsMatchIn(rules.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")))
        assertTrue("a transfer to a new phone", kept.containsMatchIn(rules.substringAfter("<device-transfer>").substringBefore("</device-transfer>")))
        assertTrue("Android 11 and earlier", kept.containsMatchIn(res("xml/backup_rules.xml")))
    }
}
