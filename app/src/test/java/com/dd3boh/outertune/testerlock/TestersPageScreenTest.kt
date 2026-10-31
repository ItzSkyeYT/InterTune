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
import java.io.File
import java.time.LocalDateTime
import kotlin.random.Random

/**
 * What the Testers page says of the lock, as a plain description, and the pieces it is made from: the tester's number out of a
 * code, the code with all but its last four symbols hidden, the lines of what to try, and when a build was made. The page is
 * drawn from these and decides nothing, so each state is tested here without a phone.
 */
class TestersPageScreenTest {

    // Codes made by the bot's own testercodes.make_code with a made-up secret, "unit-test-secret": the number is the first two symbols.
    private val made = mapOf(
        0 to "00AX-WM5T-3P4M",
        1 to "01M6-6T5N-TQ2F",
        7 to "07SH-9NAP-4KCT",
        31 to "0ZEK-VRDD-V483",
        32 to "10M2-1M5N-S6WK",
        243 to "7KY4-9J61-VRQS",
        1023 to "ZZRK-MVDQ-BJE9",
    )
    private val code = TesterCode.normalise(made.getValue(7))
    private val build = "0.12.5-preview.20261031.1138"
    private val day = 24L * 60 * 60 * 1000

    // --- the number

    @Test
    fun `the number is the first two symbols of the code, as the bot makes it`() {
        for ((number, shown) in made) {
            assertEquals(shown, number, TesterCode.numberOf(shown))
            assertEquals(shown, number, TesterCode.numberOf(TesterCode.normalise(shown)))
        }
    }

    @Test
    fun `typed any way, a code has the same number`() {
        for (typed in listOf("07sh-9nap-4kct", "07SH 9NAP 4KCT", "  07SH9NAP4KCT  ", "O7SH-9NAP-4KCT", "07sh9nap4kct", "07–SH9NAP4KCT")) {
            assertEquals(typed, 7, TesterCode.numberOf(typed))
        }
    }

    @Test
    fun `what is not shaped like a code has no number`() {
        for (not in listOf("", "07SH-9NAP-4KC", "07SH-9NAP-4KCTT", "07SU-9NAP-4KCT", "hello there", "07SH-9NAP-4KC!", "••••-••••-4KCT")) {
            assertNull(not, TesterCode.numberOf(not))
        }
    }

    // --- the code as the page shows it

    @Test
    fun `all but the last four symbols are hidden, in the code's own groups`() {
        assertEquals("••••-••••-4KCT", TesterCode.masked("07SH-9NAP-4KCT"))
        assertEquals("••••-••••-4KCT", TesterCode.masked("07sh9nap4kct"))
        assertEquals("4KCT", TesterCode.lastSymbols("07SH-9NAP-4KCT"))
    }

    @Test
    fun `no more than the last four symbols of a code ever show, whatever is given`() {
        val random = Random(20261031)
        val pool = TesterCode.ALPHABET + "oilOIL -–_!?uU"
        repeat(3000) {
            val given = String(CharArray(random.nextInt(0, 24)) { pool[random.nextInt(pool.length)] })
            val shown = TesterCode.masked(given)
            val letters = shown.filter { it != TesterCode.HIDDEN && it != '-' }
            assertTrue("$given -> $shown", letters.length <= TesterCode.SHOWN)
            // What shows is the end of the code and nothing from its middle.
            assertTrue("$given -> $shown", letters.isEmpty() || TesterCode.normalise(given).endsWith(letters))
            // Nothing is left out or added: as many places as symbols.
            assertEquals("$given -> $shown", TesterCode.normalise(given).length, shown.count { it != '-' })
        }
    }

    @Test
    fun `of a code made the way the bot makes them, the last four show and the rest do not`() {
        val random = Random(1031)
        repeat(2000) {
            val symbols = String(CharArray(TesterCode.LENGTH) { TesterCode.ALPHABET[random.nextInt(TesterCode.ALPHABET.length)] })
            val shown = TesterCode.masked(TesterCode.shown(symbols))
            assertEquals(symbols, "\u2022\u2022\u2022\u2022-\u2022\u2022\u2022\u2022-" + symbols.takeLast(4), shown)
            assertEquals(symbols, symbols.takeLast(4), TesterCode.lastSymbols(symbols))
            assertEquals(symbols, symbols.take(2).let { TesterCode.ALPHABET.indexOf(it[0]) * 32 + TesterCode.ALPHABET.indexOf(it[1]) }, TesterCode.numberOf(symbols))
        }
    }

    @Test
    fun `a value that is not a code is hidden whole, and nothing is guessed about it`() {
        assertEquals("", TesterCode.masked(""))
        assertEquals("••••-••••", TesterCode.masked("07SH-9NAP"))
        assertEquals("", TesterCode.lastSymbols("07SH-9NAP"))
        for ((_, shown) in made) {
            val masked = TesterCode.masked(shown)
            for (group in shown.split("-").dropLast(1)) assertFalse("$shown -> $masked", group in masked)
        }
    }

    // --- what the lock says of this build

    private fun memory(
        opened: Long? = null, claimed: Boolean = false, grace: Boolean = false, refused: Boolean = false, kept: String? = code,
    ) = TesterMemory(
        code = kept,
        install = "0123456789abcdef0123456789abcdef",
        openedBuild = build.takeIf { opened != null },
        openedAt = opened ?: 0L,
        claimed = claimed,
        graceBuild = build.takeIf { grace },
        graceSince = if (grace) 5L else 0L,
        refusedBuild = build.takeIf { refused },
    )

    @Test
    fun `a yes for this build says that it opens, and when`() {
        val now = 100 * day
        val screen = TestersPageScreen.of(build, memory(opened = now - 3_600_000), now)
        assertEquals(CheckResult.OPENS, screen.result)
        assertEquals(now - 3_600_000, screen.lastCheck)
    }

    @Test
    fun `the door's no, a check that could not be made and no check at all each say so`() {
        val now = 100 * day
        assertEquals(CheckResult.OUT_OF_DATE, TestersPageScreen.of(build, memory(refused = true), now).result)
        assertEquals(CheckResult.NOT_REACHED, TestersPageScreen.of(build, memory(grace = true), now).result)
        assertEquals(CheckResult.NOT_YET, TestersPageScreen.of(build, memory(), now).result)
        // The last word is the no, over a yes that came before it; and a check that could not be made is not a yes.
        assertEquals(CheckResult.OUT_OF_DATE, TestersPageScreen.of(build, memory(opened = now - 1, refused = true), now).result)
        assertEquals(CheckResult.NOT_REACHED, TestersPageScreen.of(build, memory(opened = now - 2 * day, grace = true), now).result)
    }

    @Test
    fun `what was checked for another build says nothing of this one`() {
        val now = 100 * day
        val other = memory(opened = now - 1).copy(openedBuild = "0.12.4-preview.20261020.1000", refusedBuild = "0.12.4-preview.20261020.1000")
        val screen = TestersPageScreen.of(build, other, now)
        assertEquals(CheckResult.NOT_YET, screen.result)
        assertNull(screen.lastCheck)
        assertEquals(NextCheck.AtNextOpening, screen.next)
    }

    @Test
    fun `the next check is after the yes has stood for a day, or ten minutes after a claim, and otherwise at the next opening`() {
        val opened = 100 * day
        assertEquals(NextCheck.After(opened + day), TestersPageScreen.of(build, memory(opened = opened), opened + 1000).next)
        assertEquals(NextCheck.After(opened + 10 * 60_000), TestersPageScreen.of(build, memory(opened = opened, claimed = true), opened + 1000).next)
        // Due: a yes older than it stands for, no yes on file, a no on file, and a clock put back past the yes.
        assertEquals(NextCheck.AtNextOpening, TestersPageScreen.of(build, memory(opened = opened), opened + day + 1).next)
        assertEquals(NextCheck.AtNextOpening, TestersPageScreen.of(build, memory(), opened).next)
        assertEquals(NextCheck.AtNextOpening, TestersPageScreen.of(build, memory(refused = true), opened).next)
        assertEquals(NextCheck.AtNextOpening, TestersPageScreen.of(build, memory(opened = opened), opened - 1).next)
    }

    @Test
    fun `the next check the page names is the one the rules make at a launch`() {
        val opened = 100 * day
        for (claimed in listOf(false, true)) {
            val kept = memory(opened = opened, claimed = claimed)
            val due = TesterLockRules.nextCheckAt(build, kept)!!
            for (now in listOf(opened - 1, opened, opened + 1, (opened + due) / 2, due - 1, due, due + 1, due + day)) {
                val atLaunch = TesterLockRules.atLaunch(lockOn = true, build = build, memory = kept, now = now)
                val page = TestersPageScreen.of(build, kept, now).next
                assertEquals("claimed $claimed, $now: the launch $atLaunch", atLaunch == AtLaunch.Open, page is NextCheck.After)
            }
        }
        assertNull(TesterLockRules.nextCheckAt(build, memory()))
        assertNull(TesterLockRules.nextCheckAt(build, memory(refused = true)))
        assertNull(TesterLockRules.nextCheckAt(build, memory(opened = 0L).copy(openedAt = 0L)))
    }

    @Test
    fun `the page holds the code masked and never whole`() {
        val screen = TestersPageScreen.of(build, memory(opened = 5 * day), 5 * day + 1)
        assertEquals("••••-••••-4KCT", screen.maskedCode)
        assertEquals("4KCT", screen.codeEnd)
        // Nothing the description holds, written out, has more of the code than its last four symbols.
        val written = screen.toString()
        for (piece in listOf(code, made.getValue(7), "07SH", "9NAP", code.substring(0, 8), code.substring(2))) assertFalse(piece, piece in written)
        // No code kept: nothing to show.
        val none = TestersPageScreen.of(build, memory(kept = null), 5 * day)
        assertNull(none.maskedCode)
        assertEquals("", none.codeEnd)
    }

    // --- what to try

    private val list = """
        What to try first:

        - Your tester code: this build asks for it the first time it opens. It comes with the download, or from /code in #testers, and it works on one device. Tell us if the code screen gets in your way.
        - Low quality: in Settings > Player and audio > Audio quality, pick Low and play a few songs. They should start right away and keep playing.
        - With no network: download a song, turn on airplane mode, and check its cover in your lists and in the player.
        - What's playing? on a remix or a sped-up song: it should find the original song too.
    """.trimIndent()

    @Test
    fun `what to try is the bullets of the notes without the line that introduces them`() {
        val lines = TestersPageScreen.tryLines(list)
        assertEquals(4, lines.size)
        assertTrue(lines.first().startsWith("Your tester code: this build asks for it"))
        assertEquals("What's playing? on a remix or a sped-up song: it should find the original song too.", lines.last())
        for (line in lines) assertFalse(line, line.startsWith("-") || line.startsWith("What to try"))
    }

    @Test
    fun `a list without an introduction keeps all its lines, and other bullets and line ends are read the same`() {
        assertEquals(listOf("One", "Two", "Three"), TestersPageScreen.tryLines("- One\r\n* Two\r\n• Three\r\n"))
        assertEquals(listOf("Try the new queue", "Tell us"), TestersPageScreen.tryLines("Try the new queue\n\nTell us\n"))
        // A dash that is not a bullet is part of the line.
        assertEquals(listOf("-5 dB is too quiet"), TestersPageScreen.tryLines("- -5 dB is too quiet"))
        assertEquals(listOf("-5 dB is too quiet"), TestersPageScreen.tryLines("-5 dB is too quiet"))
    }

    @Test
    fun `no file, an empty one or one with no words shows nothing at all`() {
        assertEquals(emptyList<String>(), TestersPageScreen.tryLines(null))
        assertEquals(emptyList<String>(), TestersPageScreen.tryLines(""))
        assertEquals(emptyList<String>(), TestersPageScreen.tryLines("  \n\n \t\n"))
        assertEquals(emptyList<String>(), TestersPageScreen.tryLines("What to try first:\n"))
        assertEquals(emptyList<String>(), TestersPageScreen.tryLines("What to try first:\n-\n- \n"))
    }

    // --- when it was built

    @Test
    fun `a build says when it was made in the stamp at the end of its name`() {
        assertEquals(LocalDateTime.of(2026, 10, 31, 11, 38), TestersPageScreen.builtAt("0.12.5-preview.20261031.1138"))
        assertEquals(LocalDateTime.of(2026, 1, 2, 3, 4), TestersPageScreen.builtAt("0.11.1-preview.20260102.0304"))
    }

    @Test
    fun `a build made by hand has no time to show`() {
        for (name in listOf("0.12.5", "0.12.5-preview", "0.12.5-preview.20261031", "0.12.5-preview.20261031.11", "0.12.5-preview.20261331.1138",
            "0.12.5-preview.20261031.2561", "0.12.5-preview.20261031.1138-extra", "")) {
            assertNull(name, TestersPageScreen.builtAt(name))
        }
    }

    // --- the link

    @Test
    fun `the channel is the gate's`() {
        val gate = File("src/preview/java/com/dd3boh/outertune/testerlock/TesterGate.kt").readText()
        assertTrue("private const val TESTERS_CHANNEL = \"${TestersPageScreen.CHANNEL}\"" in gate)
        assertEquals("https://discord.com/channels/1544002995887411230/1559515871305666560", TestersPageScreen.CHANNEL)
    }
}
