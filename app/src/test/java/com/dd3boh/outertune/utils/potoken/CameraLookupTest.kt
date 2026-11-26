/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils.potoken

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #263 (0.12.11 on an Amazon Echo Show 5, Android 11): the WebView's camera lookup threw "Attempt to get length of null
 * array" while BotGuard looked for a camera, and the app died. Only where the cameras cannot be read does the PoToken
 * page lose navigator.mediaDevices; everywhere else BotGuard sees what it always saw.
 */
class CameraLookupTest {

    @Test
    fun `cameras that read whole, or none at all, leave the page alone`() {
        assertFalse(CameraLookup.breaks { listOf(true, true) })
        assertFalse(CameraLookup.breaks { emptyList() })
    }

    @Test
    fun `a list that reads as null, a camera with a gap, or a throw takes the cameras away`() {
        assertTrue(CameraLookup.breaks { null })
        assertTrue(CameraLookup.breaks { listOf(true, false) })
        assertTrue(CameraLookup.breaks { throw NullPointerException("Attempt to get length of null array") })
        assertTrue(CameraLookup.breaks { throw SecurityException("camera service") })
    }

    @Test
    fun `without cameras the page's first script hides them before anything else runs`() {
        val html = File("src/main/assets/po_token.html").readText()
        val page = PoTokenPage.withoutCameras(html)
        val first = page.substringAfter("<script>")
        assertTrue(first, first.trimStart().startsWith(PoTokenPage.NO_CAMERAS))
        assertEquals("the rest is untouched", html, page.replaceFirst("\n${PoTokenPage.NO_CAMERAS}\n", ""))
        for (name in listOf("'mediaDevices'", "'getUserMedia'", "'webkitGetUserMedia'")) assertTrue(name, name in PoTokenPage.NO_CAMERAS)
        // The whole snippet in one try: a WebView that refuses to redefine them still runs the page.
        assertTrue(PoTokenPage.NO_CAMERAS.startsWith("try {") && PoTokenPage.NO_CAMERAS.endsWith("} catch (e) {}"))
    }

    @Test
    fun `the WebView takes the page without cameras only where the lookup breaks`() {
        val webView = File("src/main/java/com/dd3boh/outertune/utils/potoken/PoTokenWebView.kt").readText()
        assertTrue(webView, "if (withContext(Dispatchers.IO) { CameraLookup.breaks(webView.context) }) PoTokenPage.withoutCameras(html) else html" in webView)
        assertTrue(webView, "val data = page.replaceFirst(\"</script>\"" in webView)
        // The switch that forces it, to see BotGuard answer without cameras, is the debug app's alone.
        val lookup = File("src/main/java/com/dd3boh/outertune/utils/potoken/CameraLookup.kt").readText()
        assertTrue(lookup, "(BuildConfig.BUILD_TYPE == \"debug\" && File(context.filesDir, NO_CAMERAS_FILE).exists()) || breaks {" in lookup)
    }
}
