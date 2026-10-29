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
 * installed. For anyone who got it from F-Droid that is the wrong answer: they would be told to
 * sideload an apk by a copy of the app their store is already responsible for updating, outside
 * the checks and timing they chose F-Droid for.
 */
enum class InstallSource {
    /** F-Droid, or one of the other clients that installs from an F-Droid repository. */
    F_DROID,

    /** A sideloaded apk, Obtainium, a browser download, an unknown store, or a debug build. */
    OTHER,
}

/**
 * Clients that install from F-Droid repositories, with the words a report names each one by.
 *
 * Obtainium is deliberately absent. It tracks GitHub releases directly, so its users want exactly
 * the apk the update checker already offers them and would be worse off being sent to F-Droid.
 */
private val FDROID_CLIENTS = mapOf(
    "org.fdroid.fdroid" to "F-Droid",
    "org.fdroid.basic" to "F-Droid Basic",
    "org.fdroid.fdroid.privileged" to "F-Droid's privileged extension",
    "com.looker.droidify" to "Droid-ify, an F-Droid client",
    "com.machiav3lli.fdroid" to "Neo Store, an F-Droid client",
)

private val FDROID_INSTALLERS = FDROID_CLIENTS.keys

/** The other installers a report names. Orion Store's id is the one in its own build files. */
private val NAMED_INSTALLERS = mapOf(
    "dev.imranr.obtainium" to "Obtainium",
    "com.android.vending" to "Google Play",
    "com.orion.store" to "Orion Store",
)

/**
 * Who Android keeps as this copy's installer and, from Android 11, which app started the install.
 * Null when Android would not say.
 */
private fun Context.installers(): Pair<String?, String?>? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val info = packageManager.getInstallSourceInfo(packageName)
        info.installingPackageName to info.initiatingPackageName
    } else {
        @Suppress("DEPRECATION")
        packageManager.getInstallerPackageName(packageName) to null
    }
}.getOrNull()

fun Context.installSource(): InstallSource {
    val installer = installers()?.first

    Log.i("InstallSource", "Installed by: ${installer ?: "nobody, so sideloaded"}")
    return if (installer in FDROID_INSTALLERS) InstallSource.F_DROID else InstallSource.OTHER
}

/**
 * Where this copy came from, for the line every report has after the phone's: F-Droid's, GitHub's
 * and the updater's copies are the same app, and nothing else in a report tells them apart.
 * "unknown" when Android would not say. It never throws: the crash report asks as the process
 * goes down.
 */
fun Context.installedFrom(): String = runCatching {
    installers()?.let { (installer, initiator) -> installedFrom(installer, initiator, packageName) }
}.getOrNull() ?: "unknown"

/**
 * [installer] in plain words, then the app that started the install when that is another one: an
 * install through adb or Shizuku can name any app as the installer, Google Play for one. The
 * phone's own installer is left out there, since it does the work for whoever asks.
 */
internal fun installedFrom(installer: String?, initiator: String?, ownPackage: String): String {
    val from = if (installer == null || phonesInstaller(installer)) "a file, installed by hand" else nameOf(installer, ownPackage)
    if (initiator == null || initiator == installer || phonesInstaller(initiator)) return from
    return "$from, started by ${nameOf(initiator, ownPackage)}"
}

/** The phone's own package installer, Android's or its maker's: the one that opens an apk file. */
private fun phonesInstaller(pkg: String) = pkg.endsWith(".packageinstaller")

private fun nameOf(pkg: String, ownPackage: String): String =
    if (pkg == ownPackage) "InterTune's updater, from GitHub"
    else FDROID_CLIENTS[pkg] ?: NAMED_INSTALLERS[pkg] ?: "another app ($pkg)"

/** The page F-Droid clients open to the app's own entry. */
fun fdroidPageUrl(packageName: String) = "https://f-droid.org/packages/$packageName/"
