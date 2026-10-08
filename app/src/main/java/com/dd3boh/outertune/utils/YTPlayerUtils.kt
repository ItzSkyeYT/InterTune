/*
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.utils

import android.net.ConnectivityManager
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.PlaybackException
import com.dd3boh.outertune.constants.AudioQuality
import com.dd3boh.outertune.playback.ListenReporting
import com.dd3boh.outertune.utils.YTPlayerUtils.MAIN_CLIENT
import com.dd3boh.outertune.utils.YTPlayerUtils.STREAM_FALLBACK_CLIENTS
import com.dd3boh.outertune.utils.YTPlayerUtils.streamStatus
import com.dd3boh.outertune.utils.potoken.PoTokenGenerator
import com.dd3boh.outertune.utils.potoken.PoTokenResult
import com.dd3boh.outertune.App
import com.zionhuang.innertube.AddressPolicy
import com.zionhuang.innertube.NewPipeUtils
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.models.YouTubeClient.Companion.ANDROID
import com.dd3boh.outertune.constants.PlaybackAuthMode
import com.zionhuang.innertube.models.YouTubeClient.Companion.ANDROID_VR_NO_AUTH
import com.zionhuang.innertube.models.YouTubeClient.Companion.IOS
import com.zionhuang.innertube.models.YouTubeClient.Companion.TVHTML5
import com.zionhuang.innertube.models.YouTubeClient.Companion.TVHTML5_SIMPLY_EMBEDDED_PLAYER
import com.zionhuang.innertube.models.YouTubeClient.Companion.VISIONOS
import com.zionhuang.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.zionhuang.innertube.models.response.PlayerResponse
import com.zionhuang.innertube.utils.runCatchingCancellable
import okhttp3.OkHttpClient

object YTPlayerUtils {

    private const val TAG = "YTPlayerUtils"

    private val httpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .build()

    /** The check of a stream goes over the family its address was issued to: see [StreamFamily]. */
    private val streamCalls = StreamFamily.Calls(httpClient)

    private val poTokenGenerator = PoTokenGenerator()

    /**
     * The main client is the first one written in the chain, and its answer is preferred for
     * metadata when it has been asked.
     * Do not prefer other clients for this because it can result in inconsistent metadata.
     * For example other clients can have different normalization targets (loudnessDb).
     *
     * It is not asked for every song any more: see [StreamOrder]. On 8 Oct 2026 it had been
     * refused as a bot on every song for weeks, and a refusal carries nothing a song needs. What
     * was read from its answer is now read from whichever answer carries it (see the end of
     * [resolveOnce]), and the one thing only it is asked for, a visitorData when the app has none,
     * still puts it first.
     *
     * [com.zionhuang.innertube.models.YouTubeClient.ANDROID_VR_NO_AUTH] is what we use, because it
     * is the one that reliably serves streams here.
     *
     * The note that used to sit here, saying WEB_REMIX "should be preferred because it is the only
     * client which provides the correct metadata (like loudnessDb) and premium formats", is out of
     * date and was actively misleading. Two things changed under it:
     * - WEB_REMIX fails the poToken check on /player when it is asked without a token, as it was
     *   here, and returns UNPLAYABLE, so it provided nothing at all. See
     *   [playerResponseForMetadata]. Asked with one it answers: see [playerResponseAsAccount].
     * - ANDROID_VR_NO_AUTH stopped returning audioConfig, which is why loudness is read from the
     *   client that actually served the audio rather than from this one.
     */
    private val MAIN_CLIENT: YouTubeClient = ANDROID_VR_NO_AUTH

    /**
     * Clients used for fallback streams in case the streams of the main client do not work.
     */
    private val STREAM_FALLBACK_CLIENTS: Array<YouTubeClient> = arrayOf(
        /**
         * First on purpose. YouTube began requiring a proof-of-origin token from ANDROID_VR and
         * IOS in August 2026; without one they serve roughly 1MB and then 403 forever, which is
         * the "Source error (2004)" that cut every song off partway through. VISIONOS is exempt,
         * so it plays a track to the end.
         *
         * Every client's url gets the [streamStatus] check, the last one's included. It used to go
         * to the player unchecked, which is how a refused VISIONOS became IOS's 403 on every song
         * (see [StreamCheck.accept]). Keep this ahead of IOS all the same: IOS urls fail that check.
         */
        VISIONOS,
        // Could not parse deobfuscation function
//        WEB_REMIX,
//        ANDROID,
//        TVHTML5,
//        TVHTML5_SIMPLY_EMBEDDED_PLAYER,
        IOS, // recent api changes produce error 403 after 30 seconds
    )


    /**
     * Which client last produced a stream url, and whether it was asked as the account.
     *
     * Recorded for the error report and nothing else. This chain has been rebuilt repeatedly as
     * YouTube changed what it enforces, and every rebuild was worked out from scratch because a
     * report says "Source error (2004)" and never which of three clients produced the url that
     * then 403'd. Now it does.
     *
     * One value for the app rather than one per song: what a report needs is what was serving at
     * the moment it broke.
     */
    @Volatile
    var lastStreamClient: String? = null
        private set

    /**
     * What every client in the chain answered for the last song resolved, for the error report:
     * "ANDROID_VR LOGIN_REQUIRED, VISIONOS LOGIN_REQUIRED, IOS OK, HEAD 403".
     *
     * [lastStreamClient] names the client that ended the chain and nothing about the ones before
     * it, and for a 403 the ones before it are the story: issue #17 reported IOS's 403, and the
     * cause was VISIONOS turning the request down one step earlier.
     */
    @Volatile
    var lastStreamTrail: String? = null
        private set

    /**
     * Receives a visitorData taken from a /player answer because the app had none, so it can be
     * saved and the next launch starts with one. Set by App, which owns the stored value.
     */
    @Volatile
    var onVisitorDataFound: ((String) -> Unit)? = null

    /**
     * Which client served the last song and when the others were refused, for [StreamOrder] to
     * decide who is asked first. Set by App from the stored value at launch.
     */
    @Volatile
    var streamMemory: StreamOrder.Memory = StreamOrder.Memory()

    /**
     * Receives [streamMemory] as [StreamOrder.encode] writes it whenever a song has changed it, so
     * the next launch starts with the client that served in this one. Set by App, which owns the
     * stored value. A song served by the same client as the one before changes nothing, so this
     * is called when the chain was walked and not for every song.
     */
    @Volatile
    var onStreamMemoryChanged: ((String) -> Unit)? = null

    /**
     * Every client a song can be asked of, in the order written: the main one, the fallbacks and
     * the one that carries the account. For StreamChainProbe, which asks each of them what it
     * answers today, so that it cannot ask another list than this one.
     */
    internal val chainClients: List<YouTubeClient>
        get() = listOf(MAIN_CLIENT) + STREAM_FALLBACK_CLIENTS + AUTH_CLIENT

    /**
     * How a client is asked and a stream url checked: YouTube and a HEAD request. Handed down to
     * the walk the way FamilyChoice.resolve is handed its attempt, so that ChainWalkTest can walk
     * the chain with answers of its own and no network.
     */
    internal interface Wire {
        /** [asNewVisitor] asks without any visitorData, for the new one the answer then carries. */
        suspend fun player(
            videoId: String,
            playlistId: String?,
            client: YouTubeClient,
            signatureTimestamp: Int?,
            webPlayerPot: String?,
            hlOverride: String?,
            policy: AddressPolicy?,
            asNewVisitor: Boolean = false,
        ): Result<PlayerResponse>

        fun head(url: String): Int?
    }

    private object Live : Wire {
        override suspend fun player(
            videoId: String,
            playlistId: String?,
            client: YouTubeClient,
            signatureTimestamp: Int?,
            webPlayerPot: String?,
            hlOverride: String?,
            policy: AddressPolicy?,
            asNewVisitor: Boolean,
        ): Result<PlayerResponse> =
            if (asNewVisitor) {
                YouTube.player(videoId, playlistId, client, visitorData = null, addressPolicy = policy)
            } else {
                YouTube.player(
                    videoId, playlistId, client, signatureTimestamp, webPlayerPot,
                    hlOverride = hlOverride, addressPolicy = policy,
                )
            }

        override fun head(url: String): Int? = streamStatus(url)
    }

    /** Adds what one pass learned to [streamMemory], and hands it on to be stored if that changed it. */
    private fun rememberAsked(asked: List<StreamOrder.Asked>) {
        if (asked.isEmpty()) return
        val changed = synchronized(this) {
            val before = streamMemory
            val after = StreamOrder.remember(before, asked, System.currentTimeMillis())
            if (after == before) return
            streamMemory = after
            after
        }
        onStreamMemoryChanged?.invoke(StreamOrder.encode(changed))
    }

    /**
     * Whether playback may ask as the signed-in account, set from the preference by MusicService.
     *
     * Volatile and not read from the datastore here: this runs on every song and
     * dataStore.get blocks.
     */
    @Volatile
    var authMode: PlaybackAuthMode = PlaybackAuthMode.WHEN_REFUSED

    /**
     * The client that carries the account.
     *
     * ANDROID rather than WEB_REMIX, which is the other unrestricted login-capable one: WEB_REMIX
     * needs a po token on /player and answers UNPLAYABLE without one, as the note on
     * [MAIN_CLIENT] records. The embedded TVHTML5 player is deliberately not here. It is the
     * client people reach for to walk past an age gate without an account at all, and that is not
     * what this is for.
     */
    private val AUTH_CLIENT: YouTubeClient = ANDROID

    /**
     * The fallback chain, with the account appended or promoted depending on the setting.
     *
     * Appended rather than inserted, in the usual case: the anonymous clients are what works
     * almost always, and the authenticated one is only reached once they have all failed, which
     * is precisely the age gate. When YouTube is already refusing this address, anonymous is the
     * thing being refused, so it goes first instead and the anonymous attempts stop being the
     * reason the refusal persists.
     */
    private fun streamClients(isLoggedIn: Boolean): List<YouTubeClient> {
        val base = STREAM_FALLBACK_CLIENTS.toList()
        if (!isLoggedIn || authMode == PlaybackAuthMode.NEVER) return base
        if (!AUTH_CLIENT.loginSupported) return base
        return when {
            authMode == PlaybackAuthMode.ALWAYS || Throttle.isBlocked -> listOf(AUTH_CLIENT) + base
            else -> base + AUTH_CLIENT
        }
    }

    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
        /**
         * Whether [streamUrl] answered a status check. The last fallback client's is taken without
         * one, and those are the streams known to play a megabyte and then refuse, so anything
         * that gives up a good copy for this stream checks it first.
         */
        val validated: Boolean = false,
    )

    /** What one pass of the chain learned, for [playerResponseForPlayback] to act on. */
    private class ChainNotes {
        /** VISIONOS gave the bot check, or answered with a url that failed the check. */
        var visionosRefused = false
        var gotStream = false
        var blocked: PlayerResponse.PlayabilityStatus? = null

        /**
         * A fallback client gave the bot check. [blocked] counts the main client too, and ANDROID_VR
         * gives it on a healthy network, so a song that is simply unavailable would look refused.
         */
        var fallbackBlocked = false

        fun tellThrottle() {
            if (gotStream) Throttle.note("OK", null) else blocked?.let { Throttle.note(it.status, it.reason) }
        }
    }

    /** When a try with a new visitorData last failed too, in elapsed realtime; 0 if none has. */
    @Volatile
    private var lastSwapFailedAt = 0L

    /** Which address family worked on which network, for [FamilyChoice]. In memory only. */
    @Volatile
    private var familyMemory: FamilyChoice.Memory? = null

    /**
     * The network in use, for [FamilyChoice], or null when there is no choice to make: no network
     * known, or a proxy, whose own connection decides the family.
     */
    private fun currentNetwork(connectivityManager: ConnectivityManager?): FamilyChoice.Network? {
        if (YouTube.proxy != null) return null
        return runCatching {
            val cm = connectivityManager ?: App.instance.getSystemService(ConnectivityManager::class.java)
            val network = cm.activeNetwork ?: return null
            val addresses = cm.getLinkProperties(network)?.linkAddresses?.map { it.address } ?: return null
            FamilyChoice.networkOf(network.networkHandle, addresses)
        }.getOrNull()
    }

    /**
     * One /player request over the family [FamilyChoice] picks, and over the other one when the
     * first is refused with the bot check. For the single requests outside the stream chain, which
     * would otherwise go over a family the chain already knows is refused and trip the throttle.
     *
     * [remember] false leaves [familyMemory] as it was: for a request whose client the chain never
     * asks, so that what one odd client was told does not change the family every song starts on.
     */
    private suspend fun playerOverBestFamily(
        remember: Boolean = true,
        request: suspend (AddressPolicy?) -> Result<PlayerResponse>,
    ): Result<PlayerResponse> {
        val outcome = FamilyChoice.resolve(currentNetwork(null), familyMemory, SystemClock.elapsedRealtime()) { policy ->
            val answer = request(policy)
            FamilyChoice.Attempt(
                answer,
                ok = answer.isSuccess,
                refused = Throttle.looksLikeBlock(answer.getOrNull()?.playabilityStatus?.reason),
            )
        }
        if (remember) familyMemory = outcome.memory
        return outcome.chosen.value
    }

    /**
     * Custom player response intended to use for playback.
     * Metadata like audioConfig and videoDetails are from [MAIN_CLIENT] when it was asked and its
     * answer carries them, and from the client that served the stream otherwise.
     * Format & stream can be from [MAIN_CLIENT] or [STREAM_FALLBACK_CLIENTS].
     *
     * The chain over the address family [FamilyChoice] picks, and once more over the other family
     * when the bot check refused it there. The throttle hears the outcome once both are done, so a
     * refusal the other family clears never trips the back off.
     */
    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): Result<PlaybackData> = playerResponseForPlayback(videoId, playlistId, audioQuality, connectivityManager, Live)

    /** The same over a [Wire] of the caller's own. */
    internal suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String?,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        wire: Wire,
    ): Result<PlaybackData> {
        val trails = mutableListOf<String>()
        val outcome = FamilyChoice.resolve(
            currentNetwork(connectivityManager),
            familyMemory,
            SystemClock.elapsedRealtime(),
        ) { policy ->
            if (policy?.only == true) {
                Log.i(TAG, "[$videoId] refused over ${policy.first.other.label}, trying ${policy.first.label}")
            }
            // Cleared first, so a pass that fails before writing its own does not report the last song's.
            lastStreamTrail = null
            val (result, notes) = resolveWithNewVisitor(videoId, playlistId, audioQuality, connectivityManager, policy, wire)
            trails += listOfNotNull(policy?.first?.label, lastStreamTrail).joinToString(": ")
            FamilyChoice.Attempt(result to notes, ok = result.isSuccess, refused = result.isFailure && notes.fallbackBlocked)
        }
        familyMemory = outcome.memory
        lastStreamTrail = trails.filter { it.isNotEmpty() }.joinToString("; ").ifEmpty { null }
        val (result, notes) = outcome.chosen.value
        notes.tellThrottle()
        return result
    }

    /**
     * One pass of the chain, and when VISIONOS turned it down, one more with a visitorData YouTube
     * has only just issued: see [StreamCheck.mayRetryWithNewVisitor]. Returns what the pass that
     * counts learned, for the caller to tell the throttle.
     */
    private suspend fun resolveWithNewVisitor(
        videoId: String,
        playlistId: String?,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        policy: AddressPolicy?,
        wire: Wire,
    ): Pair<Result<PlaybackData>, ChainNotes> {
        val first = ChainNotes()
        val result = resolveOnce(videoId, playlistId, audioQuality, connectivityManager, first, policy, wire)
        val sinceFailedSwap = lastSwapFailedAt.takeIf { it != 0L }?.let { SystemClock.elapsedRealtime() - it }
        if (result.isSuccess || !StreamCheck.mayRetryWithNewVisitor(first.visionosRefused, sinceFailedSwap)) {
            return result to first
        }
        val previous = YouTube.visitorData
        val fresh = mintVisitorData(videoId, policy, wire)?.takeIf { it != previous }
        if (fresh == null) {
            return result to first
        }

        Log.i(TAG, "[$videoId] VISIONOS refused, trying again with a visitorData YouTube has just issued")
        YouTube.visitorData = fresh
        val second = ChainNotes()
        val retried = resolveOnce(videoId, playlistId, audioQuality, connectivityManager, second, policy, wire)
        if (retried.isSuccess) {
            lastSwapFailedAt = 0L
            // Kept for good only when signed out. Signed in, the stored one came from the
            // account's own sign-in page and goes with its cookie, so this one lasts until the
            // app restarts, and a bad stored one costs one extra pass per launch.
            if (YouTube.cookie == null) onVisitorDataFound?.invoke(fresh)
        } else {
            YouTube.visitorData = previous
            lastSwapFailedAt = SystemClock.elapsedRealtime()
        }
        return retried to second
    }

    /**
     * A visitorData YouTube has only just issued: the main client asked without one, whose answer
     * carries a new one whatever it says. sw.js_data would do as well, but it is the very fetch
     * that failed at every launch in issue #17.
     */
    private suspend fun mintVisitorData(videoId: String, policy: AddressPolicy?, wire: Wire): String? =
        wire.player(videoId, null, MAIN_CLIENT, null, null, null, policy, asNewVisitor = true)
            .onFailure { Throttle.noteFailure(it) }
            .getOrNull()
            ?.responseContext?.visitorData
            ?.takeIf { StreamCheck.looksLikeVisitorData(it) }

    private suspend fun resolveOnce(
        videoId: String,
        playlistId: String?,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        notes: ChainNotes,
        policy: AddressPolicy?,
        wire: Wire,
    ): Result<PlaybackData> = runCatchingCancellable {
        Log.d(TAG, "Playback info requested: $videoId")

        /**
         * This is required for some clients to get working streams however
         * it should not be forced for the [MAIN_CLIENT] because the response of the [MAIN_CLIENT]
         * is required even if the streams won't work from this client.
         * This is why it is allowed to be null.
         */
        // Both of these are expensive and both were being thrown away. InnerTube gates the
        // signature timestamp on client.useSignatureTimestamp and the po token on
        // client.useWebPoTokens, and none of the clients this actually calls sets either, so every
        // song paid for a NewPipe signature extraction and a WebView po token that were dropped
        // before the request left. The po token generator is the worse of the two: it makes two
        // POSTs of its own to youtube.com, from the same address already in trouble.
        //
        // Keyed off the client list rather than hardcoded off, so putting a client that does use
        // them back in the chain starts generating them again on its own.
        val isLoggedIn = YouTube.cookie != null
        val streamClients = streamClients(isLoggedIn)
        val playerClients = listOf(MAIN_CLIENT) + streamClients
        val wantsPoToken = playerClients.any { it.useWebPoTokens }

        // The client that served the last song is asked first, and the others as they are written
        // when it gives no stream: see StreamOrder. Clients are known there by the names the trail
        // gives them. Two with one name could not be told apart, so such a chain is asked as
        // written.
        fun label(client: YouTubeClient) =
            if (client.loginSupported && isLoggedIn) "${client.clientName} (account)" else client.clientName
        val written = playerClients.map { label(it) }
        val hasVisitorData = YouTube.visitorData?.let { StreamCheck.looksLikeVisitorData(it) } == true
        val clients =
            if (written.distinct().size != written.size) playerClients
            else StreamOrder.order(written, streamMemory, System.currentTimeMillis(), hasVisitorData)
                .map { playerClients[written.indexOf(it)] }

        // Worked out on first use rather than up front. The authenticated client asks for a
        // signature timestamp and usually sits at the end of the chain never being reached, so
        // computing this because it is merely in the list would charge every song for an
        // extraction that nothing reads. NewPipe caches the player script, so the first client
        // that genuinely needs it pays once.
        var signatureTimestamp: Int? = null
        var signatureTimestampResolved = false
        fun signatureTimestampFor(client: YouTubeClient): Int? {
            if (!client.useSignatureTimestamp) return null
            if (!signatureTimestampResolved) {
                signatureTimestamp = getSignatureTimestampOrNull(videoId)
                signatureTimestampResolved = true
            }
            return signatureTimestamp
        }

        val sessionId =
            if (isLoggedIn) {
                // signed in sessions use dataSyncId as identifier
                YouTube.dataSyncId
            } else {
                // signed out sessions use visitorData as identifier
                YouTube.visitorData
            }

        Log.d(
            TAG,
            "[$videoId] isLoggedIn: $isLoggedIn, clients: ${clients.joinToString { it.clientName }}" +
                    if (clients.first() !== playerClients.first()) " (${label(clients.first())} served last)" else "",
        )

        val (webPlayerPot, webStreamingPot) = if (!wantsPoToken) {
            Pair(null, null)
        } else {
            getWebClientPoTokenOrNull(videoId, sessionId)?.let {
                Pair(it.playerRequestPoToken, it.streamingDataPoToken)
            } ?: Pair(null, null).also {
                Log.w(TAG, "[$videoId] No po token")
            }
        }

        // A refusal from one client is not a refusal from YouTube. ANDROID_VR answers "Sign in to
        // confirm you're not a bot" on nearly every song and VISIONOS then serves it 150 ms later,
        // and reporting each of those to the throttle tripped the back off (which drops history
        // pings, downloads, sync and Home loads for as long as it lasts) and counted a strike, so
        // a genuine block later started at the top of the ladder. So: network failures are still
        // reported as they happen, but a block is only reported once the whole chain has failed,
        // and a working stream from any client clears it.
        var blockedStatus: PlayerResponse.PlayabilityStatus? = null
        fun PlayerResponse.rememberBlock() {
            if (blockedStatus == null && Throttle.looksLikeBlock(playabilityStatus.reason)) {
                blockedStatus = playabilityStatus
            }
        }

        // The main client's answer, once it has been asked. It stays null for a song the client that
        // served the last one serves too, which is nearly all of them.
        var mainPlayerResponse: PlayerResponse? = null

        var format: PlayerResponse.StreamingData.Format? = null
        var streamUrl: String? = null
        var validated = false
        var streamExpiresInSeconds: Int? = null

        var streamPlayerResponse: PlayerResponse? = null
        var lastClient: YouTubeClient? = null
        // The best explanation any fallback client gave, kept because streamPlayerResponse is
        // overwritten every iteration and the last client is free to fail outright. Five Hours went
        // VISIONOS "UNPLAYABLE - This video is not available", IOS the same, then ANDROID returned
        // nothing at all, and the reason two clients had already supplied was dropped on the floor
        // in favour of "Unknown error". The main client is left out: ANDROID_VR gives the bot check
        // on nearly every song, so its reason says nothing about why this one failed.
        var explained: PlayerResponse.PlayabilityStatus? = null
        // What a fallback client said when it turned the request down outright, and the status the
        // last refused url got. Together they explain a chain that found urls and had every one
        // refused, where the reason is the cause and the status only its symptom.
        var fallbackRefusal: PlayerResponse.PlayabilityStatus? = null
        var refusedStatus: Int? = null
        // The most recent fallback client's own failure (a dropped connection, a timeout). Thrown
        // as itself when no fallback client explained anything, so MusicService can map it to no
        // connection or a timeout.
        var lastFallbackFailure: Throwable? = null
        val trail = mutableListOf<String>()
        // What each client asked gave, for StreamOrder to remember. A client the network never
        // reached, or whose url could not be checked, is left out: nothing was learned of it.
        val asked = mutableListOf<StreamOrder.Asked>()

        // Says what was asked and answered, and remembers it. Whichever client the loop ended on,
        // successfully or not, and also when a failure ends it early, so a song that failed is
        // reported with the client that failed rather than with nothing.
        fun chainDone() {
            lastStreamClient = lastClient?.let { label(it) }
            lastStreamTrail = trail.joinToString(", ")
            Log.d(TAG, "[$videoId] chain: $lastStreamTrail")
            rememberAsked(asked)
        }

        for ((clientIndex, client) in clients.withIndex()) {
            // reset for each client
            format = null
            streamUrl = null
            streamExpiresInSeconds = null

            // the main client's streams are tried like any other's, in whatever place it is asked
            val isMain = client === MAIN_CLIENT
            if (isMain) {
                Log.d(TAG, "Trying client: ${client.clientName}")
            } else {
                Log.d(TAG, "Trying fallback client: ${client.clientName}")

                if (client.loginRequired && !isLoggedIn) {
                    // skip client if it requires login but user is not logged in
                    continue
                }
            }
            lastClient = client
            val clientLabel = label(client)
            val asAccount = client.loginSupported && isLoggedIn

            // hl=en whatever the app's language. Throttle and the error screen know the bot
            // check and the age gate only by YouTube's English wording, and a fallback client's
            // reason is what they read. Any other reason is shown on the error screen as
            // YouTube wrote it, so a song refused for its own sake now reads in English there.
            // The main client keeps the app's hl: nothing in its answer is shown, and asking it
            // in English would let its routine bot check (see blockedStatus) reach the throttle
            // in every language.
            val result =
                wire.player(
                    videoId, playlistId, client, signatureTimestampFor(client), webPlayerPot,
                    hlOverride = if (isMain) null else "en", policy = policy,
                )
                    .onFailure { Throttle.noteFailure(it) }
            streamPlayerResponse = result.getOrNull()
            result.exceptionOrNull()?.let { failure ->
                val unreached = StreamOrder.networkDidNotAnswer(failure)
                if (!unreached) asked += StreamOrder.Asked(clientLabel, worked = false, asAccount)
                // The main client's failure ends the song, as it always has. So does the first
                // request of all when the network did not carry it, which used to be the main
                // client's: see StreamOrder.networkDidNotAnswer.
                if (isMain || (clientIndex == 0 && unreached)) {
                    trail += StreamCheck.trailStep(clientLabel, null, null, checked = false)
                    chainDone()
                    throw failure
                }
                lastFallbackFailure = failure
            }
            // Noted as having given no stream, until its url passes the check below.
            if (streamPlayerResponse != null) asked += StreamOrder.Asked(clientLabel, worked = false, asAccount)
            streamPlayerResponse?.rememberBlock()

            if (isMain) {
                mainPlayerResponse = streamPlayerResponse
                // Without a visitorData YouTube issued, VISIONOS refuses every song and the chain
                // ends in IOS's 403 (issue #17: sw.js_data failed at every launch). The main client
                // has just answered, and its answer carries a fresh one whatever it said, so take
                // that before VISIONOS is asked. See StreamCheck.visitorDataToAdopt. An app without
                // one always asks the main client first: StreamOrder.order sees to that.
                StreamCheck.visitorDataToAdopt(YouTube.visitorData, streamPlayerResponse?.responseContext?.visitorData)
                    ?.let { found ->
                        Log.i(TAG, "[$videoId] no usable visitorData, taking the one ${MAIN_CLIENT.clientName}'s answer carried")
                        YouTube.visitorData = found
                        onVisitorDataFound?.invoke(found)
                    }
            } else {
                if (Throttle.looksLikeBlock(streamPlayerResponse?.playabilityStatus?.reason)) notes.fallbackBlocked = true
                streamPlayerResponse?.playabilityStatus
                    ?.takeIf { it.status != null && it.status != "OK" && explained == null }
                    ?.let { explained = it }
                streamPlayerResponse?.playabilityStatus
                    ?.takeIf { it.status != null && it.status != "OK" && fallbackRefusal == null }
                    ?.let { fallbackRefusal = it }
            }
            val isVisionos = client.clientName == VISIONOS.clientName
            if (isVisionos && Throttle.looksLikeBlock(streamPlayerResponse?.playabilityStatus?.reason)) {
                notes.visionosRefused = true
            }
            trail += StreamCheck.trailStep(clientLabel, streamPlayerResponse?.playabilityStatus?.status, null, checked = false)

            Log.d(TAG, "[$videoId] stream client: ${client.clientName}, " +
                    "playabilityStatus: ${streamPlayerResponse?.playabilityStatus?.let {
                        it.status + (it.reason?.let { " - $it" } ?: "")
                    }}")

            // process current client response
            if (streamPlayerResponse?.playabilityStatus?.status == "OK") {
                format =
                    findFormat(
                        streamPlayerResponse,
                        audioQuality,
                        connectivityManager,
                    ) ?: continue
                streamUrl = findUrlOrNull(format, videoId) ?: continue
                streamExpiresInSeconds =
                    streamPlayerResponse.streamingData?.expiresInSeconds ?: continue

                if (client.useWebPoTokens && webStreamingPot != null) {
                    streamUrl += "&pot=$webStreamingPot";
                }

                val isLast = clientIndex == clients.lastIndex
                // The family the check goes over, for the log: see StreamFamily. A refusal over
                // the family /player was asked over is the client's or the visitor's, and no
                // longer something a fetch from another address could explain.
                val issuedTo = StreamFamily.of(streamUrl)?.label ?: "no family it names"
                val status = wire.head(streamUrl)
                trail[trail.lastIndex] = StreamCheck.trailStep(clientLabel, "OK", status, checked = true)
                // For StreamOrder the client served or was refused by what the check answered. A
                // check that could not be made says more of the connection than of the client, so
                // nothing is remembered of it, even when its url is the last one and is played.
                asked.removeAt(asked.lastIndex)
                if (status != null) {
                    asked += StreamOrder.Asked(clientLabel, worked = StreamCheck.accept(status, isLast = false), asAccount)
                }
                if (StreamCheck.accept(status, isLast)) {
                    // working stream found, or the last one left with nothing to say it is not
                    Log.i(TAG, "[$videoId] [${client.clientName}] found working stream ($status), address issued to $issuedTo")
                    validated = status != null
                    break
                }
                Log.w(TAG, "[$videoId] [${client.clientName}] got bad http status code $status, address issued to $issuedTo")
                if (status != null) {
                    refusedStatus = status
                    if (isVisionos) notes.visionosRefused = true
                }
                streamUrl = null
            }
        }

        // Written before the throws below, so a failure is reported with the client that failed.
        chainDone()

        // For the throttle, which playerResponseForPlayback tells once it knows whether a second
        // pass is needed and how it went.
        notes.gotStream = streamUrl != null
        notes.blocked = blockedStatus

        // Every url the chain produced was refused by its check. Say why in the words of the client
        // that turned the request down, rather than "Could not find stream url".
        refusedStatus?.let { status ->
            if (streamUrl == null) {
                throw PlaybackException(
                    StreamCheck.refusalMessage(fallbackRefusal?.reason, status),
                    null,
                    PlaybackException.ERROR_CODE_REMOTE_ERROR,
                )
            }
        }

        if (streamPlayerResponse == null) {
            // A fallback client's reason first, then its own failure, then the generic error: see
            // StreamCheck.resolveOnceFailure.
            when (val failure = StreamCheck.resolveOnceFailure(explained, lastFallbackFailure)) {
                is StreamCheck.ChainFailure.Explained -> throw PlaybackException(
                    failure.message,
                    null,
                    PlaybackException.ERROR_CODE_REMOTE_ERROR,
                )
                is StreamCheck.ChainFailure.LastFailure -> throw failure.cause
                StreamCheck.ChainFailure.Unknown -> throw Exception("Bad stream player response")
            }
        }
        if (streamPlayerResponse.playabilityStatus.status != "OK") {
            throw PlaybackException(
                streamPlayerResponse.playabilityStatus.reason,
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR
            )
        }
        if (streamExpiresInSeconds == null) {
            throw Exception("Missing stream expire time")
        }
        if (format == null) {
            throw Exception("Could not find format")
        }
        if (streamUrl == null) {
            throw Exception("Could not find stream url")
        }

        // Never the url itself: its query carries the address it was issued to, which is the
        // listener's own, and logcat gets pasted into issues as it is. See StreamCheck.urlForLog.
        Log.d(TAG, "[$videoId] stream url: ${StreamCheck.urlForLog(streamUrl)}")

        /**
         * Loudness for volume normalisation.
         *
         * [MAIN_CLIENT] is documented above as the source of metadata, but ANDROID_VR returns no
         * playerConfig.audioConfig at all, so taking it only from there leaves loudness null on
         * every track and normalisation silently does nothing. The client that actually served the
         * stream does return one, so fall back to it.
         *
         * Preference order matters: the main client stays first so that if it ever starts sending
         * audioConfig again we use its numbers, since normalisation targets can differ per client
         * and mixing a target from one with a measurement from another would be wrong.
         *
         * Now that the main client is not asked for every song (see [StreamOrder]), its answer is
         * there only when the walk reached it, and then it is preferred as before. Each number
         * comes whole from one answer, so nothing is mixed either way.
         */
        val audioConfig = mainPlayerResponse?.playerConfig?.audioConfig
            ?: streamPlayerResponse.playerConfig?.audioConfig

        // The main client's details when it gave any, which a refusal does not, and the serving
        // client's otherwise. Only the length is read from them (MusicService.recoverSong), and
        // without them that asked VISIONOS for it in a request of its own.
        val videoDetails = mainPlayerResponse?.videoDetails ?: streamPlayerResponse.videoDetails

        // The main client's or none, as it always was: a refusal carries none, so none has been
        // the rule for weeks. It goes into a column of the format row that nothing reads, and
        // another client's is not put there in its place: a play is reported with an address
        // asked for at the time (see playerResponseForMetadata).
        val playbackTracking = mainPlayerResponse?.playbackTracking

        PlaybackData(
            audioConfig,
            videoDetails,
            playbackTracking,
            format,
            streamUrl,
            streamExpiresInSeconds,
            validated,
        )
    }

    /**
     * Simple player response intended to use for metadata only.
     * Stream URLs of this response might not work so don't use them.
     */
    /**
     * WEB_REMIX used to serve this, and stopped. YouTube tightened the poToken requirement on the
     * web /player endpoint, so the request now comes back UNPLAYABLE ("The page needs to be
     * reloaded") with no playbackTracking at all. That is silent: MusicService reads
     * `playbackTracking?.videostatsPlaybackUrl?.baseUrl` and the `playbackUrl?.let { }` below it
     * simply does nothing when it is null, so registerPlayback never fires and nothing reaches the
     * user's YouTube history. No error, no log.
     *
     * Probed directly against the API rather than guessed. With a fresh visitorData:
     *   WEB_REMIX                -> UNPLAYABLE, no playbackTracking
     *   WEB_REMIX + sts          -> byte-identical, so signatureTimestamp is NOT the problem
     *   ANDROID_VR (MAIN_CLIENT) -> LOGIN_REQUIRED, which is why the old comment here said
     *                               ANDROID_VR does not work with history
     *   VISIONOS                 -> OK, real videostatsPlaybackUrl
     *
     * VISIONOS it is. This path is metadata only, its stream urls are never used, and the only
     * other caller just wants videoDetails.lengthSeconds, which VISIONOS also returns.
     *
     * What that probe never tried is WEB_REMIX with a po token, and that was all it lacked: see
     * [playerResponseAsAccount], which a signed-in listener's play asks first since October 2026,
     * because an address from this request names nobody. This one is still what a play reports
     * to when that gives no address or is not made, and what a song's length is asked of.
     */
    suspend fun playerResponseForMetadata(
        videoId: String,
        playlistId: String? = null,
    ): Result<PlayerResponse> =
        // hl=en: noteThrottle hands the reason to Throttle.looksLikeBlock, which knows the bot
        // check only in English. Nothing here shows the reason.
        playerOverBestFamily { policy ->
            YouTube.player(videoId, playlistId, client = VISIONOS, hlOverride = "en", addressPolicy = policy)
        }.noteThrottle()

    /**
     * What the account's request came to: the answer, when one came in time, and what the request
     * was made with. For the line the log gets and for the check in developer options, which is
     * why it keeps the time each part took.
     */
    class AccountAnswer(
        /** YouTube's answer or the failure to get one. Null when the time ran out first. */
        val answer: Result<PlayerResponse>?,
        /** What the request had to be asked without: the timestamp, the token, or both. */
        val without: Set<ListenReporting.AccountStep>,
        /** What it was waiting for when the time ran out, and null when it answered in time. */
        val outOfTimeAt: ListenReporting.AccountStep?,
        /** How long each part took, of those that were finished. */
        val steps: Map<ListenReporting.AccountStep, Long>,
        val tookMs: Long,
    ) {
        /** The address a play is reported to, when the answer carried one. */
        val address: String?
            get() = answer?.let { addressIn(it) }
    }

    /** The address a play is reported to, from a /player answer that carries one. */
    fun addressIn(answer: Result<PlayerResponse>): String? =
        answer.getOrNull()?.playbackTracking?.videostatsPlaybackUrl?.baseUrl?.takeIf { it.isNotBlank() }

    /** Where the account's request has got to, written as it goes and read when its time is up. */
    private class AccountProgress {
        @Volatile
        var at = ListenReporting.AccountStep.SIGNATURE_TIMESTAMP
        val without: MutableSet<ListenReporting.AccountStep> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        val steps = java.util.concurrent.ConcurrentHashMap<ListenReporting.AccountStep, Long>()
    }

    /**
     * /player asked as the account, for the address a play is reported to and for nothing else.
     * See [ListenReporting.addressRequests] for when, and [ListenReporting.ACCOUNT_ADDRESS_CLIENT]
     * for why this client.
     *
     * The request above is a visitor's, VISIONOS taking no cookie, so the address it hands back
     * names nobody, and a brand account's channel is named nowhere in the report that follows.
     * This one carries the cookie and onBehalfOfUser, so its address is issued to the channel that
     * is signed in. It is AsterTune's request (commit 6ea96c90): WEB_REMIX with the signature
     * timestamp and a player po token, the token made for the video once the WebView has made one
     * for the session. Without the token YouTube answers UNPLAYABLE and no address. The request
     * is sent all the same when the timestamp or the token could not be had, as theirs is, and
     * [AccountAnswer.without] says so for the log.
     *
     * All of it within [limitMs], after which the caller uses the visitor's address: see
     * [ListenReporting.answerWithin]. The timestamp is read from the player's script and the token
     * comes out of a WebView, and neither may hold a finished play back.
     *
     * It is kept away from what the stream chain has learned, because its client is one the chain
     * never asks:
     * - The throttle is not told. One client's refusal is not YouTube's, and the visitor's
     *   request that follows a refusal tells the throttle what it always has.
     * - It goes over the address family the chain found to work, and what it finds is forgotten.
     */
    suspend fun playerResponseAsAccount(
        videoId: String,
        limitMs: Long = ListenReporting.ACCOUNT_ADDRESS_LIMIT_MS,
    ): AccountAnswer {
        val started = System.nanoTime()
        fun since(from: Long) = (System.nanoTime() - from) / 1_000_000
        val progress = AccountProgress()
        val answer = ListenReporting.answerWithin(limitMs) {
            var stepStarted = System.nanoTime()
            fun stepDone(step: ListenReporting.AccountStep, next: ListenReporting.AccountStep?, missing: Boolean = false) {
                progress.steps[step] = since(stepStarted)
                if (missing) progress.without += step
                if (next != null) progress.at = next
                stepStarted = System.nanoTime()
            }

            val signatureTimestamp = getSignatureTimestampOrNull(videoId)
            stepDone(ListenReporting.AccountStep.SIGNATURE_TIMESTAMP, ListenReporting.AccountStep.PO_TOKEN, missing = signatureTimestamp == null)

            // The identifier the stream chain would use, so that a chain that asks for a token
            // again finds the same WebView and not one made for another session.
            val session = YouTube.dataSyncId?.takeIf { YouTube.cookie != null && it.isNotBlank() } ?: YouTube.visitorData
            val webPlayerPot = getWebClientPoTokenOrNull(videoId, session)?.playerRequestPoToken
            stepDone(ListenReporting.AccountStep.PO_TOKEN, ListenReporting.AccountStep.ANSWER, missing = webPlayerPot == null)

            playerOverBestFamily(remember = false) { policy ->
                YouTube.player(
                    videoId,
                    client = ListenReporting.ACCOUNT_ADDRESS_CLIENT,
                    signatureTimestamp = signatureTimestamp,
                    webPlayerPot = webPlayerPot,
                    hlOverride = "en",
                    addressPolicy = policy,
                )
            }.also { stepDone(ListenReporting.AccountStep.ANSWER, null) }
        }
        return AccountAnswer(
            // A failure of the work itself and a failed request are the same to the caller.
            answer = answer?.fold(onSuccess = { it }, onFailure = { Result.failure(it) }),
            without = progress.without.toSet(),
            outOfTimeAt = if (answer == null) progress.at else null,
            steps = progress.steps.toMap(),
            tookMs = since(started),
        )
    }

    /** Outcome of a loudness lookup. Distinguishes "no value exists" from "the request failed". */
    sealed interface LoudnessResult {
        data class Found(val loudnessDb: Double) : LoudnessResult

        /** The request succeeded but carried no loudness, or a value outside the plausible range. */
        data object Unavailable : LoudnessResult

        /** Network or server problem. Worth retrying later; NOT worth writing anything for. */
        data class Failed(val cause: Throwable?) : LoudnessResult
    }

    /**
     * Fetches just the loudness for one video id, without touching playback.
     *
     * VISIONOS because it is the client that actually answers: probed directly, WEB_REMIX returns
     * UNPLAYABLE on the poToken check and ANDROID_VR returns LOGIN_REQUIRED. It also needs no
     * cookie, no signature timestamp and no poToken, so this is a plain anonymous request.
     *
     * Nothing here is written to the database and no stream is resolved, so a failure costs only
     * the request. The caller decides what to do about each outcome.
     */
    suspend fun loudnessFor(videoId: String): LoudnessResult {
        // hl=en for the same reason as playerResponseForMetadata. LoudnessRepair's batch is
        // exactly the background work the back off exists to stop on a refused network.
        val response = playerOverBestFamily { policy ->
            YouTube.player(videoId, client = VISIONOS, hlOverride = "en", addressPolicy = policy)
        }.noteThrottle()
            .getOrElse { return LoudnessResult.Failed(it) }

        val db = response.playerConfig?.audioConfig?.effectiveLoudnessDb
            ?: return LoudnessResult.Unavailable

        // Same sanity band the player applies. Observed range over 3178 real rows is -16.5 to
        // +12.6, so anything outside this is not a loudness figure and must not be stored.
        if (!db.isFinite() || db !in -60.0..40.0) return LoudnessResult.Unavailable

        return LoudnessResult.Found(db)
    }

    /** Prints what YouTube offered and what was taken. Debug only; four tiers, two outcomes. */
    private val TRACE_FORMATS = com.dd3boh.outertune.BuildConfig.DEBUG

    private fun findFormat(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): PlayerResponse.StreamingData.Format? {
        val audioFormats = playerResponse.streamingData?.adaptiveFormats?.filter { it.isAudio }
        if (audioFormats.isNullOrEmpty()) return null
        if (TRACE_FORMATS) {
            android.util.Log.d(
                "YTPlayerUtils",
                "quality=$audioQuality offered=" + audioFormats.joinToString {
                    "${it.itag}:${it.mimeType.substringBefore(';')}@${it.bitrate}"
                },
            )
        }

        // MAX takes the largest stream and nothing else is allowed a say. In particular it skips
        // the codec bonus below, which is there to break ties between streams of similar size and
        // would otherwise hand a 160 kbps Opus the win over a 256 kbps AAC, and it ignores whether
        // the connection is metered, because a tier called highest that quietly drops on mobile
        // data would be lying about what it does. Sample rate breaks genuine ties.
        if (audioQuality == AudioQuality.MAX) {
            @Suppress("NAME_SHADOWING")
            return audioFormats.maxWithOrNull(
                compareBy<PlayerResponse.StreamingData.Format> { it.bitrate }
                    .thenBy { it.audioSampleRate ?: 0 }
            )
        }

        return audioFormats.maxByOrNull {
            it.bitrate * when (audioQuality) {
                AudioQuality.AUTO -> if (connectivityManager.isActiveNetworkMetered) -1 else 1
                AudioQuality.HIGH -> 1
                AudioQuality.LOW -> -1
                AudioQuality.MAX -> 1 // returned above
            } + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0) // prefer opus stream
        }.also {
            if (TRACE_FORMATS) {
                android.util.Log.d(
                    "YTPlayerUtils",
                    "quality=$audioQuality took=${it?.itag}@${it?.bitrate} metered=${connectivityManager.isActiveNetworkMetered}",
                )
            }
        }
    }

    /**
     * The status a HEAD request for the stream url gets, or null when the request itself fails.
     *
     * 2xx means the url plays to the end. IOS and ANDROID urls answer 403 here while still serving
     * the first 512 KB of a ranged GET, so a url that fails this check fails partway through the
     * song, not at the start. [StreamCheck.accept] turns the answer into a decision.
     */
    private fun streamStatus(url: String): Int? = try {
        streamCalls.newCall(okhttp3.Request.Builder().head().url(url).build()).execute().use { it.code }
    } catch (e: Exception) {
        reportException(e)
        null
    }

    /**
     * Wrapper around the [NewPipeUtils.getSignatureTimestamp] function which reports exceptions
     */
    private fun getSignatureTimestampOrNull(
        videoId: String
    ): Int? {
        return NewPipeUtils.getSignatureTimestamp(videoId)
            .onFailure {
                reportException(it)
            }
            .getOrNull()
    }

    /**
     * Wrapper around the [NewPipeUtils.getStreamUrl] function which reports exceptions
     */
    private fun findUrlOrNull(
        format: PlayerResponse.StreamingData.Format,
        videoId: String
    ): String? {
        return NewPipeUtils.getStreamUrl(format, videoId)
            .onFailure {
                reportException(it)
            }
            .getOrNull()
    }

    /**
     * Wrapper around the [PoTokenGenerator.getWebClientPoToken] function which reports exceptions
     */
    private fun getWebClientPoTokenOrNull(videoId: String, sessionId: String?): PoTokenResult? {
        if (sessionId == null) {
            Log.d(TAG, "[$videoId] Session identifier is null")
            return null
        }
        try {
            return poTokenGenerator.getWebClientPoToken(videoId, sessionId)
        } catch (e: Exception) {
            reportException(e)
        }
        return null
    }
}