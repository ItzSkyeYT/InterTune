/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The download service's scheduler needs its job service in the manifest. When downloads have to
 * wait, PlatformScheduler schedules a job on PlatformSchedulerService, and JobScheduler refuses a
 * service the app does not declare: in 0.11.5 to 0.12.1 that crashed ExoDownloadService as it
 * started (issue #43, "No such service ComponentInfo{dev.skye.intertune/R2.a}").
 */
class DownloadSchedulerDeclaredTest {

    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val service = File("src/main/java/com/dd3boh/outertune/playback/ExoDownloadService.kt").readText()

    @Test
    fun `the scheduler the download service uses has its job service declared`() {
        if ("PlatformScheduler(" !in service) return
        val name = "android:name=\"androidx.media3.exoplayer.scheduler.PlatformScheduler\$PlatformSchedulerService\""
        val at = manifest.indexOf(name)
        assertTrue("PlatformSchedulerService is declared in the manifest", at >= 0)
        val element = manifest.substring(manifest.lastIndexOf('<', at), manifest.indexOf('>', at) + 1)
        assertTrue("only the system binds it", "android:permission=\"android.permission.BIND_JOB_SERVICE\"" in element)
        assertTrue("JobScheduler can reach it", "android:exported=\"true\"" in element)
    }
}
