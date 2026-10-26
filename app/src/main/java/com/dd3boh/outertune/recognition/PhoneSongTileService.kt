/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import android.content.Intent
import android.service.quicksettings.TileService

/**
 * Song on this phone: the Quick Settings tile that names the song in what is playing on the
 * phone, a short video above all, without leaving it (his design, 16 Oct 2026). A tap brings up
 * only Android's question to record (PhoneListenActivity), then a notification while it listens
 * and another with the song, which opens it in InterTune's search to play or add to a playlist.
 *
 * The same sound bars as every other way into recognition (ic_recognise, his word of the same day).
 * Its two neighbours: [RecognitionTileService] listens around you, [RecognitionChoiceTileService]
 * asks which.
 */
class PhoneSongTileService : TileService() {

    override fun onClick() {
        super.onClick()
        openCollapsing(PhoneListenActivity.intent(this))
    }
}
