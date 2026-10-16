/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.lifecycleScope
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.PhoneAudioExplainedKey
import com.dd3boh.outertune.utils.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The Song on this phone tile's way to Android's "Start recording or casting?": a screen with
 * nothing on it, over the video that is playing, so that only Android's question shows and the
 * video is back the moment it is answered. Then the answer is handed to [RecognitionService], which
 * listens and posts what it finds as a notification. Nothing of InterTune comes forward.
 *
 * Before the question, what it needs: the microphone permission, which Android wants for the
 * phone's own sound too (PhoneAudio), and from Android 13 the one to post notifications, the
 * tile's only way to give the song (a phone that had never been asked found it and said nothing,
 * 16 Oct 2026). And the first time only, why Android asks to record the screen at all.
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

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        when {
            granted[Manifest.permission.RECORD_AUDIO] == false -> {
                Toast.makeText(this, R.string.recognition_phone_needs_permission, Toast.LENGTH_LONG).show()
                finish()
            }
            !canNotify() -> refuseWithoutNotifications()
            else -> explainThenAsk()
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
        val missing = needed().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        when {
            missing.isNotEmpty() -> permissions.launch(missing.toTypedArray())
            !canNotify() -> refuseWithoutNotifications()
            else -> explainThenAsk()
        }
    }

    private fun needed(): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun canNotify() = NotificationManagerCompat.from(this).areNotificationsEnabled()

    /**
     * Notifications off: the song would be found and never shown. Said, and the app's notification
     * settings opened once Android no longer asks by itself (refused twice, or turned off there).
     */
    private fun refuseWithoutNotifications() {
        Toast.makeText(this, R.string.recognition_phone_needs_notifications, Toast.LENGTH_LONG).show()
        val androidStillAsks = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        if (!androidStillAsks) {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        finish()
    }

    /** The first time only, why Android asks to record the screen; then the question itself. */
    private fun explainThenAsk() {
        lifecycleScope.launch {
            if (dataStore.data.first()[PhoneAudioExplainedKey] == true) return@launch askForTheSound()
            AlertDialog.Builder(PhoneAudio.dialogContext(this@PhoneListenActivity))
                .setTitle(R.string.recognition_phone_tile)
                .setMessage(R.string.recognition_phone_note)
                .setPositiveButton(R.string.recognition_phone_continue) { _, _ ->
                    lifecycleScope.launch { dataStore.edit { it[PhoneAudioExplainedKey] = true } }
                    askForTheSound()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
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

    companion object {
        fun intent(context: Context): Intent = Intent(context, PhoneListenActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
