/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils.cipher

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Solves the signature and n values of stream addresses against one player script: see
 * [StreamCipher] for what those are.
 *
 * One implementation, [WebViewChallengeSolver], which is the one the app uses. The walk of the
 * stream chain is handed a solver the way it is handed its requests, so its tests bring a
 * stand-in and no WebView.
 */
interface ChallengeSolver {
    /** The answers, each by the value it answers. One that is missing is one the solver could not do. */
    class Solved(val signatures: Map<String, String>, val ns: Map<String, String>)

    /**
     * [signatures] and [ns] solved against [script]. Throws when the solver cannot work at all.
     *
     * Whoever calls it sets the time it may take, and cancels. A solver that is cancelled has to
     * let go of whatever it was waiting on.
     */
    suspend fun solve(script: PlayerScript, signatures: List<String>, ns: List<String>): Solved

    /**
     * Gets ready to answer about [script] before there is anything to ask: whatever a first
     * question costs more than a later one is paid now. It is a question like any other, about
     * a value nobody needs the answer to, and a failure is kept for the question that matters.
     */
    suspend fun warm(script: PlayerScript) {
        try {
            solve(script, emptyList(), listOf(WARM_N))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (kept: Throwable) {
            // For the question that matters to meet again and report.
        }
    }

    companion object {
        /** In the shape of an n as YouTube issues one. */
        const val WARM_N = "A1b2C3d4E5f6G7h8I9"
    }
}

/**
 * What is said to yt-dlp's solver and what is read of its answer, as text: kept apart from the
 * WebView that carries it so both can be tested without one.
 *
 * The shapes are the solver's own (github.com/yt-dlp/ejs, src/yt/solver/main.ts, read 9 Oct 2026
 * at release 0.8.0), and they are how yt-dlp talks to it too
 * (yt_dlp/extractor/youtube/jsc/_builtin/ejs.py):
 *
 *     in   { type: "player", player: <the script>, requests: [...], output_preprocessed: true }
 *     or   { type: "preprocessed", preprocessed_player: <what it gave back before>, requests: [...] }
 *          a request is { type: "sig" or "n", challenges: [<value>, ...] }
 *     out  { type: "result", responses: [...], preprocessed_player: <text, when asked for> }
 *          a response is { type: "result", data: { <value>: <solved value> } }
 *                     or { type: "error", error: <text> }, one for each request, in their order
 *     or   { type: "error", error: <text> }
 *
 * "Preprocessed" is the script cut down to what the two functions need. Asking with it spares the
 * solver the parsing of two and a half megabytes, which is nearly all of its work.
 */
object SolverProtocol {
    /** [errors] is what the solver said of each kind it could not do, for the log: never shown, never thrown. */
    class Reading(val solved: ChallengeSolver.Solved, val prepared: String?, val errors: List<String>)

    private const val SIGNATURE = "sig"
    private const val N = "n"

    /**
     * The question, without the script. The script, whole or prepared, is put in by the page that
     * asks, which takes it over the bridge: it is far too large to be written into a line of
     * JavaScript.
     */
    fun input(prepared: Boolean, signatures: List<String>, ns: List<String>): String = buildJsonObject {
        put("type", if (prepared) "preprocessed" else "player")
        putJsonArray("requests") {
            for ((kind, values) in kinds(signatures, ns)) {
                addJsonObject {
                    put("type", kind)
                    putJsonArray("challenges") { values.forEach { add(it) } }
                }
            }
        }
        if (!prepared) put("output_preprocessed", true)
    }.toString()

    /** The answer to a question about [signatures] and [ns], or the failure in a few words, never the solver's whole text. */
    fun read(output: String, signatures: List<String>, ns: List<String>): Result<Reading> = runCatching {
        val root = runCatching { Json.parseToJsonElement(output) }.getOrNull() as? JsonObject ?: notAnAnswer()
        when (root.text("type")) {
            "result" -> Unit
            "error" -> error("the solver failed: " + firstLine(root.text("error")))
            else -> notAnAnswer()
        }
        val asked = kinds(signatures, ns)
        val responses = root["responses"] as? JsonArray ?: notAnAnswer()
        if (asked.isEmpty() || responses.size != asked.size) notAnAnswer()

        val answers = mutableMapOf<String, Map<String, String>>()
        val errors = mutableListOf<String>()
        for ((index, kindAndValues) in asked.withIndex()) {
            val (kind, values) = kindAndValues
            val response = responses[index] as? JsonObject ?: notAnAnswer()
            if (response.text("type") != "result") {
                errors += kind + ": " + firstLine(response.text("error"))
                continue
            }
            val data = response["data"] as? JsonObject ?: notAnAnswer()
            // Only what was asked about, and only text.
            answers[kind] = values.mapNotNull { value ->
                (data[value] as? JsonPrimitive)?.takeIf { it.isString }?.let { value to it.content }
            }.toMap()
        }
        Reading(
            ChallengeSolver.Solved(answers[SIGNATURE].orEmpty(), answers[N].orEmpty()),
            prepared = (root["preprocessed_player"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            errors = errors,
        )
    }

    /** The kinds asked about, in the order they are asked in. A kind with no values is not asked about at all. */
    private fun kinds(signatures: List<String>, ns: List<String>): List<Pair<String, List<String>>> =
        listOf(SIGNATURE to signatures, N to ns).filter { it.second.isNotEmpty() }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** An error of the solver's begins with what went wrong and goes on with where in the script, which nobody needs. */
    private fun firstLine(text: String?): String = text?.lineSequence()?.firstOrNull()?.take(120)?.ifBlank { null } ?: "no reason given"

    private fun notAnAnswer(): Nothing = error("the solver's answer is not an answer")
}
