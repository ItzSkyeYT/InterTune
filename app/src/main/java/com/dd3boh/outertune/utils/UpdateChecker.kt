/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.constants.DismissedUpdateCodeKey
import com.dd3boh.outertune.constants.LastUpdateCheckKey
import com.dd3boh.outertune.constants.LastVersionKey
import com.dd3boh.outertune.constants.UpdateAvailableKey
import com.dd3boh.outertune.constants.UpdateCheckEnabledKey
import com.dd3boh.outertune.extensions.isInternetConnected
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells the user when a newer InterTune release exists.
 *
 * There is no in-app installer here on purpose. This only ever reads a small JSON document and
 * points at the release page: installing an APK needs REQUEST_INSTALL_PACKAGES plus the user
 * granting "install unknown apps", which is a lot of trust to ask for a convenience.
 *
 * Opt in. Nothing is fetched until the user turns it on, because a version check is a network
 * request to a third party that reveals roughly when the app is used.
 */
@Singleton
class UpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class Update(
        val versionCode: Int,
        val versionName: String,
        val releaseUrl: String,
    )

    private val _available = MutableStateFlow<Update?>(null)

    /** The newer release, if there is one the user has not already dismissed. */
    val available: StateFlow<Update?> = _available.asStateFlow()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Looks for a newer release.
     *
     * [force] skips the rate limit, for the manual "check now" button. The automatic call on app
     * open does not, because the GitHub API allows 60 unauthenticated requests an hour per IP and
     * everyone behind one carrier NAT shares that budget.
     */
    suspend fun check(force: Boolean = false): Update? = withContext(Dispatchers.IO) {
        val store = context.dataStore
        if (!force && !store.get(UpdateCheckEnabledKey, false)) return@withContext null
        if (!context.isInternetConnected()) return@withContext null

        val last = store.get(LastUpdateCheckKey, 0L)
        val now = System.currentTimeMillis()

        // The interval is stored on disk but the result it protects is only in memory, so the two
        // disagree after the process is killed: a cold start inside the interval skipped the
        // request and handed back a null _available for an update the app already knows about.
        // UpdateAvailableKey (persisted, and what the search-bar badge reads) still said one
        // existed, so the badge advertised an update the Updates screen would not show and the
        // "skip this version" control could not be reached, for up to MIN_CHECK_INTERVAL_MS.
        //
        // So skip the request only when it would tell us nothing new: either the answer is still
        // in memory, or the persisted flag says there was no update to hold. That leaves the rate
        // limit fully in force for the ordinary case of nothing pending, and costs at most one
        // request per cold start while an undismissed update is outstanding. It also lets the
        // badge clear itself on the first open after the user actually updates, instead of
        // sitting there stale until the interval elapses.
        val knownAvailable = store.get(UpdateAvailableKey, false)
        if (!force && now - last < MIN_CHECK_INTERVAL_MS &&
            (_available.value != null || !knownAvailable)
        ) {
            return@withContext _available.value
        }

        val body = runCatching {
            client.newCall(
                Request.Builder()
                    .url(RELEASES_URL)
                    .header("Accept", "application/vnd.github+json")
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                response.body?.string()
            }
        }.getOrNull() ?: run {
            Log.i(TAG, "Update check failed, leaving the last check time alone so it retries")
            return@withContext null
        }

        // Only stamp the clock on a request that actually answered. Stamping on failure would make
        // a single flaky moment suppress checks for the whole interval.
        context.dataStore.edit { it[LastUpdateCheckKey] = now }

        val update = runCatching { parse(body) }.getOrNull() ?: return@withContext null

        if (update.versionCode <= BuildConfig.VERSION_CODE) {
            clearAvailable()
            return@withContext null
        }

        // Dismissing is per version, so a newer one still gets through.
        if (update.versionCode == store.get(DismissedUpdateCodeKey, -1)) {
            clearAvailable()
            return@withContext null
        }

        // UpdateAvailableKey and LastVersionKey already exist and already drive the badge on the
        // search bar. Upstream deleted the checker that used to set them and left the UI behind,
        // so this fills the gap rather than adding a parallel mechanism.
        context.dataStore.edit {
            it[UpdateAvailableKey] = true
            it[LastVersionKey] = update.versionName
        }

        Log.i(TAG, "Update available: ${update.versionName} (${update.versionCode})")
        _available.value = update
        update
    }

    /** Stops this particular version being raised again. A later one still will be. */
    suspend fun dismiss(versionCode: Int) {
        context.dataStore.edit {
            it[DismissedUpdateCodeKey] = versionCode
            it[UpdateAvailableKey] = false
        }
        _available.value = null
    }

    private suspend fun clearAvailable() {
        context.dataStore.edit { it[UpdateAvailableKey] = false }
        _available.value = null
    }

    /**
     * Reads the version out of the release JSON.
     *
     * The versionCode comes from the asset filename rather than the tag, because the tag is a
     * human string ("v0.10.1-intertune.8", "v0.10.2") that does not sort or compare. The build
     * writes the code into the filename, so it is the one number both sides agree on.
     */
    private fun parse(json: String): Update? {
        val release = JSONObject(json)
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null

        val assets = release.optJSONArray("assets") ?: return null
        var code = -1
        for (i in 0 until assets.length()) {
            val name = assets.getJSONObject(i).optString("name")
            val match = ASSET_VERSION_CODE.find(name) ?: continue
            code = maxOf(code, match.groupValues[1].toIntOrNull() ?: continue)
        }
        if (code < 0) return null

        return Update(
            versionCode = code,
            versionName = release.optString("tag_name").removePrefix("v"),
            releaseUrl = release.optString("html_url"),
        )
    }

    private companion object {
        private const val TAG = "UpdateChecker"

        private const val RELEASES_URL =
            "https://api.github.com/repos/ItzSkyeYT/InterTune/releases/latest"

        /** `InterTune-0.10.2-core-release-79.apk` -> 79 */
        private val ASSET_VERSION_CODE = Regex("""-release-(\d+)\.apk$""")

        /**
         * Six hours. Frequent enough that a release is noticed the same day, rare enough that the
         * unauthenticated GitHub budget is never a concern even on shared addresses.
         */
        private const val MIN_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}
