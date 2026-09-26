/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
