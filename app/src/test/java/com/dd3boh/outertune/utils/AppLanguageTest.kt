/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.constants.SYSTEM_DEFAULT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The "App language" row (AppLanguage, AppLanguageFrag): its list is the translations the app
 * carries, the same ones Android's own App languages page shows, and the phone's language is
 * asked for by handing the platform no language at all.
 */
class AppLanguageTest {

    private val res = File("src/main/res")
    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** A folder of translations as the generated locale config names it: values-pt-rBR is pt-BR, values-b+sr+Latn is sr-Latn. */
    private fun tagOf(folder: String): String {
        val qualifier = folder.removePrefix("values-")
        return if (qualifier.startsWith("b+")) qualifier.removePrefix("b+").replace('+', '-')
        else qualifier.replace("-r", "-")
    }

    @Test
    fun `the list is the translations present, and English`() {
        val folders = res.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }!!
            .filter { dir -> dir.listFiles()!!.any { it.name.startsWith("strings") } }
            .map { tagOf(it.name) }
        assertTrue(folders.size.toString(), folders.size >= 40)
        assertEquals((folders + "en").sorted(), AppLanguage.TAGS.sorted())
        assertEquals("a language is listed twice", AppLanguage.TAGS.size, AppLanguage.TAGS.toSet().size)
    }

    @Test
    fun `system default hands the platform an empty list`() {
        assertEquals("", AppLanguage.languageTags(SYSTEM_DEFAULT))
        assertEquals("fr", AppLanguage.languageTags("fr"))
        assertEquals("pt-BR", AppLanguage.languageTags("pt-BR"))
        // Nothing the app has no words for is ever asked of the platform.
        assertEquals("", AppLanguage.languageTags("xx"))
    }

    @Test
    fun `what the platform reports is read back as a choice of the list`() {
        assertEquals(SYSTEM_DEFAULT, AppLanguage.choiceFor(null))
        assertEquals(SYSTEM_DEFAULT, AppLanguage.choiceFor(""))
        assertEquals("fr", AppLanguage.choiceFor("fr"))
        // Android's page offers regions of a language: French (France) is our French.
        assertEquals("fr", AppLanguage.choiceFor("fr-FR"))
        assertEquals("pt-BR", AppLanguage.choiceFor("pt-BR"))
        assertEquals("pt", AppLanguage.choiceFor("pt-PT"))
        assertEquals("zh-TW", AppLanguage.choiceFor("zh-Hant-TW"))
        assertEquals("zh-CN", AppLanguage.choiceFor("zh-Hans-CN"))
        assertEquals("sr-Latn", AppLanguage.choiceFor("sr-Latn-RS"))
        assertEquals("sr", AppLanguage.choiceFor("sr-Cyrl-RS"))
        // Java writes Indonesian and Hebrew with their new codes, the folders with the old ones.
        assertEquals("in", AppLanguage.choiceFor("id-ID"))
        assertEquals("iw", AppLanguage.choiceFor("he"))
        assertEquals("nb-NO", AppLanguage.choiceFor("nb-NO"))
        assertEquals(SYSTEM_DEFAULT, AppLanguage.choiceFor("xx"))
    }

    @Test
    fun `the row is the first of the group of the content language`() {
        val screen = File(sources, "ui/screens/settings/LibrarySettings.kt").readText()
        val group = screen.substringAfter("R.string.grp_localization").substringBefore("SettingsClickToReveal")
        val app = group.indexOf("AppLanguageFrag()")
        val content = group.indexOf("LocalizationFrag()")
        assertTrue("the row is not in the group", app >= 0 && content >= 0)
        assertTrue("the row is not the first", app < content)
        // One card for the three rows.
        assertEquals(1, Regex("ElevatedCard").findAll(group).count())
    }

    @Test
    fun `the search of settings finds it by language`() {
        val index = File(sources, "ui/screens/settings/SettingsIndex.kt").readText()
        val entry = index.lines().single { "R.string.app_language" in it }
        assertTrue(entry, "Tour.ROUTE_LIBRARY" in entry && "R.string.search_words_language" in entry)
        assertTrue(index.indexOf("R.string.app_language") < index.indexOf("R.string.youtube_language"))
    }

    @Test
    fun `a change of content language or country loads Home and the suggestions again`() {
        for (file in listOf("viewmodels/HomeViewModel.kt", "viewmodels/OnlineSearchSuggestionViewModel.kt")) {
            val text = File(sources, file).readText()
            assertTrue(file, "ContentLanguageKey" in text && "ContentCountryKey" in text)
        }
    }

    private fun source(path: String) = File(sources, path).readText()

    /** The class files of the components of one kind in the manifest, as (name, text). */
    private fun components(tag: String): List<Pair<String, String>> {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        return Regex("""<$tag\s[^>]*?android:name="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.mapNotNull { name ->
            val relative = if (name.startsWith(".")) name.removePrefix(".") else name.removePrefix("com.dd3boh.outertune.")
            File(sources, relative.replace('.', '/') + ".kt").takeIf { it.exists() }?.let { name to it.readText() }
        }.toList()
    }

    @Test
    fun `every screen that writes words takes the app's language in attachBaseContext`() {
        val writers = mutableListOf<String>()
        for ((name, text) in components("activity")) {
            if (!Regex("""stringResource|getString\(|Toaster\.show|Toast\.make|setContent""").containsMatchIn(text)) continue
            writers += name
            assertTrue("$name writes words but does not wrap its context", "attachBaseContext" in text && "AppLanguage.wrap(" in text)
        }
        assertTrue(writers.toString(), writers.containsAll(listOf(".MainActivity", ".widget.WidgetConfigActivity", ".recognition.PhoneListenActivity")))
    }

    @Test
    fun `a service does not wrap its own context, it reads the app's resources`() {
        // A wrapped context keeps the language it was made in, and a service lives for days:
        // after a change it would go on writing its notification in the old one. The app's own
        // resources are what AppLanguage.applyToProcess keeps in the chosen language.
        val services = components("service") + components("receiver") + components("provider")
        assertTrue(services.map { it.first }.toString(), services.size >= 5)
        for ((name, text) in services) assertFalse("$name wraps its context", "AppLanguage.wrap(" in text)
    }

    @Test
    fun `the app keeps its own words in the language at its start and after every configuration change`() {
        val app = source("App.kt")
        val attach = app.substringAfter("override fun attachBaseContext").substringBefore("override fun onConfigurationChanged")
        assertTrue("the start of the process", "AppLanguage.applyToProcess(base)" in attach)
        val changed = app.substringAfter("override fun onConfigurationChanged").substringBefore("override fun onCreate")
        assertTrue("after a change of the phone's configuration", "AppLanguage.applyToProcess(this)" in changed)
        // Android's own page can change the language under a running app only from 13; before that the
        // only way is the row, which makes the screen again, and a second watcher could only flap.
        assertTrue("and Android's own page, from 13", "SDK_INT >= 33) AppLanguage.follow(this)" in changed)
        assertTrue("not at the start", "AppLanguage.follow(this)" in app.substringAfter("override fun onCreate"))
    }

    @Test
    fun `every screen that is made follows its language before it draws, and what is built with words is made again`() {
        val onCreate = source("MainActivity.kt").substringAfter("override fun onCreate(savedInstanceState: Bundle?)")
        assertTrue("MainActivity", onCreate.indexOf("AppLanguage.follow(this)") in 0 until onCreate.indexOf("setContent"))
        // Home's rows, the media notification's buttons and the widgets were made with the old words.
        for (file in listOf("viewmodels/HomeViewModel.kt", "playback/MusicService.kt", "App.kt")) {
            assertTrue(file, "AppLanguage.changes" in source(file))
        }
    }

    @Test
    fun `the two rows for YouTube are named for YouTube and not for the app`() {
        val ot = File(res, "values/strings-ot.xml").readText()
        fun text(name: String) = Regex("""<string name="$name"[^>]*>([^<]*)</string>""").find(ot)!!.groupValues[1]
        assertTrue(text("youtube_language"), "YouTube" in text("youtube_language"))
        assertTrue(text("youtube_country"), "YouTube" in text("youtube_country"))
        val localization = source("ui/screens/settings/fragments/InterfaceFrag.kt").substringAfter("fun ColumnScope.LocalizationFrag()")
        assertTrue("R.string.youtube_language" in localization && "R.string.youtube_country" in localization)
        // The old "Default content language" was taken for the app's language (a Dutch phone set to
        // English there stayed half Dutch): nothing shows it any more.
        for (file in listOf("ui/screens/settings/fragments/InterfaceFrag.kt", "ui/screens/settings/SettingsIndex.kt", "ui/screens/walkthrough/Walkthrough.kt")) {
            val t = source(file)
            assertFalse(file, "R.string.content_language" in t || "R.string.content_country" in t)
        }
    }

    @Test
    fun `YouTube's locale is worked out in one place, from the language the app speaks`() {
        for (file in listOf("App.kt", "ui/screens/settings/fragments/InterfaceFrag.kt")) {
            val t = source(file)
            assertFalse("$file works out YouTube's locale itself", "YouTubeLocale(" in t || "Locale.getDefault()" in t)
        }
        assertTrue("ContentLocale.of(" in source("ui/screens/settings/fragments/InterfaceFrag.kt"))
        assertTrue("use(of(" in source("utils/ContentLocale.kt"))
    }

    @Test
    fun `a screen's language is the only thing its override names, so it still turns with the phone`() {
        // A copy of the whole configuration froze the orientation and the screen's size in it: MainActivity handles a
        // turn itself, so on Android 7 to 9 with a language picked it kept its portrait shape on its side.
        val wrap = source("utils/AppLanguage.kt").substringAfter("fun wrap(base: Context): Context {").substringBefore("\n    }\n")
        assertTrue(wrap, "val config = Configuration().apply { setLocale(locale) }" in wrap)
        assertFalse(wrap, "Configuration(base.resources.configuration)" in wrap)
        assertTrue("base.createConfigurationContext(config)" in wrap)
    }
}
