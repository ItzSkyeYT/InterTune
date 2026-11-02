/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * His S25U, 1 Nov 2026: the widget's settings opened as a see-through dialog, so his home screen's own
 * widget showed behind the preview that was meant to sit on the wallpaper alone. Where the preview sits
 * on the wallpaper (Unreleased.WIDGET_ON_WALLPAPER, on in every debuggable build) the screen is a full
 * window that hides the launcher; a release keeps its dialog.
 */
class WidgetConfigThemeTest {
    private val gradle = File("build.gradle.kts").readText()
    private val styles = File("src/main/res/values/styles.xml").readText()

    private fun buildType(name: String) = gradle.substringAfter("\n        $name {").substringBefore("\n        }")
    private fun style(name: String) = styles.substringAfter("<style name=\"$name\"").substringBefore("</style>")

    @Test
    fun `the activity takes the theme its build type names`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val entry = manifest.substringAfter("android:name=\".widget.WidgetConfigActivity\"").substringBefore(">")
        assertTrue("android:theme=\"\${widgetConfigTheme}\"" in entry)
        assertTrue("@style/Theme.InterTune.WidgetConfig\"" in buildType("release"))
        assertTrue("@style/Theme.InterTune.WidgetConfig.Wallpaper\"" in buildType("debug"))
        // Preview builds are made from debug, userdebug from release, so each keeps its own.
        assertTrue("initWith(getByName(\"debug\"))" in gradle.substringAfter("create(\"preview\")").substringBefore("\n        }"))
    }

    @Test
    fun `the flag is on exactly where the build type gives the full window`() {
        val flag = File("src/main/java/com/dd3boh/outertune/constants/Unreleased.kt").readLines()
            .first { "val WIDGET_ON_WALLPAPER" in it }
        assertEquals("val WIDGET_ON_WALLPAPER = BuildConfig.DEBUG", flag.trim())
        assertTrue("isDebuggable" !in buildType("debug") && "isDebuggable" !in buildType("release"))
    }

    @Test
    fun `the full window hides the home screen and shows the wallpaper`() {
        val full = style("Theme.InterTune.WidgetConfig.Wallpaper")
        assertTrue("<item name=\"android:windowIsFloating\">false</item>" in full)
        assertTrue("<item name=\"android:windowIsTranslucent\">false</item>" in full)
        assertTrue("<item name=\"android:windowShowWallpaper\">true</item>" in full)
        assertTrue("<item name=\"android:windowBackground\">@android:color/transparent</item>" in full)
        assertTrue("<item name=\"android:windowIsFloating\">true</item>" in style("Theme.InterTune.WidgetConfig"))
    }
}
