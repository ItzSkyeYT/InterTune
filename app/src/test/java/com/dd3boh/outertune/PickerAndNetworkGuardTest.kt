/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Two crashes of 0.12.9 that ended the app on some devices: a picker asked for where no app can
 * pick (#120, #121), and a network callback a phone refused as the app opened (#123). Read from the
 * source, as the guards live in the platform calls.
 */
class PickerAndNetworkGuardTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    @Test
    fun `a picker with no app to answer it is said, not a crash, and the screen gets a cancel`() {
        val main = File(sources, "MainActivity.kt").readText()
        val guard = main.substringAfter("override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {").substringBefore("\n    }\n")
        assertTrue(guard, "super.startActivityForResult(intent, requestCode, options)" in guard)
        assertTrue(guard, "catch (e: ActivityNotFoundException)" in guard)
        assertTrue(guard, "Toaster.show(this, R.string.no_app_to_pick" in guard)
        assertTrue(guard, "activityResultRegistry.dispatchResult(requestCode, RESULT_CANCELED, null)" in guard)
    }

    @Test
    fun `a phone that refuses the network callback leaves the app running`() {
        val observer = File(sources, "utils/NetworkConnectivityObserver.kt").readText()
        assertTrue("context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)" in observer)
        assertTrue("runCatching { connectivityManager.registerNetworkCallback(request, networkCallback) }" in observer)
        assertTrue("runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }" in observer)
        // Never called bare, where a refusal would end the app.
        val bare = Regex("""^\s*connectivityManager\.(registerNetworkCallback|unregisterNetworkCallback)\(""", RegexOption.MULTILINE)
        assertFalse(observer, bare.containsMatchIn(observer))
    }
}
