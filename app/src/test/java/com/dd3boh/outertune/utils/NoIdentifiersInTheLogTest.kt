/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Nothing the app writes to the log says whose phone it is.
 *
 * Logcat is copied whole into GitHub issues and Discord when something breaks, and Log.d is not
 * stripped from a release build here. The line that prompted this printed every stream url as it
 * was: the address the url had been issued to, which is the listener's own, beside the signature
 * that makes it play. The po token code printed the session a token is made for, which is the
 * visitorData or, signed in, the account's dataSyncId, and then the tokens.
 *
 * So a stream or tracking url, a cookie, a visitorData, a dataSyncId and a po token reach the log
 * as their length, or through the function that keeps what helps and drops the rest:
 * StreamCheck.urlForLog and ListenReporting.trackingAddressForLog. This reads the source, as
 * NoBlockingOnRoomFlowsTest does, because a line that says too much compiles and passes every
 * test that runs.
 *
 * It knows these things by the names the code gives them, so it catches the line added while
 * chasing a bug and proves nothing more. A line put together somewhere else and logged whole is
 * not seen, nor is a failure whose own words quote an address: ListenReporting.historyFailureLine
 * is there for that one.
 */
class NoIdentifiersInTheLogTest {

    /** The app, and the requests under it. */
    private val roots = listOf(File("src/main/java"), File("../innertube/src/main/java"))

    /** A call that writes to the log, up to the parenthesis that opens what it is given. */
    private val logCall = Regex("""\b(?:Log\.(?:v|d|i|w|e|wtf)|println|print|reportException)\s*\(""")

    /** What the code calls the things that are not logged as they are. */
    private val secret = Regex(
        """\b(?:\w*[sS]treamUrl\w*|\w*[pP]laybackUrl\w*|playbackTracking\w*|songUrlCache|\w*[cC]ookie\w*""" +
            """|\w*[vV]isitorData\w*|\w*[dD]ataSyncId\w*|\w*Pot|pot|\w*[pP]oToken\w*)\b"""
    )

    /** And what it calls them in some of the code only, by a name that is harmless elsewhere. */
    private val secretIn = mapOf(
        // The session a po token is made for, and what BotGuard answers. Elsewhere a session is a run of listens.
        "/utils/potoken/" to Regex("""\b(?:sessionId|identifier|botguardResponse|responseBody|integrityToken)\b"""),
        // A url the player handles is a stream url, and one of the requests under it may be a tracking url.
        "/utils/YTPlayerUtils.kt" to Regex("""\burl\b"""),
        "/innertube/" to Regex("""\burl\b"""),
    )

    /** What may be said of one: how long it is, or what the function for it keeps. */
    private val reduced = Regex("""\b(?:urlForLog|trackingAddressForLog)\(\s*[\w.?!]+\s*\)|[\w.?!]+\.length\b""")

    @Test
    fun `no log line says an address, a cookie, a visitorData or a token as it is`() {
        val found = mutableListOf<String>()
        var statements = 0
        roots.forEach { root ->
            assertTrue("$root was not found", root.isDirectory)
            root.walkTopDown().filter { it.extension == "kt" || it.extension == "java" }.sortedBy { it.path }.forEach { file ->
                val text = file.readText()
                logStatements(text).forEach { (at, statement) ->
                    statements++
                    namedIn(statement, file.invariantSeparatorsPath).forEach { found += "${file.invariantSeparatorsPath}:${lineAt(text, at)}: $it" }
                }
            }
        }
        assertTrue("the search finds only $statements log lines", statements > 300)
        assertEquals(
            "logged as it is, where its length or the function that reduces it would do:\n" + found.joinToString("\n"),
            emptyList<String>(),
            found,
        )
    }

    @Test
    fun `the search finds the lines it is there for`() {
        // The lines that were there, some that could be, and after the comment those that are fine.
        // A # stands for the dollar of a template, which this string would take for one of its own.
        val was = """
            Log.d(TAG, "[#videoId] stream url: #streamUrl")
            Log.d(TAG, "Web poToken requested: #videoId, #sessionId")
            Log.d(TAG, "[#videoId] playerPot=#playerPot, streamingPot=#streamingPot")
            Log.d(TAG, "Generated poToken: identifier=#identifier poToken=#poToken")
            Log.w(TAG, "Could not sign in with #{YouTube.cookie?.take(40)}")
            Log.i(TAG, "visitorData is now " + YouTube.visitorData)
            Log.d(TAG, "Got playback url: #{playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl}")
            reportException(IOException("HEAD failed for #url"))
            Log.d(
                TAG,
                "resolved #{format.itag}: #{
                    streamUrl
                }",
            )
            Log.d(TAG, "split on #{line.substringBefore('"')} with #cookie")
            // Log.d(TAG, "stream url: #streamUrl")
            Log.d(TAG, "[#videoId] stream url: #{StreamCheck.urlForLog(streamUrl)}")
            Log.d(TAG, "Got playback url: #{ListenReporting.trackingAddressForLog(playbackUrl)}")
            Log.d(TAG, "Web poToken requested: #videoId, session identifier of #{sessionId.length} characters")
            Log.e(TAG, "Could not parse cookie. Clearing existing cookie. #{e.message}")
            Log.i(TAG, "[#videoId] no usable visitorData, taking the one #{MAIN_CLIENT.clientName}'s answer carried")
            Log.d(TAG, "[#videoId] stream client: #{client.clientName}, playabilityStatus: #{status?.let { it.status + (it.reason?.let { " - #it" } ?: "") }}")
        """.trimIndent().replace('#', '$')
        val everywhere = secretIn.keys.joinToString(" ")
        val found = logStatements(was).flatMap { (at, statement) -> namedIn(statement, everywhere).map { "${lineAt(was, at)}: $it" } }
        assertEquals(
            "not a word of a message, a comment, a length or what a ForLog function is given",
            listOf(
                "1: streamUrl", "2: sessionId", "3: playerPot", "3: streamingPot", "4: identifier", "4: poToken", "5: cookie",
                "6: visitorData", "7: playbackTracking", "7: videostatsPlaybackUrl", "8: url", "9: streamUrl", "15: cookie",
            ),
            found,
        )
    }

    /** Every call that writes to the log, whole, with where it starts. A call often runs over several lines. */
    private fun logStatements(text: String): List<Pair<Int, String>> =
        logCall.findAll(text).filterNot { isComment(text, it.range.first) }
            .map { it.range.first to text.substring(it.range.first, closingParen(text, it.range.last) + 1) }
            .toList()

    /** What [statement] would write as it is, by the names the code under [path] gives such things. */
    private fun namedIn(statement: String, path: String): List<String> {
        val said = reduced.replace(code(statement), " ")
        return (listOf(secret) + secretIn.filterKeys { it in path }.values)
            .flatMap { it.findAll(said) }.sortedBy { it.range.first }.map { it.value }.distinct()
    }

    /**
     * The code of [statement]: what its string literals say is left out, what their templates put
     * in is kept. So "no cookie, visitor $visitorData" leaves visitorData and not cookie.
     */
    private fun code(statement: String): String {
        val out = StringBuilder()
        val templates = ArrayDeque<Int>() // the brace depth each ${ was opened at
        var depth = 0
        var literal = false
        var i = 0
        while (i < statement.length) {
            val c = statement[i]
            when {
                literal && c == '\\' -> i++ // an escaped character is one of the message's
                literal && c == '"' -> literal = false
                literal && c == '$' && statement.getOrNull(i + 1) == '{' -> {
                    templates.addLast(depth++)
                    literal = false
                    out.append(' ')
                    i++
                }
                literal && c == '$' -> {
                    val name = statement.substring(i + 1).takeWhile { it.isLetterOrDigit() || it == '_' }
                    out.append(' ').append(name).append(' ')
                    i += name.length
                }
                literal -> Unit
                c == '"' -> literal = true
                // A character, which may be a quote: 'x', or '\x' with its mark after it.
                c == '\'' -> i = statement.indexOf('\'', if (statement.getOrNull(i + 1) == '\\') i + 3 else i + 2).takeIf { it >= 0 } ?: i
                else -> {
                    if (c == '{') depth++
                    if (c == '}' && --depth == templates.lastOrNull()) {
                        templates.removeLast()
                        literal = true
                    }
                    out.append(c)
                }
            }
            i++
        }
        return out.toString()
    }

    /** The index of the parenthesis closing the one at [open]. Those in a message come in pairs, so they count like any other. */
    private fun closingParen(text: String, open: Int): Int {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return i
            }
        }
        return text.lastIndex
    }

    private fun lineOf(text: String, at: Int) = text.substring(text.lastIndexOf('\n', at - 1) + 1, at)
    private fun isComment(text: String, at: Int) = lineOf(text, at).trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } || "//" in lineOf(text, at)
    private fun lineAt(text: String, at: Int) = text.substring(0, at).count { it == '\n' } + 1
}
