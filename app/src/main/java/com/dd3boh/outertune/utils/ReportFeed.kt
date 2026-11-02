/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.datastore.preferences.core.Preferences
import com.dd3boh.outertune.constants.Unreleased
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * What feeds [ReportTrail] from the app as a whole, started once by [com.dd3boh.outertune.App]: the
 * app going to the background and coming back, and the settings that change. The rest has a hook of
 * its own where it happens: screens in MainActivity, songs and errors in MusicService, the network
 * in [NetworkConnectivityObserver].
 *
 * Settings are not hooked at their screens. The store they all write to is followed, and a change
 * between two readings is told by the name and the value [ReportSettings] gives it, so a setting
 * that is not on its list is never seen, whatever it holds, and a setting changed from somewhere new
 * is caught without anyone remembering to add a call.
 */
object ReportFeed {
    /** The settings as last read, for the crash handler, which must not read a file while the process is going down. */
    @Volatile
    var latest: Preferences? = null
        private set

    @OptIn(DelicateCoroutinesApi::class)
    fun start(app: Application) {
        if (!Unreleased.REPORT_PLUS) return
        val trail = ReportTrail.thisRun
        trail.enabled = true
        app.registerActivityLifecycleCallbacks(Callbacks(Foreground(trail)))
        GlobalScope.launch(Dispatchers.Default) {
            var before: Preferences? = null
            app.dataStore.data.collect { now ->
                latest = now
                before?.let { was ->
                    runCatching {
                        ReportSettings.changes(was, now).forEach { (label, value) -> trail.setting(label, value) }
                        val signedIn = ReportSettings.signedIn(now)
                        if (ReportSettings.signedIn(was) != signedIn) trail.signedIn(signedIn)
                    }
                }
                before = now
            }
        }
    }

    /**
     * Whether the app is in front, from the activities started and stopped. A rotation stops an
     * activity and starts it again at once, which is not the app leaving, and a start after one is
     * not a return.
     */
    class Foreground(private val trail: ReportTrail) {
        private var started = 0
        private var opened = false
        private var turning = false

        @Synchronized
        fun started() {
            if (started++ > 0) return
            if (turning) {
                turning = false
                return
            }
            trail.app(if (opened) ReportTrail.App.BACK_IN_FRONT else ReportTrail.App.OPENED)
            opened = true
        }

        @Synchronized
        fun stopped(changingConfigurations: Boolean) {
            if (started > 0) started--
            if (started > 0) return
            if (changingConfigurations) turning = true else trail.app(ReportTrail.App.IN_BACKGROUND)
        }
    }

    private class Callbacks(private val foreground: Foreground) : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) = foreground.started()
        override fun onActivityStopped(activity: Activity) = foreground.stopped(activity.isChangingConfigurations)
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
