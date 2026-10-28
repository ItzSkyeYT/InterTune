/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.util.UnknownFormatConversionException

/**
 * A line built with String.format takes its words as arguments, never inside the template: a
 * template that carries a description reads every % in that description as a conversion. An
 * opening whose rhythm checks failed on "3:2 rival at 45%; tempo spread 3.2%" closed the app the
 * moment it was read (on a phone, 25 Oct 2026), and the same kind of text in a change's own line
 * dropped the change on bands, or the editor's Auto, without a word.
 */
class MixFormatTemplatesTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** The text without its comments. */
    private fun code(text: String) = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `a description with a percent sign in it cannot go through a template`() {
        val failures = "trusted: nothing (3:2 rival at 45%; tempo spread 3.2%)"
        assertThrows(UnknownFormatConversionException::class.java) { "$failures, first sound %.2f s".format(0.07) }
        assertEquals("$failures, first sound 0.07 s", failures + ", first sound %.2f s".format(java.util.Locale.ROOT, 0.07))
    }

    @Test
    fun `no template in the Mix code carries text of its own`() {
        // A string literal with an interpolation and a conversion in it, handed straight to format.
        val template = Regex("""["][^"\n]*(\$\{?[A-Za-z][^"\n]*%|%[^"\n]*\$\{?[A-Za-z])[^"\n]*["]\s*\.format\(""")
        val found = listOf("playback/automix", "ui/screens/mix").flatMap { dir ->
            File(sources, dir).walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
                template.findAll(code(file.readText())).map { "${file.relativeTo(sources).path}: ${it.value}" }
            }.toList()
        }
        assertEquals(emptyList<String>(), found)
    }
}
