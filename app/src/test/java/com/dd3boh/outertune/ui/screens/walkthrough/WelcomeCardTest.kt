/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.walkthrough

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The card at the top of Home that the welcome back page waits behind: who gets one, what it
 * says, and that it stands aside for whatever else has the screen. A card for somebody with
 * nothing new to be shown, or one that came back after it was dismissed, would be the page's
 * nuisance again in a smaller size.
 */
class WelcomeCardTest {

    private val v0109 = 80          // somebody still on 0.10.9
    private val v011 = 91           // 0.11 as it shipped
    private val v0111 = 92          // 0.11.1, fixes only: it took the number 0.11.5 was to have
    private val v0115 = 93          // the first build of 0.11.5

    private val main = File("src/main/java/com/dd3boh/outertune")

    private fun card(
        seen: Int,
        build: Int = v0115,
        install: Install = Install(),
        setupDone: Boolean = true,
        questionsOpen: Boolean = false,
        tourUp: Boolean = false,
        pageOpen: Boolean = false,
        lookedAt: Set<String> = emptySet(),
    ) = welcomeCardFor(
        seenVersionCode = seen,
        buildVersionCode = build,
        install = install,
        enabled = true,
        setupDone = setupDone,
        questionsOpen = questionsOpen,
        tourUp = tourUp,
        pageOpen = pageOpen,
        lookedAt = lookedAt,
    )

    // Who gets one

    @Test
    fun `a first install gets no card`() {
        assertNull(card(seen = 0))
        assertNull(card(seen = -1))
    }

    @Test
    fun `somebody on 0_11 who opens 0_11_5 gets one, saying how many things and since when`() {
        assertEquals(WelcomeCard(count = 3, since = "0.11"), card(seen = v011))
    }

    @Test
    fun `somebody up to date gets none`() {
        assertNull(card(seen = v0115, build = v0115))
        assertNull(card(seen = v011, build = v011))
    }

    @Test
    fun `0_11_1 brings no card, and somebody on it gets the one for 0_11_5`() {
        assertNull(card(seen = v011, build = v0111))
        assertNull(card(seen = v0111, build = v0111))
        assertEquals(WelcomeCard(count = 3, since = "0.11"), card(seen = v0111))
    }

    @Test
    fun `seen or dismissed it stays away, and the next update with something new brings another`() {
        // Everything looked at, or the cross on the card, marks the build somebody is on as seen.
        assertNotNull(card(seen = v0109, build = v011))
        assertNull("it came back at the next launch", card(seen = v011, build = v011))
        assertNotNull("0.11.5 brought nothing to say", card(seen = v011, build = v0115))
        assertNull(card(seen = v0115, build = v0115))
    }

    @Test
    fun `with the flag off there is no card`() {
        assertNull(welcomeCardFor(seenVersionCode = v011, buildVersionCode = v0115, enabled = false))
        val walkthrough = File(main, "ui/screens/walkthrough/Walkthrough.kt").readText()
        assertTrue("the card is not behind the flag", "enabled: Boolean = Unreleased.WELCOME_BACK" in walkthrough)
    }

    // What it says

    @Test
    fun `the number is the number of things the page lists, on this install`() {
        for (seen in listOf(0, v0109, 88, v011, v0111, v0115)) for (build in listOf(v011, v0111, v0115)) {
            for (install in listOf(Install(quickPicksChips = true), Install(quickPicksChips = false))) {
                val listed = newThingsFor(seen, buildVersionCode = build, install = install).size
                val said = card(seen = seen, build = build, install = install)?.count ?: 0
                assertEquals("from $seen to $build, chips ${install.quickPicksChips}", listed, said)
            }
        }
        // One of them is Quick picks, which is not on every install.
        assertEquals(5, card(seen = v0109, build = v011)!!.count)
        assertEquals(4, card(seen = v0109, build = v011, install = Install(quickPicksChips = false))!!.count)
    }

    @Test
    fun `what has been looked at is not counted, and with all of it looked at there is no card`() {
        val things = newThingsFor(v011, buildVersionCode = v0115).map { it.id }
        assertEquals(3, things.size)
        assertEquals(WelcomeCard(count = 2, since = "0.11"), card(seen = v011, lookedAt = setOf(things[0])))
        assertEquals(WelcomeCard(count = 1, since = "0.11"), card(seen = v011, lookedAt = setOf(things[0], things[2])))
        assertNull("everything looked at", card(seen = v011, lookedAt = things.toSet()))
        // The walk round Settings is not one of the new things: looking at it alone changes nothing.
        assertEquals(WelcomeCard(count = 3, since = "0.11"), card(seen = v011, lookedAt = setOf(SETTINGS_WALK)))
    }

    @Test
    fun `a build is named by the release it belongs to, as people know it`() {
        assertEquals("0.11", releaseAt(v011))
        assertEquals("0.11", releaseAt(88))
        assertEquals("a fix release is known by the release it fixes", "0.11", releaseAt(v0111))
        assertEquals("0.11.5", releaseAt(v0115))
        assertEquals("what 0.12 brings names the builds from its own on", "0.12", releaseAt(95))
        assertEquals("and 0.12.1, a fix release, as well", "0.12", releaseAt(96))
        assertEquals("until what 0.12.5 brings", "0.12.5", releaseAt(v0115 + 30))
    }

    @Test
    fun `somebody from before anything the list knows is given the number alone`() {
        assertNull(releaseAt(v0109))
        assertNull(releaseAt(0))
        assertEquals(WelcomeCard(count = NEW_THINGS.count { it.sinceVersionCode <= v0115 }, since = null), card(seen = v0109))
    }

    @Test
    fun `no release is named by a number`() {
        // "since 91" is what the card must never say.
        for (code in 0..120) assertTrue("build $code", releaseAt(code)?.contains('.') ?: true)
    }

    // Standing aside

    @Test
    fun `not while the setup, the questions, a tour or the page itself is up`() {
        assertNotNull(card(seen = v011))
        assertNull(card(seen = v011, setupDone = false))
        assertNull(card(seen = v011, questionsOpen = true))
        assertNull(card(seen = v011, tourUp = true))
        assertNull(card(seen = v011, pageOpen = true))
    }

    // Where it is, and what its two buttons do. Lines of the activity and of Home, read there.

    @Test
    fun `the page no longer opens by itself`() {
        val activity = File(main, "MainActivity.kt").readText()
        val atLaunch = activity
            .substringAfter("LaunchedEffect(oobeStatus, catchUpOpen, pendingStops, welcomeOwed, updatePromptVisible) {")
            .substringBefore("// Asked for from Settings, under About.")
        assertFalse("something at launch opens the page", "welcomeOpen = true" in atLaunch)
        // Nor does the tour's handful of new stops start in its place.
        assertTrue("pendingStops.isNotEmpty() && !welcomeOwed" in atLaunch)
    }

    @Test
    fun `See opens the page as it used to open, and the cross marks this build as seen and opens nothing`() {
        val activity = File(main, "MainActivity.kt").readText()
        val see = activity.substringAfter("val seeWelcome = remember {").substringBefore("}")
        assertTrue("welcomeOpen = true" in see)
        assertTrue("it would list everything, as the page asked for from Settings does", "welcomeEverything = false" in see)
        assertFalse("looking is not yet having seen", "setWalkthroughSeen" in see)
        val cross = activity.substringAfter("val dismissWelcome = remember {").substringBefore("\n                        }")
        assertTrue("setWalkthroughSeen(BuildConfig.VERSION_CODE)" in cross)
        assertTrue("the ticks are let go with it", "setWelcomeLookedAt(\"\")" in cross)
    }

    @Test
    fun `closing the page leaves the card on Home while something on it is unseen`() {
        // His word of 16 Oct 2026: the card went away though he had not looked at everything.
        val activity = File(main, "MainActivity.kt").readText()
        val done = activity.substringAfter("onShowSettings = {").substringAfter("onDone = {").substringBefore("},")
        assertTrue("welcomeOpen = false" in done)
        assertFalse("closing the page is not having seen it all", "setWalkthroughSeen" in done)
    }

    @Test
    fun `every new thing looked at marks the build as seen, once the page and its tours are closed`() {
        val activity = File(main, "MainActivity.kt").readText()
        val effect = activity.substringAfter("// Every new thing looked at").substringBefore("\n                        }\n")
        assertTrue("owedThings.all { it.id in welcomeLookedAt }" in effect)
        for (closed in listOf("!welcomeOpen", "!tourState.running", "!tourFromWelcome")) assertTrue(closed, closed in effect)
        assertTrue("setWalkthroughSeen(BuildConfig.VERSION_CODE)" in effect)
        assertTrue("setWelcomeLookedAt(\"\")" in effect)
    }

    @Test
    fun `the ticks are kept on the phone, so they are still there when the page is opened again`() {
        val activity = File(main, "MainActivity.kt").readText()
        assertTrue("rememberPreference(WelcomeLookedAtKey, defaultValue = \"\")" in activity)
        assertTrue("seen = welcomeLookedAt," in activity)
        assertTrue("the card counts what is left", "lookedAt = welcomeLookedAt," in activity)
        val see = activity.substringAfter("val seeWelcome = remember {").substringBefore("}")
        assertFalse("See does not wipe them", "setWelcomeLookedAt" in see)
    }

    @Test
    fun `a tour from the page ends where it is, with the page closed and the card waiting on Home`() {
        // His word of 16 Oct 2026: Done should leave him in the setting to look at it, and Back
        // take him Home, where the card still is, not back to the page.
        val activity = File(main, "MainActivity.kt").readText()
        val fromPage = activity.substringAfter("onFinish = {").substringAfter("if (tourFromWelcome) {").substringBefore("} else {")
        assertTrue("tourFromWelcome = false" in fromPage)
        assertFalse("the page does not come back by itself", "welcomeOpen = true" in fromPage)
        assertFalse("nothing is popped from under the setting", "popBackStack" in fromPage)
    }

    @Test
    fun `a tour asked for under About does not send a waiting card away`() {
        // The end of any other tour marks the build as seen, which is what takes the card off.
        val activity = File(main, "MainActivity.kt").readText()
        val finish = activity.substringAfter("onFinish = {").substringBefore("SnackbarHost(")
        assertTrue("if (!welcomeOwed) setWalkthroughSeen(BuildConfig.VERSION_CODE)" in finish)
    }

    @Test
    fun `the page stays under About for whoever sent the card away`() {
        val about = File(main, "ui/screens/settings/AboutScreen.kt").readText()
        assertTrue("R.string.welcome_back_entry" in about)
        assertTrue("navController.navigate(\"whatsnew\")" in about)
    }

    @Test
    fun `Home draws it where its other cards are, first of them`() {
        val home = File(main, "ui/screens/HomeScreen.kt").readText()
        val card = home.indexOf("item(key = \"welcome_card\")")
        assertTrue("Home has no card", card > 0)
        assertTrue(home.indexOf("item(key = \"throttle_banner\")") < card)
        assertTrue(card < home.indexOf("item(key = \"announcement_banner\")"))
        assertTrue(card < home.indexOf("item(key = \"poll_banner\")"))
    }

    @Test
    fun `its words are open to translators with their French, and are plain`() {
        val strings = File("src/main/res/values/strings-ot.xml").readLines()
        val french = File("src/main/res/values-fr/strings-ot.xml").readText()
        for (name in listOf("welcome_card", "welcome_card_since", "welcome_card_see", "welcome_card_dismiss")) {
            val at = strings.indexOfFirst { "name=\"$name\"" in it }
            assertTrue("$name is not in strings-ot.xml", at >= 0)
            assertFalse("$name is held back from translators", "translatable=\"false\"" in strings[at])
            assertTrue("$name has no French", "name=\"$name\"" in french)
            // A plural's words are on the lines under its name.
            val words = strings.drop(at).takeWhile { "</plurals>" !in it }.take(if ("<plurals" in strings[at]) 4 else 1)
            for (line in words) {
                val text = line.substringAfter('>')
                assertFalse("$name has an exclamation mark", '!' in text)
                assertFalse("$name has a long dash", 0x2014.toChar() in text)
            }
        }
        // English needs both forms: "1 new thing", "2 new things".
        for (name in listOf("welcome_card", "welcome_card_since")) {
            val at = strings.indexOfFirst { "name=\"$name\"" in it }
            val forms = strings.drop(at + 1).takeWhile { "</plurals>" !in it }
            assertEquals("$name", listOf("one", "other"), forms.map { it.substringAfter("quantity=\"").substringBefore('"') })
        }
    }
}
