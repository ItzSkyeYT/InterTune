/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The song caches are built once, and the activity builds nothing on its way to the first frame
 * that no screen has asked for yet.
 *
 * Each cache was built, released and built again, and the release waited for the first build to
 * read its whole folder and index: on the main thread, when the activity was injected, before
 * setContent. The activity's singletons were all built then too, the caches with them.
 */
class StartupSingletonsTest {
    private val module = File("src/main/java/com/dd3boh/outertune/di/AppModule.kt").readText()
    private val activity = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()

    private val singletons = listOf(
        "DownloadUtil", "SyncUtils", "Scrobbler", "LoudnessRepair",
        "UpdateChecker", "UpdateInstaller", "PollChecker", "ActiveCount",
    )

    @Test
    fun eachCacheIsBuiltOnce() {
        assertFalse("no cache is built only to be released", ".release()" in module)
        assertEquals("one build each, the player's and the downloads'", 2, Regex("""\bSimpleCache\(""").findAll(module).count())
    }

    @Test
    fun theActivityInjectsItsSingletonsLazily() {
        singletons.forEach { type ->
            assertTrue(type, Regex("""@Inject\s+lateinit var \w+: dagger\.Lazy<$type>""").containsMatchIn(activity))
            assertFalse("$type is not injected as itself", Regex("""lateinit var \w+: $type\b""").containsMatchIn(activity))
        }
    }

    @Test
    fun theProvidersHandEachOnWhenItIsAskedFor() {
        singletons.forEach { type ->
            assertTrue(type, Regex("""Local$type providesComputed \{""").containsMatchIn(activity))
        }
    }

    @Test
    fun theDownloadsForTheScanAreHadOnTheMainThread() {
        val scan = activity.substringBefore("scanInit(").substringAfterLast("LaunchedEffect(Unit)")
        val launch = scan.lastIndexOf("coroutineScope.launch(lmScannerCoroutine)")
        val had = scan.lastIndexOf("val downloads = downloadUtil")
        assertTrue("before the launch, not in it", had in 0 until launch)
    }
}
