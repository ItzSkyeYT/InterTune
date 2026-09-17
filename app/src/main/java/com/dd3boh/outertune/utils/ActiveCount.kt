/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.constants.Polls
import com.dd3boh.outertune.constants.UsageCountEnabledKey
import com.dd3boh.outertune.constants.UsageCountIdKey
import com.dd3boh.outertune.constants.UsageCountLastDayKey
import com.dd3boh.outertune.constants.UsageCountPeriodKey
import com.dd3boh.outertune.extensions.isInternetConnected
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How many people use this, and how many of them came from F-Droid.
 *
 * That second half is the whole reason this exists. F-Droid publishes no per-app download figures
 * to anyone, deliberately and permanently: it keeps no account of what you install, the counts it
 * kept until 2015 were dropped, and what it publishes now is website traffic with no app in it.
 * GitHub does give release download counts, but they are downloads rather than people and the
 * in-app updater inflates them. So the only thing that can ever count an F-Droid listener is the
 * app they are holding, and only if they agree to it.
 *
 * WHAT LEAVES THE DEVICE, in full, once a day at most, and only after somebody has said yes:
 *  - a name that is random, belongs to this calendar month, and is replaced by an unrelated one
 *    when the month turns. See [ActivePeriod] for why that shape and not a permanent install id.
 *  - whether the app was installed by an F-Droid client or by anything else
 *  - the app version, and the Android API level as a bare number
 * No account, no hardware identifier, nothing about the library, nothing about what was played.
 *
 * Kept apart from [PollChecker] on purpose, in its own event under its own switch. A poll answer
 * carries no id at all and its modal says so, and folding the two together would quietly make that
 * sentence false. They share only the endpoint and the User-Agent.
 */
@Singleton
class ActiveCount @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Says "still here", or far more often does nothing at all.
     *
     * Safe to call on every launch: the day is checked before anything else happens, so the usual
     * cost of this is one blocking preference read on a background thread.
     */
    suspend fun ping() = withContext(Dispatchers.IO) {
        if (!Polls.isConfigured) return@withContext
        if (!context.dataStore.get(UsageCountEnabledKey, false)) return@withContext

        val ping = ActivePeriod.decide(
            nowMillis = System.currentTimeMillis(),
            zone = ZoneId.systemDefault(),
            storedId = context.dataStore.get(UsageCountIdKey, ""),
            storedPeriod = context.dataStore.get(UsageCountPeriodKey, ""),
            lastDay = context.dataStore.get(UsageCountLastDayKey, ""),
            freshId = { UUID.randomUUID().toString() },
        ) ?: return@withContext

        // Checked after the day, not before it: an offline launch should not be the thing that
        // decides whether today counts, and this way a day missed for want of signal is simply
        // tried again on the next launch.
        if (!context.isInternetConnected()) return@withContext

        val payload = JSONObject()
            .put("website", Polls.UMAMI_WEBSITE_ID)
            .put("hostname", Polls.UMAMI_HOSTNAME)
            .put("name", UMAMI_EVENT)
            // The one field that makes this a count of people rather than of addresses. Umami
            // otherwise derives a visitor from a hash of User-Agent and address, and this app
            // pins its User-Agent to a constant, so without this the figure is unique addresses
            // per day: carrier NAT folds strangers together and a new address splits one person
            // in two.
            .put("id", ping.id)
            .put(
                "data", JSONObject()
                    // Spelled for the dashboard rather than for the enum: "f_droid" is what
                    // the constant is called and "fdroid" is what the column should read.
                    .put(
                        "source",
                        when (context.installSource()) {
                            InstallSource.F_DROID -> "fdroid"
                            InstallSource.OTHER -> "other"
                        }
                    )
                    .put("version", BuildConfig.VERSION_NAME)
                    .put("android", Build.VERSION.SDK_INT)
            )

        val envelope = JSONObject().put("payload", payload).put("type", "event")

        runCatching {
            client.newCall(
                Request.Builder()
                    .url("${Polls.UMAMI_URL}/api/send")
                    // Must read as a browser. Umami runs bot detection over this and drops what
                    // does not, while still answering 200, which is how the poll sender spent a
                    // week reporting success to an empty dashboard.
                    .header("User-Agent", USER_AGENT)
                    .post(envelope.toString().toRequestBody(JSON))
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Umami refused the ping: HTTP ${response.code}")
                    return@use false
                }
                true
            }
        }.onSuccess { landed ->
            // Written only once the server has taken it. A day that failed is a day worth trying
            // again on the next launch, and a name that was never sent is not worth keeping.
            if (landed == true) {
                context.dataStore.edit {
                    it[UsageCountIdKey] = ping.id
                    it[UsageCountPeriodKey] = ping.period
                    it[UsageCountLastDayKey] = ping.day
                }
                Log.i(TAG, "Counted ${ping.day}${if (ping.rotated) ", new name for ${ping.period}" else ""}")
            }
        }.onFailure {
            Log.w(TAG, "Ping could not be sent, will try again next launch: $it")
        }
        Unit
    }

    /**
     * Forgets the current name, so the next launch mints a different one.
     *
     * What "stop counting me" has to mean if it is to mean anything: turning the switch off stops
     * new pings, and this makes sure that turning it back on later is a new person rather than the
     * same one resumed.
     */
    suspend fun forget() = withContext(Dispatchers.IO) {
        context.dataStore.edit {
            it.remove(UsageCountIdKey)
            it.remove(UsageCountPeriodKey)
            it.remove(UsageCountLastDayKey)
        }
        Log.i(TAG, "Name forgotten")
    }

    private companion object {
        private const val TAG = "ActiveCount"

        /** Its own event, so the dashboard can separate people from poll answers. */
        private const val UMAMI_EVENT = "active_day"

        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Same string and same reason as [PollChecker]: it has to look like a browser to count. */
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
