/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import com.dd3boh.outertune.R
import com.dd3boh.outertune.ui.screens.walkthrough.Tour
import com.dd3boh.outertune.widget.OfferedWidget
import com.dd3boh.outertune.widget.WidgetStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Settings > Widgets (Unreleased.WIDGETS_PAGE), his ask of 3 Nov 2026 built as the mockup he
 * approved (experiments/features/widget-settings-mock): a card for each widget on the home screen
 * with its live preview, its style and the two choices most changed, All options for its own setup
 * sheet, and the two widgets to add at the foot.
 *
 * Most of it is wiring a screen hides: which widgets get a card, which write a change goes
 * through, when Add is there. So this reads the source, as the other tests of this package do.
 */
class WidgetsSettingsTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** The text without its comments, so that a sentence that names a call is not taken for one. */
    private fun code(text: String) = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    private fun kotlin(path: String) = code(File(sources, path).readText())

    private val page = kotlin("ui/screens/settings/WidgetsSettings.kt")
    private val store = kotlin("widget/WidgetStore.kt")
    private val sheet = kotlin("widget/WidgetConfigActivity.kt")
    private val picker = kotlin("widget/WidgetPicker.kt")

    /** The body of the function [head] starts, up to the brace that closes it. */
    private fun body(text: String, head: String): String {
        val start = text.indexOf(head)
        assertTrue("$head is not there", start >= 0)
        val open = text.indexOf('{', start)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open, i + 1)
            }
        }
        return text.substring(open)
    }

    // ---- the flag, and where the page is listed ----

    @Test
    fun `the flag is on in debug and preview builds and off in a release`() {
        val flags = kotlin("constants/Unreleased.kt")
        assertTrue(flags.lines().any { it.trim() == "val WIDGETS_PAGE = BuildConfig.DEBUG" })
        val released = File("src/test/java/com/dd3boh/outertune/constants/ReleasedFlagsTest.kt").readText()
        assertFalse("WIDGETS_PAGE" in released)
    }

    @Test
    fun `Widgets is listed right under Look and feel, behind the flag, and has its route behind it too`() {
        val list = kotlin("ui/screens/settings/SettingsScreen.kt")
        val row = list.indexOf("if (Unreleased.WIDGETS_PAGE) {\n                PreferenceEntry(\n                    title = { Text(stringResource(R.string.widgets)) }")
        assertTrue("no Widgets row behind its flag", row >= 0)
        assertTrue(row > list.indexOf("navController.navigate(\"settings/appearance\")"))
        assertTrue(row < list.indexOf("navController.navigate(\"settings/player\")"))
        assertTrue("navController.navigate(\"settings/widgets\")" in list.substring(row, list.indexOf("navController.navigate(\"settings/player\")")))
        val nav = kotlin("ui/navigation/AppNavGraph.kt")
        assertTrue(Regex("""if \(Unreleased\.WIDGETS_PAGE\) \{\s*screen\("settings/widgets"\) \{\s*WidgetsSettings\(navController, scrollBehavior\)""").containsMatchIn(nav))
        assertEquals("settings/widgets", Tour.ROUTE_WIDGETS)
    }

    @Test
    fun `in the two panes it is under Look and feel too, and goes with its flag alone`() {
        val with = settingsGroups(engine = true, battery = true, widgets = true)
        val without = settingsGroups(engine = true, battery = true, widgets = false)
        assertEquals(listOf(Tour.ROUTE_LOOK_AND_FEEL, Tour.ROUTE_WIDGETS, "settings/player"), with[1].map { it.route }.take(3))
        assertEquals(R.string.widgets, with[1][1].title)
        assertEquals(with.flatten().map { it.route } - Tour.ROUTE_WIDGETS, without.flatten().map { it.route })
        val panes = kotlin("ui/screens/settings/SettingsTwoPane.kt")
        assertTrue("the pane draws the page", "Tour.ROUTE_WIDGETS -> WidgetsSettings(navController, scrollBehavior)" in panes)
    }

    // ---- the cards ----

    @Test
    fun `the widgets on the home screen are the launcher's own record of the player widget`() {
        val placed = body(store, "fun placed(context: Context)")
        assertTrue(placed, "getAppWidgetIds(ComponentName(context, MusicWidgetReceiver::class.java))" in placed)
        assertTrue("the page reads them there", "WidgetStore.placed(context)" in page)
    }

    @Test
    fun `there is a card for each widget on the home screen, and none without one`() {
        val shown = page.substringAfter("if (placed.isNotEmpty()) {")
        assertTrue("no part for the widgets on the home screen", shown != page)
        val part = body("{" + shown, "{")
        assertTrue("the heading is in the part", "R.string.widgets_on_home_screen" in part)
        assertTrue("a card for each one", Regex("""for \(id in placed\)""").containsMatchIn(part))
        // Called nowhere else: with nothing placed, only the Add part is drawn.
        val calls = Regex("""(?<![A-Za-z])WidgetCard\(""").findAll(page).count()
        assertEquals("WidgetCard is called once and defined once", 2, calls)
        assertTrue(Regex("""(?<![A-Za-z])WidgetCard\(""").containsMatchIn(part))
        assertEquals("the heading is written once", 1, Regex("""R\.string\.widgets_on_home_screen""").findAll(page).count())
        // The Add part is outside, so it is there with no widget at all.
        assertTrue("R.string.widgets_add_title" !in part)
        assertTrue("R.string.widgets_add_title" in page)
    }

    @Test
    fun `each card offers what the setup sheet offers for its style`() {
        assertEquals(listOf(WidgetCardRow.BACKGROUND, WidgetCardRow.BUTTONS, WidgetCardRow.ALL_OPTIONS), widgetCardRows(WidgetStyle.COVER))
        // Full cover is drawn on its cover: the sheet has no background for it either.
        assertEquals(listOf(WidgetCardRow.BUTTONS, WidgetCardRow.ALL_OPTIONS), widgetCardRows(WidgetStyle.FULL_COVER))
        assertEquals(listOf(WidgetCardRow.WHICH_LIST, WidgetCardRow.ALL_OPTIONS), widgetCardRows(WidgetStyle.SHELF))
        assertTrue("if (s.style != WidgetStyle.FULL_COVER)" in sheet.substringAfter("private fun Config(").substringBefore("private val SheetTop"))
        // A Shelf's looks, and only a Shelf's.
        val card = body(page, "private fun WidgetCard(")
        val looks = card.substringAfter("if (s.style == WidgetStyle.SHELF) {")
        assertTrue("LookChoice(" in body("{" + looks, "{"))
        assertEquals(1, Regex("""LookChoice\(""").findAll(card).count())
        assertTrue("the style's tiles are the sheet's", "StyleChoice(" in card)
        // What All options says it holds, for each style.
        assertEquals(R.string.widgets_all_options_cover, allOptionsLine(WidgetStyle.COVER))
        assertEquals(R.string.widgets_all_options_full_cover, allOptionsLine(WidgetStyle.FULL_COVER))
        assertEquals(R.string.widgets_all_options_shelf, allOptionsLine(WidgetStyle.SHELF))
    }

    @Test
    fun `the labels are the setup sheet's own words`() {
        for (name in listOf(
            "widget_config_style", "widget_config_look", "widget_config_background", "widget_config_buttons",
            "widget_config_which_list", "widget_label", "recognise",
            "widget_background_artwork", "widget_buttons_all", "quick_picks",
        )) assertTrue("$name is not on the page", "R.string.$name" in page)
    }

    // ---- a change ----

    @Test
    fun `a change on the page goes through WidgetStore, as the setup sheet's does`() {
        assertTrue("WidgetStore.changeSettings(context, id, " in page)
        assertTrue("WidgetStore.settingsOf(context, id)" in page)
        for (own in listOf("updateAppWidgetState", "getAppWidgetState", "WidgetKeys.", "MusicWidget()")) {
            assertFalse("the page writes $own itself", own in page)
            assertFalse("the sheet writes $own itself", own in sheet)
        }
        assertTrue("WidgetStore.saveSettings(this@WidgetConfigActivity, appWidgetId, settings)" in sheet)
        assertTrue("WidgetStore.settingsOf(this, appWidgetId)" in sheet)
    }

    @Test
    fun `the write is the widget's own state, and that widget is drawn again with it at once`() {
        val save = body(store, "suspend fun saveSettings(")
        val write = save.indexOf("WidgetKeys.write(prefs, settings)")
        val draw = save.indexOf("MusicWidget().update(context, glanceId)")
        assertTrue(save, write >= 0 && draw > write)
        assertTrue("settingsLock.withLock" in save)
        assertTrue("WidgetKeys.read(getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId))" in body(store, "suspend fun settingsOf("))
    }

    @Test
    fun `changes land in the order they were made, after the page has gone, and the widget gets its rows as from the sheet`() {
        val change = body(store, "fun changeSettings(")
        assertTrue(change, "settingsScope.launch(start = CoroutineStart.UNDISPATCHED)" in change)
        val saves = change.indexOf("saveSettings(app, appWidgetId, settings)")
        val fills = change.indexOf("hydrate(app)")
        assertTrue(change, saves >= 0 && fills > saves)
        assertTrue("its own scope, outliving the screen", "private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)" in store)
    }

    @Test
    fun `All options opens the widget's own setup sheet, over the app and back to the page`() {
        assertTrue("context.startActivity(WidgetConfigActivity.edit(context, id))" in page)
        val edit = body(sheet, "fun edit(context: Context, appWidgetId: Int)")
        assertTrue(edit, "putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)" in edit)
        assertFalse("a task of its own would not come back to the page", "FLAG_ACTIVITY_NEW_TASK" in edit)
        // Back on the page, every card reads its settings again: the sheet may have changed any of them.
        assertTrue("Lifecycle.Event.ON_RESUME" in page)
    }

    // ---- adding one ----

    @Test
    fun `Add asks the launcher for its own box, and is not there where the launcher cannot take a widget`() {
        val can = picker.lines().single { "fun canPinWidgets(" in it }
        assertTrue(can, "Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported" in can)
        assertEquals("requestPinAppWidget is asked in one place", 1, Regex("""\.requestPinAppWidget\(""").findAll(picker).count())
        assertTrue("requestPinAppWidget" !in page)
        assertTrue("val canPin = remember { canPinWidgets(context) }" in page)
        assertEquals(2, Regex("""onAdd = if \(canPin\) \{ \{ add\(OfferedWidget\.\w+\) \} \} else null""").findAll(page).count())
        val row = body(page, "private fun WidgetAddRow(")
        assertTrue("the button only with something to do", "if (onAdd != null)" in row)
        // Where it cannot, the page says why and where to add them instead.
        assertTrue("if (!canPin)" in page && "R.string.widgets_add_from_home" in page)
    }

    @Test
    fun `the two widgets to add are the launcher's own, at the size they are dropped at`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        for (widget in OfferedWidget.entries) {
            val receiver = widget.receiver.simpleName
            val entry = manifest.substringAfter("android:name=\".widget.$receiver\"").substringBefore("</receiver>")
            val xml = Regex("""android:resource="@xml/(\w+)"""").find(entry)!!.groupValues[1]
            val info = File("src/main/res/xml/$xml.xml").readText()
            fun attribute(name: String) = Regex("""android:$name="([^"]+)"""").find(info)!!.groupValues[1]
            assertEquals("$receiver's width in cells", attribute("targetCellWidth").toInt(), widget.cellsWide)
            assertEquals("$receiver's height in cells", attribute("targetCellHeight").toInt(), widget.cellsHigh)
            val layout = R.layout::class.java.fields.single { it.getInt(null) == widget.picture }.name
            assertEquals("$receiver's picture is the picker's", "@layout/$layout", attribute("previewLayout"))
        }
        assertEquals(listOf("MusicWidgetReceiver", "RecognitionWidgetReceiver"), OfferedWidget.entries.map { it.receiver.simpleName })
    }

    // ---- the words ----

    private val english = File("src/main/res/values/strings-ot.xml").readText()
    private val french = File("src/main/res/values-fr/strings-ot.xml").readText()

    private val words = listOf(
        Triple("widgets", "Widgets", "Widgets"),
        Triple("settings_widgets_description", "Add widgets to your home screen, and change how each one looks.", "Ajoutez des widgets à votre écran d\\'accueil, et changez l\\'apparence de chacun."),
        Triple("widgets_on_home_screen", "On your home screen", "Sur votre écran d\\'accueil"),
        Triple("widgets_add_title", "Add a widget", "Ajouter un widget"),
        Triple("widgets_add", "Add", "Ajouter"),
        Triple("widgets_all_options", "All options", "Toutes les options"),
        Triple("widgets_all_options_cover", "Opacity, text size, what it shows", "Opacité, taille du texte, ce qu\\'il affiche"),
        Triple("widgets_all_options_full_cover", "Text size, what it shows", "Taille du texte, ce qu\\'il affiche"),
        Triple("widgets_all_options_shelf", "Background, buttons, rows", "Arrière-plan, boutons, lignes"),
        Triple("widgets_add_from_home", "This home screen does not take widgets from apps. Add them from its own widget list.", "Cet écran d\\'accueil n\\'accepte pas les widgets des applications. Ajoutez-les depuis sa propre liste de widgets."),
    )

    @Test
    fun `the words are the ones that were drawn, in English and in French`() {
        for ((name, en, fr) in words) {
            assertTrue("$name in English", "<string name=\"$name\" translatable=\"false\">$en</string>" in english)
            assertTrue("$name in French", "<string name=\"$name\">$fr</string>" in french)
        }
        // A size in cells is figures and a sign, the same in every language.
        assertTrue("<string name=\"widgets_cells\" translatable=\"false\">%1\$d × %2\$d</string>" in english)
        assertFalse("name=\"widgets_cells\"" in french)
    }

    @Test
    fun `the words are short, plain and without a long dash`() {
        for ((name, en, fr) in words) for (text in listOf(en, fr)) {
            assertFalse("$name has a long dash", '\u2014' in text || '\u2013' in text)
            assertTrue("$name runs to ${text.length} characters", text.length <= 120)
        }
    }

    @Test
    fun `every one of the words is read by the code`() {
        val used = page + kotlin("ui/screens/settings/SettingsScreen.kt") + kotlin("ui/screens/settings/SettingsTwoPane.kt")
        for ((name, _, _) in words) assertTrue("$name is not used", "R.string.$name" in used)
        assertTrue("R.string.widgets_cells" in page)
    }

    @Test
    fun `the preview is drawn again when the covers arrive`() {
        // A widget just added was drawn before its covers were on the phone and kept a note for its
        // cover (seen on the emulator, 4 Nov 2026): the covers in the store now redraw it.
        val config = java.io.File("src/main/java/com/dd3boh/outertune/widget/WidgetConfigActivity.kt").readText()
        val live = config.substringAfter("private fun LiveWidget(").substringBefore("BoxWithConstraints(")
        assertTrue("WidgetStore.drawn.collectAsState()" in live)
        assertTrue("LaunchedEffect(settings, size, redraw, covers)" in live)
    }
}
