/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.testerlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** A tester's code as it is typed or pasted, and the shape it is checked for before it is sent. */
class TesterCodeTest {

    @Test
    fun `a code is read the way the bot means it`() {
        assertEquals("ABCD1234WXYZ", TesterCode.normalise("abcd-1234-wxyz"))
        assertEquals("ABCD1234WXYZ", TesterCode.normalise("  ABCD 1234 WXYZ\n"))
        // O, I and L are not in the alphabet: they are what a 0 and a 1 look like.
        assertEquals("0110", TesterCode.normalise("OIlo"))
        assertEquals("A1B1C0", TesterCode.normalise("aIbLcO"))
        // A dash copied from a chat is often not the hyphen.
        assertEquals("ABCDEFGH", TesterCode.normalise("ABCD\u2011EF\u2013GH"))
    }

    @Test
    fun `whatever else is kept, for the shape to refuse`() {
        assertEquals("ABCU", TesterCode.normalise("abcu"))
        assertFalse(TesterCode.isWellFormed(TesterCode.normalise("ABCD-EFGH-JKMU")))
        assertFalse(TesterCode.isWellFormed(TesterCode.normalise("ABCD-EFGH-JK!N")))
    }

    @Test
    fun `in a Turkish phone too`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("1111", TesterCode.normalise("iIlL"))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun `only twelve symbols of the alphabet make a code`() {
        assertTrue(TesterCode.isWellFormed("0123456789AB"))
        assertTrue(TesterCode.isWellFormed("CDEFGHJKMNPQ"))
        assertTrue(TesterCode.isWellFormed("RSTVWXYZ0000"))
        assertFalse("eleven", TesterCode.isWellFormed("0123456789A"))
        assertFalse("thirteen", TesterCode.isWellFormed("0123456789ABC"))
        assertFalse("not normalised", TesterCode.isWellFormed("abcdefghjkmn"))
        assertFalse("U is in no code", TesterCode.isWellFormed("UUUUUUUUUUUU"))
        assertFalse(TesterCode.isWellFormed(""))
        assertEquals(32, TesterCode.ALPHABET.toSet().size)
        assertTrue(TesterCode.ALPHABET.none { it in "ILOU" })
    }

    @Test
    fun `it is shown in three groups of four`() {
        assertEquals("ABCD-EFGH-JKMN", TesterCode.shown("ABCDEFGHJKMN"))
        assertEquals("ABCD-E", TesterCode.shown("ABCDE"))
        assertEquals("ABCD", TesterCode.shown("ABCD"))
        assertEquals("", TesterCode.shown(""))
    }

    @Test
    fun `each typed character is read the way the bot means it`() {
        assertEquals("A", TesterCode.read('a'))
        assertEquals("0", TesterCode.read('o'))
        assertEquals("1", TesterCode.read('i'))
        assertEquals("1", TesterCode.read('L'))
        assertEquals("7", TesterCode.read('7'))
        for (grouping in listOf(' ', '-', '\u2013', '\n')) assertEquals("$grouping", "", TesterCode.read(grouping))
        // Kept as typed, in capitals, for the shape to refuse.
        assertEquals("U", TesterCode.read('u'))
        assertEquals("!", TesterCode.read('!'))
    }

    @Test
    fun `the field draws what will be sent, dashes and all`() {
        // The field's drawing (TesterGate's CodeAsSent), done here on a plain string: each typed
        // character read from the end, then the dashes from the end.
        fun drawn(typed: String): String {
            val text = StringBuilder(typed)
            for (i in text.length - 1 downTo 0) text.replace(i, i + 1, TesterCode.read(text[i]))
            for (at in TesterCode.dashesBefore(text.length).asReversed()) text.insert(at, "-")
            return text.toString()
        }
        for (typed in listOf("7k3m 9qxr 2htv", "ABCD-EFGH-JKMN", "4wpn-8dzb-6cjf", "oIl0", "ABCDE", "", "abcd efgh jkmn pqrs")) {
            assertEquals(typed, TesterCode.shown(TesterCode.normalise(typed)), drawn(typed))
        }
        assertEquals("7K3M-9QXR-2HTV", drawn("7k3m 9qxr 2htv"))
        assertEquals(listOf(4, 8), TesterCode.dashesBefore(12))
        assertEquals(emptyList<Int>(), TesterCode.dashesBefore(4))
        assertEquals(listOf(4), TesterCode.dashesBefore(5))
    }

    @Test
    fun `a paste of the code alone goes in as it was copied, for the field to draw`() {
        for (copied in listOf(
            "7K3M-9QXR-2HTV", "7k3m-9qxr-2htv", "7K3M9QXR2HTV", "7K3M 9QXR 2HTV", "7k3m\u20119qxr\u20132htv",
            // O, I and L for 0, 1 and 1: a code read off a screen.
            "OIBQ-DQ4P-8B94",
        )) {
            assertEquals(copied, TesterCode.fromPasted(copied))
            assertTrue(copied, TesterCode.isWellFormed(TesterCode.normalise(TesterCode.fromPasted(copied))))
        }
        assertEquals("01BQDQ4P8B94", TesterCode.normalise(TesterCode.fromPasted("OIBQ-DQ4P-8B94")))
        // What is around it on the clipboard, a line break or spaces, is not part of it.
        assertEquals("7K3M-9QXR-2HTV", TesterCode.fromPasted("  7K3M-9QXR-2HTV\n"))
    }

    @Test
    fun `a paste of the bot's whole message goes in as the code in it`() {
        val message = "Your tester code: `7K3M-9QXR-2HTV`. It opens test builds on one device. The build asks for it the first " +
            "time it opens and checks it again about once a day, sending only the code, a random number the app makes for " +
            "itself, the build's name and its version number."
        assertEquals("7K3M-9QXR-2HTV", TesterCode.fromPasted(message))
        // Wherever it stands, in whatever dash a chat draws, and the first one when there are two.
        assertEquals("7K3M\u20139QXR\u20132HTV", TesterCode.fromPasted("code: 7K3M\u20139QXR\u20132HTV (not 4WPN-8DZB-6CJF)"))
        assertEquals("7K3M-9QXR-2HTV", TesterCode.fromPasted("(7K3M-9QXR-2HTV)"))
        assertEquals("7K3M-9QXR-2HTV", TesterCode.fromPasted("ABCD-EFGH-JKLMN wrong, 7K3M-9QXR-2HTV right"))
        // Not inside a longer word, and only a code's own symbols: nothing else is picked out.
        assertEquals("x7K3M-9QXR-2HTVx", TesterCode.fromPasted("x7K3M-9QXR-2HTVx"))
        assertEquals("UUUU-UUUU-UUUU", TesterCode.fromPasted("UUUU-UUUU-UUUU"))
    }

    @Test
    fun `anything else goes in as it is, cut to what a field takes, for the gate to say it is no code`() {
        assertEquals("hello world", TesterCode.fromPasted("hello world"))
        assertEquals("", TesterCode.fromPasted(""))
        assertEquals("", TesterCode.fromPasted(" \n\t "))
        val long = "x".repeat(500)
        assertEquals(TesterCode.MOST_PASTED, TesterCode.fromPasted(long).length)
        assertFalse(TesterCode.isWellFormed(TesterCode.normalise(TesterCode.fromPasted(long))))
        assertEquals(64, TesterCode.MOST_PASTED)
    }
}
