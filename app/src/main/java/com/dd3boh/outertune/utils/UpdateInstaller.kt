/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import com.dd3boh.outertune.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads a release and hands it to Android's installer.
 *
 * The app never installs anything by itself. This downloads when asked, then opens the system
 * install prompt, which is the only thing that can actually install and which the user has to
 * confirm. If the "install unknown apps" permission has not been granted, Android shows that
 * request first; it is a one time thing, and declining it simply cancels.
 *
 * PackageInstaller rather than an ACTION_VIEW intent, because a session reports back what happened.
 * With the intent form the app hands the file over and never learns whether the install succeeded,
 * was cancelled, or failed on a signature mismatch, so it cannot say anything useful afterwards.
 */
@Singleton
class UpdateInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    sealed interface State {
        data object Idle : State

        /** [downloaded] and [total] are bytes; total is 0 when the release did not declare a size. */
        data class Downloading(val downloaded: Long, val total: Long) : State

        /**
         * The apk is being copied into Android's installer. Android's window cannot open before the
         * copy ends, which takes seconds for forty five megabytes, and through it the prompt used to
         * sit unchanged with its Install button still lit, so the tap looked lost and was repeated.
         */
        data object Preparing : State

        /**
         * Handed to Android, waiting for the person's answer. The system prompt is up, or about to
         * be, or was left without an answer and waits in its own task. [shownAt] is when Android's
         * window was last opened, on the elapsedRealtime clock, and 0 until it has been.
         */
        data class AwaitingConfirmation(val shownAt: Long = 0L) : State {
            val shown: Boolean get() = shownAt > 0L
        }

        /**
         * The person said yes and Android is installing. Its window closes at once on the tap and
         * says nothing more, and an update of this app ends with Android closing it before any
         * answer is sent, so this is the last thing the app can say.
         */
        data object Installing : State

        /** The user was sent to grant "install unknown apps" and has not come back yet. */
        data object NeedsPermission : State

        data class Failed(val reason: String) : State

        /**
         * The phone refused the install. Samsung's Auto Blocker refuses it after the person taps
         * Update, and says "aborted", as for a cancel: the prompt used to come back as if nothing
         * had happened, so tapping Install looked like it did nothing.
         */
        data object Blocked : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val isBusy: Boolean get() = job?.isActive == true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    // Android's confirmation, held until the app is in front to show it. Started from behind,
    // Android 10 and later drop it without a word, and the install waited for ever: a download
    // that ended while the person was in another app.
    @Volatile private var inFront = false
    @Volatile private var pendingConfirm: Intent? = null
    @Volatile private var sessionId = -1

    // The same confirmation, kept for as long as the session waits. Android leaves its window out
    // of recents, so after Home the only way back to it is to start it again, and that is the same
    // window for the same session, never a new install.
    @Volatile private var windowIntent: Intent? = null

    // When Android last said anything about the session in hand: the commit, the window handed
    // over, a step of progress, a change of activity. See sessionVerdict.
    @Volatile private var lastSign = 0L

    // Listens to Android about the session in hand, from its commit to its end.
    @Volatile private var watcher: PackageInstaller.SessionCallback? = null

    private val receiverRegistered = AtomicBoolean(false)

    init {
        scope.launch { dropInstalledApk() }
    }

    /**
     * The app came to the front: the session is looked at again, then a confirmation held back
     * while it was away is shown now.
     */
    fun onShown() {
        inFront = true
        recheck()
        showPendingConfirm()
    }

    fun onHidden() {
        inFront = false
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** True when Android will let this app ask to install. Always true below Oreo. */
    fun canRequestInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /**
     * The apk is already here and the right size, so install will not have to fetch it.
     *
     * Downloads go to the app's own cache, so they need no storage permission and Android clears
     * them if space runs short. A whole apk stays there until the install goes through: a blocked,
     * cancelled or failed install can be tried again without fetching it a second time.
     */
    fun isDownloaded(expectedBytes: Long): Boolean {
        val apk = File(context.cacheDir, APK_NAME)
        return apk.exists() && (expectedBytes <= 0 || apk.length() == expectedBytes)
    }

    /**
     * Fetch it and stop, ready for a later install. Used when automatic downloading is on.
     *
     * The guard is the whole point, and it was missing. installOrDownload has always checked
     * isDownloaded before spending the bytes; this path never did, and it is the one that runs by
     * itself. Worse, a finished background download parks the state back at Idle, and the caller in
     * MainActivity only tests for Idle before starting one. So the APK was fetched again in full on
     * every re-entry into composition, which is every rotation, every activity recreate and every
     * return to the app, for as long as an update sat pending. Forty five megabytes at a time,
     * silently, on whatever connection was to hand.
     */
    fun download(url: String, expectedBytes: Long) {
        if (isDownloaded(expectedBytes)) return
        start(url, expectedBytes, installWhenDone = false)
    }

    /** Install what is already downloaded, or fetch it first if it is not. */
    fun installOrDownload(url: String, expectedBytes: Long) {
        if (isBusy) return
        // A session Android still holds is never dropped for a new one. Each tap used to start
        // over, and the abandon in commit() threw away the session Android might be installing and
        // opened a second window. A tap now brings back the window that is waiting, or, once
        // Android is installing, only shows that it is.
        if (resumeLiveSession()) return
        if (isDownloaded(expectedBytes)) {
            if (!canRequestInstall()) {
                _state.value = State.NeedsPermission
                return
            }
            // Said at once, before the copy starts, so the tap is answered: see Preparing.
            _state.value = State.Preparing
            job = scope.launch {
                try {
                    commit(File(context.cacheDir, APK_NAME))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Update install failed", ErrorText.forLog(e))
                    _state.value = State.Failed(ErrorText.withoutAddresses(e.message ?: "install failed"))
                }
            }
        } else {
            start(url, expectedBytes, installWhenDone = true)
        }
    }

    /**
     * Picks up the session in hand if Android still holds it, and says whether it did: nothing
     * new may start then.
     */
    private fun resumeLiveSession(): Boolean {
        val info = liveSession() ?: return false
        return when {
            _state.value == State.Installing || accepted(info) -> {
                installing()
                true
            }

            windowIntent != null -> {
                showWindowAgain()
                true
            }

            // Committed a moment ago, and Android has not handed its window over yet. It opens by
            // itself when it comes.
            SystemClock.elapsedRealtime() - lastSign < HANDOVER_QUIET_MS -> true

            // Its window never came, so nobody can answer it, and it is safe to drop for a new one.
            else -> false
        }
    }

    /**
     * Shows Android's window again for the session it waits on: the Show button of the dialog, and
     * a tap on Install while the window waits. Never a new session.
     */
    fun showWindowAgain() {
        if (isBusy) return
        val info = liveSession()
        if (info == null) {
            sessionGone(wasInstalling = false)
            return
        }
        // The yes was given after all, and its callback is only slower than this tap. Showing the
        // window again now would ask about an install that is already under way.
        if (accepted(info)) {
            installing()
            return
        }
        // Not handed over yet: it opens by itself when it is.
        val window = windowIntent ?: return
        Log.i(TAG, "Showing Android's window again for session $sessionId")
        pendingConfirm = window
        showPendingConfirm()
    }

    /**
     * Stops an install still waiting for the person's answer, keeping the apk for a later try.
     * Once Android is installing it is too late, and the dialog says that instead.
     */
    fun stop() {
        if (isBusy) return
        val info = liveSession()
        if (info != null && accepted(info)) {
            installing()
            return
        }
        abandon()
        _state.value = State.Idle
    }

    /**
     * Looks at the session in hand again: when the app comes back to the front, and every
     * INSTALL_RECHECK_MS while the dialog is up. The callbacks come late at times, an install can
     * end without its answer reaching the app, and an install Android goes quiet about would
     * otherwise keep the dialog up for ever. Nothing here abandons anything: a quiet install is
     * let go of, not stopped, and an answer that arrives later still counts, because the session
     * stays the one in hand.
     */
    fun recheck() {
        if (isBusy) return
        val state = _state.value
        val wasInstalling = state == State.Installing
        if (!wasInstalling && state !is State.AwaitingConfirmation) return
        val info = liveSession()
        val verdict = sessionVerdict(
            installing = wasInstalling,
            exists = info != null,
            accepted = info != null && accepted(info),
            handedOver = windowIntent != null,
            quietMs = SystemClock.elapsedRealtime() - lastSign,
        )
        when (verdict) {
            SessionVerdict.WAITING -> Unit
            SessionVerdict.INSTALLING -> if (!wasInstalling) installing()
            SessionVerdict.GONE -> sessionGone(wasInstalling)
            SessionVerdict.STALLED -> {
                Log.w(TAG, "Android said nothing about the install for a while, letting it go")
                pendingConfirm = null
                _state.value = State.Failed(NO_ANSWER)
            }
        }
    }

    /** The session in hand, while Android still has it. Null once it has ended, or was dropped. */
    private fun liveSession(): PackageInstaller.SessionInfo? {
        val id = sessionId
        if (id < 0) return null
        return runCatching { context.packageManager.packageInstaller.getSessionInfo(id) }.getOrNull()
    }

    private fun accepted(info: PackageInstaller.SessionInfo): Boolean =
        installAccepted(progress = info.progress, active = info.isActive, handedOver = windowIntent != null)

    private fun installing() {
        pendingConfirm = null
        lastSign = SystemClock.elapsedRealtime()
        if (_state.value != State.Installing) Log.i(TAG, "Session $sessionId is installing")
        _state.value = State.Installing
    }

    /**
     * The session ended and its answer has not come. It stays the session in hand, so an answer
     * still on its way is read when it arrives and says what really happened. Ended while
     * installing, and the app still running, it did not install: an update of this app would have
     * closed it.
     */
    private fun sessionGone(wasInstalling: Boolean) {
        pendingConfirm = null
        windowIntent = null
        unwatch()
        _state.value = if (wasInstalling) State.Failed(NO_ANSWER) else State.Idle
    }

    /**
     * Listens to Android about one session. The status broadcast says how an install ended, but
     * not that the person said yes: Android's window closes at once on the tap, and for an update
     * of this app the broadcast never comes, because Android closes the app first. These callbacks
     * are what say it.
     */
    @Synchronized
    private fun watch(id: Int) {
        unwatch()
        val callback = object : PackageInstaller.SessionCallback() {
            override fun onCreated(sessionId: Int) = Unit
            override fun onBadgingChanged(sessionId: Int) = Unit
            override fun onActiveChanged(sessionId: Int, active: Boolean) {
                if (sessionId == id) heard(id, progress = 0f, active = active)
            }

            override fun onProgressChanged(sessionId: Int, progress: Float) {
                if (sessionId == id) heard(id, progress = progress, active = false)
            }

            // How it ended comes with the broadcast, which says why. This only ends the watch.
            override fun onFinished(sessionId: Int, success: Boolean) {
                if (sessionId == id) unwatch()
            }
        }
        runCatching {
            context.packageManager.packageInstaller.registerSessionCallback(callback, Handler(Looper.getMainLooper()))
            watcher = callback
        }.onFailure { Log.w(TAG, "Could not listen to the install", it) }
    }

    @Synchronized
    private fun unwatch() {
        val callback = watcher ?: return
        watcher = null
        runCatching { context.packageManager.packageInstaller.unregisterSessionCallback(callback) }
    }

    private fun heard(id: Int, progress: Float, active: Boolean) {
        // One already queued when its session was dropped for a newer one.
        if (id != sessionId) return
        lastSign = SystemClock.elapsedRealtime()
        if (_state.value is State.AwaitingConfirmation &&
            installAccepted(progress = progress, active = active, handedOver = windowIntent != null)
        ) {
            installing()
        }
    }

    private fun start(url: String, expectedBytes: Long, installWhenDone: Boolean) {
        if (isBusy) return
        // Debug builds only: with the fake-update file there is nothing to fetch, and the apk put
        // in place by hand must not be deleted by the download below.
        if (fakeUpdateBytes(context) != null) {
            _state.value = if (installWhenDone) State.Failed("no apk in place for the fake update") else State.Idle
            return
        }
        job = scope.launch {
            val apk = File(context.cacheDir, APK_NAME)
            var whole = false
            // A download nobody asked to install fails quietly: the prompt offers it again anyway,
            // and "Could not install" for a tap that never happened only confuses.
            fun failed(reason: String) {
                _state.value = if (installWhenDone) State.Failed(reason) else State.Idle
            }
            try {
                apk.delete()
                _state.value = State.Downloading(0, expectedBytes)

                val response = client.newCall(Request.Builder().url(url).build()).execute()
                if (!response.isSuccessful) {
                    failed("HTTP ${response.code}")
                    return@launch
                }
                val body = response.body ?: run {
                    failed("empty response")
                    return@launch
                }

                val total = if (expectedBytes > 0) expectedBytes else body.contentLength()
                var written = 0L
                body.byteStream().use { input ->
                    apk.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            if (!currentCoroutineContext().isActive) {
                                apk.delete()
                                _state.value = State.Idle
                                return@launch
                            }
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            if (currentCoroutineContext().isActive) _state.value = State.Downloading(written, total)
                        }
                    }
                }
                whole = true

                if (!installWhenDone) {
                    // Downloaded and parked. The prompt will offer to install it.
                    _state.value = State.Idle
                    return@launch
                }

                // Ask only once the file is actually here. Sending someone to a settings screen
                // before knowing the download works would waste the trip.
                if (!canRequestInstall()) {
                    _state.value = State.NeedsPermission
                    return@launch
                }

                commit(apk)
            } catch (e: CancellationException) {
                // Stopped from the app, which has already put the state back.
                if (!whole) apk.delete()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Update download or install failed", ErrorText.forLog(e))
                if (!whole) apk.delete()
                _state.value = if (installWhenDone) {
                    State.Failed(ErrorText.withoutAddresses(e.message ?: "unknown error"))
                } else {
                    State.Idle
                }
            }
        }
    }

    /** Streams the apk into a session and commits it. Android then asks for the confirmation. */
    private suspend fun commit(apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        // One install at a time. installOrDownload never comes here while Android may still act on
        // the session in hand, so this only clears one that ended, or whose window never came.
        abandon()
        _state.value = State.Preparing
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite(APK_NAME, 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }

                val intent = Intent(ACTION_INSTALL_RESULT).setPackage(context.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val pending = PendingIntent.getBroadcast(context, id, intent, flags)

                // Stopped while the file was copied: the install must not go ahead after all.
                ensureActive()
                sessionId = id
                lastSign = SystemClock.elapsedRealtime()
                // Before the commit, so not one of Android's answers about it is missed.
                watch(id)
                _state.value = State.AwaitingConfirmation()
                session.commit(pending.intentSender)
                Log.i(TAG, "Session $id handed to Android")
            }
        } catch (e: Exception) {
            // Out of space, or stopped while copying: a session left open keeps its copy of the apk.
            unwatch()
            sessionId = -1
            runCatching { installer.abandonSession(id) }
            throw e
        }
        // The apk stays: see isDownloaded.
    }

    /** Drops the install that is waiting, if there is one. Its answer, when it comes, is ignored. */
    private fun abandon() {
        val id = sessionId
        sessionId = -1
        pendingConfirm = null
        windowIntent = null
        unwatch()
        if (id >= 0) runCatching { context.packageManager.packageInstaller.abandonSession(id) }
    }

    /** Shows Android's confirmation if one is waiting and the app is in front to show it. */
    private fun showPendingConfirm() {
        val confirm = pendingConfirm ?: return
        if (!inFront) return
        pendingConfirm = null
        val shown = runCatching {
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "The install prompt would not open", it) }.isSuccess
        if (!shown) {
            abandon()
            _state.value = State.Blocked
            return
        }
        // From here an app back in front without an answer is an app whose window waits.
        if (_state.value != State.Installing) {
            _state.value = State.AwaitingConfirmation(shownAt = SystemClock.elapsedRealtime())
        }
    }

    /** An apk kept from an install that went through is the version now running, or older. */
    private fun dropInstalledApk() {
        val apk = File(context.cacheDir, APK_NAME)
        if (!apk.exists() || isBusy) return
        // Debug builds only: the apk put in place for the fake update is the version running.
        if (fakeUpdateBytes(context) != null) return
        val code = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(apk.path, 0)?.let { PackageInfoCompat.getLongVersionCode(it) }
        }.getOrNull() ?: return
        if (code <= BuildConfig.VERSION_CODE) apk.delete()
    }

    /** Called after a successful install, so the checker can forget what it found. */
    @Volatile
    var onInstalled: (() -> Unit)? = null

    fun cancel() {
        job?.cancel()
        job = null
        abandon()
        File(context.cacheDir, APK_NAME).delete()
        _state.value = State.Idle
    }

    /**
     * Clears a finished or failed run so the row goes back to resting. A confirmation Android is
     * showing is left alone, and so is an install under way: the prompt would otherwise come back
     * over it. Leaving Settings > Updates calls this, and used to drop the wait that way.
     */
    fun acknowledge() {
        val state = _state.value
        if (!isBusy && state !is State.AwaitingConfirmation && state != State.Installing) _state.value = State.Idle
    }

    /**
     * Receives the outcome of the install.
     *
     * STATUS_PENDING_USER_ACTION is the normal path, not an error: Android is asking us to show the
     * confirmation dialog, and it hands back the intent to start.
     */
    val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            // The answer for an install that was dropped for a newer one, or by us.
            val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
            if (id >= 0 && id != sessionId) return
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // Not an error. Android is handing back the confirmation dialog for us to show.
                    @Suppress("DEPRECATION")
                    val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    }
                    // Debug builds only: a file that makes the install answer as Samsung's Auto
                    // Blocker does, so what follows can be seen on any phone.
                    if (BuildConfig.DEBUG && File(ctx.filesDir, FAKE_AUTO_BLOCKER_FILE).exists()) {
                        Log.w(TAG, "Answering as Auto Blocker would ($FAKE_AUTO_BLOCKER_FILE)")
                        abandon()
                        aborted("INSTALL_FAILED_ABORTED: Self update is blocked by unknown source package")
                        return
                    }
                    if (confirm == null) {
                        abandon()
                        _state.value = State.Failed("no install prompt")
                        return
                    }
                    windowIntent = confirm
                    lastSign = SystemClock.elapsedRealtime()
                    pendingConfirm = confirm
                    showPendingConfirm()
                }

                PackageInstaller.STATUS_SUCCESS -> {
                    // Usually nobody is left to see this: installing over ourselves kills the
                    // process. It still matters when the install did not replace us, and it clears
                    // the stored update so a completed one cannot keep prompting.
                    ended()
                    // Debug builds only: the apk put in place for the fake update is kept for the next try.
                    if (fakeUpdateBytes(context) == null) File(context.cacheDir, APK_NAME).delete()
                    _state.value = State.Idle
                    onInstalled?.invoke()
                }

                PackageInstaller.STATUS_FAILURE_ABORTED -> {
                    ended()
                    aborted(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
                }

                PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                    ended()
                    _state.value = State.Blocked
                }

                else -> {
                    ended()
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    Log.w(TAG, "Install failed, status $status: $msg")
                    _state.value = State.Failed(msg ?: "status $status")
                }
            }
        }
    }

    /** The session in hand has its answer: nothing is left to show, watch or keep. */
    private fun ended() {
        sessionId = -1
        pendingConfirm = null
        windowIntent = null
        unwatch()
    }

    /**
     * Cancelled by the user, which is no failure, unless the reason names a block. Samsung's Auto
     * Blocker lets the person tap Update in Android's prompt, then drops the install as "aborted",
     * with "Self update is blocked by unknown source package" (Galaxy S25 Ultra, 19 Oct 2026).
     */
    private fun aborted(message: String?) {
        Log.i(TAG, "Install aborted: $message")
        _state.value = if (abortedMeansBlocked(message)) State.Blocked else State.Idle
    }

    /**
     * Once for the process. The receiver is registered on the application, and MainActivity asks
     * on every creation, so after a rotation it was registered a second time.
     */
    fun registerReceiver() {
        if (!receiverRegistered.compareAndSet(false, true)) return
        ContextCompat.registerReceiver(
            context,
            resultReceiver,
            IntentFilter(ACTION_INSTALL_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private companion object {
        private const val TAG = "UpdateInstaller"
        private const val APK_NAME = UPDATE_APK_NAME
        private const val ACTION_INSTALL_RESULT = "com.dd3boh.outertune.INSTALL_RESULT"
        private const val FAKE_AUTO_BLOCKER_FILE = "fake-auto-blocker"

        /** Why the dialog went, when Android went quiet: shown after "Could not install". */
        private const val NO_ANSWER = "no answer from Android"
    }
}

/** A cancel reads "User rejected permissions"; Auto Blocker's refusal names a block. */
internal fun abortedMeansBlocked(message: String?): Boolean =
    message?.contains("block", ignoreCase = true) == true

internal const val UPDATE_APK_NAME = "intertune-update.apk"

/** How often the dialog has the session looked at again while Android has the install. */
internal const val INSTALL_RECHECK_MS = 10_000L

/**
 * An install Android says nothing about for this long is let go of. After the yes it sends one
 * step of progress and then closes the app, within seconds, unless something stopped it without
 * telling the app. Nobody is kept behind the dialog for that.
 */
internal const val INSTALL_QUIET_MS = 2 * 60_000L

/** A window Android has not handed over this long after the commit is not coming. */
internal const val HANDOVER_QUIET_MS = 30_000L

/** Between the commit's 0.8 and the 0.9 Android reports once the install pass after the yes runs. */
internal const val ACCEPTED_PROGRESS = 0.85f

/**
 * Whether the session says the person said yes and Android is installing. The commit puts its
 * progress at 0.8, and the install pass that runs only after the yes puts it at 0.9, on every
 * Android from 9 to 16. From 14, Android also marks the session active again at the yes; before
 * the window was handed over, an active session is only the commit.
 */
internal fun installAccepted(progress: Float, active: Boolean, handedOver: Boolean): Boolean =
    progress >= ACCEPTED_PROGRESS || (active && handedOver)

internal enum class SessionVerdict {
    /** Android's window waits for an answer, or is about to open. */
    WAITING,
    INSTALLING,
    /** The session ended and its answer did not reach the app. */
    GONE,
    /** Android went quiet: the dialog lets go, the session is not touched. */
    STALLED,
}

/**
 * What a look at the session in hand finds. [installing] is what the app already says, [quietMs]
 * the time since Android last said anything about it, and [handedOver] whether its window has
 * come. A window that waits is never given up on: the person can take all the time they like,
 * and the dialog offers Stop.
 */
internal fun sessionVerdict(
    installing: Boolean,
    exists: Boolean,
    accepted: Boolean,
    handedOver: Boolean,
    quietMs: Long,
): SessionVerdict = when {
    !exists -> SessionVerdict.GONE
    installing && quietMs >= INSTALL_QUIET_MS -> SessionVerdict.STALLED
    installing || accepted -> SessionVerdict.INSTALLING
    !handedOver && quietMs >= HANDOVER_QUIET_MS -> SessionVerdict.STALLED
    else -> SessionVerdict.WAITING
}

/**
 * Debug builds only: with this file in filesDir, UpdateChecker reports a newer version, and the
 * apk put by hand at cacheDir/intertune-update.apk is installed as it, so the whole install can be
 * tried on an emulator without a release. The file holds that apk's size in bytes; left empty,
 * the size of the apk in place is taken. Android accepts the same build again as an update.
 */
internal const val FAKE_UPDATE_FILE = "fake-update"

/** The fake update's size, or null when there is none: always null in a release. */
internal fun fakeUpdateBytes(context: Context): Long? {
    if (!BuildConfig.DEBUG) return null
    val file = File(context.filesDir, FAKE_UPDATE_FILE)
    if (!file.exists()) return null
    return runCatching { file.readText().trim().toLongOrNull() }.getOrNull()
        ?: File(context.cacheDir, UPDATE_APK_NAME).length()
}
