/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import android.media.AudioAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Song recognition of the phone's own sound (PhoneAudio): what is taken, and that the permission
 * Android gives for it lives no longer than one listen.
 */
class PhoneAudioTest {
    private fun source(path: String) = File("src/main/$path").readText()

    @Test
    fun `what a video, a music app or a game plays, and nothing else`() {
        assertEquals(
            setOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN),
            PhoneAudio.USAGES.toSet(),
        )
        for (never in listOf(
            AudioAttributes.USAGE_VOICE_COMMUNICATION, AudioAttributes.USAGE_ALARM,
            AudioAttributes.USAGE_NOTIFICATION, AudioAttributes.USAGE_ASSISTANT,
        )) assertFalse("usage $never is taken", never in PhoneAudio.USAGES)
    }

    @Test
    fun `our own playback is left out`() {
        assertTrue("excludeUid(context.applicationInfo.uid)" in source("java/com/dd3boh/outertune/recognition/PhoneAudio.kt"))
    }

    @Test
    fun `a listen of the phone's sound never quietly becomes one of the room`() {
        val listener = source("java/com/dd3boh/outertune/recognition/MicrophoneListener.kt")
        val open = listener.substring(listener.indexOf("private fun open("), listener.indexOf("for (source in SOURCES)"))
        assertTrue("open() no longer looks at the projection first", "PhoneAudio.projection.value?.let" in open)
        assertTrue("a failed capture falls back to the microphone", "return PhoneAudio.recorder(" in open)
    }

    @Test
    fun `the service holds the projection with its type, and lets it go with the listen`() {
        val manifest = source("AndroidManifest.xml")
        assertTrue("android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" in manifest)
        assertTrue("android:foregroundServiceType=\"microphone|mediaProjection\"" in manifest)
        val service = source("java/com/dd3boh/outertune/recognition/RecognitionService.kt")
        assertTrue("the mediaProjection type is claimed without a yes", "(if (consent != null) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)" in service)
        val destroy = service.substring(service.indexOf("override fun onDestroy()"))
        assertTrue("the projection outlives the listen", "PhoneAudio.release()" in destroy.substring(0, destroy.indexOf("\n    }\n")))
        val stopRule = service.substring(service.indexOf("engine.running.collect"))
        assertTrue("the service stops itself before the engine could start", "if (!running && !PhoneAudio.awaitingStart) {" in stopRule.substring(0, 300))
        assertTrue("the service no longer stops when a listen ends", "stopSelf()" in stopRule.substring(0, 300))
    }

    @Test
    fun `the tile wears the sound bars, its screen is ours alone, and the song found opens in the search`() {
        val manifest = source("AndroidManifest.xml")
        val tile = manifest.substring(manifest.indexOf("android:name=\".recognition.PhoneSongTileService\""))
        assertTrue("the tile has another icon than recognition's", "android:icon=\"@drawable/ic_recognise\"" in tile.substring(0, tile.indexOf("</service>")))
        val activity = manifest.substring(manifest.indexOf("android:name=\".recognition.PhoneListenActivity\""))
        assertTrue("the question's screen can be opened by other apps", "android:exported=\"false\"" in activity.substring(0, activity.indexOf("/>")))
        val service = source("java/com/dd3boh/outertune/recognition/RecognitionService.kt")
        assertTrue("the song found no longer opens InterTune's search", "SystemSearch.searchLink(words)" in service)
        assertTrue("the tile's listen does not use the short windows", "windowSeconds = PhoneAudio.WINDOW_SECONDS" in service)
    }

    @Test
    fun `Android's question is one tap, with no app to pick`() {
        val audio = source("java/com/dd3boh/outertune/recognition/PhoneAudio.kt")
        assertTrue("MediaProjectionConfig.createConfigForDefaultDisplay()" in audio)
        for (path in listOf(
            "java/com/dd3boh/outertune/recognition/PhoneListenActivity.kt",
            "java/com/dd3boh/outertune/ui/screens/RecognitionScreen.kt",
        )) {
            val code = source(path)
            assertTrue("$path asks without the one-tap question", "PhoneAudio.consentIntent(projections)" in code)
            assertFalse("$path asks the question that offers a single app", "createScreenCaptureIntent()" in code)
        }
    }

    @Test
    fun `the recording ends before the song is shown`() {
        val service = source("java/com/dd3boh/outertune/recognition/RecognitionService.kt")
        val watch = service.substring(service.indexOf("private fun watchForTheSong()"))
        val release = watch.indexOf("PhoneAudio.release()")
        assertTrue("the projection is still held when the song is posted", release in 0 until watch.indexOf("notifyFound(state.track)"))
    }

    @Test
    fun `short windows, about twenty seconds in all`() {
        assertEquals(4, PhoneAudio.WINDOW_SECONDS)
        assertEquals(20, PhoneAudio.WINDOW_SECONDS * PhoneAudio.MAX_MISSES)
    }
}
