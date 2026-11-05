/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.constants.AutoBlockerBlockedByKey
import com.dd3boh.outertune.constants.AutoBlockerReturnAtKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * How long a tap on Open Auto Blocker is remembered: time enough to go through Settings and
 * come back, and no more. Someone back after longer has long since done something else, and an
 * install that starts by itself then would be a surprise.
 */
internal const val AUTO_BLOCKER_RETURN_MS = 30 * 60_000L

/** What coming back to InterTune means for a tap on Open Auto Blocker. */
internal enum class ReturnVerdict {
    /** Nothing was asked for. */
    NOTHING_OWED,

    /** Asked for, and the person has not been away since: the tap and the way out are one moment. */
    NOT_AWAY_YET,

    /** Back in time: the install starts again, once. */
    INSTALL_AGAIN,

    /** Back too late, or the clock was put back since: forgotten, and nothing starts. */
    LAPSED,
}

/**
 * What a resume of the app means. [askedAt] is when Open Auto Blocker was tapped, on the wall
 * clock because it has to outlive the process, or null when it was not; [away] whether the person
 * has left InterTune since.
 */
internal fun returnVerdict(askedAt: Long?, now: Long, away: Boolean): ReturnVerdict = when {
    askedAt == null -> ReturnVerdict.NOTHING_OWED
    now < askedAt || now - askedAt > AUTO_BLOCKER_RETURN_MS -> ReturnVerdict.LAPSED
    !away -> ReturnVerdict.NOT_AWAY_YET
    else -> ReturnVerdict.INSTALL_AGAIN
}

/**
 * Whether the build now running owes a word about an install that an older build saw blocked: it
 * is what the person updated to. Not the build that saw it, and not an older one if the app was
 * put back.
 */
internal fun reminderOwed(blockedBy: Int?, running: Int): Boolean =
    blockedBy != null && blockedBy in 1 until running

/**
 * Whether the person has left InterTune since they tapped Open Auto Blocker. The tap alone does
 * not make them gone: the screen is handed to Settings a moment after it, and a resume in that
 * moment would start the install while they are still on their way out. A process that starts
 * with a tap remembered never saw the tap, so by then they have been away.
 */
internal class Presence {
    @Volatile
    private var stayed = false

    val away: Boolean get() = !stayed

    /** The tap on Open Auto Blocker. */
    fun asked() {
        stayed = true
    }

    /** InterTune left the front. */
    fun left() {
        stayed = false
    }
}

/**
 * What the app does about Samsung's Auto Blocker beyond saying that it stopped an install
 * ([AutoBlocker]): two promises, each kept in the settings and not in memory, since Android ends
 * the app at will while the person is in Settings, and an update of the app ends it for certain.
 *
 * The install starts again by itself. The person is told to turn Auto Blocker off, so a tap on
 * Open Auto Blocker is remembered ([ask]), and the next time InterTune is in front after having
 * been left, the install is started again ([take]). The apk is kept from the first try, so
 * Android's prompt comes at once. Once for each tap: the tap is forgotten as it is used, and if
 * Auto Blocker stops the install again the message is back and the next tap asks again.
 *
 * The person is told afterwards that it can go back on. The build that sees Auto Blocker stop an
 * install notes its own version ([noteBlock]); the build that replaces it, on its first start, is
 * owed a word ([reminderOwed]) and clears the note as it says it ([clearNote]).
 *
 * The functions on a store are the whole of it and are tested with a store of their own; the
 * functions on a context are the thin way in from the app.
 */
object AutoBlockerReturn {
    // Its own, since the writes are for the app to finish and no screen's scope may cut them short.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val presence = Presence()

    /** Whether the person has left InterTune since the tap. */
    val away: Boolean get() = presence.away

    /**
     * The tap on Open Auto Blocker, after a page of it opened: the install is to start again when
     * the person is back. Written away from the caller, which is about to hand the screen over.
     */
    fun ask(context: Context) {
        presence.asked()
        val store = context.dataStore
        scope.launch { ask(store, System.currentTimeMillis()) }
    }

    /** InterTune left the front: the next time it is in front the person is back. */
    fun left() {
        presence.left()
    }

    /** Auto Blocker stopped an install of this build, to be said once the build is replaced. */
    fun noteBlock(context: Context, versionCode: Int) {
        val store = context.dataStore
        scope.launch { noteBlock(store, versionCode) }
    }

    /** The note is said, and goes. */
    fun clearNote(context: Context) {
        val store = context.dataStore
        scope.launch { clearNote(store) }
    }

    suspend fun ask(store: DataStore<Preferences>, now: Long) {
        store.edit { it[AutoBlockerReturnAtKey] = now }
    }

    /**
     * What a resume of the app is owed, and the tap forgotten when it is used or has lapsed. The
     * read and the clearing are one edit of the store, which takes them in turn: two resumes in
     * a row cannot both start the install.
     */
    internal suspend fun take(store: DataStore<Preferences>, now: Long, away: Boolean): ReturnVerdict {
        // Nearly every resume has nothing owed, and a look costs nothing where an edit does not.
        if (store.data.first()[AutoBlockerReturnAtKey] == null) return ReturnVerdict.NOTHING_OWED
        var verdict = ReturnVerdict.NOTHING_OWED
        store.edit { prefs ->
            verdict = returnVerdict(prefs[AutoBlockerReturnAtKey], now, away)
            if (verdict == ReturnVerdict.INSTALL_AGAIN || verdict == ReturnVerdict.LAPSED) {
                prefs.remove(AutoBlockerReturnAtKey)
            }
        }
        return verdict
    }

    suspend fun noteBlock(store: DataStore<Preferences>, versionCode: Int) {
        store.edit { it[AutoBlockerBlockedByKey] = versionCode }
    }

    suspend fun clearNote(store: DataStore<Preferences>) {
        store.edit { it.remove(AutoBlockerBlockedByKey) }
    }
}
