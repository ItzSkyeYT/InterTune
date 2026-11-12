/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.constants

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app sends people to the wiki on intertune.app (his word of 8 Nov 2026: "some of the pages in
 * the app still link to the old github wiki, head tracking included").
 */
class WikiLinksTest {

    @Test
    fun `no page of the app links to the GitHub wiki`() {
        val old = File("src/main").walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .filter { "github.com/ItzSkyeYT/InterTune/wiki" in it.readText() }.map { it.path }.toList()
        assertTrue("still on the GitHub wiki: $old", old.isEmpty())
    }

    @Test
    fun `each page is a page of the site's wiki`() {
        // The wiki's pages on intertune.app, by their names there (experiments/site/sources/wiki, lower case).
        val pages = setOf("", "audio-offload/", "head-tracking/")
        for (link in listOf(WikiLinks.HOME, WikiLinks.AUDIO_OFFLOAD, WikiLinks.HEAD_TRACKING)) {
            assertTrue(link, link.startsWith("https://intertune.app/wiki/") && link.removePrefix("https://intertune.app/wiki/") in pages)
        }
    }
}
