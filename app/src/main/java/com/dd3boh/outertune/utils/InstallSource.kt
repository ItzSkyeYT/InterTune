/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Where this copy of the app came from, so it can point at the right place for the next one.
 *
 * The update checker reads GitHub releases and, until now, did so regardless of how the app was
 * installed. For anyone who got it from F-Droid that is the wrong answer twice over: they would be
 * told to sideload an apk by a copy of the app their store is already responsible for updating,
 * and the store would then refuse to update over a differently signed install.
 */
enum class InstallSource {
    /** F-Droid, or one of the other clients that installs from an F-Droid repository. */
    F_DROID,

    /** A sideloaded apk, Obtainium, a browser download, an unknown store, or a debug build. */
    OTHER,
}

/**
 * Clients that install from F-Droid repositories.
 *
 * Obtainium is deliberately absent. It tracks GitHub releases directly, so its users want exactly
 * the apk the update checker already offers them and would be worse off being sent to F-Droid.
 */
private val FDROID_INSTALLERS = setOf(
    "org.fdroid.fdroid",
    "org.fdroid.basic",
    "org.fdroid.fdroid.privileged",
    "com.looker.droidify",
    "com.machiav3lli.fdroid",
)

fun Context.installSource(): InstallSource {
    val installer = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            packageManager.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstallerPackageName(packageName)
        }
    }.getOrNull()

    Log.i("InstallSource", "Installed by: ${installer ?: "nobody, so sideloaded"}")
    return if (installer in FDROID_INSTALLERS) InstallSource.F_DROID else InstallSource.OTHER
}

/** The page F-Droid clients open to the app's own entry. */
fun fdroidPageUrl(packageName: String) = "https://f-droid.org/packages/$packageName/"
