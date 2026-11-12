/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.innertube

import com.zionhuang.innertube.utils.parseCookieString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The cookie typed in the token editor (UI scan, 5 Nov 2026). A part with no "=" made the parse
 * throw, and every screen that reads the cookie while drawing closed the app with it.
 */
class CookieParseTest {
    @Test
    fun `a part with no equals sign makes it no cookie, without throwing`() {
        // As the sign-in page reads it (LoginScreenTest): the app never writes such a string.
        assertTrue(parseCookieString("SID=abc; SAPISID=x/y; Secure").isEmpty())
        assertTrue(parseCookieString("no equals sign in here").isEmpty())
        assertTrue(parseCookieString(";;; ;").isEmpty())
        assertEquals(mapOf("SID" to "abc", "SAPISID" to "x/y"), parseCookieString("SID=abc; SAPISID=x/y"))
    }

    @Test
    fun `a value holding an equals sign is kept whole`() {
        assertEquals("a=b==", parseCookieString("PREF=a=b==")["PREF"])
    }

    @Test
    fun `an empty cookie is no parts`() {
        assertTrue(parseCookieString("").isEmpty())
        assertTrue(parseCookieString("; ").isEmpty())
    }

    @Test
    fun `the token editor checks the cookie line, and lets an empty one through`() {
        val frag = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/fragments/AccountFrag.kt").readText()
        val check = frag.substringAfter("isInputValid = { text ->").substringBefore("},")
        assertTrue(".substringAfter(\"***INNERTUBE COOKIE*** =\")" in check)
        // Without an account the cookie line is empty, and the other lines are still edited.
        assertTrue("(cookie.isEmpty() || \"SAPISID\" in parseCookieString(cookie))" in check)
        assertFalse("its answer was ignored", "true\n" in check)
    }
}
