/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import android.webkit.WebView
import androidx.annotation.MainThread
import androidx.core.net.toUri
import androidx.webkit.WebViewCompat

/**
 * Whether this phone can make a web view at all (#221).
 *
 * The sign-in pages are web views, and a phone whose web view cannot start threw from WebView's
 * constructor while the page was being drawn, which closed the app: an ASUS on Android 7.1 whose
 * Chrome, the web view there, was a 32-bit build while the app runs 64-bit ("libmonochrome.so is
 * 32-bit instead of 64-bit"). Those pages ask here first and say what is wrong instead.
 */
object WebViewCheck {
    private const val TAG = "WebViewCheck"

    /** Known to work in this run: asked once. A failure is asked again, in case the phone was fixed meanwhile. */
    @Volatile
    private var works = false

    /** Makes and destroys a web view the first time; false, with the reason in the log, when it cannot be made. */
    @MainThread
    fun works(context: Context): Boolean {
        if (works) return true
        works = runCatching { WebView(context).destroy() }
            .onFailure { Log.w(TAG, "This phone cannot start a web view", it) }
            .isSuccess
        // Before Android 13 the first WebView of the process puts the app's words back in the
        // phone's language, even one made only to see that it can be (AppLanguage.afterWebView).
        AppLanguage.afterWebView(context)
        return works
    }

    /**
     * The package that gives this phone its web view (Chrome on Android 7 to 9 when it is
     * enabled, Android System WebView otherwise), or Android System WebView when it cannot be told.
     */
    fun provider(context: Context): String =
        runCatching { WebViewCompat.getCurrentWebViewPackage(context)?.packageName }.getOrNull()
            ?: SYSTEM_WEBVIEW

    /** The Play Store's page for the web view, or its page on the web without a store app. */
    fun openStore(context: Context) {
        val pkg = provider(context)
        val store = Intent(Intent.ACTION_VIEW, "market://details?id=$pkg".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val web = Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$pkg".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(store)
        } catch (_: ActivityNotFoundException) {
            runCatching { context.startActivity(web) }.onFailure { Log.w(TAG, "Nothing can open the store's page", it) }
        }
    }

    const val SYSTEM_WEBVIEW = "com.google.android.webview"
}
