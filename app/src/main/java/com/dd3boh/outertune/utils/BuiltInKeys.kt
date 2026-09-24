/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Log
import com.dd3boh.outertune.BuildConfig
import java.security.MessageDigest

/**
 * The credentials this app carries for the maintainer's own services: the Last.fm API account, and
 * the gist and Umami site the polls use. Everything that reads one goes through here.
 *
 * They are in the public source (services.properties), because a reproducible F-Droid build has to
 * be able to produce the APK that was published, and they were only ever lightly hidden in that APK
 * anyway. What this adds is that they only work in the app itself. A copy signed with any other
 * key, which is how a clone is made from a published APK, gets empty strings, and every feature
 * that needs one hides itself exactly as it does in a build that has none.
 *
 * It is a speed bump, not a lock. Anyone can copy the values out of the source into an app of their
 * own, and Last.fm has no way to tie a key to one app the way Google's API keys can be. If one is
 * ever abused, it is revoked and a new one shipped.
 */
object BuiltInKeys {
    private const val TAG = "BuiltInKeys"

    /**
     * SHA-256 of the release signing certificate, CN=InterTune, O=skye.dev. F-Droid's reproducible
     * build ships the APK with this same signature, so it passes too. If the app is ever signed by
     * another party the maintainer trusts (Play App Signing, say), that certificate goes here.
     */
    private val RELEASE_CERTS = setOf(
        "69c6b5fbf1220a44206e8a3a098f41c78af7f94d2fa56eb3165ca40b9199a2a7",
    )

    @Volatile
    private var genuine = false

    /**
     * Checks the installed app's signature. Called first thing in App.onCreate, so nothing can read
     * a key before it has run; until it has, every key reads as empty. Debug builds are signed with
     * each developer's own debug key and are always let through.
     */
    fun verify(context: Context) {
        genuine = BuildConfig.DEBUG || runCatching { signingCerts(context) }
            .onFailure { Log.w(TAG, "Could not read the app's signature", it) }
            .getOrDefault(emptySet())
            .any { it in RELEASE_CERTS }
        if (!genuine) Log.w(TAG, "Not signed with the InterTune release key, so the built-in keys are off")
    }

    val lastFmApiKey: String get() = revealIfGenuine(BuildConfig.LASTFM_API_KEY)
    val lastFmApiSecret: String get() = revealIfGenuine(BuildConfig.LASTFM_API_SECRET)
    val pollsUrl: String get() = revealIfGenuine(BuildConfig.POLLS_URL)
    val umamiUrl: String get() = revealIfGenuine(BuildConfig.POLLS_UMAMI_URL)
    val umamiWebsiteId: String get() = revealIfGenuine(BuildConfig.POLLS_UMAMI_WEBSITE_ID)

    private fun revealIfGenuine(masked: IntArray): String = if (genuine) reveal(masked) else ""

    /**
     * Undoes the obfuscation applied at build time. See the note in app/build.gradle.kts: it hides
     * the values from a plain `strings` sweep of the APK and from nothing else.
     */
    private fun reveal(masked: IntArray): String {
        if (masked.isEmpty()) return ""
        val mask = "InterTune".toByteArray()
        return String(ByteArray(masked.size) { i -> (masked[i] xor mask[i % mask.size].toInt()).toByte() })
    }

    /**
     * SHA-256 of every certificate the installed package is signed with. From API 28 that includes
     * the rotation history, and a history that contains the release key could only have been
     * produced by whoever holds it.
     */
    @Suppress("DEPRECATION")
    private fun signingCerts(context: Context): Set<String> {
        val pm = context.packageManager
        val signatures: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                ?: return emptySet()
            if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        } ?: return emptySet()
        val sha256 = MessageDigest.getInstance("SHA-256")
        return signatures.map { signature ->
            sha256.digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }
}
