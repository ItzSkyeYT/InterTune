/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android shows a toast's text on two lines at most and cuts the rest (Android 12 and later), about
 * 35 characters a line on the Pixel 5 (5 Nov 2026: the cast ladder's message lost the part that said
 * what to do). So every text a toast shows fits in 70 characters, in English and in French.
 * Read from the source: each Toaster.show call and the texts named inside it.
 */
class ToastLengthTest {
    private val most = 70

    private fun strings(folder: String): Map<String, String> =
        listOf("strings.xml", "strings-ot.xml").map { File("src/main/res/$folder/$it") }.filter { it.exists() }
            .flatMap { file -> Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(file.readText()).map { it.groupValues[1] to it.groupValues[2] } }
            .associate { (name, text) -> name to text.replace("\\'", "'").replace("\\\"", "\"").replace("&amp;", "&") }

    /** Every text named inside a Toaster.show( ... ) call in the app's code. */
    private fun toastTexts(): Set<String> {
        val names = HashSet<String>()
        File("src/main/java").walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val code = file.readText()
            var at = code.indexOf("Toaster.show(")
            while (at >= 0) {
                var i = at + "Toaster.show(".length
                var depth = 1
                while (i < code.length && depth > 0) {
                    when (code[i]) { '(' -> depth++; ')' -> depth-- }
                    i++
                }
                Regex("""R\.string\.(\w+)""").findAll(code.substring(at, i)).forEach { names += it.groupValues[1] }
                at = code.indexOf("Toaster.show(", i)
            }
        }
        return names
    }

    @Test
    fun `every toast fits on two lines, in English and in French`() {
        val names = toastTexts()
        assertTrue("the toasts were not found", names.size > 20)
        for (folder in listOf("values", "values-fr")) {
            val texts = strings(folder)
            for (name in names) {
                val text = texts[name] ?: continue
                // A text with a song's or a list's name in it cannot be measured here.
                if ('%' in text) continue
                assertTrue("$folder/$name is ${text.length} characters, more than two lines of a toast: $text", text.length <= most)
            }
        }
    }
}
