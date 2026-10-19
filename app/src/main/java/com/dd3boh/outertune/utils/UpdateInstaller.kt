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

        /** Handed to Android. The system prompt is up, or about to be. */
        data object AwaitingConfirmation : State

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

    init {
        scope.launch { dropInstalledApk() }
    }

    /** The app came to the front: a confirmation held back while it was away is shown now. */
    fun onShown() {
        inFront = true
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
        // Android's prompt is on its way and was held back: show it rather than starting a second
        // install of the same file.
        if (_state.value == State.AwaitingConfirmation && pendingConfirm != null) {
            showPendingConfirm()
            return
        }
        if (isDownloaded(expectedBytes)) {
            job = scope.launch {
                if (!canRequestInstall()) {
                    _state.value = State.NeedsPermission
                    return@launch
                }
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

    private fun start(url: String, expectedBytes: Long, installWhenDone: Boolean) {
        if (isBusy) return
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
        // One install at a time: one still waiting is dropped first.
        abandon()
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
                _state.value = State.AwaitingConfirmation
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            // Out of space, or stopped while copying: a session left open keeps its copy of the apk.
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
        }
    }

    /** An apk kept from an install that went through is the version now running, or older. */
    private fun dropInstalledApk() {
        val apk = File(context.cacheDir, APK_NAME)
        if (!apk.exists() || isBusy) return
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
     * showing is left alone: the prompt would otherwise come back over it.
     */
    fun acknowledge() {
        if (!isBusy && _state.value != State.AwaitingConfirmation) _state.value = State.Idle
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
                    pendingConfirm = confirm
                    showPendingConfirm()
                }

                PackageInstaller.STATUS_SUCCESS -> {
                    // Usually nobody is left to see this: installing over ourselves kills the
                    // process. It still matters when the install did not replace us, and it clears
                    // the stored update so a completed one cannot keep prompting.
                    sessionId = -1
                    File(context.cacheDir, APK_NAME).delete()
                    _state.value = State.Idle
                    onInstalled?.invoke()
                }

                PackageInstaller.STATUS_FAILURE_ABORTED -> {
                    sessionId = -1
                    pendingConfirm = null
                    aborted(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
                }

                PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                    sessionId = -1
                    pendingConfirm = null
                    _state.value = State.Blocked
                }

                else -> {
                    sessionId = -1
                    pendingConfirm = null
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    Log.w(TAG, "Install failed, status $status: $msg")
                    _state.value = State.Failed(msg ?: "status $status")
                }
            }
        }
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

    fun registerReceiver() {
        ContextCompat.registerReceiver(
            context,
            resultReceiver,
            IntentFilter(ACTION_INSTALL_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private companion object {
        private const val TAG = "UpdateInstaller"
        private const val APK_NAME = "intertune-update.apk"
        private const val ACTION_INSTALL_RESULT = "com.dd3boh.outertune.INSTALL_RESULT"
        private const val FAKE_AUTO_BLOCKER_FILE = "fake-auto-blocker"
    }
}

/** A cancel reads "User rejected permissions"; Auto Blocker's refusal names a block. */
internal fun abortedMeansBlocked(message: String?): Boolean =
    message?.contains("block", ignoreCase = true) == true
