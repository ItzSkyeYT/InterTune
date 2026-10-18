/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.dd3boh.outertune.MainActivity

/**
 * Android's share sheet for [send], with InterTune itself left out of it. The app takes shared
 * text, to open a YouTube Music link someone sends it, so its own share sheets offered InterTune as
 * a place to share a song, a playlist or lyrics to, which only sent them back where they came from
 * (seen sharing lyrics on 18 Oct 2026).
 */
fun Context.shareSheet(send: Intent): Intent =
    Intent.createChooser(send, null).putExtra(
        Intent.EXTRA_EXCLUDE_COMPONENTS,
        arrayOf(ComponentName(this, MainActivity::class.java)),
    )
