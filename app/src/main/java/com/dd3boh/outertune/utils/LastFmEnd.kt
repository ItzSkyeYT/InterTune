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
 * What of Last.fm this app keeps after 1 December 2026.
 *
 * Last.fm first said (its mail of 18 Nov 2026) that from that day every API key needs Last.fm Pro,
 * paid by whoever owns the key, and the owner of InterTune's will not pay for it (his decision of
 * 18 Nov 2026). Then it changed its mind for scrobbling (its staff's announcement of 26 Nov 2026,
 * support.last.fm/t/121188): every existing key keeps track.scrobble, track.updateNowPlaying,
 * track.removeNowPlaying, auth.getSession and auth.getToken without Pro; every other method needs
 * it. Of the five calls this app makes, only track.getSimilar is in the second group.
 *
 * So from [PRO_FROM_MS] only Last.fm's similar songs stop ([similarGone]): they come from YouTube
 * alone, without a word, and the listener's choice of where they come from is kept as it was, as
 * are Last.fm's lists already on the phone. Scrobbling, now playing and connecting an account go
 * on as before, and the account is kept.
 *
 * Should Last.fm refuse the key itself one day, the first refusal of a scrobbling call closes it
 * all, at once ([refusesKey], written down in [LastFmRefusedAtKey] by the Scrobbler): BuiltInKeys
 * then reads the key and its secret as empty, every Last.fm feature takes the way a build without
 * a key has always taken, nothing more is sent, the Last.fm card in Settings says why (LastFmFrag)
 * and the account is forgotten at the next start (Scrobbler.forgetIfClosed).
 *
 * Read at each use, so an app left open over midnight stops asking for similar songs as well.
 */
object LastFmEnd {
    /** 1 December 2026, 00:00 UTC: from then every Last.fm method but the scrobbling ones needs Pro. */
    const val PRO_FROM_MS = 1_796_083_200_000L

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
     * Whether Last.fm takes nothing more from this app, with these [settings]: once it has
     * refused the key itself. Not on a date: scrobbling stays free after 1 December.
     */
    fun closed(settings: Preferences?): Boolean = settings?.get(LastFmRefusedAtKey) != null

    /** The same, with the app's settings once App has started (BuiltInKeys.verify). */
    fun closed(): Boolean = closed(read?.invoke())

    /**
     * Whether Last.fm's similar songs are out of reach at [nowMs], with these [settings]: from
     * [PRO_FROM_MS] on (track.getSimilar needs Pro), once Last.fm is [closed], and in a debug build
     * told to act as after the date (Developer, to walk it before then).
     */
    fun similarGone(nowMs: Long, settings: Preferences?): Boolean =
        nowMs >= PRO_FROM_MS ||
            closed(settings) ||
            (BuildConfig.DEBUG && settings?.get(LastFmActAsEndedKey) == true)

    /** The same now, with the app's settings once App has started. */
    fun similarGone(): Boolean = similarGone(clock(), read?.invoke())

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

    /** The clock everything here reads. A test moves it to either side of [PRO_FROM_MS]. */
    @Volatile
    internal var clock: () -> Long = { System.currentTimeMillis() }
}
