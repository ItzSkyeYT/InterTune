/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils.potoken

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.dd3boh.outertune.BuildConfig
import java.io.File

/**
 * Whether the WebView's own camera lookup would die on this device (#263).
 *
 * BotGuard, which the PoToken page runs, looks for a camera (its VM hands back a checkCameraFunction). The WebView then
 * asks Android for the cameras in the app's own process, and on an Amazon Echo Show 5 (Android 11) that list or a
 * camera's details read as null: Chromium's VideoCaptureFactory.getCaptureApiType threw "Attempt to get length of null
 * array" on a WebView thread and took the whole app down, music and all. The same questions are asked here first, where
 * a null or a throw can be caught.
 */
internal object CameraLookup {

    /**
     * The debug app alone can be told to take the cameras away, to see BotGuard answer without them on any device:
     * `adb shell run-as dev.skye.intertune.debug touch files/po-token-no-cameras`.
     */
    const val NO_CAMERAS_FILE = "po-token-no-cameras"

    /** True when the camera list, or a camera's hardware level or capabilities, cannot be read here. */
    fun breaks(context: Context): Boolean =
        (BuildConfig.BUILD_TYPE == "debug" && File(context.filesDir, NO_CAMERAS_FILE).exists()) || breaks {
            val manager = context.getSystemService(CameraManager::class.java) ?: return@breaks null
            val ids: Array<String>? = manager.cameraIdList
            ids?.map { id ->
                val camera = manager.getCameraCharacteristics(id)
                val level: Int? = camera.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                val capabilities: IntArray? = camera.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                level != null && capabilities != null
            }
        }

    /**
     * [read] says, camera by camera, whether everything Chromium reads is there; a null list, a camera with a gap, or a
     * throw means Chromium would meet a null too. No camera at all is fine: Chromium lists none.
     */
    fun breaks(read: () -> List<Boolean>?): Boolean =
        runCatching { read()?.all { it } != true }.getOrDefault(true)
}

/** The PoToken page as loaded, see [CameraLookup]. */
internal object PoTokenPage {

    /**
     * Run before anything else on the page: no navigator.mediaDevices and no getUserMedia, as on any page served without
     * https, so BotGuard finds no camera and the WebView never looks for one.
     */
    const val NO_CAMERAS =
        "try { ['mediaDevices', 'getUserMedia', 'webkitGetUserMedia'].forEach(function (name) {" +
            " Object.defineProperty(Navigator.prototype, name, { get: function () { return undefined }, configurable: true }) }) } catch (e) {}"

    fun withoutCameras(html: String): String = html.replaceFirst("<script>", "<script>\n$NO_CAMERAS\n")
}
