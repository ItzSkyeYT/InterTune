/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri

/**
 * "Open links in InterTune" (Settings > Library and content): whether this install takes the
 * links the manifest gives to the activity [COMPONENT]: the share page's, YouTube's and YouTube Music's.
 *
 * An app cannot flip Android's own "Open supported links", but it can enable and disable its own
 * components, and that is the same thing for Android: a link goes to an app that has a filter for
 * it, and a disabled activity has none. So with the release app, a Preview and a Debug build all
 * installed, each can be told to leave the links to the others.
 *
 * The component is a small activity of its own (OpenLinksActivity), which hands the link to
 * MainActivity, and not an alias of MainActivity: Android finishes every activity that is named
 * after a component that gets disabled, and one started under an alias is named after the alias,
 * so the switch closed the app when it was turned off from inside an app that a link had started
 * (seen on an emulator, Android 17: "disabled-package"). Started through this one, MainActivity
 * is named after itself, and what is disabled has already finished.
 *
 * The state is the system's, not the app's: it lives in the package's settings, survives a
 * restart and an update, and is read back from there, so the switch can only show what is true.
 * The manifest enables the activity, so a build nobody has asked is on ([isOn] counts DEFAULT as on).
 */
object OpenLinks {
    private const val TAG = "OpenLinks"

    /** The activity that carries the link filters, as the manifest names it (OpenLinksTest holds the two together). */
    const val COMPONENT = "com.dd3boh.outertune.OpenLinksActivity"

    /** Whether a component state means the links come here: the manifest enables the activity, so DEFAULT is on. */
    fun isOn(state: Int): Boolean =
        state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT || state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /** The state to write for [on]: written out, so that the switch is a choice and not the absence of one. */
    fun stateFor(on: Boolean): Int =
        if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    private fun component(context: Context) = ComponentName(context.packageName, COMPONENT)

    /**
     * Whether Android's own "Open supported links" is on for this app (Settings > Apps > this app >
     * Open by default), from Android 12. It is read and never written: an app cannot flip it, only
     * the person can, on Android's page ([openAndroidSettings]). When it is off no link opens in the
     * app whatever the switch here says, so the row shows it. Before Android 12 there is no such
     * setting, and a reading that fails counts as on.
     */
    fun androidAllows(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return runCatching {
            context.getSystemService(DomainVerificationManager::class.java)
                ?.getDomainVerificationUserState(context.packageName)?.isLinkHandlingAllowed
        }.onFailure { Log.w(TAG, "Could not read Android's link setting", it) }.getOrNull() ?: true
    }

    /** Whether links open in this app: when it takes them and Android lets it. What the row's switch shows. */
    fun opens(ours: Boolean, androidAllows: Boolean): Boolean = ours && androidAllows

    /** The page of Android's settings for an app's links: Open by default from Android 12, the app's own page before. */
    @SuppressLint("InlinedApi")   // a string, and only chosen from Android 12 on
    fun androidPageAction(sdk: Int): String =
        if (sdk >= Build.VERSION_CODES.S) Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS else Settings.ACTION_APPLICATION_DETAILS_SETTINGS

    /** Opens Android's own page for this app's links, or the app's page where there is none. */
    fun openAndroidSettings(context: Context) {
        val app = "package:${context.packageName}".toUri()
        val opened = runCatching { context.startActivity(Intent(androidPageAction(Build.VERSION.SDK_INT), app)) }.isSuccess
        if (!opened) {
            runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app)) }
                .onFailure { Log.w(TAG, "Could not open Android's settings for the app", it) }
        }
    }

    /** Whether the links come to this install now. A reading that fails is on: that is what the manifest says. */
    fun isOn(context: Context): Boolean =
        runCatching { isOn(context.packageManager.getComponentEnabledSetting(component(context))) }
            .onFailure { Log.w(TAG, "Could not read the link setting", it) }
            .getOrDefault(true)

    /**
     * Turns the links on or off, without closing or restarting the app (DONT_KILL_APP), and
     * returns whether they are on now, as the system says, so that a write that did not go
     * through shows as it is.
     */
    fun set(context: Context, on: Boolean): Boolean {
        runCatching {
            context.packageManager.setComponentEnabledSetting(component(context), stateFor(on), PackageManager.DONT_KILL_APP)
        }.onFailure { Log.w(TAG, "Could not change the link setting", it) }
        return isOn(context)
    }
}
