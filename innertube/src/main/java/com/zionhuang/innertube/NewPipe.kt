package com.zionhuang.innertube

import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.models.response.PlayerResponse
import io.ktor.http.URLBuilder
import io.ktor.http.parseQueryString
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import java.io.IOException
import java.net.Proxy

private class NewPipeDownloaderImpl(proxy: Proxy?) : Downloader() {

    // The n and signature step of every app client goes through here: YouTube's page and its
    // player script, fetched once a process. OkHttp's defaults kept an idle connection five
    // minutes, the trap a dropped connection set for the player on 20 Oct 2026, and had no
    // limit on a read that never ends. Connect 5 s, 6 s of silence in a read, idle connections
    // dropped after 5 s; no limit on the whole call, so a 0.7 MB script on a slow link is not
    // cut while it still comes in. The app's RouteTimes holds the same numbers.
    private val client = OkHttpClient.Builder()
        .proxy(proxy)
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(2, 5, java.util.concurrent.TimeUnit.SECONDS))
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

}

object NewPipeUtils {

    init {
        NewPipe.init(NewPipeDownloaderImpl(YouTube.proxy))
    }

    /**
     * Held around every use of the player script, so that its first fetch is made once: a song
     * that needs it while the warm-up is fetching it waits for that fetch instead of starting
     * another. Once fetched, each use takes milliseconds.
     */
    private val scriptLock = Any()

    @Volatile
    private var clearedAt = 0L

    fun getSignatureTimestamp(videoId: String): Result<Int> = runCatching {
        synchronized(scriptLock) { YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId) }
    }

    /**
     * Fetches YouTube's player script and compiles its n function ahead of the first song, with
     * [videoId] for the page it is found through. Failures are the first song's to meet again.
     */
    fun warm(videoId: String): Result<Unit> = runCatching {
        synchronized(scriptLock) {
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
        synchronized(scriptLock) {
            if (clearedAt != 0L && now - clearedAt < everyMs) return false
            clearedAt = now
            YoutubeJavaScriptPlayerManager.clearAllCaches()
            return true
        }
    }

    fun getStreamUrl(format: PlayerResponse.StreamingData.Format, videoId: String): Result<String> =
        runCatching {
            val url = format.url ?: format.signatureCipher?.let { signatureCipher ->
                val params = parseQueryString(signatureCipher)
                val obfuscatedSignature = params["s"]
                    ?: throw ParsingException("Could not parse cipher signature")
                val signatureParam = params["sp"]
                    ?: throw ParsingException("Could not parse cipher signature parameter")
                val url = params["url"]?.let { URLBuilder(it) }
                    ?: throw ParsingException("Could not parse cipher url")
                url.parameters[signatureParam] = synchronized(scriptLock) {
                    YoutubeJavaScriptPlayerManager.deobfuscateSignature(videoId, obfuscatedSignature)
                }
                url.toString()
            } ?: throw ParsingException("Could not find format url")

            return@runCatching synchronized(scriptLock) {
                YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, url)
            }
        }

}
