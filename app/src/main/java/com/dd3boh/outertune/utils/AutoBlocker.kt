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
 * is refused the same way. The only way through is turning Auto Blocker off for the install.
 */
object AutoBlocker {
    private const val ACTION_SETTINGS = "com.samsung.android.rampart.action.MAIN_SETTING_ACTIVITY"

    /** A Samsung phone on One UI 6 or later, where Auto Blocker exists, whether it is on or off. */
    fun likely(): Boolean =
        Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** Opens Auto Blocker's own page, or the security settings, or Settings, whichever opens. */
    fun open(context: Context) {
        val tries = listOf(
            Intent(ACTION_SETTINGS),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in tries) {
            val opened = runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (opened) return
        }
    }
}
