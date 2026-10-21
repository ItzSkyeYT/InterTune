/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * How the app tells, from Android's session, that the person said yes and Android is installing,
 * and when it lets go of an install that says nothing more. What Android reports was read in AOSP
 * on 21 Oct 2026: the session's progress is 0.8 from the commit and 0.9 once the install pass
 * after the yes runs, from Android 9 to 16, and from 14 the session is marked active again at the
 * yes. Its window closes at once on the tap, and an update of the app itself ends with Android
 * closing the app before any answer is sent.
 *
 * The last tests read the source, as UpdateBlockedTest does, for the rules no function can hold:
 * a tap never drops a session Android still has, and the fake update is for debug builds only.
 */
class UpdateSessionTest {

    @Test
    fun `the yes is heard from the progress step, or from the session waking after its window came`() {
        assertFalse("the commit", installAccepted(progress = 0.8f, active = false, handedOver = true))
        assertTrue("the install pass after the yes", installAccepted(progress = 0.9f, active = false, handedOver = true))
        assertTrue("Android 14 and later, at the yes", installAccepted(progress = 0.8f, active = true, handedOver = true))
        // Before the window is handed over, an active session is only the commit holding it.
        assertFalse(installAccepted(progress = 0.8f, active = true, handedOver = false))
        assertFalse("the copy", installAccepted(progress = 0f, active = false, handedOver = false))
    }

    @Test
    fun `a session that has ended ends the wait, whatever was said`() {
        for (installing in listOf(false, true)) {
            assertEquals(
                SessionVerdict.GONE,
                sessionVerdict(installing = installing, exists = false, accepted = false, handedOver = true, quietMs = 0),
            )
        }
    }

    @Test
    fun `a yes seen while waiting moves to installing`() {
        assertEquals(
            SessionVerdict.INSTALLING,
            sessionVerdict(installing = false, exists = true, accepted = true, handedOver = true, quietMs = 0),
        )
    }

    @Test
    fun `an install Android goes quiet about lets the dialog go after two minutes`() {
        assertEquals(
            SessionVerdict.INSTALLING,
            sessionVerdict(installing = true, exists = true, accepted = true, handedOver = true, quietMs = INSTALL_QUIET_MS - 1),
        )
        assertEquals(
            SessionVerdict.STALLED,
            sessionVerdict(installing = true, exists = true, accepted = true, handedOver = true, quietMs = INSTALL_QUIET_MS),
        )
        assertEquals(2 * 60_000L, INSTALL_QUIET_MS)
    }

    @Test
    fun `a window that waits is never given up on, one that never came is`() {
        assertEquals(
            SessionVerdict.WAITING,
            sessionVerdict(installing = false, exists = true, accepted = false, handedOver = true, quietMs = 24 * 3_600_000L),
        )
        assertEquals(
            SessionVerdict.WAITING,
            sessionVerdict(installing = false, exists = true, accepted = false, handedOver = false, quietMs = HANDOVER_QUIET_MS - 1),
        )
        assertEquals(
            SessionVerdict.STALLED,
            sessionVerdict(installing = false, exists = true, accepted = false, handedOver = false, quietMs = HANDOVER_QUIET_MS),
        )
    }

    private val installer = File("src/main/java/com/dd3boh/outertune/utils/UpdateInstaller.kt").readText()

    /**
     * The code of the method of [installer] declared by [signature], up to its closing brace,
     * without its comments: a sentence that names commit() is not a call of it.
     */
    private fun body(signature: String): String {
        val start = installer.indexOf(signature)
        assertTrue("$signature is not in UpdateInstaller.kt", start >= 0)
        return installer.substring(start).substringBefore("\n    }\n")
            .lines().joinToString("\n") { it.substringBefore("//") }
    }

    @Test
    fun `a tap picks up the session Android has before it can start another`() {
        val install = body("fun installOrDownload(")
        val resume = install.indexOf("if (resumeLiveSession()) return")
        assertTrue(resume >= 0)
        assertTrue(resume < install.indexOf("commit("))
        assertTrue(resume < install.indexOf("start("))
        // Only a session whose window never came may be dropped for a new one.
        assertTrue(body("private fun resumeLiveSession()").substringAfterLast("->").trim().startsWith("false"))
    }

    @Test
    fun `nothing that looks at a session or shows its window abandons it`() {
        for (signature in listOf(
            "fun recheck()", "fun showWindowAgain()", "private fun resumeLiveSession()",
            "private fun sessionGone(", "private fun heard(", "private fun installing()",
        )) {
            assertFalse(signature, "abandon" in body(signature))
        }
        // Stop abandons, but only once the session is known not to be installing.
        val stop = body("fun stop()")
        assertTrue(stop.indexOf("accepted(info)") in 0 until stop.indexOf("abandon()"))
    }

    @Test
    fun `the watch on a session ends with the session`() {
        assertTrue("unwatch()" in body("private fun abandon()"))
        assertTrue("unwatch()" in body("private fun ended()"))
        assertTrue("unregisterSessionCallback" in body("private fun unwatch()"))
        assertTrue(installer.substringAfter("override fun onFinished(").substringBefore("}").contains("unwatch()"))
        // Registered before the commit, so not one of Android's answers about it is missed.
        val commit = body("private suspend fun commit(")
        assertTrue(commit.indexOf("watch(id)") in 0 until commit.indexOf("session.commit("))
    }

    @Test
    fun `silence is only counted while the app is in front`() {
        // Play Protect can ask "Scan app" or "Don't install app" after the yes, in a window over the
        // app, and the install waits on that answer: the dialog let go after two minutes while the
        // question waited (emulator, 21 Oct 2026). Time behind another window is not Android going quiet.
        assertTrue(body("fun recheck()").contains("quietMs = if (inFront)"))
        val shown = body("fun onShown()")
        assertTrue("onShown does not start the count again", "lastSign = SystemClock.elapsedRealtime()" in shown)
        assertTrue("the count starts again before the session is looked at",
            shown.indexOf("lastSign =") < shown.indexOf("recheck()"))
    }

    @Test
    fun `the fake update is for debug builds only`() {
        assertTrue(body("internal fun fakeUpdateBytes(").lines()[1].trim() == "if (!BuildConfig.DEBUG) return null")
        // Read nowhere else: everything that acts on the fake update asks fakeUpdateBytes.
        val sources = File("src/main/java/com/dd3boh/outertune")
        val readers = sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> file.readLines().any { "FAKE_UPDATE_FILE" in it.substringBefore("//") && !it.trim().startsWith("*") } }
            .map { it.name }
            .toList()
        assertEquals(listOf("UpdateInstaller.kt"), readers)
        assertEquals(2, installer.lines().count { "FAKE_UPDATE_FILE" in it.substringBefore("//") && !it.trim().startsWith("*") })
        val checker = File(sources, "utils/UpdateChecker.kt").readText()
        assertTrue("val bytes = fakeUpdateBytes(context) ?: return null" in checker)
    }
}
