/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune

import android.content.pm.PackageManager
import com.dd3boh.outertune.utils.OpenLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * "Open links in InterTune": the release app, a Preview and a Debug build all declared the same
 * links on MainActivity, and Android handed each link to one of them with no way to choose from
 * inside the app. The links now sit on a small activity of their own (OpenLinksActivity), which
 * hands them to MainActivity and that the app enables and disables itself (OpenLinks). This holds
 * the manifest to what that needs: every link, host and path as it was, and nothing that is not a
 * link to open (a voice search, the launcher, Share) behind the switch. What Android then does
 * with a link needs a device.
 *
 * It is not an alias of MainActivity. An activity started under an alias is named after the
 * alias, and Android finishes the activities of a component that gets disabled ("disabled-package"
 * on an emulator, Android 17): the switch closed the app when it was turned off from inside one
 * that a link had started. Nothing that stays running may be named after what the switch turns off.
 */
class OpenLinksTest {

    private class Filter(val autoVerify: Boolean, val actions: Set<String>, val categories: Set<String>, val data: List<Map<String, String>>) {
        fun all(attribute: String) = data.mapNotNull { it["android:$attribute"] }.toSet()
    }

    private val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))

    private fun NodeList.elements(): List<Element> = (0 until length).map { item(it) as Element }

    private fun filtersOf(component: Element): List<Filter> =
        component.getElementsByTagName("intent-filter").elements().map { filter ->
            fun named(tag: String) = filter.getElementsByTagName(tag).elements().map { it.getAttribute("android:name") }.toSet()
            Filter(
                autoVerify = filter.getAttribute("android:autoVerify") == "true",
                actions = named("action"),
                categories = named("category"),
                data = filter.getElementsByTagName("data").elements().map { data ->
                    (0 until data.attributes.length).associate { data.attributes.item(it).nodeName to data.attributes.item(it).nodeValue }
                },
            )
        }

    private val main = manifest.getElementsByTagName("activity").elements().single { it.getAttribute("android:name") == ".MainActivity" }
    private val aliases = manifest.getElementsByTagName("activity-alias").elements()
    private val links = manifest.getElementsByTagName("activity").elements().single { it.getAttribute("android:name") == ".OpenLinksActivity" }
    private val onMain = filtersOf(main)
    private val onLinks = filtersOf(links)

    private val view = "android.intent.action.VIEW"
    private val tag = "android.nfc.action.NDEF_DISCOVERED"
    private val playFromSearch = "android.media.action.MEDIA_PLAY_FROM_SEARCH"
    private val browsable = setOf("android.intent.category.DEFAULT", "android.intent.category.BROWSABLE")

    // The activity itself

    @Test
    fun `the links are on one small activity of their own, exported and on in the manifest, and no alias carries them`() {
        // An alias of MainActivity closed the app when it was turned off from inside one a link had started.
        assertEquals("no alias in the app: ${aliases.map { it.getAttribute("android:name") }}", 0, aliases.size)
        // A filter on a component with no exported attribute fails to install from Android 12.
        assertEquals("true", links.getAttribute("android:exported"))
        // Written out, though it is the default: a build nobody has asked takes the links as before.
        assertEquals("true", links.getAttribute("android:enabled"))
        // It shows nothing and is not a place to be: no window, not in the recents, no history, no task of its own to keep.
        assertEquals("@android:style/Theme.NoDisplay", links.getAttribute("android:theme"))
        assertEquals("true", links.getAttribute("android:excludeFromRecents"))
        assertEquals("true", links.getAttribute("android:noHistory"))
        assertTrue(links.hasAttribute("android:taskAffinity") && links.getAttribute("android:taskAffinity").isEmpty())
        // What it hands the link to takes it into its own task, one at a time.
        assertEquals("singleTask", main.getAttribute("android:launchMode"))
    }

    @Test
    fun `the code switches the activity the manifest declares`() {
        val gradle = File("build.gradle.kts").readText()
        val namespace = Regex("""namespace = "([\w.]+)"""").find(gradle)!!.groupValues[1]
        assertEquals(namespace + links.getAttribute("android:name"), OpenLinks.COMPONENT)
        assertTrue(File("src/main/java/" + OpenLinks.COMPONENT.replace('.', '/') + ".kt").isFile)
    }

    @Test
    fun `the activity hands the intent, as it came, to MainActivity and finishes, showing nothing`() {
        val source = File("src/main/java/com/dd3boh/outertune/OpenLinksActivity.kt").readText()
        assertTrue(source, "class OpenLinksActivity : Activity()" in source)
        val onCreate = source.substringAfter("override fun onCreate(").substringBefore("\n    companion object")
        // MainActivity reads the link as one that started it directly (launchLink, newIntentLink).
        assertTrue(onCreate, "startActivity(handedOn(intent, ComponentName(this, MainActivity::class.java)))" in onCreate)
        assertTrue(source, "Intent(intent)" in source && ".setComponent(target)" in source)
        // Its own excludeFromRecents is added to the intent it is started with: handed on, it would keep the app out of the recents.
        assertEquals("true", links.getAttribute("android:excludeFromRecents"))
        val dropped = "flags = flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS.inv()"
        assertTrue(source, dropped in source)
        assertTrue(source, source.indexOf(dropped) < source.indexOf(".setComponent(target)"))
        // Intent.removeFlags is Android 8 and later (lint: NewApi); the app runs from Android 7.
        assertFalse(source, ").removeFlags(" in source || " removeFlags(" in source.substringAfter("internal fun handedOn"))
        // Finished before onCreate returns: a NoDisplay activity that is not is an error.
        assertTrue(onCreate, onCreate.indexOf("startActivity(") in 0 until onCreate.indexOf("finish()"))
        for (window in listOf("setContent", "setContentView", "setTheme", "requestWindowFeature")) assertFalse(window, window in source)
    }

    // What sits on it

    @Test
    fun `the share page's two paths, verified, as they were`() {
        val page = onLinks.single { "intertune.app" in it.all("host") }
        assertTrue("autoVerify", page.autoVerify)
        assertEquals(setOf(view), page.actions)
        assertEquals(browsable, page.categories)
        assertEquals(setOf("https"), page.all("scheme"))
        assertEquals(setOf("intertune.app"), page.all("host"))
        assertEquals(setOf("/s", "/s/"), page.all("path"))
        for (attribute in listOf("pathPrefix", "pathPattern", "pathAdvancedPattern", "pathSuffix")) assertTrue(attribute, page.all(attribute).isEmpty())
    }

    @Test
    fun `YouTube and YouTube Music's hosts and pages, verified, as they were`() {
        val youtube = onLinks.single { "music.youtube.com" in it.all("host") }
        assertTrue("autoVerify", youtube.autoVerify)
        assertEquals(setOf(view, tag), youtube.actions)
        assertEquals(browsable, youtube.categories)
        assertEquals(setOf("http", "https"), youtube.all("scheme"))
        assertEquals(setOf("youtube.com", "m.youtube.com", "www.youtube.com", "music.youtube.com"), youtube.all("host"))
        assertEquals(setOf("/v/", "/embed/", "/watch", "/channel/", "/user/", "/c/", "/playlist"), youtube.all("pathPrefix"))
        assertTrue(youtube.all("path").isEmpty())
    }

    @Test
    fun `youtu_be, verified, as it was`() {
        val short = onLinks.single { "youtu.be" in it.all("host") }
        assertTrue("autoVerify", short.autoVerify)
        assertEquals(setOf(view, tag), short.actions)
        assertEquals(browsable, short.categories)
        assertEquals(setOf("http", "https"), short.all("scheme"))
        assertEquals(setOf("youtu.be"), short.all("host"))
        assertEquals(setOf("/"), short.all("pathPrefix"))
    }

    @Test
    fun `YouTube's own schemes, as they were`() {
        val schemes = onLinks.single { "vnd.youtube" in it.all("scheme") }
        assertFalse("these were never verified: they are no web address", schemes.autoVerify)
        assertEquals(setOf(view, tag), schemes.actions)
        assertEquals(browsable, schemes.categories)
        assertEquals(setOf("vnd.youtube", "vnd.youtube.launch"), schemes.all("scheme"))
        assertTrue(schemes.all("host").isEmpty())
    }

    @Test
    fun `those four are all it holds, and the domains Android verifies are the six it always asked for`() {
        assertEquals(4, onLinks.size)
        val verified = onLinks.filter { it.autoVerify }.flatMap { it.all("host") }.toSet()
        assertEquals(setOf("intertune.app", "youtube.com", "m.youtube.com", "www.youtube.com", "music.youtube.com", "youtu.be"), verified)
        // A tag with a link on it is a link to open too: it follows the switch with the others.
        assertTrue(onLinks.all { tag in it.actions && view in it.actions || it.actions == setOf(view) })
    }

    // What stays on MainActivity

    @Test
    fun `play from search stays on MainActivity itself, where no switch reaches it, and the activity of the links does not name it`() {
        val plain = onMain.filter { playFromSearch in it.actions }
        assertEquals(1, plain.size)
        assertTrue("no data: a voice request carries only its words", plain.single().data.isEmpty())
        assertTrue("the default category: startActivity finds only those", "android.intent.category.DEFAULT" in plain.single().categories)
        assertTrue("the activity of the links names play from search", onLinks.none { playFromSearch in it.actions })
    }

    @Test
    fun `MainActivity keeps no web address, no YouTube scheme and no tag, and of its links only the app's own scheme`() {
        val withData = onMain.filter { view in it.actions && it.data.isNotEmpty() }
        assertEquals("the app's own scheme, and nothing else", listOf(setOf("intertune")), withData.map { it.all("scheme") })
        assertTrue(onMain.none { tag in it.actions })
        assertTrue(onMain.none { it.autoVerify })
        val hosts = onMain.flatMap { it.all("host") }
        assertTrue(hosts.toString(), hosts.isEmpty())
    }

    @Test
    fun `the launcher, the phone's search and Share stay on MainActivity`() {
        assertTrue(onMain.any { "android.intent.action.MAIN" in it.actions && "android.intent.category.LAUNCHER" in it.categories })
        assertTrue(onMain.any { "android.intent.action.SEARCH" in it.actions })
        assertTrue(onMain.any { "android.intent.action.SEND" in it.actions && it.all("mimeType") == setOf("text/plain") })
    }

    @Test
    fun `no other component of the app takes a web address`() {
        val others = manifest.getElementsByTagName("*").elements()
            .filter { it.tagName in setOf("activity", "activity-alias", "service", "receiver", "provider") && it != main && it != links }
        val withWeb = others.filter { c -> filtersOf(c).any { f -> f.all("scheme").any { it == "http" || it == "https" } } }
        assertTrue(withWeb.joinToString { it.getAttribute("android:name") }, withWeb.isEmpty())
    }

    // The switch

    @Test
    fun `a state counts as on when it is default or enabled, because the manifest enables the activity`() {
        assertTrue(OpenLinks.isOn(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT))
        assertTrue(OpenLinks.isOn(PackageManager.COMPONENT_ENABLED_STATE_ENABLED))
        assertFalse(OpenLinks.isOn(PackageManager.COMPONENT_ENABLED_STATE_DISABLED))
        assertFalse(OpenLinks.isOn(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER))
        assertFalse(OpenLinks.isOn(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED))
    }

    @Test
    fun `the switch writes enabled or disabled, never default, and reads back what it wrote`() {
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, OpenLinks.stateFor(true))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, OpenLinks.stateFor(false))
        assertNotEquals(OpenLinks.stateFor(true), OpenLinks.stateFor(false))
        for (on in listOf(true, false)) assertEquals(on, OpenLinks.isOn(OpenLinks.stateFor(on)))
    }

    @Test
    fun `Android's own page for the links is Open by default from Android 12, the app's own page before`() {
        assertEquals("android.settings.APP_OPEN_BY_DEFAULT_SETTINGS", OpenLinks.androidPageAction(31))
        assertEquals("android.settings.APP_OPEN_BY_DEFAULT_SETTINGS", OpenLinks.androidPageAction(36))
        assertEquals("android.settings.APPLICATION_DETAILS_SETTINGS", OpenLinks.androidPageAction(30))
        assertEquals("android.settings.APPLICATION_DETAILS_SETTINGS", OpenLinks.androidPageAction(24))
    }

    @Test
    fun `links open in the app only when it takes them and Android lets it`() {
        assertTrue(OpenLinks.opens(ours = true, androidAllows = true))
        assertFalse(OpenLinks.opens(ours = false, androidAllows = true))
        assertFalse(OpenLinks.opens(ours = true, androidAllows = false))
        assertFalse(OpenLinks.opens(ours = false, androidAllows = false))
    }

    @Test
    fun `Android's own switch is read for the app's own package and never written, and a reading that fails counts as on`() {
        val source = File("src/main/java/com/dd3boh/outertune/utils/OpenLinks.kt").readText()
        assertTrue(source, "getDomainVerificationUserState(context.packageName)" in source && "isLinkHandlingAllowed" in source)
        // An app cannot write it (a permission only the system holds), and must not try.
        for (writer in listOf("setDomainVerificationLinkHandlingAllowed", "setDomainVerificationUserSelection", "setDomainVerificationStatus")) {
            assertFalse(writer, writer in source)
        }
        // Before Android 12 there is no such switch.
        assertTrue(source, "Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true" in source)
        assertTrue(source, "}.onFailure { Log.w(TAG, \"Could not read Android's link setting\", it) }.getOrNull() ?: true" in source)
        // And the way into Android's page falls back to the app's page where that page is not there.
        assertTrue(source, "Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app" in source)
    }

    @Test
    fun `it changes the state without closing the app`() {
        val source = File("src/main/java/com/dd3boh/outertune/utils/OpenLinks.kt").readText()
        val call = source.substringAfter("setComponentEnabledSetting(").substringBefore(")\n")
        // Flag 0 kills the app's process: DONT_KILL_APP is what lets the switch be flipped while it is open.
        assertTrue(call, "PackageManager.DONT_KILL_APP" in call)
        assertEquals("one place writes it", 1, Regex("""setComponentEnabledSetting\(""").findAll(source).count())
    }

    @Test
    fun `the row is the only thing that writes it, and it sits beside Links you share`() {
        val sources = File("src/main/java/com/dd3boh/outertune")
        val writers = sources.walkTopDown().filter { it.extension == "kt" && "OpenLinks.set(" in it.readText() }.map { it.name }.toList()
        assertEquals(listOf("InterfaceFrag.kt"), writers)
        val library = File(sources, "ui/screens/settings/LibrarySettings.kt").readText()
        val share = library.indexOf("ShareLinksFrag()")
        val open = library.indexOf("OpenLinksFrag()")
        val filters = library.indexOf("ContentFiltersFrag()")
        assertTrue("$share $open $filters", share in 0 until open && open < filters)
    }
}
