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
 * His screen of 1 Nov 2026: One UI's widget picker showed "Couldn't add widget." where the Now
 * playing widget's preview belongs. The preview layout held a Space, and a launcher may draw only
 * the views RemoteViews allows. Every layout a widget's info names as its preview keeps to them.
 */
class WidgetLayoutViewsTest {
    private val allowed = setOf(
        "FrameLayout", "LinearLayout", "RelativeLayout", "GridLayout", "ImageView", "ImageButton",
        "TextView", "Button", "ProgressBar", "Chronometer", "AnalogClock", "TextClock", "ViewFlipper",
        "ListView", "GridView", "StackView", "AdapterViewFlipper", "CheckBox", "Switch", "RadioButton", "RadioGroup",
    )

    @Test
    fun `every widget preview layout uses only views a launcher may draw`() {
        val infos = File("src/main/res/xml").listFiles { f -> f.name.endsWith("_widget_info.xml") }!!.toList()
        assertTrue("no widget infos found", infos.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (info in infos) {
            val layout = Regex("""android:(?:previewLayout|initialLayout)="@layout/([a-z0-9_]+)"""")
                .findAll(info.readText()).map { it.groupValues[1] }.toSet()
            for (name in layout) {
                // Glance's own loading layout comes from the library, not from this folder.
                val file = File("src/main/res/layout/$name.xml").takeIf { it.exists() } ?: continue
                val xml = file.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
                val tags = Regex("""<([A-Za-z][A-Za-z0-9_.]*)""").findAll(xml).map { it.groupValues[1].substringAfterLast('.') }.toSet()
                assertEquals("$name.xml (${info.name}) has views a launcher may not draw", emptySet<String>(), tags - allowed)
                checked += name
            }
        }
        assertTrue("the Now playing widget's preview was not checked", "widget_preview" in checked)
    }
}
