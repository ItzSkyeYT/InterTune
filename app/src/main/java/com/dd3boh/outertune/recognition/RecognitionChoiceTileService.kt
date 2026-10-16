/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import android.app.AlertDialog
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.dd3boh.outertune.R

/**
 * "What's playing?" in Quick Settings, asking first which sound: a small question over the panel
 * with the two choices, and the tap on one starts it (his idea, 16 Oct 2026). Its neighbours go
 * straight to one each: [RecognitionTileService] around you, [PhoneSongTileService] this phone.
 * Below Android 10 the phone's own sound cannot be had, so there is nothing to ask.
 */
class RecognitionChoiceTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { ask() } else ask()
    }

    private fun ask() {
        if (!PhoneAudio.supported) return openCollapsing(RecognitionShortcut.intent(this))
        val choices = arrayOf(getString(R.string.recognition_source_room), getString(R.string.recognition_source_phone))
        val dialog = AlertDialog.Builder(PhoneAudio.dialogContext(this))
            .setTitle(R.string.recognise)
            .setSingleChoiceItems(choices, -1) { shown, which ->
                shown.dismiss()
                openCollapsing(if (which == 0) RecognitionShortcut.intent(this) else PhoneListenActivity.intent(this))
            }
            .create()
        showDialog(dialog)
    }
}
