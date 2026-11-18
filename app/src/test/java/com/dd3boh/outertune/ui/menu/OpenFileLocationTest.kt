/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.menu

import com.dd3boh.outertune.ui.menu.OpenFileLocation.Ask
import com.dd3boh.outertune.ui.menu.OpenFileLocation.Finding
import com.dd3boh.outertune.ui.menu.OpenFileLocation.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Open file location offers every file manager on the phone, not only the ones that answer the
 * storage provider's document. File managers answer different intents (Files by Google the
 * document, ZArchiver "resource/folder" with a file:// address, Samsung's My Files its own action
 * and a path), so the folder is asked for in four ways and the apps that answer any of them are
 * shown once, in one picker.
 *
 * From Android 11 an app sees only the apps its manifest names, so every way of asking must be in
 * the manifest's <queries> too: one that is not finds nobody, and the app that needed it is
 * silently missing from the picker. Read from the source and the manifest.
 */
class OpenFileLocationTest {

    private val menus = File("src/main/java/com/dd3boh/outertune/ui/menu")
    private val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))

    private fun code(file: File) = file.readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""(^|\s)//.*$""", RegexOption.MULTILINE), "$1")

    /** The <intent> entries of the manifest's <queries>: the action, and the scheme, host and mime type of its data. */
    private fun declared(): List<Ask> {
        val found = mutableListOf<Ask>()
        val queries = manifest.getElementsByTagName("queries")
        for (q in 0 until queries.length) {
            val intents = (queries.item(q) as Element).getElementsByTagName("intent")
            for (i in 0 until intents.length) {
                val intent = intents.item(i) as Element
                val action = (intent.getElementsByTagName("action").item(0) as Element).getAttribute("android:name")
                val data = intent.getElementsByTagName("data").item(0) as Element?
                fun attr(name: String) = data?.getAttribute("android:$name")?.takeIf { it.isNotEmpty() }
                found += Ask(action, attr("scheme"), attr("host"), attr("mimeType"))
            }
        }
        return found
    }

    private fun finding(pkg: String, ask: Ask) = Finding(pkg, "$pkg.Main", ask)

    // --- the four ways of asking, and the manifest that must name each

    @Test
    fun `the folder is asked for the four ways file managers answer`() {
        val asks = OpenFileLocation.asks
        assertTrue(asks.any { it.action == "android.intent.action.VIEW" && it.scheme == "content" && it.mimeType == "vnd.android.document/directory" })
        assertTrue(asks.any { it.action == "android.intent.action.VIEW" && it.scheme == "file" && it.mimeType == "resource/folder" })
        assertTrue(asks.any { it.action == "android.intent.action.VIEW" && it.scheme == "file" && it.mimeType == "inode/directory" })
        assertTrue(asks.any { it.action == "samsung.myfiles.intent.action.LAUNCH_MY_FILES" })
        assertEquals("no way twice", asks.size, asks.toSet().size)
        // The document first: it is what the system's own picker lists, and what was asked for before.
        assertEquals(OpenFileLocation.documentAsk, asks.first())
    }

    @Test
    fun `Samsung's My Files gets the folder as a path in the extra it reads`() {
        // My Files reads this extra with getStringExtra (LaunchPages.setPageInfo), found in a decompiled copy of the app.
        val text = code(File(menus, "OpenFileLocation.kt"))
        assertTrue("samsung.myfiles.intent.extra.START_PATH" in text)
        assertTrue(Regex("""samsungAsk\s*->\s*intent\.putExtra\(SAMSUNG_START_PATH,\s*folder\)""").containsMatchIn(text))
    }

    @Test
    fun `every way of asking is declared in the manifest's queries`() {
        val declared = declared()
        for (ask in OpenFileLocation.asks) {
            assertTrue("$ask is not an <intent> of <queries> in AndroidManifest.xml, so Android 11 and later would hide the apps that answer it:\n$declared", ask in declared)
        }
    }

    // --- one entry per app, the picker only when there is something to add

    @Test
    fun `nothing found is tried as before, and the apps the document finds are left to the system`() {
        val own = "dev.skye.intertune"
        assertEquals(Plan.None, OpenFileLocation.plan(emptyList(), emptyList(), own))
        assertEquals(Plan.Document, OpenFileLocation.plan(listOf("files"), emptyList(), own))
        // Two apps answer the document and nobody else does: the system's own list, with its Always, as before.
        assertEquals(Plan.Document, OpenFileLocation.plan(listOf("files", "xapk"), emptyList(), own))
        // The same app twice (two activities) is one app.
        assertEquals(Plan.Document, OpenFileLocation.plan(listOf("files", "files"), emptyList(), own))
    }

    @Test
    fun `one app that wants its own way is asked directly`() {
        val zip = finding("zarchiver", OpenFileLocation.resourceFolderAsk)
        assertEquals(Plan.Single(zip), OpenFileLocation.plan(emptyList(), listOf(zip), "dev.skye.intertune"))
    }

    @Test
    fun `two or more apps are one picker, the document as its intent and the other ways given to it`() {
        val own = "dev.skye.intertune"
        val myFiles = finding("com.sec.android.app.myfiles", OpenFileLocation.samsungAsk)
        val zip = finding("zarchiver", OpenFileLocation.resourceFolderAsk)
        // A phone where Files and the XAPK installer answer the document, and ZArchiver and My Files do not.
        val picker = OpenFileLocation.plan(listOf("files", "xapk"), listOf(myFiles, zip), own)
        assertEquals(
            Plan.Picker(OpenFileLocation.documentAsk, listOf(OpenFileLocation.samsungAsk, OpenFileLocation.resourceFolderAsk), listOf(myFiles, zip)),
            picker,
        )
        // A phone where the document finds nobody: the first way an app was found by is the picker's intent.
        assertEquals(
            Plan.Picker(OpenFileLocation.samsungAsk, listOf(OpenFileLocation.resourceFolderAsk), listOf(myFiles, zip)),
            OpenFileLocation.plan(emptyList(), listOf(myFiles, zip), own),
        )
        // Two apps that answer the same way and nobody else: that way is the picker's intent, with nothing to add.
        val fx = finding("fx", OpenFileLocation.resourceFolderAsk)
        assertEquals(
            Plan.Picker(OpenFileLocation.resourceFolderAsk, emptyList(), listOf(zip, fx)),
            OpenFileLocation.plan(emptyList(), listOf(zip, fx), own),
        )
    }

    @Test
    fun `an app that answers several ways is one entry, by the first way`() {
        val own = "dev.skye.intertune"
        val first = finding("zarchiver", OpenFileLocation.samsungAsk)
        val again = finding("zarchiver", OpenFileLocation.resourceFolderAsk)
        val third = finding("zarchiver", OpenFileLocation.inodeDirectoryAsk)
        val other = finding("fx", OpenFileLocation.inodeDirectoryAsk)
        // The resource/folder way found nobody who was not found by an earlier one, so it is not given to the picker.
        assertEquals(
            Plan.Picker(OpenFileLocation.documentAsk, listOf(OpenFileLocation.samsungAsk, OpenFileLocation.inodeDirectoryAsk), listOf(first, other)),
            OpenFileLocation.plan(listOf("files"), listOf(first, again, third, other), own),
        )
    }

    @Test
    fun `an app the document already finds is not added a second time`() {
        val own = "dev.skye.intertune"
        // The XAPK installer answers all three ways, and is in the document's list: it stays there only.
        val xapkByFolder = finding("xapk", OpenFileLocation.resourceFolderAsk)
        val xapkByInode = finding("xapk", OpenFileLocation.inodeDirectoryAsk)
        val zip = finding("zarchiver", OpenFileLocation.resourceFolderAsk)
        assertEquals(
            Plan.Picker(OpenFileLocation.documentAsk, listOf(OpenFileLocation.resourceFolderAsk), listOf(zip)),
            OpenFileLocation.plan(listOf("files", "xapk"), listOf(xapkByFolder, xapkByInode, zip), own),
        )
        assertEquals(Plan.Document, OpenFileLocation.plan(listOf("files", "xapk"), listOf(xapkByFolder, xapkByInode), own))
    }

    @Test
    fun `any number of apps is offered, all as alternate intents, and the first two also named as initial ones`() {
        val own = "dev.skye.intertune"
        val many = listOf(
            finding("com.sec.android.app.myfiles", OpenFileLocation.samsungAsk),
            finding("zarchiver", OpenFileLocation.resourceFolderAsk),
            finding("solid", OpenFileLocation.resourceFolderAsk),
            finding("mixplorer", OpenFileLocation.resourceFolderAsk),
            finding("fx", OpenFileLocation.inodeDirectoryAsk),
        )
        val picker = OpenFileLocation.plan(listOf("files"), many, own) as Plan.Picker
        assertEquals(OpenFileLocation.documentAsk, picker.primary)
        assertEquals(listOf(OpenFileLocation.samsungAsk, OpenFileLocation.resourceFolderAsk, OpenFileLocation.inodeDirectoryAsk), picker.alternates)
        // The system's picker keeps only two of the explicit intents of EXTRA_INITIAL_INTENTS (MAX_INITIAL_INTENTS in Android's
        // chooser: seen on the emulator with a picker built without this code, and in its source), so the others are given as
        // alternate intents, which the picker resolves itself without a limit. A third file manager would have been left out.
        assertEquals(many.take(2), picker.named)
        assertEquals(2, OpenFileLocation.NAMED)
        val text = code(File(menus, "OpenFileLocation.kt"))
        assertTrue("EXTRA_ALTERNATE_INTENTS" in text && "EXTRA_INITIAL_INTENTS" in text)
    }

    @Test
    fun `InterTune itself is never offered`() {
        val own = "dev.skye.intertune"
        val me = finding(own, OpenFileLocation.resourceFolderAsk)
        assertEquals(Plan.None, OpenFileLocation.plan(listOf(own), listOf(me), own))
        assertEquals(Plan.Document, OpenFileLocation.plan(listOf("files", own), listOf(me), own))
    }

    // --- the folder, as a path

    @Test
    fun `a file's folder is found as a path for the file managers that take one`() {
        assertEquals("/storage/emulated/0/Music/Rock", OpenFileLocation.folderPath("/storage/emulated/0/Music/Rock/a.mp3"))
        assertEquals("/storage/emulated/0", OpenFileLocation.folderPath("/storage/emulated/0/a.mp3"))
        assertEquals("/storage/1234-ABCD/Music", OpenFileLocation.folderPath("/storage/1234-ABCD/Music/b.flac"))
        assertEquals("/storage/emulated/0/Music", OpenFileLocation.folderPath("/sdcard/Music/c.mp3"))
        assertEquals("/storage/emulated/10/Music", OpenFileLocation.folderPath("/storage/emulated/10/Music/d.mp3"))
        assertNull(OpenFileLocation.folderPath("/data/data/x/a.mp3"))
        assertNull(OpenFileLocation.folderPath(""))
        assertNull(OpenFileLocation.folderPath(null))
    }

    // --- the one launch, with StrictMode relaxed only around it

    @Test
    fun `a file address is allowed for the one launch only, and StrictMode is put back whatever happens`() {
        val text = code(File(menus, "OpenFileLocation.kt"))
        assertEquals("one place starts an activity", 1, Regex("""startActivity\(""").findAll(text).count())
        val launch = text.substringAfter("private fun launch(").substringBefore("\n    }\n")
        assertTrue("launch() not found", launch.isNotBlank())
        assertTrue("the policy in force is kept first:\n$launch", "val before = StrictMode.getVmPolicy()" in launch)
        val relax = launch.indexOf("StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())")
        val start = launch.indexOf("startActivity(")
        assertTrue("the policy is relaxed before the launch:\n$launch", relax in 0 until start)
        assertTrue(
            "the policy is put back in a finally:\n$launch",
            Regex("""finally \{\s*if \(fileUris\) StrictMode\.setVmPolicy\(before\)""").containsMatchIn(launch),
        )
        // The picker is the app's one chooser builder, which leaves InterTune out of the list (ShareSheetTest allows no other).
        assertTrue("the picker is built by shareSheet:\n$text", "context.shareSheet(intentFor(how.primary, id, folder))" in text && "createChooser" !in text)
        // Nothing thrown by the launch, the queries or the building of an intent reaches the user as a crash.
        val open = text.substringAfter("fun open(context: Context, path: String?)").substringBefore("\n    }\n")
        assertTrue("open() must catch everything and say it in a line:\n$open", "runCatching" in open && "R.string.open_file_location_failed" in open)
    }
}
