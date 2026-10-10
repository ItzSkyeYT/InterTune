/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.zionhuang.innertube.NewPipeUtils
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.models.YouTubeLocale
import com.zionhuang.innertube.models.response.PlayerResponse
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * What each client of the stream chain answers today, asked the way the app asks it.
 *
 * The chain has been rebuilt every time YouTube changed what it enforces, and each time the first
 * to know were the people whose music had stopped. This asks every client the player can ask
 * ([YTPlayerUtils.chainClients], so the list cannot drift from the app's) for two songs that have
 * been on YouTube for many years, through the app's own request ([YouTube.player]), and says of
 * each in one line:
 * - whether it plays, or the refusal in YouTube's words;
 * - what a HEAD request for the stream url got, which is the check the player makes;
 * - whether bytes two megabytes into the file are served. IOS and ANDROID urls serve the first
 *   512 KB and refuse the rest (27 Sep 2026), which is the song that stops after half a minute;
 * - whether the answer carries loudness, the song's details and the address a play is reported
 *   to, which the player and the history report read from it.
 *
 * It asks as a phone that has just been installed does: no account and no visitorData to begin
 * with, the main client first, whose answer carries a visitorData that the others are then asked
 * with. The client that carries the account is asked without one, since there is none here, so its
 * line says what YouTube gives that client anonymously and not what a signed in listener gets.
 *
 * Skipped unless STREAM_CHAIN_PROBE=1, so an ordinary run and CI never touch the network. A run
 * makes eight /player requests and at most two requests per stream url, a second and a half apart.
 *
 *     STREAM_CHAIN_PROBE=1 ./gradlew :app:testCoreDebugUnitTest --rerun --tests "*StreamChainProbe*" -i
 *
 * STREAM_CHAIN_KEPT names a file the lines are kept in. With it the last line printed is SAME or
 * CHANGED against what the file held, and a line that changed is followed by what it was. A run
 * that could not reach YouTube at all leaves the file alone and says SAME: a dead connection is not
 * news about the chain. STREAM_CHAIN_VIDEOS picks other songs, comma separated.
 *
 * STREAM_CHAIN_CANDIDATES=1 also asks the clients the player does not ask today ([candidates]):
 * what would be left to try on the day the one client that serves whole songs stops. Each is asked
 * once, for the first song, through the same request, and its line begins "candidate". It says in
 * addition what kind of address the answer carried (plain, ciphered or none, which is a client
 * that streams over SABR only) and whether a ciphered one could be deciphered. Before them comes
 * one line on the deciphering itself, tried on a made-up address. These lines are printed after
 * the chain's and are neither kept nor compared, so the chain's SAME or CHANGED means what it did.
 * A candidate that needs a po token is asked without one, since a unit test has no WebView to
 * make it, and one that takes a login is asked without the account, as the chain's own is.
 */
class StreamChainProbe {

    /** Queen, Bohemian Rhapsody (2008) and a-ha, Take On Me (2010): official uploads, not going anywhere. */
    private val videos = (System.getenv("STREAM_CHAIN_VIDEOS")?.takeIf { it.isNotBlank() } ?: "fJ9rUzIMcZQ,djV11Xbc914")
        .split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private val http = OkHttpClient()

    private companion object {
        /** The desktop Safaris that yt-dlp's visionos and web_embedded clients say they are. */
        const val SAFARI_26 = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"
        const val SAFARI_15 = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15,gzip(gfe)"
    }

    /** Well past the 512 KB that a capped url serves, and past the megabyte docs/403.md measured. */
    private val probeAt = 2L * 1024 * 1024

    private var reached = false

    /** A client the chain does not ask, by the name its line gets. */
    private class Candidate(val name: String, val client: YouTubeClient)

    /**
     * The clients left to try, as the app would ask them: the ones [YouTubeClient] defines and the
     * chain leaves out, and the ones other projects ask today, written here and nowhere else.
     * Versions and user agents as read on 9 Oct 2026 in yt-dlp (yt_dlp/extractor/youtube/_base.py
     * on master) and NewPipeExtractor (ClientsConstants.java on dev).
     *
     * Two that [YouTubeClient] defines are not asked, their answers being known and each request
     * being one more from this address: WEB_CREATOR answers only to an account, and the embedded
     * TV player was removed from yt-dlp as broken in January 2026.
     */
    private val candidates = listOf(
        Candidate("WEB_REMIX (no po token)", YouTubeClient.WEB_REMIX),
        Candidate("WEB_REMIX at yt-dlp's version (no po token)", YouTubeClient.WEB_REMIX.copy(clientVersion = "1.20260707.12.00")),
        Candidate("WEB (no po token)", YouTubeClient.WEB),
        Candidate("TVHTML5 (asked without the account)", YouTubeClient.TVHTML5),
        // The two the chain asks after VISIONOS itself (YTPlayerUtils.VISIONOS_IDENTITIES), by the very definitions it asks with.
        Candidate("VISIONOS with NewPipe's version and user agent", YouTubeClient.VISIONOS_1_04),
        Candidate("VISIONOS with yt-dlp's user agent", YouTubeClient.VISIONOS_SAFARI),
        Candidate(
            "WEB_EMBEDDED_PLAYER as yt-dlp has it, without encryptedHostFlags",
            YouTubeClient(
                clientName = "WEB_EMBEDDED_PLAYER",
                clientVersion = "2.20260708.00.00",
                clientId = "56",
                userAgent = SAFARI_15,
                loginSupported = true,
                useSignatureTimestamp = true,
                isEmbedded = true,
            ),
        ),
        Candidate(
            "TVHTML5 downgraded as yt-dlp has it (asked without the account)",
            YouTubeClient.TVHTML5.copy(clientVersion = "5.20260707", userAgent = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"),
        ),
        Candidate(
            "TVHTML5_SIMPLY as yt-dlp has it (no po token)",
            YouTubeClient(clientName = "TVHTML5_SIMPLY", clientVersion = "1.0", clientId = "75", userAgent = YouTubeClient.TVHTML5.userAgent, useSignatureTimestamp = true),
        ),
        Candidate(
            "MWEB as yt-dlp has it (no po token)",
            YouTubeClient(
                clientName = "MWEB",
                clientVersion = "2.20260708.05.00",
                clientId = "2",
                userAgent = "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)",
                loginSupported = true,
                useSignatureTimestamp = true,
            ),
        ),
    )

    @Test
    fun probe(): Unit = runBlocking {
        assumeTrue("set STREAM_CHAIN_PROBE=1 to run", System.getenv("STREAM_CHAIN_PROBE") == "1")
        // English, so a refusal reads the same wherever this runs. The country is the machine's, as
        // the app's is the phone's.
        YouTube.locale = YouTubeLocale(gl = Locale.getDefault().country.ifEmpty { "US" }, hl = "en")
        YouTube.visitorData = null

        val clients = YTPlayerUtils.chainClients
        val said = clients.associate { name(it) to mutableListOf<String>() }
        for (videoId in videos) {
            for (client in clients) {
                said.getValue(name(client)) += ask(client, isMain = client === clients.first(), videoId)
                delay(1_500)
            }
        }

        // Asked last, with the visitorData the main client's answer gave, as the chain's own are.
        val others = mutableListOf<String>()
        if (System.getenv("STREAM_CHAIN_CANDIDATES") == "1") {
            others += "deciphering (NewPipeExtractor): " + deciphering(videos.first())
            for (candidate in candidates) {
                others += "candidate ${candidate.name}: " + ask(candidate.client, isMain = false, videos.first(), detail = true)
                delay(1_500)
            }
        }

        val lines = StreamChainReport.lines(videos, said)
        val kept = System.getenv("STREAM_CHAIN_KEPT")?.takeIf { it.isNotBlank() }?.let { File(it) }
        if (kept == null) {
            (lines + others).forEach { println("STREAM_CHAIN $it") }
            return@runBlocking
        }
        val before = kept.takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }
        val report = StreamChainReport.against(before, lines, reached)
        StreamChainReport.withOthers(report.printed, others).forEach { println("STREAM_CHAIN $it") }
        if (report.keep != null) kept.writeText(report.keep.joinToString("\n", postfix = "\n"))
    }

    private fun name(client: YouTubeClient) =
        if (client.loginSupported) "${client.clientName} (asked without the account)" else client.clientName

    /** One client's answer for one song, as the player would get it, in a few words. */
    private suspend fun ask(client: YouTubeClient, isMain: Boolean, videoId: String, detail: Boolean = false): String {
        val signatureTimestamp =
            if (client.useSignatureTimestamp) NewPipeUtils.getSignatureTimestamp(videoId).getOrNull() else null
        // The main client in the app's language and the others in English, as resolveOnce asks them.
        val result = YouTube.player(videoId, client = client, signatureTimestamp = signatureTimestamp, hlOverride = if (isMain) null else "en")
        val response = result.getOrElse { failure ->
            val status = (failure as? ResponseException)?.response?.status?.value
            if (status != null) reached = true
            // Never the failure's own words: they can quote an address.
            return "no answer (" + (status?.let { "HTTP $it" } ?: failure.javaClass.simpleName) + ")"
        }
        reached = true
        // What the player does with the main client's answer when the app has no visitorData.
        if (isMain) {
            StreamCheck.visitorDataToAdopt(YouTube.visitorData, response.responseContext.visitorData)?.let { YouTube.visitorData = it }
        }
        val carries = "loudness " + yesNo(response.playerConfig?.audioConfig?.effectiveLoudnessDb != null) +
            ", details " + yesNo(response.videoDetails != null) +
            ", history address " + yesNo(response.playbackTracking?.videostatsPlaybackUrl?.baseUrl != null)
        val playability = response.playabilityStatus
        if (playability.status != "OK") {
            return playability.status + (playability.reason?.let { " \"$it\"" } ?: "") + ", $carries"
        }
        return "OK, " + (if (detail) streamInDetail(response, videoId) else stream(response, videoId)) + ", $carries"
    }

    private fun yesNo(value: Boolean) = if (value) "yes" else "no"

    /** What the stream url of the best audio format does: the player's HEAD check, then bytes from further in. */
    private suspend fun stream(response: PlayerResponse, videoId: String): String {
        val format = response.streamingData?.adaptiveFormats?.filter { it.isAudio }?.maxByOrNull { it.bitrate }
            ?: return "no audio format"
        val address = NewPipeUtils.getStreamUrl(format, videoId).getOrNull() ?: return "no stream url"
        return checked(address, format.contentLength ?: 0L)
    }

    /**
     * The same for a candidate, with what kind of address its answer carried. A web client's is
     * ciphered or missing: missing from every format is a client that streams over SABR only, and
     * a ciphered one is only as good as the deciphering the app has.
     */
    private suspend fun streamInDetail(response: PlayerResponse, videoId: String): String {
        val audio = response.streamingData?.adaptiveFormats?.filter { it.isAudio }.orEmpty()
        if (audio.isEmpty()) return "no audio format"
        val format = audio.filter { it.url != null || it.signatureCipher != null }.maxByOrNull { it.bitrate }
            ?: return "${audio.size} audio formats and none with an address (SABR only)"
        val kind = if (format.url != null) "plain address" else "ciphered address"
        // Never the failure's own words: they can quote an address.
        val address = NewPipeUtils.getStreamUrl(format, videoId)
            .getOrElse { return "$kind, not deciphered (${it.javaClass.simpleName})" }
        return "$kind, " + checked(address, format.contentLength ?: 0L)
    }

    /**
     * Whether the app can decipher at all today: the signature timestamp, then the signature and
     * the n parameter of a made-up address, through the code the player uses. No request but the
     * player script NewPipe fetches once, and nothing is asked of the made-up address.
     */
    private fun deciphering(videoId: String): String {
        fun made(url: String?, cipher: String?) = PlayerResponse.StreamingData.Format(
            itag = 251, url = url, mimeType = "audio/webm", bitrate = 0, width = null, height = null,
            contentLength = null, quality = "tiny", fps = null, qualityLabel = null, averageBitrate = null,
            audioQuality = null, approxDurationMs = null, audioSampleRate = null, audioChannels = null,
            loudnessDb = null, lastModified = null, signatureCipher = cipher,
        )
        val madeUp = "https://example.invalid/videoplayback"
        val signature = "AJfQdSswRQIhAKx3mPq7Zt-0Yb1cD2eF4gH6iJ8kL0mN2oP4qR6sT8uVAiB9wX1yZ3aB5cD7eF9gH1iJ3kL5mN7oP9qR1sT3uV5w=="
        val n = "AbCdEfGhIjKlMnOpQr"
        val timestamp = NewPipeUtils.getSignatureTimestamp(videoId)
        fun encoded(value: String) = java.net.URLEncoder.encode(value, "UTF-8")
        val signed = NewPipeUtils.getStreamUrl(made(null, "s=${encoded(signature)}&sp=sig&url=${encoded(madeUp)}"), videoId)
        val unthrottled = NewPipeUtils.getStreamUrl(made("$madeUp?n=$n", null), videoId)
        fun said(result: Result<String>, parameter: String, given: String): String = result.fold(
            onSuccess = { url ->
                val now = java.net.URLDecoder.decode(url.substringAfter("$parameter=", "").substringBefore('&'), "UTF-8")
                if (now.isNotEmpty() && now != given) "runs" else "ran and changed nothing"
            },
            onFailure = { "fails (${it.javaClass.simpleName})" },
        )
        return "signature timestamp " + (if (timestamp.isSuccess) "found" else "not found (${timestamp.exceptionOrNull()?.javaClass?.simpleName})") +
            ", signature function " + said(signed, "sig", signature) +
            ", n function " + said(unthrottled, "n", n)
    }

    /** What an address does: the player's HEAD check, then bytes from further in. */
    private suspend fun checked(address: String, length: Long): String {
        val head = status(Request.Builder().head().url(address).build())
        delay(1_500)
        if (length <= 512 * 1024 + 16) return "HEAD ${head ?: "failed"}, too short to say more"
        val from = minOf(probeAt, length - 16)
        val request = Request.Builder().url(address).header("Range", "bytes=$from-${from + 15}").build()
        val past = runCatching {
            http.newCall(request).execute().use { answer ->
                val bytes = if (answer.isSuccessful) answer.body?.bytes()?.size ?: 0 else 0
                if (bytes > 0) "served" else "refused (${answer.code})"
            }
        }.getOrElse { "failed" }
        return "HEAD ${head ?: "failed"}, bytes past 512 KB $past"
    }

    private fun status(request: Request): Int? = runCatching { http.newCall(request).execute().use { it.code } }.getOrNull()
}

/** What the probe prints once every client has answered, kept apart from the asking so it can be tested without a network. */
internal object StreamChainReport {
    /** What to print, the last line of it SAME or CHANGED, and what to keep: null leaves the file as it is. */
    class Report(val printed: List<String>, val keep: List<String>?)

    /** One line per client: its answer when every song got the same one, and each song's when they differ. */
    fun lines(videos: List<String>, said: Map<String, List<String>>): List<String> = said.map { (client, perSong) ->
        val answers = if (perSong.distinct().size == 1) perSong.first()
        else perSong.mapIndexed { i, answer -> "${videos.getOrNull(i)}: $answer" }.joinToString("; ")
        "$client: $answers"
    }

    /**
     * [printed] with [others] put before its last line, which is SAME or CHANGED and has to stay
     * last: the wrapper's caller reads it there.
     */
    fun withOthers(printed: List<String>, others: List<String>): List<String> =
        if (printed.isEmpty()) others else printed.dropLast(1) + others + printed.last()

    /**
     * [lines] against what was [kept] by the run before, null if there was none.
     *
     * A line that changed is followed by what it was. [reached] false is a run in which YouTube
     * answered nothing at all: that is the connection and not the chain, so it says SAME and keeps
     * what was there, as watch.py keeps the last value of a source that does not answer.
     */
    fun against(kept: List<String>?, lines: List<String>, reached: Boolean): Report {
        fun client(line: String) = line.substringBefore(": ")
        if (!reached) {
            return Report(lines + "YouTube could not be reached, so what was kept stands" + "SAME", keep = null)
        }
        if (kept.isNullOrEmpty()) return Report(lines + "nothing was kept to compare with" + "CHANGED", keep = lines)
        val printed = lines.flatMap { line ->
            val was = kept.firstOrNull { client(it) == client(line) }
            when (was) {
                line -> listOf(line)
                null -> listOf(line, "  was: not in the chain")
                else -> listOf(line, "  was: " + was.substringAfter(": "))
            }
        } + kept.filter { old -> lines.none { client(it) == client(old) } }.map { "no longer in the chain: ${client(it)}" }
        return Report(printed + if (kept == lines) "SAME" else "CHANGED", keep = lines)
    }
}
