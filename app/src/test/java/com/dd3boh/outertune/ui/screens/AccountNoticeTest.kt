/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.dd3boh.outertune.constants.AccountNoticeSeenKey
import com.dd3boh.outertune.constants.applyNewInstallDefaults
import com.dd3boh.outertune.constants.markFirstSetup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What signing in can cost is said where somebody signs in, and once to whoever already had.
 *
 * YouTube may restrict or close an account used with the app. Setup says so on its sign-in page
 * and the Account page says so beside its way in. The dialog is for the people who signed in
 * before either did, and it must not come up for anybody else, nor twice, nor over another screen.
 */
class AccountNoticeTest {

    private fun source(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    @Test
    fun `the dialog is owed to somebody signed in, past setup and not yet told, and to nobody else`() {
        assertTrue(accountNoticeOwed(setupDone = true, signedIn = true, seen = false))
        // Told already.
        assertFalse(accountNoticeOwed(setupDone = true, signedIn = true, seen = true))
        // No account here: nothing is at risk, and the way in says it when there is one.
        assertFalse(accountNoticeOwed(setupDone = true, signedIn = false, seen = false))
        // In setup, which says it on its own page: a backup restored there can sign somebody in.
        assertFalse(accountNoticeOwed(setupDone = false, signedIn = true, seen = false))
        assertFalse(accountNoticeOwed(setupDone = false, signedIn = false, seen = false))
    }

    @Test
    fun `a cookie is an account's when it carries the account's own entry`() {
        assertTrue(isAccountCookie("VISITOR_INFO1_LIVE=abc; SAPISID=def; YSC=ghi"))
        assertFalse(isAccountCookie("VISITOR_INFO1_LIVE=abc; YSC=ghi"))
        assertFalse(isAccountCookie(""))
    }

    @Test
    fun `setup has told whoever finishes it, a first time or again`() {
        val first = mutablePreferencesOf()
        markFirstSetup(first)
        applyNewInstallDefaults(first)
        assertEquals(true, first[AccountNoticeSeenKey])

        // Somebody sent through setup again, from Developer.
        val again = mutablePreferencesOf()
        applyNewInstallDefaults(again)
        assertEquals(true, again[AccountNoticeSeenKey])
    }

    @Test
    fun `setup's sign-in page carries the note over its button`() {
        val setup = source("ui/screens/ShortSetup.kt")
        val heading = setup.indexOf("R.string.setup_sign_in_title")
        val button = setup.indexOf("R.string.setup_sign_in)")
        assertTrue("the page was not found", heading > 0 && button > heading)
        // Upright under the page's words, and on a phone on its side at the head of the buttons,
        // which stand beside the page there: over the button both times.
        val page = setup.substring(heading, button)
        assertTrue("if (!beside) {" in page && "if (beside) SetupAccountNote()" in page)
        assertEquals(2, Regex("""SetupAccountNote\(\)""").findAll(page).count())
        assertTrue("R.string.account_notice_body" in setup.substringAfter("private fun SetupAccountNote()").substringBefore("\n}\n"))
    }

    @Test
    fun `the Account page carries the note, and signing in from it counts as told`() {
        val page = source("ui/screens/settings/fragments/AccountFrag.kt")
        assertTrue("InfoLabel(text = stringResource(R.string.account_notice_body))" in page)
        // By the row that opens the sign-in page: told before the page is opened.
        val told = page.indexOf("setAccountNoticeSeen(true)")
        val opens = page.indexOf("navController.navigate(\"login\")")
        assertTrue(told > 0 && opens > told && opens - told < 80)
        // And by hand, with a cookie pasted in.
        val byHand = page.substringAfter("TextFieldDialog(").substringBefore("onDismiss")
        assertTrue("setAccountNoticeSeen(true)" in byHand)
    }

    @Test
    fun `the dialog waits for whatever else has the screen, and is closed by its button alone`() {
        val main = source("MainActivity.kt")
        val shown = main.substringBefore("AccountNoticeDialog(onDone").substringAfterLast("if (accountNoticeOwed(")
        assertTrue("the condition was not found", shown.length < 400)
        for (other in listOf("!catchUpOpen", "!welcomeOpen", "!tourState.running", "!tourFromWelcome", "!updatePromptVisible")) {
            assertTrue("$other is not waited for", other in shown)
        }
        assertTrue("oobeStatus >= OOBE_VERSION" in shown)
        // A tap beside it must not mark it read: it is shown once.
        assertTrue("onDismiss = {}," in source("ui/screens/AccountNotice.kt"))
    }

    @Test
    fun `the notice is written in English and in French, and names the Settings entry by its own string`() {
        for (language in listOf("values", "values-fr")) {
            val texts = File("src/main/res/$language/strings-ot.xml").readText()
            for (name in listOf("account_notice_title", "account_notice_body", "account_notice_signed_in")) {
                assertTrue("$language has no $name", "name=\"$name\"" in texts)
            }
            val signedIn = Regex("""name="account_notice_signed_in">([^<]+)<""").find(texts)!!.groupValues[1]
            assertTrue(language, "%1\$s" in signedIn)
            // Said in two sentences. It is a note, not a page.
            val body = Regex("""name="account_notice_body">([^<]+)<""").find(texts)!!.groupValues[1]
            assertEquals(language, 2, body.count { it == '.' })
        }
        assertTrue("stringResource(R.string.grp_account_sync)" in source("ui/screens/AccountNotice.kt"))
    }
}
