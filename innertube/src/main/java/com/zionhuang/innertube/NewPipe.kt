package com.zionhuang.innertube

import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.models.response.PlayerResponse
import io.ktor.http.URLBuilder
import io.ktor.http.parseQueryString
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import java.io.File
import java.io.IOException
import java.net.Proxy

private class NewPipeDownloaderImpl(proxy: Proxy?, cacheDir: File?) : Downloader() {

    // The n and signature step of every app client goes through here: YouTube's page and its
    // player script, fetched once a process. OkHttp's defaults kept an idle connection five
    // minutes, the trap a dropped connection set for the player on 20 Oct 2026, and had no
    // limit on a read that never ends. Connect 5 s, 6 s of silence in a read, idle connections
    // dropped after 5 s, and 3 minutes for the whole call. The script is about 0.7 MB: 15 s for
    // the whole call cut it below about 400 kbit/s, every time, and a fetch that is cut keeps
    // nothing, so on a plan slowed after its quota no streamed song of a new process played.
    // 3 minutes let it through down to about 30 kbit/s and still end a fetch that trickles in
    // for good; one that stops is ended by the 6 s read limit, and songs wait for the lock 8 s
    // at most. The app's RouteTimes holds the same numbers.
    private val client = OkHttpClient.Builder()
        .proxy(proxy)
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(3, java.util.concurrent.TimeUnit.MINUTES)
        .connectionPool(okhttp3.ConnectionPool(2, 5, java.util.concurrent.TimeUnit.SECONDS))
        // The player script kept on disk for as long as YouTube allows, a year, since its address
        // names its version: a new process reads it there instead of fetching it again before its
        // first song can play. The small page that names the version in use is asked every time,
        // as before (it says max-age=0), so a new version is still fetched the day it comes.
        .apply { if (cacheDir != null) cache(Cache(cacheDir, CACHE_BYTES)) }
        .build()

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBuilder = okhttp3.Request.Builder()
            .method(httpMethod, dataToSend?.toRequestBody())
            .url(url)
            .addHeader("User-Agent", YouTubeClient.USER_AGENT_WEB)

        headers.forEach { (headerName, headerValueList) ->
            if (headerValueList.size > 1) {
                requestBuilder.removeHeader(headerName)
                headerValueList.forEach { headerValue ->
                    requestBuilder.addHeader(headerName, headerValue)
                }
            } else if (headerValueList.size == 1) {
                requestBuilder.header(headerName, headerValueList[0])
            }
        }

        val response = client.newCall(requestBuilder.build()).execute()

        if (response.code == 429) {
            response.close()

            throw ReCaptchaException("reCaptcha Challenge requested", url)
        }

        val responseBodyToReturn = response.body?.string()

        val latestUrl = response.request.url.toString()
        return Response(response.code, response.message, response.headers.toMultimap(), responseBodyToReturn, latestUrl)
    }

    private companion object {
        /** About 1 MB a version of the script, as it comes compressed: room for several. */
        const val CACHE_BYTES = 16L * 1024 * 1024
    }
}

object NewPipeUtils {

    init {
        NewPipe.init(NewPipeDownloaderImpl(YouTube.proxy, YouTube.newPipeCacheDir))
    }

    /**
     * Held around every use of the player script, so that its first fetch is made once: a song
     * that needs it while the warm-up is fetching it waits for that fetch instead of starting
     * another. Once fetched, each use takes milliseconds. NewPipeExtractor's caches are plain
     * statics and a HashMap, so the uses stay one at a time.
     *
     * Waited for [LOCK_WAIT_S] at most, then given up as a timeout: a fetch that trickled in held
     * the lock, and every song's n step queued behind it on a thread nobody could stop.
     */
    private val scriptLock = java.util.concurrent.locks.ReentrantLock()

    private const val LOCK_WAIT_S = 8L

    private inline fun <T> withScript(block: () -> T): T {
        if (!scriptLock.tryLock(LOCK_WAIT_S, java.util.concurrent.TimeUnit.SECONDS)) {
            throw java.net.SocketTimeoutException("YouTube's player script is still being fetched")
        }
        try {
            return block()
        } finally {
            scriptLock.unlock()
        }
    }

    /**
     * NewPipeExtractor wraps a failed fetch of the script in a ParsingException. A network that
     * failed is said as such, so it is taken for no answer and not for a script that cannot be
     * read: see the app's walk, which strikes a client only for the second.
     */
    private fun <T> networkFailureFirst(result: Result<T>): Result<T> {
        val failure = result.exceptionOrNull() ?: return result
        val network = generateSequence(failure) { it.cause }.take(8).firstOrNull { it is IOException }
        return if (network != null && network !== failure) Result.failure(network) else result
    }

    @Volatile
    private var clearedAt = 0L

    fun getSignatureTimestamp(videoId: String): Result<Int> = networkFailureFirst(runCatching {
        withScript { YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId) }
    })

    /**
     * Fetches YouTube's player script and compiles its n function ahead of the first song, with
     * [videoId] for the page it is found through when YouTube's own small page for it fails: any
     * song, or none (""), since that page names the script without one. Failures are the first
     * song's to meet again.
     */
    fun warm(videoId: String): Result<Unit> = runCatching {
        withScript {
            YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId)
            YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
                videoId, "https://rr1---sn-warm.googlevideo.com/videoplayback?n=aBcDeFgHiJkLmNoP",
            )
        }
    }

    /**
     * NewPipeExtractor keeps a failure to read the player script for the life of the process, and
     * every app client's address then fails to decipher until the app restarts. Cleared here,
     * at most once every [everyMs], so the next song tries again. Returns whether it cleared.
     */
    fun clearAfterFailure(everyMs: Long = 10 * 60 * 1000L, now: Long = System.currentTimeMillis()): Boolean {
        if (clearedAt != 0L && now - clearedAt < everyMs) return false
        return runCatching {
            withScript {
                clearedAt = now
                YoutubeJavaScriptPlayerManager.clearAllCaches()
                true
            }
        }.getOrDefault(false)
    }

    fun getStreamUrl(format: PlayerResponse.StreamingData.Format, videoId: String): Result<String> =
        networkFailureFirst(runCatching {
            val url = format.url ?: format.signatureCipher?.let { signatureCipher ->
                val params = parseQueryString(signatureCipher)
                val obfuscatedSignature = params["s"]
                    ?: throw ParsingException("Could not parse cipher signature")
                val signatureParam = params["sp"]
                    ?: throw ParsingException("Could not parse cipher signature parameter")
                val url = params["url"]?.let { URLBuilder(it) }
                    ?: throw ParsingException("Could not parse cipher url")
                url.parameters[signatureParam] = withScript {
                    YoutubeJavaScriptPlayerManager.deobfuscateSignature(videoId, obfuscatedSignature)
                }
                url.toString()
            } ?: throw ParsingException("Could not find format url")

            // No n to work out: nothing of the script is needed, nor the lock.
            if (!Regex("""[?&]n=""").containsMatchIn(url)) return@runCatching url
            return@runCatching withScript {
                YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, url)
            }
        })

}
