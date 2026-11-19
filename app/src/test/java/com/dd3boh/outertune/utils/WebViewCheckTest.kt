/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #221 (0.12.11, an ASUS on Android 7.1.1): the sign-in page closed the app while it was being
 * drawn, because the phone's web view could not start (its Chrome was a 32-bit build, the app runs
 * 64-bit) and WebView's constructor threw. The web pages of the app ask first and say so instead.
 */
class WebViewCheckTest {

    private fun source(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    @Test
    fun `a web view that cannot start is a no, never a throw`() {
        val check = source("utils/WebViewCheck.kt")
        val works = check.substringAfter("fun works(context: Context): Boolean {").substringBefore("\n    }\n")
        assertTrue(works, "runCatching { WebView(context).destroy() }" in works)
        assertTrue(works, ".isSuccess" in works)
        // The store's page opens whatever the phone has: a store app, or the page on the web.
        val store = check.substringAfter("fun openStore(context: Context) {").substringBefore("\n    }\n")
        assertTrue(store, "catch (_: ActivityNotFoundException)" in store && "runCatching { context.startActivity(web) }" in store)
    }

    @Test
    fun `the sign-in pages ask before they make their web view`() {
        for (screen in listOf("ui/screens/LoginScreen.kt", "ui/screens/LastFmLoginScreen.kt")) {
            val page = source(screen)
            val ask = page.indexOf("val webViewWorks = remember { WebViewCheck.works(context) }")
            val note = page.indexOf("NoWebViewNote()")
            val made = page.indexOf("WebView(context).apply {")
            assertTrue("$screen asks", ask >= 0 && note > ask)
            assertTrue("$screen asks before the page is made", made > note)
        }
        val login = source("ui/screens/LoginScreen.kt")
        // Nothing else opens on the sign-in page then: no account picker for a page that cannot come.
        val noPage = login.substringAfter("if (!webViewWorks) {").substringBefore("\n    }\n")
        assertTrue(noPage, "return" in noPage)
        assertTrue("before the account picker", login.indexOf("if (!webViewWorks) {") < login.indexOf("val accountPicker = rememberLauncherForActivityResult"))
    }

    @Test
    fun `the note says why and offers the fix, in English and French`() {
        val note = source("ui/component/NoWebViewNote.kt")
        assertTrue(note, "NoteButton(stringResource(R.string.no_web_view_store)) { WebViewCheck.openStore(context) }" in note)
        for (values in listOf("values", "values-fr")) {
            val strings = File("src/main/res/$values/strings-ot.xml").readText()
            for (name in listOf("no_web_view_title", "no_web_view_body", "no_web_view_store")) {
                assertTrue("$values: $name", "<string name=\"$name\" translatable=\"false\">" in strings)
            }
            assertFalse("$values: no em dash", "—" in strings.substringAfter("name=\"no_web_view_title\"").substringBefore("name=\"no_web_view_store\""))
        }
    }
}
