/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.constants.LastFmActAsEndedKey

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
 */
object LastFmEnd {
    /** 1 December 2026, 00:00 UTC. */
    const val END_MS = 1_796_083_200_000L

    /**
     * Whether Last.fm is closed at [nowMs], with these [settings]: from [END_MS] on, and in a
     * debug build told to act as after it (Developer, to walk all of this before the date).
     */
    fun closed(nowMs: Long, settings: Preferences?): Boolean =
        nowMs >= END_MS ||
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
