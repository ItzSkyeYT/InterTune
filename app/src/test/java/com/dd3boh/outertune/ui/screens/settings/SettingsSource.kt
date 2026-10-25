/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import java.io.File

/**
 * The rows of settings as the source draws them, for SettingsIndexTest: every screen of
 * settings is walked from the composable the navigation draws for its route, through the
 * composables of the settings package it calls, and each row it meets is written down with what
 * stands around it on the way, the folds and the flags.
 *
 * Read as text, the way the other tests of this package read the screens: a row is a call of
 * one of [ROW_COMPONENTS], or of a function of the package that makes rows from a title it is
 * given ([Row.maker]). What a row is called is the string resources written in its title, and
 * its mark is the one written in its modifier with settingRow.
 */
internal class SettingsSource(sources: File) {

    /** A call of a row, on the screen at [route]. */
    class Row(
        val route: String,
        val file: String,
        val line: Int,
        val component: String,
        /** The string resources written in its title, by name: empty for a title that is worked out. */
        val titles: List<String>,
        /** The marks written in its modifier, as the names of the titles they are made from. */
        val marks: List<String>,
        /** The string resources written in its description, by name: the line it has, or the lines it can have. */
        val descriptions: List<String>,
        /** The headings of the folds it is in, by name. */
        val folds: List<String>,
        /** The flags it is behind, written as an entry of the index writes them: "Unreleased.X", "!Unreleased.X". */
        val flags: Set<String>,
        /** Whether it is the inside of a function that makes rows from the title it is given. */
        val maker: Boolean,
    )

    private val settingsDir = File(sources, "ui/screens/settings")

    private class Fn(val name: String, val file: File, val text: String, val start: Int, val end: Int, val params: String)

    private val files: List<File> = settingsDir.walkTopDown().filter { it.extension == "kt" }.sortedBy { it.path }.toList()

    private val texts: Map<File, String> = files.associateWith { blank(it.readText()) }

    private val functions: Map<String, List<Fn>> = buildMap<String, MutableList<Fn>> {
        val head = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_.]+\.)?([A-Z][A-Za-z0-9_]*)\s*\(""")
        for ((file, text) in texts) {
            for (m in head.findAll(text)) {
                val open = m.range.last
                val close = close(text, open)
                if (close < 0) continue
                val rest = Regex("""^\s*(?::\s*[A-Za-z<>?, .]+)?\s*([={])""").find(text.substring(close + 1)) ?: continue
                val at = close + 1 + rest.groups[1]!!.range.first
                val end = if (text[at] == '{') {
                    close(text, at)
                } else {
                    val call = Regex("""^=\s*[A-Za-z_][A-Za-z0-9_.]*\s*\(""").find(text.substring(at)) ?: continue
                    close(text, at + call.range.last) + 1
                }
                if (end <= at) continue
                getOrPut(m.groupValues[1]) { mutableListOf() } += Fn(m.groupValues[1], file, text, at, end, text.substring(open + 1, close))
            }
        }
    }

    /** The functions that make rows from a title they are given: a title parameter, and a row inside. */
    val makers: Set<String> = functions.filterValues { fns ->
        fns.any { fn -> Regex("""\btitle\s*:\s*String\b""").containsMatchIn(fn.params) && ROW_COMPONENTS.any { Regex("""\b$it\s*\(""").containsMatchIn(fn.text.substring(fn.start, fn.end)) } }
    }.keys

    /** The settings routes the navigation has, each with the composable it draws and the flags it is behind. */
    val routes: Map<String, Pair<String, Set<String>>> = run {
        val nav = blank(File(sources, "ui/navigation/AppNavGraph.kt").readText())
        val gated = Regex("""\bif\s*\(""").findAll(nav).mapNotNull { m ->
            val open = m.range.last
            val close = close(nav, open)
            val brace = Regex("""^\s*\{""").find(nav.substring(close + 1)) ?: return@mapNotNull null
            val start = close + 1 + brace.range.last
            Triple(start, close(nav, start), flagsOf(nav.substring(open + 1, close), negated = false))
        }.toList()
        Regex("""screen\("(settings/[^"]*)"\)\s*\{\s*([A-Z][A-Za-z]+)\(""").findAll(nav).associate { m ->
            val flags = gated.filter { (s, e, _) -> m.range.first in s..e }.flatMap { it.third }.toSet()
            m.groupValues[1] to (m.groupValues[2] to flags)
        }
    }

    /** Every row of every screen of settings. */
    val rows: List<Row> = buildList {
        for ((route, screen) in routes) walk(route, screen.first, emptyList(), screen.second, emptyList(), this)
    }

    private fun walk(route: String, name: String, folds: List<String>, flags: Set<String>, stack: List<String>, out: MutableList<Row>) {
        if (name in stack) return
        for (fn in functions[name].orEmpty()) {
            val text = fn.text
            val context = Context(text, fn.start, fn.end)
            val call = Regex("""(?<![A-Za-z0-9_.])([A-Z][A-Za-z0-9_]*)\s*\(""")
            for (m in call.findAll(text.substring(fn.start, fn.end))) {
                val callee = m.groupValues[1]
                val at = fn.start + m.range.first
                val open = fn.start + m.range.last
                val hereFolds = folds + context.folds(at)
                val hereFlags = flags + context.flags(at)
                if (callee in ROW_COMPONENTS || callee in makers) {
                    val close = close(text, open)
                    val args = text.substring(open + 1, close)
                    val title = argument(args, "title")
                    val description = argument(args, "description")
                    val modifier = argument(args, "modifier").orEmpty()
                    out += Row(
                        route = route,
                        file = fn.file.relativeTo(settingsDir).path,
                        line = text.substring(0, at).count { it == '\n' } + 1,
                        component = callee,
                        titles = title?.let { STRING.findAll(it).map { r -> r.groupValues[1] }.toList() }.orEmpty(),
                        marks = Regex("""settingRow\(R\.string\.(\w+)\)""").findAll(modifier).map { it.groupValues[1] }.toList(),
                        descriptions = description?.let { STRING.findAll(it).map { r -> r.groupValues[1] }.toList() }.orEmpty(),
                        folds = hereFolds,
                        flags = hereFlags,
                        maker = name in makers && callee in ROW_COMPONENTS,
                    )
                    // A row maker's own rows are its call sites' rows: not gone into.
                } else if (callee in functions && callee != name) {
                    walk(route, callee, hereFolds, hereFlags, stack + name, out)
                }
            }
        }
    }

    /** The folds and the flags around each place of a function's body. */
    private class Context(val text: String, start: Int, end: Int) {
        private class Span(val from: Int, val to: Int, val fold: String? = null, val flags: Set<String> = emptySet())

        private val spans = mutableListOf<Span>()

        init {
            val body = text.substring(start, end)
            // The blocks of the body, to find the one a statement stands in.
            val blocks = mutableListOf<IntRange>()
            run {
                val open = ArrayDeque<Int>()
                var i = start
                while (i < end) {
                    when (text[i]) {
                        '"' -> i = skipString(text, i)
                        '\'' -> i = skipChar(text, i)
                        '{' -> open.addLast(i)
                        '}' -> open.removeLastOrNull()?.let { blocks += it..i }
                    }
                    i++
                }
            }
            fun blockOf(at: Int) = blocks.filter { at in it }.minByOrNull { it.last - it.first }

            Regex("""SettingsClickToReveal\s*\(\s*(?:title\s*=\s*)?stringResource\(R\.string\.(\w+)\)\s*\)\s*\{""").findAll(body).forEach { m ->
                val brace = start + m.range.last
                spans += Span(brace, close(text, brace), fold = m.groupValues[1])
            }
            Regex("""\b(if|AnimatedVisibility)\s*\(""").findAll(body).forEach { m ->
                val open = start + m.range.last
                val close = close(text, open)
                if (close < 0) return@forEach
                var condition = text.substring(open + 1, close)
                if (m.groupValues[1] == "AnimatedVisibility") condition = condition.substringBefore(',').removePrefix("visible =").trim()
                val after = text.substring(close + 1)
                val brace = Regex("""^\s*\{""").find(after)
                if (brace != null) {
                    val from = close + 1 + brace.range.last
                    val to = close(text, from)
                    spans += Span(from, to, flags = flagsOf(condition, negated = false))
                    val otherwise = Regex("""^\s*else\s*\{""").find(text.substring(to + 1))
                    if (m.groupValues[1] == "if" && otherwise != null) {
                        val elseFrom = to + 1 + otherwise.range.last
                        spans += Span(elseFrom, close(text, elseFrom), flags = flagsOf(condition, negated = true))
                    }
                } else if (m.groupValues[1] == "if") {
                    // One statement: an early return leaves what follows it in the block behind
                    // the opposite; anything else is behind the condition to the end of its line.
                    val statement = after.substringBefore('\n')
                    if (Regex("""^\s*return\b""").containsMatchIn(statement)) {
                        val block = blockOf(open)
                        if (block != null) spans += Span(close, block.last, flags = flagsOf(condition, negated = true))
                    } else {
                        spans += Span(close, close + 1 + statement.length, flags = flagsOf(condition, negated = false))
                    }
                }
            }
        }

        fun folds(at: Int): List<String> = spans.filter { it.fold != null && at > it.from && at < it.to }.map { it.fold!! }
        fun flags(at: Int): Set<String> = spans.filter { at > it.from && at < it.to }.flatMap { it.flags }.toSet()
    }

    companion object {
        val ROW_COMPONENTS = listOf(
            "PreferenceEntry", "SwitchPreference", "ListPreference", "EnumListPreference",
            "EditTextPreference", "ExplainedPreference", "ExplainedSwitchPreference",
        )

        private val STRING = Regex("""R\.string\.(\w+)""")
        private val FLAG = Regex("""^!?(Unreleased\.[A-Z][A-Z0-9_]*|ENABLE_FFMETADATAEX)$""")

        /**
         * The flags a condition puts a row behind: each flag of a condition that is all of its
         * parts ("&&"), and, for the other side of an if, the opposite of a condition that is one
         * flag alone. Anything that is not a flag (a switch, the phone) is not a flag of the build.
         */
        fun flagsOf(condition: String, negated: Boolean): Set<String> {
            val terms = condition.trim().removeSurrounding("(", ")").split("&&").map { it.trim() }
            if (terms.any { "||" in it }) return emptySet()
            if (negated) {
                val only = terms.singleOrNull()?.takeIf { FLAG.matches(it) } ?: return emptySet()
                return setOf(if (only.startsWith("!")) only.drop(1) else "!$only")
            }
            return terms.filter { FLAG.matches(it) }.toSet()
        }

        /** The flags an entry of the index is written behind, read off its line. */
        fun flagsWritten(line: String): Set<String> {
            val there = line.substringAfter("there = ", "").substringBeforeLast(")")
            return Regex("""!?(?:Unreleased\.[A-Z][A-Z0-9_]*|ENABLE_FFMETADATAEX)""").findAll(there).map { it.value }.toSet()
        }

        /** [text] with its comments blanked out, strings and offsets left as they were. */
        fun blank(text: String): String {
            val out = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                when {
                    text.startsWith("//", i) -> {
                        val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                        repeat(end - i) { out.append(' ') }
                        i = end
                    }
                    text.startsWith("/*", i) -> {
                        val end = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                        for (c in text.substring(i, end)) out.append(if (c == '\n') '\n' else ' ')
                        i = end
                    }
                    text[i] == '"' -> {
                        val end = skipString(text, i) + 1
                        out.append(text, i, end)
                        i = end
                    }
                    text[i] == '\'' -> {
                        val end = skipChar(text, i) + 1
                        out.append(text, i, end)
                        i = end
                    }
                    else -> out.append(text[i++])
                }
            }
            return out.toString()
        }

        /** The last character of the string starting at [at], templates and the strings in them skipped. */
        fun skipString(text: String, at: Int): Int {
            if (text.startsWith("\"\"\"", at)) return text.indexOf("\"\"\"", at + 3).let { if (it < 0) text.lastIndex else it + 2 }
            var i = at + 1
            while (i < text.length && text[i] != '"') {
                when {
                    text[i] == '\\' -> i++
                    text.startsWith("\${", i) -> {
                        var depth = 0
                        var j = i + 1
                        while (j < text.length) {
                            when (text[j]) {
                                '"' -> j = skipString(text, j)
                                '{' -> depth++
                                '}' -> if (--depth == 0) break
                            }
                            j++
                        }
                        i = j
                    }
                }
                i++
            }
            return minOf(i, text.lastIndex)
        }

        /** The last character of the character written at [at], 'a' or '\n'. */
        fun skipChar(text: String, at: Int): Int {
            var i = at + 1
            while (i < text.length && text[i] != '\'') {
                if (text[i] == '\\') i++
                i++
            }
            return minOf(i, text.lastIndex)
        }

        /** Where the bracket opened at [at] closes, strings skipped; -1 when it never does. */
        fun close(text: String, at: Int): Int {
            val open = text[at]
            val shut = when (open) { '(' -> ')'; '{' -> '}'; '[' -> ']'; else -> return -1 }
            var depth = 0
            var i = at
            while (i < text.length) {
                when (text[i]) {
                    '"' -> i = skipString(text, i)
                    '\'' -> i = skipChar(text, i)
                    open -> depth++
                    shut -> if (--depth == 0) return i
                }
                i++
            }
            return -1
        }

        /** The text of the named argument [name] of a call's arguments, or null. */
        fun argument(args: String, name: String): String? {
            var depth = 0
            var start = 0
            var i = 0
            fun piece(to: Int): String? {
                val m = Regex("""^\s*$name\s*=\s*""").find(args.substring(start, to)) ?: return null
                return args.substring(start + m.range.last + 1, to).trim()
            }
            while (i < args.length) {
                when (args[i]) {
                    '"' -> i = skipString(args, i)
                    '\'' -> i = skipChar(args, i)
                    '(', '{', '[' -> depth++
                    ')', '}', ']' -> depth--
                    ',' -> if (depth == 0) {
                        piece(i)?.let { return it }
                        start = i + 1
                    }
                }
                i++
            }
            return piece(args.length)
        }
    }
}
