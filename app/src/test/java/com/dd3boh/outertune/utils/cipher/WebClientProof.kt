/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils.cipher

import com.dd3boh.outertune.utils.StreamCheck
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.models.YouTubeLocale
import com.zionhuang.innertube.models.response.PlayerResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Whether a WEB_REMIX address is served once it is deciphered, shown on this machine before any
 * phone is asked to.
 *
 * Everything but the solving is the app's own code, so what this shows is what the app would get:
 * the player script through [PlayerScripts], the request through [YouTube.player] with that
 * script's signature timestamp, the address through [StreamCipher], the question and the answer
 * through [SolverProtocol]. The solving is yt-dlp's solver, which here runs outside the JVM: the
 * command in WEB_CLIENT_SOLVER is given the question on its standard input and gives the answer
 * on its output. On a phone the same question goes to [WebViewChallengeSolver].
 *
 * For two songs it says in one line each: what WEB_REMIX answered, whether the address was
 * deciphered, what a HEAD request for it got, and whether bytes two megabytes in are served.
 * Without a po token: this machine has no WebView to make one, so a refusal here says "a token is
 * needed" and not "it does not work". A phone has to show the rest.
 *
 * It asks as a phone that has just been installed does, with no account: the main client once,
 * for the visitorData its answer carries, then WEB_REMIX for each song. Three /player requests,
 * the two requests for the player script, and two or three requests per stream address.
 *
 * Skipped unless WEB_CLIENT_PROOF=1. WEB_CLIENT_SOLVER is the command, its words separated by
 * single spaces. WEB_CLIENT_VIDEOS picks other songs, comma separated. Run it through
 * experiments/research/youtube-plan-b/host-proof/run.sh, which sets all of this and starts the
 * solver with node's permissions shut.
 *
 * WEB_CLIENT_PROOF=plumbing asks nobody anything: it takes a made-up address the way to the
 * solver and back, once with a whole script and once with a prepared one, to show that the way
 * is open before a request is spent on it. It is meant for the stand-in solver beside run.sh.
 *
 * Nothing it prints or throws carries an address, a token or a visitorData: only statuses,
 * counts and the names of failures.
 */
class WebClientProof {
    /** Queen, Bohemian Rhapsody (2008) and a-ha, Take On Me (2010): the stream check's two songs. */
    private val videos = (System.getenv("WEB_CLIENT_VIDEOS")?.takeIf { it.isNotBlank() } ?: "fJ9rUzIMcZQ,djV11Xbc914")
        .split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private val http = OkHttpClient()

    /** Well past the 512 KB that a capped address serves: the stream check's own distance. */
    private val probeAt = 2L * 1024 * 1024

    private fun say(line: String) = println("WEB_CLIENT_PROOF $line")

    @Test
    fun proof(): Unit = runBlocking {
        val mode = System.getenv("WEB_CLIENT_PROOF")
        assumeTrue("set WEB_CLIENT_PROOF=1 to run", mode == "1" || mode == "plumbing")
        val command = System.getenv("WEB_CLIENT_SOLVER")?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
        if (command.isEmpty()) {
            say("WEB_CLIENT_SOLVER is not set, so nothing was asked of anybody")
            return@runBlocking
        }
        if (mode == "plumbing") {
            runCatching { plumbing(command) }.onFailure { say("plumbing: stopped by a failure of the proof itself (${it.javaClass.simpleName})") }
            return@runBlocking
        }
        // Never a failure's own words: they can quote an address.
        runCatching { ask(command) }.onFailure { say("stopped by a failure of the proof itself (${it.javaClass.simpleName})") }
    }

    private suspend fun ask(command: List<String>) {
        YouTube.locale = YouTubeLocale(gl = Locale.getDefault().country.ifEmpty { "US" }, hl = "en")
        YouTube.visitorData = null

        val script = PlayerScripts(PlayerScripts.over(http)).current()
        if (script == null) {
            say("no player script could be had, so WEB_REMIX was not asked")
            return
        }
        say("player script ${script.id}: ${script.text.length} characters, signature timestamp ${script.signatureTimestamp}")
        delay(1_500)

        // What a new install does: the main client's answer, a refusal included, carries a visitorData.
        YouTube.player(videos.first(), client = YouTubeClient.ANDROID_VR_NO_AUTH).getOrNull()
            ?.let { StreamCheck.visitorDataToAdopt(YouTube.visitorData, it.responseContext.visitorData) }
            ?.let { YouTube.visitorData = it }
        say("visitorData from the main client's answer: " + if (YouTube.visitorData != null) "yes" else "no")

        // The script as the solver cut it down for the first song, which the second is then asked with.
        var prepared: String? = null
        for (videoId in videos) {
            delay(1_500)
            val answer = YouTube.player(videoId, client = YouTubeClient.WEB_REMIX, signatureTimestamp = script.signatureTimestamp, hlOverride = "en")
            val response = answer.getOrNull()
            if (response == null) {
                say("$videoId: WEB_REMIX gave no answer (${answer.exceptionOrNull()?.javaClass?.simpleName})")
                continue
            }
            if (response.playabilityStatus.status != "OK") {
                say("$videoId: WEB_REMIX ${response.playabilityStatus.status}" + (response.playabilityStatus.reason?.let { " \"$it\"" } ?: ""))
                continue
            }
            val line = stream(response, script, prepared, command) { prepared = it }
            say("$videoId: WEB_REMIX OK, $line")
        }
    }

    /** The way to the solver and back with a made-up address. No request to anybody. */
    private fun plumbing(command: List<String>) {
        val inner = "https://rr1---sn-test.googlevideo.com/videoplayback?itag=251&n=ISSUED"
        val address = checkNotNull(StreamCipher.read(null, "s=SCRAMBLED&sp=sig&url=" + java.net.URLEncoder.encode(inner, "UTF-8")))
        val signatures = listOfNotNull(address.signature)
        val ns = listOfNotNull(address.n)
        var prepared: String? = null
        for (round in listOf("with a whole script", "with the prepared script")) {
            val question = withScript(SolverProtocol.input(prepared != null, signatures, ns), prepared ?: "var _yt_player={};")
            val output = solver(command, question)
            if (output == null) {
                say("plumbing, $round: the solver's process gave no answer")
                return
            }
            val reading = SolverProtocol.read(output, signatures, ns).getOrElse {
                say("plumbing, $round: ${it.message}")
                return
            }
            val solved = reading.solved
            val url = StreamCipher.solved(address, address.signature?.let { solved.signatures[it] }, address.n?.let { solved.ns[it] })
            say(
                "plumbing, $round: signature " + answered(address.signature, solved.signatures) + ", n " + answered(address.n, solved.ns) +
                    ", address " + (if (url != null) "built" else "not built") +
                    ", prepared script " + (if (reading.prepared != null) "given back" else "not given back"),
            )
            prepared = reading.prepared ?: prepared
        }
    }

    /** What the best audio format's address comes to: deciphered or not, and what a fetch of it gets, without a po token. */
    private suspend fun stream(
        response: PlayerResponse,
        script: PlayerScript,
        prepared: String?,
        command: List<String>,
        keep: (String) -> Unit,
    ): String {
        val audio = response.streamingData?.adaptiveFormats?.filter { it.isAudio }.orEmpty()
        val format = audio.filter { it.url != null || it.signatureCipher != null }.maxByOrNull { it.bitrate }
            ?: return "${audio.size} audio formats and none with an address (SABR only)"
        val address = StreamCipher.read(format.url, format.signatureCipher) ?: return "a cipher that could not be read"
        val kind = if (address.signature != null) "ciphered address" else "plain address"

        val signatures = listOfNotNull(address.signature)
        val ns = listOfNotNull(address.n)
        var solved = ChallengeSolver.Solved(emptyMap(), emptyMap())
        if (address.needsSolving) {
            val question = withScript(SolverProtocol.input(prepared != null, signatures, ns), prepared ?: script.text)
            val output = solver(command, question) ?: return "$kind, the solver's process gave no answer"
            val reading = SolverProtocol.read(output, signatures, ns).getOrElse { return "$kind, not deciphered: ${it.message}" }
            reading.errors.forEach { say("  the solver could not do $it") }
            reading.prepared?.let(keep)
            solved = reading.solved
        }
        val url = StreamCipher.solved(address, address.signature?.let { solved.signatures[it] }, address.n?.let { solved.ns[it] })
            ?: return "$kind, not deciphered (signature " + answered(address.signature, solved.signatures) + ", n " + answered(address.n, solved.ns) + ")"

        val asked = (if (prepared != null) "with the prepared script" else "with the whole script")
        return "$kind, deciphered $asked, " + fetched(url, format.contentLength ?: 0L) + ", no po token"
    }

    private fun answered(question: String?, answers: Map<String, String>) = when {
        question == null -> "not needed"
        question in answers -> "answered"
        else -> "not answered"
    }

    /** The player's HEAD check, then sixteen bytes from further in, and from the start when those are refused. */
    private suspend fun fetched(url: String, length: Long): String {
        val head = status(Request.Builder().head().url(url).build())
        delay(1_500)
        if (length <= 512 * 1024 + 16) return "HEAD ${head ?: "failed"}, too short to say more"
        val from = minOf(probeAt, length - 16)
        val past = bytes(url, from)
        if (past == "served") return "HEAD ${head ?: "failed"}, bytes past 512 KB served"
        delay(1_500)
        return "HEAD ${head ?: "failed"}, bytes past 512 KB $past, the first bytes ${bytes(url, 0)}"
    }

    private fun bytes(url: String, from: Long): String = runCatching {
        http.newCall(Request.Builder().url(url).header("Range", "bytes=$from-${from + 15}").build()).execute().use { answer ->
            val count = if (answer.isSuccessful) answer.body?.bytes()?.size ?: 0 else 0
            if (count > 0) "served" else "refused (${answer.code})"
        }
    }.getOrElse { "failed" }

    private fun status(request: Request): Int? = runCatching { http.newCall(request).execute().use { it.code } }.getOrNull()

    /** [input] with the script in it, whole or prepared, as the page of [WebViewChallengeSolver] puts it in. */
    private fun withScript(input: String, script: String): String {
        val question = Json.parseToJsonElement(input).jsonObject
        val field = if ((question["type"] as? JsonPrimitive)?.content == "player") "player" else "preprocessed_player"
        return JsonObject(question + (field to JsonPrimitive(script))).toString()
    }

    /**
     * The solver's answer to [question], or null. The question goes in on standard input and the
     * answer comes out on standard output, so neither is ever in a file. What the process writes
     * to its error output is counted and not repeated.
     */
    private fun solver(command: List<String>, question: String): String? {
        val process = ProcessBuilder(command).redirectErrorStream(false).start()
        val errors = Thread {
            process.errorStream.bufferedReader().use { it.readText() }.let { text ->
                if (text.isNotBlank()) {
                    say("  the solver's process wrote ${text.lines().size} lines to its error output, beginning:")
                    text.lines().filter { it.isNotBlank() }.take(4).forEach { say("    " + it.take(160)) }
                }
            }
        }
        errors.start()
        var output: String? = null
        val reading = Thread { output = process.inputStream.bufferedReader().use { it.readText() } }
        reading.start()
        process.outputStream.bufferedWriter().use { it.write(question) }
        if (!process.waitFor(180, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            say("  the solver's process was stopped after three minutes")
            return null
        }
        reading.join(5_000)
        errors.join(5_000)
        if (process.exitValue() != 0) {
            say("  the solver's process ended with ${process.exitValue()}")
            return null
        }
        return output?.trim()?.takeIf { it.isNotEmpty() }
    }
}
