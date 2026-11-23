/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.utils

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #197: 0.12.9 crashed with "Unable to add window, the token is not valid; is your activity running?" (a Pixel 8
 * Pro on Android 17). Retraced with the release's map, the trace is Compose's own Dialog adding its window as a
 * recomposition was applied, with nothing of ours on it: a dialog came up after Android had let its screen go. A
 * dialog that opens by itself (a check that answers, a timer, a setting, the phone) now opens only while the app is
 * on screen, through OpensOnScreen, and one owed while the app is away waits for it to be back.
 *
 * The rule first, then where it is kept, read from the source as ToastOnMainThreadTest reads it: every dialog or
 * sheet a LaunchedEffect opens, found by a search, and those that open on a condition of their own, named here.
 */
class OpensOnScreenTest {
    private val sources = File("src/main/java/com/dd3boh/outertune")

    @Test
    fun `a dialog may open while the app is on screen, and not while it is away or closing`() {
        assertTrue(windowMayOpen(Lifecycle.State.RESUMED, finishing = false))
        // Started is on screen too: under a see-through window, or beside another app.
        assertTrue(windowMayOpen(Lifecycle.State.STARTED, finishing = false))
        // Away (another app in front, the screen off), not made yet, or gone.
        assertFalse(windowMayOpen(Lifecycle.State.CREATED, finishing = false))
        assertFalse(windowMayOpen(Lifecycle.State.INITIALIZED, finishing = false))
        assertFalse(windowMayOpen(Lifecycle.State.DESTROYED, finishing = false))
        // Closing, though it is still there to be seen.
        assertFalse(windowMayOpen(Lifecycle.State.RESUMED, finishing = true))
        assertFalse(windowMayOpen(Lifecycle.State.STARTED, finishing = true))
    }

    /** Dialogs that open on a condition of their own rather than a LaunchedEffect's, each as its call reads in its file. */
    private val openOnTheirOwn = listOf(
        // A check that answers, the hour of Later being up, the install moving on.
        "MainActivity.kt" to "UpdatePrompt(",
        // Once the questions, the welcome page, a tour and the update offer are out of the way.
        "MainActivity.kt" to "AccountNoticeDialog(",
    )

    @Test
    fun `the dialogs that open on a condition of their own go through OpensOnScreen`() {
        val unguarded = openOnTheirOwn.flatMap { (file, call) ->
            val source = Source(File(sources, file).readLines())
            val calls = Regex("""(?<![\w.])""" + Regex.escape(call)).findAll(source.text).map { it.range.first }
                .filterNot { Regex("""\bfun\s+$""").containsMatchIn(source.text.substring(maxOf(0, it - 8), it)) }.toList()
            assertTrue("$call is not called in $file any more: take it off the list, or say where it went", calls.isNotEmpty())
            calls.filterNot { source.insideOpensOnScreen(it) }.map { "$file:${source.lineOf(it) + 1} $call" }
        }
        assertEquals(emptyList<String>(), unguarded)
    }

    @Test
    fun `every dialog or sheet a LaunchedEffect opens goes through OpensOnScreen`() {
        val found = sources.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            val source = Source(file.readLines())
            source.openedByEffects().map { (at, window) ->
                Triple("${file.relativeTo(sources).path}:${source.lineOf(at) + 1}", window, source.insideOpensOnScreen(at))
            }
        }.toList()
        // The search still finds the oldest of them, the questions after an update, there in 0.12.9 already.
        assertTrue(found.any { it.first.startsWith("MainActivity.kt:") && it.second == "OptInCatchUp" })
        assertEquals(emptyList<String>(), found.filterNot { it.third }.map { "${it.first} ${it.second}" })
    }

    @Test
    fun `the search tells a dialog a LaunchedEffect opens from one a tap opens, and a guarded one from one that is not`() {
        val was = """
            @Composable
            fun Screen() {
                var asked by rememberSaveable { mutableStateOf(false) }
                var tapped by remember { mutableStateOf(false) }
                var later by remember { mutableStateOf<Int?>(null) }
                var shown by remember { mutableStateOf(false) }
                LaunchedEffect(
                    owed,
                    // "a { in words" and '{' are not blocks
                    tapped,
                ) {
                    if (owed()) asked = true
                    later = 3
                    shown = false
                }
                if (asked) {
                    QuestionsDialog(onDone = { asked = false })
                }
                later?.let { n -> OpensOnScreen { LaterSheet(n) } }
                if (later != null && asked) OpensOnScreen {
                    CompositionLocalProvider(LocalThing provides 1) {
                        AlertDialog(onDismissRequest = {})
                    }
                }
                if (tapped) AlertDialog(onDismissRequest = { tapped = false })
                if (shown) ModalBottomSheet(onDismissRequest = { shown = false }) { }
                Button(onClick = { tapped = true }) { }
            }
        """.trimIndent().lines()
        val source = Source(was)
        // The questions, opened by the effect and left open to any moment; the sheet and the dialog inside a provider,
        // guarded; not the tap's dialog, nor the sheet the effect only ever closes.
        assertEquals(
            listOf("QuestionsDialog" to false, "LaterSheet" to true, "AlertDialog" to true),
            source.openedByEffects().map { (at, window) -> window to source.insideOpensOnScreen(at) },
        )
    }

    @Test
    fun `the phone sound tile's question waits for its screen, and a screen that is closing asks nothing`() {
        // A dialog of Android's own, put up after a read in a coroutine, the same way round (PhoneListenActivity).
        val text = File(sources, "recognition/PhoneListenActivity.kt").readText()
        val explain = text.substringAfter("private fun explainThenAsk()").substringBefore("private fun askForTheSound()")
        val waits = explain.indexOf("withStarted<Unit> {")
        assertTrue(waits >= 0)
        assertTrue("if (isFinishing) return@withStarted" in explain.substring(waits))
        assertTrue(explain.indexOf(".show()") > waits)
    }

    /** A file's code as one text, comments and the insides of strings and characters blanked, so braces count true. */
    private class Source(lines: List<String>) {
        val text: String = lines.joinToString("\n") { code(it) }
        private val lineStarts = listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }

        fun lineOf(offset: Int): Int = lineStarts.indexOfLast { it <= offset }

        /** Where the bracket opened at [open] closes, or the end of the text. */
        private fun closing(open: Int): Int {
            val opener = text[open]
            val closer = if (opener == '(') ')' else '}'
            var depth = 0
            for (i in open until text.length) {
                if (text[i] == opener) depth++
                else if (text[i] == closer && --depth == 0) return i
            }
            return text.length
        }

        /** The bodies of the LaunchedEffects in this file, as ranges of [text]. */
        private fun effects(): List<IntRange> = Regex("""\bLaunchedEffect\(""").findAll(text).mapNotNull { call ->
            val keysEnd = closing(call.range.last)
            val open = text.indexOf('{', keysEnd)
            if (open < 0 || keysEnd >= open || text.substring(keysEnd + 1, open).isNotBlank()) null
            else open..minOf(closing(open), text.length - 1)
        }.toList()

        /** The names an effect gives a value to, other than null or false: a dialog may hang on one. */
        private fun setByEffects(): Set<String> = effects().flatMap { body ->
            Regex("""(?<![\w.])([a-z]\w*)\s*=(?!=)(?!\s*(?:null|false)\b)""").findAll(text.substring(body.first, body.last + 1))
                .filterNot { Regex("""\b(val|var)\s+$""").containsMatchIn(text.substring(maxOf(0, body.first + it.range.first - 6), body.first + it.range.first)) }
                .map { it.groupValues[1] }.toList()
        }.toSet()

        /** Each dialog or sheet opened by a name an effect sets, as its call's place in [text] and its name. */
        fun openedByEffects(): List<Pair<Int, String>> = setByEffects().flatMap { name ->
            Regex("""\bif \(""" + name + """\b[^)]*\)|(?<![\w.])""" + name + """\?\.(let|takeIf)\b""").findAll(text).mapNotNull { guard ->
                // The window it opens, on the guard's last line or within the three after it.
                val end = lineStarts.getOrElse(lineOf(guard.range.last) + 4) { text.length }
                WINDOW.find(text.substring(0, end), guard.range.last + 1)?.let { it.range.first to it.groupValues[1] }
            }.toList()
        }.sortedBy { it.first }

        /** Whether the call at [at] sits in an OpensOnScreen block, in its own function. */
        fun insideOpensOnScreen(at: Int): Boolean {
            var depth = 0
            for (i in at - 1 downTo 0) {
                when (text[i]) {
                    '}' -> depth++
                    '{' -> if (depth > 0) depth-- else {
                        val before = text.substring(text.lastIndexOf('\n', i - 1) + 1, i)
                        if (before.trimEnd().endsWith("OpensOnScreen")) return true
                        if (Regex("""\bfun\b""").containsMatchIn(before)) return false
                    }
                }
            }
            return false
        }

        companion object {
            /** A call that adds a window of its own: a dialog, a sheet, the update prompt, the catch-up questions. */
            val WINDOW = Regex("""(?<![\w.])([A-Z]\w*(?:Dialog|Sheet|Prompt|CatchUp)|AlertDialog|BasicAlertDialog|ModalBottomSheet|Dialog)\(""")

            fun code(line: String): String {
                // A line of a doc comment says nothing to the search.
                if (line.trimStart().let { it.startsWith("*") || it.startsWith("/*") }) return ""
                val blanked = line
                    .replace(Regex(""""(?:\\.|[^"\\])*""""), "\"\"")
                    .replace(Regex("""'(?:\\.|[^'\\])'"""), "' '")
                val comment = blanked.indexOf("//")
                return if (comment < 0) blanked else blanked.substring(0, comment)
            }
        }
    }
}
