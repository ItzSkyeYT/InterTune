/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.dd3boh.outertune.BuildConfig
import java.io.File

/**
 * Samsung's Auto Blocker ("Rampart" inside One UI 6 and later) refuses every install that does not
 * come from Google Play or the Galaxy Store, the in-app updater's included, even with "install
 * unknown apps" allowed. Android's prompt still comes up and the person can tap Update; the install
 * is then dropped and the app told "aborted", as for a cancel, with the reason "Self update is
 * blocked by unknown source package" (seen on a Galaxy S25 Ultra, 19 Oct 2026). UpdateInstaller
 * reads that reason, and says what to do.
 *
 * Whether it is on cannot be asked beforehand: its switch is a hidden settings key, which apps
 * may not read since Android 12, and Auto Blocker's own providers need Samsung's signature.
 *
 * Downloading the apk from GitHub does not get round it either, since a browser or a file manager
 * is refused the same way. The only way through is turning Auto Blocker off for the install, so the
 * app sends the person to its page, starts the install again by itself when they are back
 * ([AutoBlockerReturn]), and says afterwards that it can go back on.
 */
object AutoBlocker {
    internal const val FAKE_SAMSUNG_FILE = "fake-samsung"

    private const val ACTION_SETTINGS = "com.samsung.android.rampart.action.MAIN_SETTING_ACTIVITY"

    /** Debug builds only: see [pretendFrom]. Never true in a release. */
    @Volatile
    private var pretending = false

    /** A Samsung phone on One UI 6 or later, where Auto Blocker exists, whether it is on or off. */
    fun likely(): Boolean =
        pretending || (
                Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)

    /**
     * Debug builds only: with the file [FAKE_SAMSUNG_FILE] in filesDir this phone counts as a Samsung
     * with Auto Blocker, so what the app says and does about it can be seen on an emulator. Read
     * when the app starts. It does not make the install fail: that is the file of UpdateInstaller
     * that answers as Auto Blocker would, which can be taken away again to stand for turning it off.
     */
    fun pretendFrom(context: Context) {
        pretending = BuildConfig.DEBUG && File(context.filesDir, FAKE_SAMSUNG_FILE).exists()
    }

    /**
     * Opens Auto Blocker's own page, or the security settings, or Settings, whichever opens, and
     * says whether one did.
     */
    fun open(context: Context): Boolean {
        val tries = listOf(
            Intent(ACTION_SETTINGS),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in tries) {
            val opened = runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (opened) return true
        }
        return false
    }

    /**
     * Opens Auto Blocker for someone whose update it stopped, and asks for the install to start
     * again by itself when they are back in InterTune ([AutoBlockerReturn]). Asked only when a page
     * opened: with none, there is nowhere to come back from.
     */
    fun openForUpdate(context: Context) {
        if (open(context)) AutoBlockerReturn.ask(context)
    }
}
