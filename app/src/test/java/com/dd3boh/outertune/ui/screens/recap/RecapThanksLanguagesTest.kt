/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Recap's last page, his words written once for every language of the app (24 Nov 2026, "just this once we can translate it
 * correctly to each language? so it feels personal"), each in a file of its own outside the files Weblate writes, with the
 * donors thanked as in English (his "acknowledge the donors too").
 */
class RecapThanksLanguagesTest {
    private val res = File("src/main/res")

    @Test
    fun `every language of the app has the last page, whole, and thanks the donors in English and French`() {
        // Every language the app has strings for, those with only the older strings.xml included (25 Nov 2026: 19 more).
        val languages = res.listFiles()!!.filter { it.name.startsWith("values-") && (File(it, "strings-ot.xml").exists() || File(it, "strings.xml").exists()) }
            .map { it.name.removePrefix("values-") }.filter { it != "en-rCA" && it != "fr" }
        assertTrue(languages.size >= 47)
        for (lang in languages) {
            val file = File(res, "values-$lang/strings-recap-thanks.xml")
            assertTrue("$lang has no last page", file.exists())
            val text = file.readText()
            val title = Regex("""<string name="recap_thanks_title">(.+?)</string>""").find(text)?.groupValues?.get(1).orEmpty()
            val words = Regex("""<string name="recap_thanks_words">(.+?)</string>""").find(text)?.groupValues?.get(1).orEmpty()
            assertTrue(lang, title.isNotBlank())
            assertTrue(lang, "InterTune" in words && words.length > 90)
            assertFalse("$lang: a long dash", "—" in words || "–" in words)
            // Kept out of the files Weblate writes, so a sync never drops it.
            for (weblate in listOf("strings-ot.xml", "strings.xml")) {
                val other = File(res, "values-$lang/$weblate")
                if (other.exists()) assertFalse("$lang/$weblate", "recap_thanks_" in other.readText())
            }
        }
        val english = File(res, "values/strings-ot.xml").readText()
        assertTrue("a big thank you to everyone who donated" in english)
        assertTrue("grand merci à tous ceux qui ont fait un don" in File(res, "values-fr/strings-ot.xml").readText())
        assertEquals(1, Regex("""name="recap_thanks_words" translatable="false"""").findAll(english).count())
    }
}
