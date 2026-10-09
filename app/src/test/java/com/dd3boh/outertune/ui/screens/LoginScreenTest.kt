/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginScreenTest {

    @Test
    fun `an account picked opens the page for it, whatever was picked before`() {
        assertEquals("a@example.com", accountAfterPicker(previous = null, picked = "a@example.com"))
        assertEquals("b@example.com", accountAfterPicker(previous = "a@example.com", picked = "b@example.com"))
        assertEquals("b@example.com", accountAfterPicker(previous = "", picked = "b@example.com"))
    }

    @Test
    fun `a Switch dismissed keeps the account picked before it`() {
        assertEquals("a@example.com", accountAfterPicker(previous = "a@example.com", picked = null))
        // Back from the picker with no account in it is the same as dismissing it.
        assertEquals("a@example.com", accountAfterPicker(previous = "a@example.com", picked = ""))
    }

    @Test
    fun `the first picker dismissed opens the page with nothing filled in`() {
        assertEquals("", accountAfterPicker(previous = null, picked = null))
        assertEquals("", accountAfterPicker(previous = null, picked = ""))
    }

    @Test
    fun `Pick one dismissed after Another account leaves the page as it is`() {
        assertEquals("", accountAfterPicker(previous = "", picked = null))
    }

    @Test
    fun `dismissing the picker that opened by itself stops it opening by itself`() {
        assertTrue(pickerDeclinedAfter(previous = null, picked = null, declined = false))
    }

    @Test
    fun `a picker that comes back with no account counts as dismissed`() {
        // When adding an account fails, the system's picker answers RESULT_OK with no name, which
        // reaches here as null; an empty name is taken the same way.
        assertTrue(pickerDeclinedAfter(previous = null, picked = "", declined = false))
    }

    @Test
    fun `a Switch or Pick one dismissed leaves it as it was`() {
        assertFalse(pickerDeclinedAfter(previous = "a@example.com", picked = null, declined = false))
        assertTrue(pickerDeclinedAfter(previous = "", picked = null, declined = true))
        assertFalse(pickerDeclinedAfter(previous = "", picked = null, declined = false))
    }

    @Test
    fun `an account picked has the picker open by itself again`() {
        assertFalse(pickerDeclinedAfter(previous = "", picked = "a@example.com", declined = true))
        assertFalse(pickerDeclinedAfter(previous = null, picked = "a@example.com", declined = false))
    }

    @Test
    fun `Google's sign-in over https on exactly accounts google com is called Google's`() {
        assertTrue(isGoogleSignInPage("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"))
        assertTrue(isGoogleSignInPage("https://accounts.google.com/v3/signin/identifier?flowName=GlifWebSignIn"))
        assertTrue(isGoogleSignInPage("https://accounts.google.com:443/"))
        assertTrue(isGoogleSignInPage("HTTPS://ACCOUNTS.GOOGLE.COM/ServiceLogin"))
    }

    @Test
    fun `a host that only starts or ends like Google's is not Google's`() {
        assertFalse(isGoogleSignInPage("https://accounts.google.com.x.example/ServiceLogin"))
        assertFalse(isGoogleSignInPage("https://accounts.google.community/"))
        assertFalse(isGoogleSignInPage("https://myaccounts.google.com/"))
        // Before the at sign it is a user name; the page is x.example's.
        assertFalse(isGoogleSignInPage("https://accounts.google.com@x.example/ServiceLogin"))
    }

    @Test
    fun `plain http, another port, Google's other pages and no page at all are not called Google's`() {
        assertFalse(isGoogleSignInPage("http://accounts.google.com/ServiceLogin"))
        assertFalse(isGoogleSignInPage("https://accounts.google.com:8443/ServiceLogin"))
        assertFalse(isGoogleSignInPage("https://myaccount.google.com/"))
        assertFalse(isGoogleSignInPage("https://music.youtube.com/"))
        assertFalse(isGoogleSignInPage("https://x.example/?continue=https://accounts.google.com/"))
        assertFalse(isGoogleSignInPage("about:blank"))
        assertFalse(isGoogleSignInPage(null))
    }

    @Test
    fun `the lock is for https only`() {
        assertTrue(isHttpsPage("https://x.example/"))
        assertFalse(isHttpsPage("http://accounts.google.com/"))
        assertFalse(isHttpsPage("about:blank"))
        assertFalse(isHttpsPage(null))
    }

    @Test
    fun `the address bar gets the whole host, for the line to cut from its start`() {
        assertEquals(
            "accounts.google.com.x.example",
            addressBarHost("https://accounts.google.com.x.example/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"),
        )
        assertEquals("x.example", addressBarHost("https://accounts.google.com@x.example/"))
        assertEquals("accounts.google.com", addressBarHost("http://accounts.google.com/"))
        assertEquals("about:blank", addressBarHost("about:blank"))
    }

    @Test
    fun `YouTube Music is music youtube com itself, over https`() {
        assertTrue(isYouTubeMusicPage("https://music.youtube.com/"))
        assertTrue(isYouTubeMusicPage("https://music.youtube.com"))
        assertTrue(isYouTubeMusicPage("https://music.youtube.com/?cbrd=1"))
    }

    @Test
    fun `an address that only starts like YouTube Music's is someone else's`() {
        // Each of these passed startsWith("https://music.youtube.com").
        assertFalse(isYouTubeMusicPage("https://music.youtube.com.example.net/"))
        assertFalse(isYouTubeMusicPage("https://music.youtube.community/"))
        assertFalse(isYouTubeMusicPage("https://music.youtube.com@x.example/"))
        assertFalse(isYouTubeMusicPage("https://music.youtube.com:8443/"))
    }

    @Test
    fun `other pages, and YouTube Music over http, are not YouTube Music`() {
        assertFalse(isYouTubeMusicPage("http://music.youtube.com/"))
        assertFalse(isYouTubeMusicPage("https://www.youtube.com/"))
        assertFalse(isYouTubeMusicPage("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"))
        assertFalse(isYouTubeMusicPage("https://x.example/?next=https://music.youtube.com/"))
        assertFalse(isYouTubeMusicPage(null))
    }

    // Once the sign-in is made, the page says so and goes back by itself. It used to stay on
    // YouTube Music's own site, signed in, with nothing to say what to do next: in setup that
    // read as being stuck (9 Oct 2026).

    private val music = "https://music.youtube.com/"
    private val google = "https://accounts.google.com/v3/signin/identifier"
    private val visitor = "VISITOR_INFO1_LIVE=abc; YSC=q; PREF=tz=Europe.Paris"
    private fun cookieOf(signIn: String) = "$visitor; SAPISID=$signIn; HSID=h"

    @Test
    fun `a cookie with SAPISID in it is a sign-in, and nothing else is`() {
        assertEquals("xyz/123", signInOf("VISITOR_INFO1_LIVE=abc; SAPISID=xyz/123; YSC=q"))
        assertEquals("xyz", signInOf("SAPISID=xyz"))
        // A visitor's cookies, which YouTube Music hands anybody who opens it.
        assertNull(signInOf(visitor))
        // Other cookies that only have those letters in their name.
        assertNull(signInOf("__Secure-3PAPISID=xyz; __Secure-1PAPISID=xyz"))
        assertNull(signInOf("SAPISID="))
        assertNull(signInOf(null))
        assertNull(signInOf(""))
    }

    @Test
    fun `a cookie the app cannot read is not a sign-in, and does not stop the page`() {
        // The rest of the app gives up on such a string and signs the account out, so the page
        // must not call it signed in.
        assertNull(signInOf("no equals sign in here"))
        assertNull(signInOf(";;; ;"))
        assertNull(signInOf("odd; SAPISID=xyz"))
    }

    @Test
    fun `a sign-in made on this visit goes back by itself`() {
        assertEquals(SignInEnd.GO_BACK, signInEnd(madeHere = true, accountPicked = false))
        assertEquals(SignInEnd.GO_BACK, signInEnd(madeHere = true, accountPicked = true))
    }

    @Test
    fun `an account picked for it that was signed in already goes back as well`() {
        // The page came up signed in at once, as the account that was asked for.
        assertEquals(SignInEnd.GO_BACK, signInEnd(madeHere = false, accountPicked = true))
    }

    @Test
    fun `a page that comes up signed in with nobody having chosen anything waits to be told`() {
        // An older sign-in was still in the page. Gone back from by itself, there would be no
        // way left to sign in as somebody else: every visit would end before it began.
        assertEquals(SignInEnd.ASK, signInEnd(madeHere = false, accountPicked = false))
    }

    @Test
    fun `Google's own page is never a sign-in made, whatever its cookie holds`() {
        assertNull(signedInOn(null, google, cookieOf("s1"), cameWith = null, accountPicked = true))
        assertNull(signedInOn(null, null, cookieOf("s1"), cameWith = null, accountPicked = true))
        // Nor a page that only starts like YouTube Music's, or is not over https.
        assertNull(signedInOn(null, "https://music.youtube.com.example.net/", cookieOf("s1"), null, true))
        assertNull(signedInOn(null, "http://music.youtube.com/", cookieOf("s1"), null, true))
    }

    @Test
    fun `YouTube Music with a visitor's cookie is not a sign-in made`() {
        assertNull(signedInOn(null, music, visitor, cameWith = null, accountPicked = true))
        assertNull(signedInOn(null, music, null, cameWith = null, accountPicked = true))
    }

    @Test
    fun `the first sign-in of a fresh page is one made here`() {
        val landed = signedInOn(null, music, cookieOf("s1"), cameWith = null, accountPicked = false)
        assertEquals(SignedIn("s1", SignInEnd.GO_BACK), landed)
    }

    @Test
    fun `a page opened signed in is one made here only once the sign-in has changed`() {
        // Opened from Settings while signed in, the picker dismissed: the same sign-in comes up.
        assertEquals(
            SignedIn("s1", SignInEnd.ASK),
            signedInOn(null, music, cookieOf("s1"), cameWith = "s1", accountPicked = false),
        )
        // Somebody then signed in as another account on that page.
        assertEquals(
            SignedIn("s2", SignInEnd.GO_BACK),
            signedInOn(SignedIn("s1", SignInEnd.ASK), music, cookieOf("s2"), cameWith = "s1", accountPicked = false),
        )
        // With an account picked for it, the same sign-in is what was asked for.
        assertEquals(
            SignedIn("s1", SignInEnd.GO_BACK),
            signedInOn(null, music, cookieOf("s1"), cameWith = "s1", accountPicked = true),
        )
    }

    @Test
    fun `the same sign-in seen again keeps what is known of it`() {
        // YouTube Music moves between its own pages, and reports each: the name already had and
        // the page already loaded are not forgotten, and the wait does not start over.
        val known = SignedIn("s1", SignInEnd.GO_BACK, loaded = true, answered = true, name = "Ada")
        // Its other cookies change from one page to the next; the sign-in is the same one.
        val later = "YSC=another; SAPISID=s1; ST-abc=def"
        assertSame(known, signedInOn(known, music + "library", later, cameWith = null, accountPicked = false))
    }

    @Test
    fun `leaving YouTube Music for Google's page again ends it`() {
        // From the page left up, somebody goes to switch accounts: nothing is signed in "here"
        // while Google's page is the one showing, and Back belongs to that page again.
        val known = SignedIn("s1", SignInEnd.ASK, loaded = true, answered = true, name = "Ada")
        assertNull(signedInOn(known, google, cookieOf("s1"), cameWith = "s1", accountPicked = false))
    }

    @Test
    fun `the account's name goes to the sign-in it was asked for, and to no other`() {
        val known = SignedIn("s1", SignInEnd.GO_BACK)
        assertEquals(known.copy(answered = true, name = "Ada"), signedInNamed(known, "s1", "Ada"))
        // An answer for the account before, arriving late.
        assertSame(known, signedInNamed(known, "s0", "Grace"))
        assertNull(signedInNamed(null, "s1", "Ada"))
    }

    @Test
    fun `an account that does not say its name has still answered`() {
        // The request failed, or came back with nothing: the page does not wait for it again.
        val known = SignedIn("s1", SignInEnd.GO_BACK)
        assertEquals(known.copy(answered = true), signedInNamed(known, "s1", null))
        assertEquals(known.copy(answered = true), signedInNamed(known, "s1", "  "))
        // A second request failing does not take back the name the first one gave.
        val named = known.copy(answered = true, name = "Ada")
        assertEquals(named, signedInNamed(named, "s1", null))
    }

    @Test
    fun `it goes back once the page has loaded, the name has come and the words were up long enough`() {
        assertFalse(mayGoBack(shownForMs = 0, loaded = true, answered = true))
        assertFalse(mayGoBack(shownForMs = SIGNED_IN_SHOWN_MS - 1, loaded = true, answered = true))
        assertTrue(mayGoBack(shownForMs = SIGNED_IN_SHOWN_MS, loaded = true, answered = true))
        // The name is waited for, since setup's next page says it.
        assertFalse(mayGoBack(shownForMs = SIGNED_IN_SHOWN_MS, loaded = true, answered = false))
        // So is the page: its script hands over what the app keeps beside the cookie.
        assertFalse(mayGoBack(shownForMs = SIGNED_IN_SHOWN_MS, loaded = false, answered = true))
        assertFalse(mayGoBack(shownForMs = SIGNED_IN_WAIT_MS - 1, loaded = false, answered = false))
    }

    @Test
    fun `a page that never finishes or a name that never comes does not hold it for good`() {
        assertTrue(mayGoBack(shownForMs = SIGNED_IN_WAIT_MS, loaded = false, answered = false))
        assertTrue(mayGoBack(shownForMs = SIGNED_IN_WAIT_MS, loaded = true, answered = false))
        assertTrue(mayGoBack(shownForMs = SIGNED_IN_WAIT_MS, loaded = false, answered = true))
        assertTrue(SIGNED_IN_SHOWN_MS in 800..3_000)
        assertTrue(SIGNED_IN_WAIT_MS in 4_000..15_000)
    }

    @Test
    fun `what the sign-in page keeps is written where leaving the page cannot drop it`() {
        // A rememberPreference setter writes in the screen's own scope, and a write still on its
        // way when the screen leaves is dropped. This page now leaves by itself a moment after
        // the sign-in, so the cookie, the account's name and the two ids go through keep().
        val source = File("src/main/java/com/dd3boh/outertune/ui/screens/LoginScreen.kt").readText()
        for (key in listOf("InnerTubeCookieKey", "AccountNameKey", "AccountEmailKey", "AccountChannelHandleKey", "VisitorDataKey", "DataSyncIdKey")) {
            assertFalse("$key is written through rememberPreference", Regex("rememberPreference\\(\\s*$key").containsMatchIn(source))
            assertTrue("$key is not written at all", Regex("it\\[$key] = ").containsMatchIn(source))
        }
    }
}
