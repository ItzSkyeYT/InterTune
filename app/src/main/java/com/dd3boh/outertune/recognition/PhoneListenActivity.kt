/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import android.Manifest
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.dd3boh.outertune.R

/**
 * The Song on this phone tile's way to Android's "Start recording or casting?": a screen with
 * nothing on it, over the video that is playing, so that only Android's question shows and the
 * video is back the moment it is answered. The microphone permission first when it is missing,
 * since Android wants it for the phone's own sound too (PhoneAudio), then the question, then the
 * answer handed to [RecognitionService], which listens and posts what it finds as a notification.
 * Nothing of InterTune comes forward.
 */
class PhoneListenActivity : ComponentActivity() {

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            // Set before the service starts, so it does not take the engine's not running yet
            // for a listen that is over (RecognitionService).
            PhoneAudio.awaitingStart = true
            RecognitionService.startOnPhone(this, result.resultCode, data, headless = true)
        }
        finish()
    }

    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            askForTheSound()
        } else {
            Toast.makeText(this, R.string.recognition_phone_needs_permission, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Brought back by a rotation while a question is up: that question is still the one.
        if (savedInstanceState != null) return
        if (!PhoneAudio.supported) {
            finish()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            askForTheSound()
        } else {
            microphone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun askForTheSound() {
        val projections = getSystemService(MediaProjectionManager::class.java)
        if (projections == null) {
            finish()
            return
        }
        consent.launch(PhoneAudio.consentIntent(projections))
    }
}
