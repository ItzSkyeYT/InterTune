/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Every change Your data makes to what it has learned runs past the page (pastThePage), so leaving
 * the page halfway cannot leave it half done. A forget marks the listens and then rebuilds, and a
 * load writes the weights and then marks them as a loaded copy: cancelled between the two, the
 * page's own scope left listens forgotten with what they taught still in place, or a copy loaded
 * without its mark, so a later forget, reset or rebuild said nothing of it.
 *
 * This reads the source, as PlayOriginCoverageTest does, since the scope a coroutine runs in leaves
 * nothing a plain test can see.
 */
class LearnedChangesPastThePageTest {

    private val source = File("src/main/java/com/dd3boh/outertune/viewmodels/RecommendationsViewModel.kt")

    /** The calls that change what it has learned, or the mark that says it came from a copy. */
    private val writes = listOf(
        "upsertEngineWeights(", "learning.rebuild(", "learning.reset(", "forgetSession(", "forgetBetween(",
        "dropForgottenExamples(", "it[EngineCopyLoadedKey] =",
    )

    @Test
    fun `every change to what it has learned runs past the page`() {
        val text = source.readText()
        // The spans handed to pastThePage, from its opening parenthesis to its match.
        val inside = Regex("""\bpastThePage\(""").findAll(text)
            .filter { !isComment(text, it.range.first) && !text.substring(text.lastIndexOf('\n', it.range.first) + 1, it.range.first).contains("fun ") }
            .map { it.range.last + 1 until closingParen(text, it.range.last + 1) }
            .toList()
        val outside = writes.flatMap { call ->
            Regex(Regex.escape(call)).findAll(text).map { it.range.first }
                .filter { at -> !isComment(text, at) && inside.none { at in it } }
                .map { at -> "$call at line ${text.substring(0, at).count { it == '\n' } + 1}" }
                .toList()
        }
        assertEquals("Changes to what it has learned outside pastThePage:\n" + outside.joinToString("\n"), emptyList<String>(), outside)
    }

    private fun isComment(text: String, at: Int): Boolean {
        val line = text.substring(text.lastIndexOf('\n', at) + 1, at).trimStart()
        return line.startsWith("//") || line.startsWith("*")
    }

    /** The index of the parenthesis that closes the one before [start]. */
    private fun closingParen(text: String, start: Int): Int {
        var depth = 1
        var i = start
        var inString = false
        while (depth > 0 && i < text.length) {
            val c = text[i]
            if (c == '"' && text[i - 1] != '\\') inString = !inString
            else if (!inString) when (c) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
            }
            i++
        }
        return i - 1
    }
}
