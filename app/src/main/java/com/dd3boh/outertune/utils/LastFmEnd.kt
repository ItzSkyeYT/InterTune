/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import com.dd3boh.lastfm.LastFmException
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.constants.LastFmActAsEndedKey
import com.dd3boh.outertune.constants.LastFmRefusedAtKey

/**
 * The end of Last.fm in this app. From 1 December 2026 an API key needs Last.fm Pro, paid by
 * whoever owns the key (Last.fm's mail of 18 Nov 2026), and the owner of InterTune's will not pay
 * for it (his decision of 18 Nov 2026). From that day Last.fm refuses whatever this app sends.
 *
 * So the app does not wait to be refused. Once [closed], BuiltInKeys reads the Last.fm key and its
 * secret as empty, and every Last.fm feature takes the way a build without a key has always
 * taken: nothing is sent, so there is no error to handle and nothing to try again. The Last.fm
 * card in Settings says why (LastFmFrag), the account is forgotten at the next start
 * (Scrobbler.forgetIfClosed), and similar songs come from YouTube alone. The listener's choice of
 * where they come from is kept as it was, and so are Last.fm's lists already on the phone.
 *
 * Read at each use, so an app left open over midnight stops as well.
 *
 * Should Last.fm refuse the key before the date, the first refusal closes it the same way, at
 * once ([refusesKey], written down in [LastFmRefusedAtKey] by the Scrobbler).
 */
object LastFmEnd {
    /** 1 December 2026, 00:00 UTC. */
    const val END_MS = 1_796_083_200_000L

    /**
     * Last.fm's errors that refuse the key itself, whatever was asked (last.fm/api/errorcodes):
     * 10 "Invalid API key" and 26 "API Key Suspended". Not 4 "Authentication Failed": Last.fm
     * also gives it for a login token not approved yet, which the login screen asks with on
     * every page it loads. Not 9 either: that is the account's session, and only disconnects it.
     */
    val KEY_REFUSED = setOf(10, 26)

    /** Whether [failure] is Last.fm refusing the key itself: from then it takes nothing more from this app. */
    fun refusesKey(failure: Throwable): Boolean = (failure as? LastFmException)?.let { it.code in KEY_REFUSED } == true

    /**
     * Whether Last.fm is closed at [nowMs], with these [settings]: from [END_MS] on, once it has
     * refused the key, and in a debug build told to act as after the date (Developer, to walk all
     * of this before then).
     */
    fun closed(nowMs: Long, settings: Preferences?): Boolean =
        nowMs >= END_MS ||
            settings?.get(LastFmRefusedAtKey) != null ||
            (BuildConfig.DEBUG && settings?.get(LastFmActAsEndedKey) == true)

    /** The same now, with these [settings]: for a screen that holds them as a state. */
    fun closed(settings: Preferences?): Boolean = closed(clock(), settings)

    /** The same now, with the app's settings once App has started (BuiltInKeys.verify). */
    fun closed(): Boolean = closed(read?.invoke())

    @Volatile
    private var read: (() -> Preferences)? = null

    /** From App.onCreate, through BuiltInKeys.verify, before anything reads a key. */
    fun attach(context: Context) {
        val app = context.applicationContext
        read = { app.dataStore.current }
    }

    /** A test's own settings, or none. */
    internal fun attach(settings: SettingsSnapshot?) {
        read = settings?.let { s -> { s.current } }
    }

    /** The clock everything here reads. A test moves it to either side of [END_MS]. */
    @Volatile
    internal var clock: () -> Long = { System.currentTimeMillis() }
}
