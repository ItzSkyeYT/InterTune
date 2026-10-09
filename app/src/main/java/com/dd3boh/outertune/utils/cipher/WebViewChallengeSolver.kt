/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils.cipher

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The solver's page failed, was closed, or said something that is not an answer. */
class SolverException(message: String) : Exception(message)

/** The solver's two files are not in this build: see [WebViewChallengeSolver]. */
class SolverMissing : Exception("the solver's two files are not in this build")

/**
 * yt-dlp's solver, run in a WebView: the [ChallengeSolver] the app uses.
 *
 * The solver (github.com/yt-dlp/ejs, Unlicense, with a parser under ISC and a printer under MIT
 * inside it) is two JavaScript files and needs nothing but an engine to run in. The app has one
 * without adding anything: the WebView that already runs BotGuard for the po token
 * (utils/potoken/PoTokenWebView), which this is modelled on. It is a page of its own, though.
 * BotGuard's page stands at youtube.com's address and may load what it likes. This one stands
 * nowhere and has its network shut, because what the solver runs is YouTube's player script.
 *
 * What is expected of the two files, which is all that is relied on (their release 0.8.0, the
 * one yt-dlp pins):
 * - [LIB_ASSET], evaluated first, leaves a global `lib` holding the parser and the printer the
 *   solver is written against;
 * - [CORE_ASSET], evaluated after `Object.assign(globalThis, lib)`, leaves a global function
 *   `jsc(input)` that answers at once and not with a promise;
 * - `jsc` takes and gives what [SolverProtocol] writes and reads.
 * That is how yt-dlp itself runs them (yt_dlp/extractor/youtube/jsc/_builtin/ejs.py on master,
 * read 9 Oct 2026), so the unminified files of a release go in as they are, under their own
 * names.
 *
 * THE TWO FILES ARE NOT IN THE TREE YET, and nothing here has run against the real solver. Until
 * they are under assets/solver, every [solve] fails with [SolverMissing] before a WebView is
 * even made, and the stream chain goes on to its next client.
 *
 * One page at a time, made when first needed and closed once nothing has been asked of it for
 * [IDLE_MS], and no question waited on for longer than [LIMIT_MS]. A player script is parsed once: the solver gives back the script cut down to what
 * it needs, that is kept here under the script's name, and later questions are asked with it.
 * The prepared script is kept in memory only, and the solver calls it large, so it goes with the
 * process.
 */
class WebViewChallengeSolver(private val context: Context) : ChallengeSolver {
    private val lock = Mutex()
    private val scope = MainScope()
    private var page: SolverPage? = null
    private var closing: Job? = null

    /** The script the solver last cut down, by its name, and what it gave back. */
    private var prepared: Pair<String, String>? = null

    override suspend fun solve(script: PlayerScript, signatures: List<String>, ns: List<String>): ChallengeSolver.Solved {
        if (signatures.isEmpty() && ns.isEmpty()) return ChallengeSolver.Solved(emptyMap(), emptyMap())
        return lock.withLock {
            closing?.cancel()
            val ready = prepared?.takeIf { it.first == script.id }?.second
            val started = System.nanoTime()
            try {
                // Bounded here too, whatever limit the caller has set: the page's answers are
                // waited for with no time limit of their own, and this holds a lock.
                val output = withTimeoutOrNull(LIMIT_MS) {
                    val open = page?.takeIf { !it.closed } ?: SolverPage.open(context).also { page = it }
                    open.ask(SolverProtocol.input(prepared = ready != null, signatures, ns), ready ?: script.text)
                } ?: throw SolverException("the solver did not answer in time")
                val reading = SolverProtocol.read(output, signatures, ns).getOrThrow()
                Log.i(TAG, "answered in ${(System.nanoTime() - started) / 1_000_000} ms, asked with " + (if (ready != null) "the prepared script" else "the whole script"))
                reading.errors.forEach { Log.w(TAG, "the solver could not do $it") }
                reading.prepared?.let { prepared = script.id to it }
                closeWhenIdle()
                reading.solved
            } catch (failure: Throwable) {
                // A page that failed, or was given up on when the time ran out, is not asked
                // again: it may be deep in a script it will never come out of.
                val failed = page
                page = null
                if (failed != null) withContext(NonCancellable + Dispatchers.Main) { failed.close() }
                // And a prepared script that did not give an answer is not trusted a second time.
                if (failure !is CancellationException) prepared = null
                throw failure
            }
        }
    }

    private fun closeWhenIdle() {
        closing = scope.launch {
            delay(IDLE_MS)
            lock.withLock {
                page?.close()
                page = null
            }
        }
    }

    companion object {
        private const val TAG = "ChallengeSolver"

        /** Where the solver's two files go, under assets, by the names their release gives them. */
        const val LIB_ASSET = "solver/yt.solver.lib.js"
        const val CORE_ASSET = "solver/yt.solver.core.js"

        /** A song asks once, and the next one minutes later: the page is not worth its memory in between. */
        const val IDLE_MS = 30_000L

        /**
         * The longest one question may take, the making of the page included. What the po token's
         * WebView is allowed (PoTokenGenerator.POTOKEN_TIMEOUT_MS). The player's own allowance for
         * its experiment is shorter: see YTPlayerUtils.TRIAL_LIMIT_MS.
         */
        const val LIMIT_MS = 20_000L
    }
}

/**
 * One page with the solver loaded in it. Made, asked and closed on the main thread, as a WebView
 * has to be. What the page calls back comes in on a thread of the WebView's own.
 */
private class SolverPage private constructor(
    context: Context,
    private val lib: String,
    private val core: String,
    // answered exactly once: when the solver is loaded, or could not be
    private val opened: CancellableContinuation<SolverPage>,
) {
    private val webView = WebView(context)
    private val main = Handler(Looper.getMainLooper())
    private val openAnswered = AtomicBoolean(false)
    private val waiting = ConcurrentHashMap<Int, Question>()
    private val lastId = AtomicInteger(0)

    /** How many pages have begun to load in the WebView. One is the solver's own. */
    private val loads = AtomicInteger(0)

    @Volatile
    var closed = false
        private set

    /** What one question hands the page over the bridge, and who is waiting for its answer. */
    private class Question(val input: String, val player: String, val answer: CancellableContinuation<String>)

    init {
        val settings = webView.settings
        //noinspection SetJavaScriptEnabled running JavaScript is what the page is for
        settings.javaScriptEnabled = true
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(settings, false)
        }
        // What runs in here is YouTube's player script. It gets no network, no files and nothing
        // of the app but the five calls of the bridge.
        settings.blockNetworkLoads = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false

        webView.addJavascriptInterface(this, BRIDGE)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                // Everything the page does itself is inside a try. An uncaught error is one of
                // the two files failing to load, on a WebView too old for what they are written in.
                if (m.message().contains("Uncaught")) {
                    Log.e(TAG, "the solver did not load: line ${m.lineNumber()}")
                    fail(SolverException("the solver did not load in this WebView"))
                }
                return super.onConsoleMessage(m)
            }
        }

        // Without this Android ends the whole app when the page's renderer dies, and the player
        // with it: see PoTokenWebView.
        webView.webViewClient = object : WebViewClient() {
            // YouTube's script, once it has run in here, tries to take the page somewhere else:
            // seen on 9 Oct 2026, where the second question found an error page and no solver.
            // The page goes nowhere, and one that has left all the same is not asked again.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (loads.incrementAndGet() > 1 && !closed) {
                    Log.w(TAG, "the solver's page was taken elsewhere, closing it")
                    fail(SolverException("the solver's page was taken elsewhere"))
                }
            }

            @RequiresApi(Build.VERSION_CODES.O)
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.e(TAG, "the renderer process of the solver's page is gone")
                fail(SolverException("the WebView's renderer process is gone"))
                return true
            }
        }
    }

    private fun load() {
        webView.loadDataWithBaseURL(null, PAGE, "text/html", "utf-8", null)
    }

    /** Called by the page once it is there. The two files are then evaluated in it, in their order. */
    @JavascriptInterface
    fun onPageReady() {
        main.post {
            if (closed) return@post
            webView.evaluateJavascript(lib) {
                if (closed) return@evaluateJavascript
                webView.evaluateJavascript("Object.assign(globalThis, lib);\n$core") {
                    if (closed) return@evaluateJavascript
                    webView.evaluateJavascript("typeof jsc") { kind ->
                        if (kind == "\"function\"") {
                            if (openAnswered.compareAndSet(false, true)) opened.resume(this)
                        } else {
                            fail(SolverException("the solver's files left nothing to call"))
                        }
                    }
                }
            }
        }
    }

    /** [input] put to the solver with [player] as its script, whole or prepared: its answer as it wrote it. */
    suspend fun ask(input: String, player: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { answer ->
            if (closed) {
                answer.resumeWithException(SolverException("the solver's page is closed"))
                return@suspendCancellableCoroutine
            }
            val id = lastId.incrementAndGet()
            waiting[id] = Question(input, player, answer)
            // Given up on, by the time limit of whoever asked: the page goes with the question.
            answer.invokeOnCancellation { main.post { close() } }
            webView.evaluateJavascript("$ASK($id)", null)
        }
    }

    @JavascriptInterface
    fun input(id: Int): String = waiting[id]?.input.orEmpty()

    @JavascriptInterface
    fun player(id: Int): String = waiting[id]?.player.orEmpty()

    @JavascriptInterface
    fun onSolved(id: Int, output: String) {
        waiting.remove(id)?.answer?.resume(output)
    }

    /** Never the error's whole text: it goes on with where in YouTube's script it happened. */
    @JavascriptInterface
    fun onFailed(id: Int, error: String) {
        waiting.remove(id)?.answer?.resumeWithException(SolverException("the solver threw: " + error.lineSequence().firstOrNull().orEmpty().take(120)))
    }

    /** On the WebView's thread, whichever thread the failure came in on. */
    private fun fail(failure: Throwable) {
        main.post { close(failure) }
    }

    /** Ends the page, and tells whoever is still waiting on it. */
    @MainThread
    fun close(reason: Throwable = SolverException("the solver's page was closed")) {
        if (closed) return
        closed = true
        waiting.keys.toList().forEach { id -> waiting.remove(id)?.answer?.resumeWithException(reason) }
        if (openAnswered.compareAndSet(false, true)) opened.resumeWithException(reason)

        webView.loadUrl("about:blank")
        webView.onPause()
        webView.removeAllViews()
        webView.destroy()
    }

    companion object {
        private const val TAG = "ChallengeSolver"
        private const val BRIDGE = "SolverBridge"
        private const val ASK = "askTheSolver"

        /**
         * The page: one function, which puts the script into the question and hands the solver's
         * answer back as text. The script comes over the bridge because it is far too large to be
         * written into a line of JavaScript.
         */
        private val PAGE = """
            <!DOCTYPE html>
            <html><head><title></title><script>
            function $ASK(id) {
              try {
                var input = JSON.parse($BRIDGE.input(id));
                var player = $BRIDGE.player(id);
                if (input.type === "player") input.player = player; else input.preprocessed_player = player;
                $BRIDGE.onSolved(id, JSON.stringify(jsc(input)));
              } catch (error) {
                $BRIDGE.onFailed(id, "" + error);
              }
            }
            $BRIDGE.onPageReady();
            </script></head><body></body></html>
        """.trimIndent()

        /**
         * A page with the solver in it, or [SolverMissing] when the two files are not in this
         * build. The files are read before any WebView is made, and off the main thread.
         */
        suspend fun open(context: Context): SolverPage {
            val (lib, core) = withContext(Dispatchers.IO) {
                try {
                    fun text(asset: String) = context.assets.open(asset).bufferedReader().use { it.readText() }
                    text(WebViewChallengeSolver.LIB_ASSET) to text(WebViewChallengeSolver.CORE_ASSET)
                } catch (missing: FileNotFoundException) {
                    throw SolverMissing()
                }
            }
            return withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { opened ->
                    val page = SolverPage(context, lib, core, opened)
                    // Given up on before it was ready: nobody else holds it yet, so it is closed here.
                    opened.invokeOnCancellation { page.main.post { page.close() } }
                    page.load()
                }
            }
        }
    }
}
