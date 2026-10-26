/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Date

/**
 * Every report says where the copy came from, on the line after the phone: F-Droid's, GitHub's
 * and the updater's copies are the same app, and a report did not say which one it was about.
 */
class InstalledFromTest {
    private val own = "dev.skye.intertune"
    private fun from(installer: String?, initiator: String? = null) = installedFrom(installer, initiator, own)

    @Test
    fun `F-Droid and the clients that install from its repositories are named`() {
        assertEquals("F-Droid", from("org.fdroid.fdroid"))
        assertEquals("F-Droid Basic", from("org.fdroid.basic"))
        assertEquals("F-Droid's privileged extension", from("org.fdroid.fdroid.privileged"))
        assertEquals("Droid-ify, an F-Droid client", from("com.looker.droidify"))
        assertEquals("Neo Store, an F-Droid client", from("com.machiav3lli.fdroid"))
    }

    @Test
    fun `the app's own updater says the copy came from GitHub`() {
        assertEquals("InterTune's updater, from GitHub", from(own))
        // A debug or preview build is its own package, and updates itself the same way.
        assertEquals("InterTune's updater, from GitHub", installedFrom("dev.skye.intertune.debug", null, "dev.skye.intertune.debug"))
    }

    @Test
    fun `Obtainium, Google Play and Orion Store are named`() {
        assertEquals("Obtainium", from("dev.imranr.obtainium"))
        assertEquals("Google Play", from("com.android.vending"))
        assertEquals("Orion Store", from("com.orion.store"))
    }

    @Test
    fun `no installer, or the phone's own, is a file installed by hand`() {
        assertEquals("a file, installed by hand", from(null))
        assertEquals("a file, installed by hand", from("com.google.android.packageinstaller"))
        assertEquals("a file, installed by hand", from("com.android.packageinstaller"))
        // A phone maker's own installer.
        assertEquals("a file, installed by hand", from("com.miui.packageinstaller"))
    }

    @Test
    fun `any other app is named by its package`() {
        assertEquals("another app (com.aurora.store)", from("com.aurora.store"))
        assertEquals("another app (com.example.store)", from("com.example.store"))
    }

    @Test
    fun `the app that started the install is noted when it is another one`() {
        // Obtainium through Shizuku, set to pass for Google Play.
        assertEquals("Google Play, started by another app (com.android.shell)", from("com.android.vending", "com.android.shell"))
        assertEquals("Google Play, started by Obtainium", from("com.android.vending", "dev.imranr.obtainium"))
        // adb install.
        assertEquals("a file, installed by hand, started by another app (com.android.shell)", from(null, "com.android.shell"))
        // The same app, or the phone's installer doing the work for it, adds nothing.
        assertEquals("F-Droid", from("org.fdroid.fdroid", "org.fdroid.fdroid"))
        assertEquals("F-Droid", from("org.fdroid.fdroid", "com.google.android.packageinstaller"))
        assertEquals("InterTune's updater, from GitHub", from(own, own))
        assertEquals("a file, installed by hand", from("com.google.android.packageinstaller", "com.google.android.packageinstaller"))
        assertEquals("a file, installed by hand", from(null, "com.google.android.packageinstaller"))
    }

    @Test
    fun `Report a problem says it after the phone, and the form's cut keeps it`() {
        val lines = List(200) { "10-13 18:00:00.000 D/MusicService( 6711): line $it of what led up to it" } +
            "10-13 18:44:40.811 E/MusicService( 6711): it failed"
        val report = ProblemLog.report(ProblemLog.excerpt(lines), build = "b", device = "d", installedFrom = "F-Droid")
        assertEquals(listOf("InterTune b", "d", "Installed from: F-Droid", "The last fault and what led up to it:"), report.lines().take(4))
        val cut = ProblemLog.fitted(report, 1500)
        assertTrue(cut.length <= 1500)
        assertEquals(report.lines().take(4) + "(the earlier lines are left out)", cut.lines().take(5))
        // An earlier run's, with the line that says which run it was.
        val earlier = ProblemLog.report(ProblemLog.excerpt(lines), build = "b", device = "d", installedFrom = "F-Droid", started = Date(0))
        assertTrue(earlier.lines()[3], earlier.lines()[3].startsWith("From the run that began "))
        assertEquals(earlier.lines().take(5) + "(the earlier lines are left out)", ProblemLog.fitted(earlier, 1500).lines().take(6))
    }

    private val main = File("src/main/java/com/dd3boh/outertune")

    /** The line of [file] after the first one holding [device], comments left out. */
    private fun afterDevice(file: String, device: String): String {
        val lines = File(main, file).readLines()
        val at = lines.indexOfFirst { device in it }
        assertTrue("$file has lost its device line", at >= 0)
        return lines.drop(at + 1).first { !it.trim().startsWith("//") }.trim()
    }

    private fun assertInstalledFrom(file: String, line: String) =
        assertTrue("$file: the line after the device is $line", "\"Installed from: " in line && "installedFrom" in line)

    @Test
    fun `the crash report says it after the phone`() {
        val line = afterDevice("utils/CrashLog.kt", "\${Build.MANUFACTURER} \${Build.MODEL}")
        assertInstalledFrom("CrashLog.kt", line)
        assertTrue(line, "context.installedFrom()" in line)
    }

    @Test
    fun `Report a problem asks for it and hands it to the report`() {
        assertInstalledFrom("ProblemLog.kt", afterDevice("utils/ProblemLog.kt", "appendLine(device)"))
        val dialog = File(main, "ui/component/ProblemReportDialog.kt").readText()
        assertTrue("context.installedFrom()" in dialog)
        assertTrue("installedFrom = installedFrom" in dialog)
    }

    @Test
    fun `About's device information says it after the device`() {
        val about = "ui/screens/settings/AboutScreen.kt"
        assertInstalledFrom(about, afterDevice(about, "\"Device: \${Build.BRAND}"))
        assertTrue("context.installedFrom()" in File(main, about).readText())
    }

    @Test
    fun `the player's copied error says it after the device`() {
        val line = afterDevice("ui/player/ThumbnailPlaybackError.kt", "appendLine(\"\${Build.BRAND} \${Build.DEVICE} (\${Build.MODEL})\")")
        assertInstalledFrom("ThumbnailPlaybackError.kt", line)
        assertTrue(line, "context.installedFrom()" in line)
    }

    @Test
    fun `the lookup never throws, since the crash report calls it as the process goes down`() {
        val source = File(main, "utils/InstallSource.kt").readText()
        val body = source.substringAfter("fun Context.installedFrom(): String")
        assertTrue("installedFrom() is gone", body != source)
        assertTrue("installedFrom() is not wrapped in runCatching", body.trimStart().startsWith("= runCatching {"))
    }
}
