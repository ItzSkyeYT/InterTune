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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
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
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val isBusy: Boolean get() = job?.isActive == true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

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
     * Downloads [url] and opens the install prompt.
     *
     * Downloads to the app's own cache, so it needs no storage permission and Android clears it if
     * space runs short. The file is deleted as soon as the session owns a copy, and any leftover
     * from an earlier attempt is deleted first, so a failed run cannot leave an apk behind.
     */
    /** The apk is already here and the right size, so install will not have to fetch it. */
    fun isDownloaded(expectedBytes: Long): Boolean {
        val apk = File(context.cacheDir, APK_NAME)
        return apk.exists() && (expectedBytes <= 0 || apk.length() == expectedBytes)
    }

    /** Fetch it and stop, ready for a later install. Used when automatic downloading is on. */
    fun download(url: String, expectedBytes: Long) = start(url, expectedBytes, installWhenDone = false)

    /** Install what is already downloaded, or fetch it first if it is not. */
    fun installOrDownload(url: String, expectedBytes: Long) {
        if (isBusy) return
        if (isDownloaded(expectedBytes)) {
            job = scope.launch {
                if (!canRequestInstall()) {
                    _state.value = State.NeedsPermission
                    return@launch
                }
                runCatching { commit(File(context.cacheDir, APK_NAME)) }
                    .onFailure { _state.value = State.Failed(it.message ?: "install failed") }
            }
        } else {
            start(url, expectedBytes, installWhenDone = true)
        }
    }

    fun downloadAndInstall(url: String, expectedBytes: Long) = start(url, expectedBytes, true)

    private fun start(url: String, expectedBytes: Long, installWhenDone: Boolean) {
        if (isBusy) return
        job = scope.launch {
            val apk = File(context.cacheDir, APK_NAME)
            try {
                apk.delete()
                _state.value = State.Downloading(0, expectedBytes)

                val response = client.newCall(Request.Builder().url(url).build()).execute()
                if (!response.isSuccessful) {
                    _state.value = State.Failed("HTTP ${response.code}")
                    return@launch
                }
                val body = response.body ?: run {
                    _state.value = State.Failed("empty response")
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
                                return@launch
                            }
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            _state.value = State.Downloading(written, total)
                        }
                    }
                }

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
            } catch (e: Exception) {
                Log.w(TAG, "Update download or install failed", e)
                apk.delete()
                _state.value = State.Failed(e.message ?: "unknown error")
            }
        }
    }

    /** Streams the apk into a session and commits it. Android then shows the confirmation. */
    private suspend fun commit(apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)

        installer.openSession(sessionId).use { session ->
            session.openWrite(APK_NAME, 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }

            val intent = Intent(ACTION_INSTALL_RESULT).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)

            _state.value = State.AwaitingConfirmation
            session.commit(pending.intentSender)
        }
        // The session holds its own copy now, so the cached file is no longer needed.
        apk.delete()
    }

    /** Called when the user comes back from granting the permission. */
    fun retryAfterPermission(url: String, expectedBytes: Long) {
        if (_state.value is State.NeedsPermission) {
            _state.value = State.Idle
            downloadAndInstall(url, expectedBytes)
        }
    }

    /** Called after a successful install, so the checker can forget what it found. */
    @Volatile
    var onInstalled: (() -> Unit)? = null

    fun cancel() {
        job?.cancel()
        job = null
        File(context.cacheDir, APK_NAME).delete()
        _state.value = State.Idle
    }

    /** Clears a finished or failed run so the row goes back to resting. */
    fun acknowledge() {
        if (!isBusy) _state.value = State.Idle
    }

    /**
     * Receives the outcome of the install.
     *
     * STATUS_PENDING_USER_ACTION is the normal path, not an error: Android is asking us to show the
     * confirmation dialog, and it hands back the intent to start.
     */
    val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // Not an error. Android is handing back the confirmation dialog for us to show.
                    @Suppress("DEPRECATION")
                    val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    }
                    confirm?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }

                PackageInstaller.STATUS_SUCCESS -> {
                    // Usually nobody is left to see this: installing over ourselves kills the
                    // process. It still matters when the install did not replace us, and it clears
                    // the stored update so a completed one cannot keep prompting.
                    _state.value = State.Idle
                    onInstalled?.invoke()
                }

                PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = State.Idle

                else -> {
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    Log.w(TAG, "Install failed, status $status: $msg")
                    _state.value = State.Failed(msg ?: "status $status")
                }
            }
        }
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
    }
}
