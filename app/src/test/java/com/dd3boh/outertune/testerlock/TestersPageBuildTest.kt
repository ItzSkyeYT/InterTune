/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.testerlock

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Science
import com.dd3boh.outertune.R
import com.dd3boh.outertune.ui.screens.settings.SettingsCategory
import com.dd3boh.outertune.ui.screens.settings.SettingsSource
import com.dd3boh.outertune.ui.screens.settings.settingsGroups
import com.dd3boh.outertune.ui.screens.settings.settingsOpened
import com.dd3boh.outertune.utils.ProblemLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Testers page of Settings (TestersPage.kt), read from its source as the other tests of the lock are: the page is in the
 * Preview and the Tester app, which the unit tests do not compile, and the emulator is where it was seen
 * (experiments/features/testers-page). What they hold here is that it exists only where the lock is on, that main knows it
 * only through the build, that a report carries the tester's number and never the code, and that every row is in the search.
 */
class TestersPageBuildTest {

    private val dir = "src/preview/java/com/dd3boh/outertune/testerlock"
    private val page = File("$dir/TestersPage.kt").readText()
    private val lock = File("$dir/TesterLock.kt").readText()
    private val model = File("src/preview/jvm/com/dd3boh/outertune/testerlock/TestersPageScreen.kt").readText()
    private val developer = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/fragments/DeveloperFrag.kt").readText()
    private val build = File("build.gradle.kts").readText()

    private fun main(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    private fun files(sourceSet: String): List<File> =
        File("src/$sourceSet").walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java", "xml") }.toList()

    // --- only where the lock is on

    @Test
    fun `the Testers page is the lock's, and every build without the lock answers that it has none`() {
        assertTrue("internal val testers: ExtraSettingsPage? = if (BuildConfig.TESTER_LOCK) TestersPage else null" in lock)
        for (sourceSet in listOf("debug", "release")) {
            val standIn = File("src/$sourceSet/java/com/dd3boh/outertune/testerlock/TesterLock.kt").readText()
            assertTrue(sourceSet, "internal val testers: ExtraSettingsPage? = null" in standIn)
            assertTrue(sourceSet, "fun testerNumber(context: Context): Int? = null" in standIn)
        }
        // Three answers, one for each kind of build, and one page: nothing else gives Settings a page.
        val all = files("main") + files("debug") + files("release") + files("preview") + files("beta")
        assertEquals(3, all.sumOf { Regex("val testers: ExtraSettingsPage\\?").findAll(it.readText()).count() })
        assertEquals(listOf("TestersPage.kt"), all.filter { Regex(":\\s*ExtraSettingsPage\\b").containsMatchIn(it.readText().replace("val testers: ExtraSettingsPage?", "")) }.map { it.name })
        // And the number: none without the lock on.
        assertTrue("if (!BuildConfig.TESTER_LOCK) return null" in lock.substringAfter("fun testerNumber(context: Context): Int? {"))
    }

    @Test
    fun `Settings asks the build for the page in the list, the panes, the route and the search, and nowhere else`() {
        val asking = listOf(
            "ui/screens/settings/SettingsScreen.kt", "ui/screens/settings/SettingsTwoPane.kt", "ui/screens/settings/SettingsIndex.kt",
            "ui/navigation/AppNavGraph.kt",
        )
        for (path in asking) assertTrue(path, "TesterLock.testers?" in main(path))
        // Only the build's answer is used, and only as a question that may come back empty.
        for (file in files("main")) assertFalse(file.path, Regex("TesterLock\\.testers(?!\\?)").containsMatchIn(SettingsSource.blank(file.readText())))
        // MainActivity and the report dialog are the others that ask the lock anything.
        val mentions = files("main").filter { "TesterLock" in it.readText() }.map { it.name }.sorted()
        assertEquals(
            listOf(
                "AppNavGraph.kt", "ExtraSettingsPage.kt", "MainActivity.kt", "ProblemReportDialog.kt", "SettingsIndex.kt",
                "SettingsScreen.kt", "SettingsTwoPane.kt",
            ),
            mentions,
        )
        assertTrue("TesterLock.testers?.let { testers ->" in main("ui/navigation/AppNavGraph.kt"))
        assertTrue("screen(testers.category.route) {" in main("ui/navigation/AppNavGraph.kt"))
        assertTrue("else -> TesterLock.testers?.takeIf { it.category.route == route }?.Screen(navController, scrollBehavior)" in main("ui/screens/settings/SettingsTwoPane.kt"))
        assertTrue("SETTINGS_INDEX + TesterLock.testers?.entries.orEmpty()" in main("ui/screens/settings/SettingsIndex.kt"))
    }

    @Test
    fun `the Testers card is the first of the upright list, and its group the first of the two panes`() {
        val list = main("ui/screens/settings/SettingsScreen.kt")
        // In the stacked list and in the left column of the cards set two by two.
        assertEquals(2, Regex("testers\\(\\)\\s+you\\(\\)").findAll(list).count())
        assertTrue("TesterLock.testers?.let { page ->\n            page.ListCard(navController)" in list)
        // The panes: a group before the four, which the tour does not point at.
        val panes = main("ui/screens/settings/SettingsTwoPane.kt")
        assertTrue("listOfNotNull(testers?.let { listOf(it) }) + listOf(" in panes)
        assertTrue("val groupTargets = List(groups.size - 4) { null } + listOf(Tour.SETTINGS_YOU, Tour.SETTINGS_LOOK_AND_SOUND, Tour.SETTINGS_KEPT, Tour.SETTINGS_REST)" in panes)
        assertTrue("modifier = groupTargets[at]?.let { Modifier.tourTarget(it) } ?: Modifier," in panes)
    }

    @Test
    fun `with the page the list has a group before the four, without it the four, and the panes open the page`() {
        val category = SettingsCategory("settings/testers", R.string.about, Icons.Rounded.Science)
        val with = settingsGroups(testers = category)
        val without = settingsGroups(testers = null)
        assertEquals(5, with.size)
        assertEquals(listOf(category), with.first())
        assertEquals(without, with.drop(1))
        assertEquals(4, without.size)
        // The page is a screen of the list: the panes open it in place of what is open, and the first thing they show.
        val listed = with.flatten().map { it.route }
        assertEquals("settings/testers", listed.first())
        assertEquals(listOf("settings/testers"), settingsOpened(listOf("settings/player"), "settings/testers", listed, emptyList()))
    }

    // --- the report: the number, never the code

    @Test
    fun `a report from a build for testers names the tester by number, in its heading`() {
        val withNumber = ProblemLog.report(ProblemLog.excerpt(emptyList()), build = "b", device = "d", installedFrom = "F-Droid", testerNumber = 7)
        assertTrue(withNumber, withNumber.startsWith("InterTune b\nd\nInstalled from: F-Droid\nTester number: 7\n"))
        // Cut for the form's link, the heading stays whole, so the number does.
        val long = ProblemLog.report(
            ProblemLog.excerpt(List(60) { "10-13 18:00:00.000 D/MusicService( 6711): a line number $it of the many that led up to it" }),
            build = "b", device = "d", installedFrom = "F-Droid", testerNumber = 7,
        )
        assertTrue(ProblemLog.fitted(long, 500).startsWith("InterTune b\nd\nInstalled from: F-Droid\nTester number: 7\n"))
        // Any other report has no such line.
        val without = ProblemLog.report(ProblemLog.excerpt(emptyList()), build = "b", device = "d", installedFrom = "F-Droid")
        assertFalse(without, "Tester" in without)
    }

    @Test
    fun `a report holds the number a code stands for and nothing of the code`() {
        for (shown in listOf("07SH-9NAP-4KCT", "0ZEK-VRDD-V483", "ZZRK-MVDQ-BJE9")) {
            val number = TesterCode.numberOf(shown)
            assertNotNull(shown, number)
            val report = ProblemLog.report(ProblemLog.excerpt(emptyList()), build = "b", device = "d", installedFrom = "F-Droid", testerNumber = number)
            val symbols = TesterCode.normalise(shown)
            assertTrue(report, "Tester number: $number\n" in report)
            for (piece in listOf(shown, symbols, symbols.substring(2), symbols.substring(4), symbols.takeLast(4), shown.substringBeforeLast("-"))) {
                assertFalse("$piece in $report", piece in report)
            }
        }
    }

    @Test
    fun `the report dialog takes the number from the build and the code from nobody`() {
        val dialog = main("ui/component/ProblemReportDialog.kt")
        assertEquals(listOf("TesterLock.testerNumber(context)"), Regex("TesterLock\\.\\w+\\([^)]*\\)").findAll(dialog).map { it.value }.toList())
        assertTrue("testerNumber = testerNumber," in dialog)
        // Every run listed carries it, an earlier run's too: it is the sender's.
        // This run's, beside the settings and steps (Unreleased.REPORT_PLUS), and every earlier run's.
        assertTrue("testerNumber = tester, settings = extrasNow?.settings.orEmpty(), trail = extrasNow?.steps," in dialog)
        assertTrue("installedFrom, phoneNow, testerNumber = tester, settings = extras?.settings.orEmpty())" in dialog)
        // The number is what the lock hands out: from the code kept, through the one function that reads a number from it.
        assertTrue("TesterLockStore(context.applicationContext).load().code?.let { TesterCode.numberOf(it) }" in lock)
    }

    @Test
    fun `the code is shown masked, once, and goes nowhere from the page`() {
        // The page reads it only as the masked form and the last four symbols its description holds.
        assertFalse(Regex("\\.code\\b").containsMatchIn(page))
        assertEquals(1, Regex("memory\\.code").findAll(model).count())
        assertTrue("maskedCode = code?.let { TesterCode.masked(it) }?.takeIf { it.isNotEmpty() }," in model)
        assertTrue("codeEnd = code?.let { TesterCode.lastSymbols(it) }.orEmpty()," in model)
        // Not copied, logged, sent, shared or put on a screen as a toast, by the page or by what it is made from.
        for (text in listOf(page, model)) {
            for (leaves in listOf(
                "android.util.Log", "ClipData", "ClipboardManager", "LocalClipboard", "setPrimaryClip", "Toast", "println", "EXTRA_TEXT",
                "createChooser", "ACTION_SEND", "SharedPreferences", "putString",
            )) {
                assertFalse(leaves, leaves in text)
            }
            assertFalse(Regex("\\bLog\\.").containsMatchIn(text))
        }
        // And the lock's own file writes nothing to the log.
        assertFalse(Regex("\\bLog\\.").containsMatchIn(lock))
    }

    // --- the words

    private fun words(path: String) = Regex("<string name=\"(testers_page_\\w+)\">(.*?)</string>").findAll(File(path).readText())
        .associate { it.groupValues[1] to it.groupValues[2] }

    private val english = words("src/preview/res-lock/values/strings-tester.xml")
    private val french = words("src/preview/res-lock/values-fr/strings-tester.xml")

    @Test
    fun `every word of the page is in English and in French, and the page uses all of them and no others`() {
        assertTrue(english.size >= 25)
        assertEquals(english.keys.toList(), french.keys.toList())
        val used = Regex("R\\.string\\.(testers_page_\\w+)").findAll(page).map { it.groupValues[1] }.toSet()
        assertEquals(english.keys, used)
    }

    @Test
    fun `the words are short and plain, with no dash standing in for a comma and no shout`() {
        for ((name, text) in english + french.mapKeys { "fr:${it.key}" }) {
            assertFalse(name, "—" in text || "–" in text)
            assertFalse(name, "!" in text)
            assertFalse(name, "seamless" in text.lowercase() || "simply" in text.lowercase())
            // Two or three short sentences at most, each as the app's other notes are.
            val sentences = Regex("[.?](?=\\s|$)").findAll(text).count()
            assertTrue("$name: $sentences sentences", sentences <= 3)
            assertTrue("$name: ${text.length} characters", text.length <= 130)
        }
    }

    @Test
    fun `the words say what the design asked of them`() {
        val rules = english.getValue("testers_page_rules_text")
        assertTrue(rules, "Try the builds" in rules && "report what breaks" in rules && "Keep builds and codes to yourself" in rules)
        val back = english.getValue("testers_page_give_back_text")
        assertEquals("Take the Tester role off in Channels &amp; Roles on Discord. Your spot goes to the next person waiting.", back)
        assertTrue(french.getValue("testers_page_give_back_text"), "rôle Tester dans Salons et rôles sur Discord" in french.getValue("testers_page_give_back_text"))
        assertEquals("Testers", english.getValue("testers_page_title"))
        assertEquals("#testers", english.getValue("testers_page_channel"))
        assertEquals("#testers", french.getValue("testers_page_channel"))
        assertEquals("Check now", english.getValue("testers_page_check_now"))
        assertEquals("Used on this device", english.getValue("testers_page_code_used"))
        assertEquals("Test switches", english.getValue("testers_page_switches"))
        assertEquals("What to try", english.getValue("testers_page_try"))
        assertEquals("Your tester code", english.getValue("testers_page_code"))
        assertTrue(english.getValue("testers_page_report_description"), "never your code" in english.getValue("testers_page_report_description"))
    }

    @Test
    fun `the words are the lock's alone, out of Weblate's files and out of every other build`() {
        val elsewhere = File("src").walkTopDown()
            .filter { it.isFile && it.extension in setOf("xml", "kt", "java") && "testers_page_" in it.readText() }
            .map { it.path.replace('\\', '/') }
            .filterNot { it.endsWith("strings-tester.xml") || it.endsWith("TestersPage.kt") || it.startsWith("src/test/") }.toList()
        assertTrue(elsewhere.toString(), elsewhere.isEmpty())
    }

    // --- the order of the page

    @Test
    fun `the page has its parts in the order the design gave`() {
        val body = page.substringAfter("private fun Testers(")
        val order = listOf(
            "R.string.testers_page_build)", "R.string.testers_page_try", "R.string.testers_page_switches", "R.string.problem_report_title",
            "R.string.testers_page_code)", "R.string.testers_page_channel", "R.string.testers_page_rules", "R.string.testers_page_give_back",
        )
        val at = order.map { body.indexOf(it) }
        assertTrue("$order at $at", at.all { it >= 0 })
        assertEquals("$order at $at", at.sorted(), at)
        // This build: its name and when it was made and what the last check said.
        assertTrue("description = BuildConfig.VERSION_NAME," in body)
        assertTrue("TestersPageScreen.builtAt(BuildConfig.VERSION_NAME)" in body)
        assertTrue("description = stringResource(screen.result.words())," in body)
        // What to try shows nothing without a list.
        assertTrue("if (tryLines.isNotEmpty()) {" in body)
        assertTrue("TestersPageScreen.tryLines(readTryFile(context))" in body)
        assertTrue("context.assets.open(TestersPageScreen.TRY_ASSET)" in page)
    }

    @Test
    fun `the test switches are the developer block's own rows, and the page makes no switch of its own`() {
        assertEquals(1, Regex("fun FamiliarFirstRows\\(").findAll(developer).count())
        assertTrue("internal val familiarFirstOffered: Boolean get() = BuildConfig.DEBUG" in developer)
        assertTrue("if (familiarFirstOffered) {\n            FamiliarFirstRows()\n        }" in developer)
        assertTrue("if (familiarFirstOffered) {\n            PreferenceGroupTitle(title = stringResource(R.string.testers_page_switches))" in page)
        assertEquals(1, Regex("FamiliarFirstRows\\(\\)").findAll(page).count())
        // The developer block holds one copy of each row, in that function.
        assertEquals(1, Regex("title = \\{ Text\\(stringResource\\(R\\.string\\.familiar_first\\)\\) \\}").findAll(developer).count())
        assertEquals(1, Regex("title = \\{ Text\\(stringResource\\(R\\.string\\.familiar_mix\\)\\) \\}").findAll(developer).count())
        // No new switch, no new key, no new flag.
        for (invented in listOf("SwitchPreference(", "booleanPreferencesKey", "rememberPreference", "Unreleased.")) assertFalse(invented, invented in page)
        assertFalse(Regex("val .* = BuildConfig\\.DEBUG").containsMatchIn(page))
        // The same two keys the home screen reads under the same test.
        val home = main("viewmodels/HomeViewModel.kt")
        assertTrue("BuildConfig.DEBUG && context.dataStore[FamiliarFirstKey] == true" in home)
    }

    @Test
    fun `the report row opens the app's own report page, and the #testers rows its channel`() {
        assertTrue("if (reporting) ProblemReportDialog(onDismiss = { reporting = false })" in page)
        assertTrue("onClick = { reporting = true }," in page)
        // The page makes no form, no address and no report of its own.
        for (own in listOf("Links.", "ISSUES", "template=", "ProblemLog", "bug_report")) assertFalse(own, own in page)
        assertEquals(0, Regex("https?://").findAll(page).count())
        assertEquals(1, Regex("TestersPageScreen\\.CHANNEL").findAll(page).count())
        assertEquals(2, Regex("openTesters\\(context\\)").findAll(page).count())
        assertTrue("catch (e: ActivityNotFoundException)" in page.substringAfter("private fun openTesters("))
    }

    @Test
    fun `Check now runs the lock's own check, once at a time, and the lock keeps what it says for the page`() {
        assertTrue("onClick = { session.checkNow() }," in page)
        assertTrue("isEnabled = !checking," in page)
        val now = lock.substringAfter("fun checkNow() {").substringBefore("\n    }\n")
        assertTrue(now, "if (shown.value != null || checking?.isActive == true) return" in now)
        assertTrue(now, "val code = store.load().code ?: return" in now)
        assertTrue(now, "check(code)" in now)
        val check = lock.substringAfter("private fun check(code: String) {").substringBefore("\n    }\n")
        assertTrue(check, "working.value = true" in check)
        assertTrue(check, "}.also { it.invokeOnCompletion { working.value = false } }" in check)
        // What is kept is told to the page right after it is written, and the gate's own answer is unchanged.
        assertTrue(check, check.indexOf("store.save(after.memory)") < check.indexOf("remembered.value = after.memory"))
        assertTrue(check, "shown.value = if (after.opens) null" in check)
    }

    // --- every row in the search

    private class Row(val component: String, val title: List<String>, val mark: List<String>, val description: List<String>)

    private fun rows(source: String): List<Row> {
        val text = SettingsSource.blank(source)
        return Regex("""\b(PreferenceEntry|SwitchPreference)\s*\(""").findAll(text).map { match ->
            val open = match.range.last
            val args = text.substring(open + 1, SettingsSource.close(text, open))
            fun names(argument: String) = SettingsSource.argument(args, argument)?.let { Regex("""R\.string\.(\w+)""").findAll(it).map { r -> r.groupValues[1] }.toList() }.orEmpty()
            Row(match.groupValues[1], names("title"), Regex("""settingRow\(R\.string\.(\w+)\)""").findAll(SettingsSource.argument(args, "modifier").orEmpty()).map { it.groupValues[1] }.toList(), names("description"))
        }.toList()
    }

    /** The rows of the page that are readings or lead to the page itself, not settings to search for, and why. */
    private val leftOut = mapOf(
        "testers_page_title" to "The card that leads to the page: the search finds the page by its name.",
        "testers_page_version" to "A reading of this build.",
        "testers_page_built" to "A reading of this build.",
        "testers_page_status" to "A reading of the lock.",
        "testers_page_no_code" to "The code, masked: a reading, and what the lock keeps.",
        "testers_page_last_check" to "A reading of the lock.",
        "testers_page_next_check" to "A reading of the lock.",
        "testers_page_rules" to "The rules, to read.",
    )

    @Test
    fun `every row of the page that is a setting carries the mark of its title, and is in the search index`() {
        val onPage = rows(page)
        assertTrue(onPage.size.toString(), onPage.size >= 11)
        val unmarked = onPage.filter { it.mark.isEmpty() && it.title.none { title -> title in leftOut } }
        assertTrue("rows with no mark and no reason: " + unmarked.map { it.title }, unmarked.isEmpty())
        // The mark is made from the row's own title: that is how a result finds it.
        for (row in onPage.filter { it.mark.isNotEmpty() }) assertEquals(row.title.toString(), row.title, row.mark)
        // The developer block's rows that the page draws are marked in the code both draw them with.
        val shared = rows(developer.substringAfter("internal fun FamiliarFirstRows() {").substringBefore("\n}\n"))
        assertEquals(listOf("familiar_first", "familiar_mix"), shared.flatMap { it.mark })
        for (row in shared) assertEquals(row.title.toString(), row.title, row.mark)

        val marked = (onPage + shared).flatMap { it.mark }.toSet()
        val entries = page.substringAfter("override val entries = listOf(").substringBefore("\n    )\n").lines()
            .mapNotNull { Regex("""SettingEntry\(ROUTE, R\.string\.(\w+)(?:, R\.string\.(\w+))?""").find(it) }
        val indexed = entries.map { it.groupValues[1] }
        assertEquals("every row in the index once, and nothing in it without a row", marked, indexed.toSet())
        assertEquals(indexed.toString(), indexed.size, indexed.toSet().size)
        // An entry's description is the row's own.
        for (entry in entries) {
            val described = entry.groupValues[2]
            val row = (onPage + shared).first { entry.groupValues[1] in it.mark }
            if (described.isNotEmpty()) assertTrue("${entry.value}: the row has ${row.description}", described in row.description)
        }
        // The search is not to find what is not on the screen: the test switches are there only where the page draws them.
        for (name in listOf("familiar_first", "familiar_mix")) {
            assertTrue(name, entries.first { it.groupValues[1] == name }.value.isNotEmpty())
            assertTrue(name, "there = familiarFirstOffered" in page.lines().first { "R.string.$name," in it && "SettingEntry" in it })
        }
    }

    @Test
    fun `the page is reached by the route the entry and the index name, from one place`() {
        assertEquals(1, Regex("\"settings/testers\"").findAll(page).count())
        assertTrue("private const val ROUTE = \"settings/testers\"" in page)
        assertEquals(6, Regex("SettingEntry\\(ROUTE").findAll(page).count())
        assertTrue("override val category = SettingsCategory(ROUTE, R.string.testers_page_title, Icons.Rounded.Science)" in page)
        assertTrue("onClick = { navController.navigate(ROUTE) }," in page)
    }

    // --- what to try is baked in at build time

    @Test
    fun `-PtesterTry copies a file into the build as an asset for the two builds that have the page`() {
        assertEquals(1, Regex("findProperty\\(\"testerTry\"\\)").findAll(build).count())
        assertEquals(TestersPageScreen.TRY_ASSET, "tester_try.txt")
        assertTrue("copyTo(File(dir, \"${TestersPageScreen.TRY_ASSET}\"), overwrite = true)" in build)
        assertTrue("if (variant.buildType != \"preview\" && variant.buildType != \"beta\") return@onVariants" in build)
        assertTrue("variant.sources.assets?.addGeneratedSourceDirectory(copy, CopyTesterTry::assets)" in build)
        // No property, no asset: the property is the only way in, and the page then shows nothing.
        assertTrue("?.takeIf { it.isNotBlank() } ?: return@onVariants" in build)
        // A path that is not a file stops the build.
        assertTrue("throw GradleException(\"-PtesterTry=\$path is not a file\")" in build)
        // And it is not one of the lock's values: the lock's own build checks count what they count.
        assertFalse("intertune.testerTry" in build)
        assertFalse("testerTry" in File("../gradle.properties").readText())
        // The page reads the asset by the same name and nothing is shipped in a source folder.
        assertTrue(files("preview").none { it.path.contains("assets") && it.name == TestersPageScreen.TRY_ASSET })
        assertTrue(files("beta").none { it.name == TestersPageScreen.TRY_ASSET })
    }

    @Test
    fun `main has none of it`() {
        for (file in files("main") + files("debug") + files("release") + files("beta")) {
            val text = file.readText()
            for (name in listOf("TestersPage", "TestersScreen", "testers_page_", "tester_try")) assertFalse("${file.path}: $name", name in text)
        }
    }
}
