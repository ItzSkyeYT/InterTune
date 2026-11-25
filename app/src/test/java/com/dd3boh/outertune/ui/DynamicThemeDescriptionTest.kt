/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What Settings > Look and feel says of Enable dynamic theme is what the switch does: with it on the
 * app's colours follow the cover of the song playing, and with it off, or with nothing playing, they
 * are the phone's own (a tester's report: the row said "from your wallpaper", which is what it does
 * when it is off).
 */
class DynamicThemeDescriptionTest {

    private val english = File("src/main/res/values/strings-ot.xml").readText()
    private val french = File("src/main/res/values-fr/strings-ot.xml").readText()
    private val main = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()
    private val theme = File("src/main/java/com/dd3boh/outertune/ui/theme/Theme.kt").readText()

    private fun description(text: String): String =
        Regex("""<string name="enable_dynamic_theme_description"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.get(1).orEmpty().replace("\\'", "'")

    @Test
    fun `the description says the colours come from the cover of the song playing, in English and in French`() {
        val en = description(english)
        val fr = description(french)
        // Ti-Die's shorter words since 20 Nov 2026 (his review of the texts), the cover still named.
        assertTrue(en, en.startsWith("The app's colours follow the cover of the song playing."))
        assertTrue(fr, fr.startsWith("Les couleurs de l'application suivent la pochette du titre en cours."))
    }

    @Test
    fun `the wallpaper is the phone's own colours when it is off or nothing plays, not what the switch turns on`() {
        for (text in listOf(description(english), description(french))) {
            assertFalse(text, text.contains("from your wallpaper"))
            assertFalse(text, text.contains("depuis votre fond d'écran"))
        }
        // It says what happens with the switch off and with nothing playing, once, in the second sentence.
        // The off state is no longer spelled out (the shorter words); what stays is never calling it the wallpaper.
    }

    @Test
    fun `the words are as short as a setting's note is, with no em dash`() {
        for (text in listOf(description(english), description(french))) {
            assertTrue(text, Regex("""[.!?](\s|$)""").findAll(text).count() <= 3)
            assertFalse(text, text.contains('\u2014'))
        }
    }

    @Test
    fun `the search still finds the row by wallpaper and Material You`() {
        val words = Regex("""<string name="search_words_dynamic_theme"[^>]*>(.*?)</string>""").find(english)?.groupValues?.get(1).orEmpty()
        assertTrue(words, words.contains("wallpaper") && words.contains("Material You"))
        val fr = Regex("""<string name="search_words_dynamic_theme"[^>]*>(.*?)</string>""").find(french)?.groupValues?.get(1).orEmpty()
        assertTrue(fr, fr.contains("fond d\\'écran") && fr.contains("Material You"))
    }

    @Test
    fun `what the text says is what the code does`() {
        // On: the colour is taken from the cover of the song shown, a small thumbnail, and follows each song. The song
        // shown, not only the one playing: before Play (after an update or a restart) it is the stored queue's, so the
        // app no longer sits in the default colour until Play (24 Nov 2026).
        val effect = main.substringAfter("LaunchedEffect(playerConnection, enableDynamicTheme, isSystemInDarkTheme)").substringBefore("OuterTuneTheme(")
        assertTrue(effect.contains("playerConnection.mediaMetadata.collectLatest { song ->"))
        assertTrue(effect.contains("song.getThumbnailModel(100, 100)"))
        assertTrue(effect.contains("extractThemeSource()"))
        // Off: the default colour, which is not taken from any song. With no song shown at all, the default too.
        assertTrue(Regex("""if \(!enableDynamicTheme\) \{\s*themeColor = DefaultThemeColor""").containsMatchIn(effect))
        assertTrue(Regex("""if \(song == null\) \{[^}]*themeColor = DefaultThemeColor""").containsMatchIn(effect))
        // The default colour is the phone's own where Android has them (12 and newer), and the app's red below.
        assertTrue(theme.contains("if (themeColor == DefaultThemeColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)"))
        assertTrue(theme.contains("dynamicDarkColorScheme(context)"))
        assertEquals("MainActivity reads the switch once", 1, Regex("""DynamicThemeKey, defaultValue = true""").findAll(main).count())
    }
}
