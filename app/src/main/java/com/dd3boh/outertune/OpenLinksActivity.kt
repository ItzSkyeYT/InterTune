/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Where the links the app opens for others arrive (the manifest's filters for the share page,
 * YouTube and YouTube Music), so that "Open links in InterTune" has a component to turn off. It
 * shows nothing: it hands the intent, as it came, to MainActivity and finishes, so MainActivity
 * reads the link exactly as one that started it directly (launchLink, newIntentLink).
 *
 * It is not an alias of MainActivity because the record of an activity started under an alias is
 * named after the alias, and Android finishes the activities of a component that is disabled: the
 * app closed when the switch was turned off from inside it after a link had started it. Nothing
 * that stays running is ever named after this one.
 */
class OpenLinksActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { startActivity(handedOn(intent, ComponentName(this, MainActivity::class.java))) }
            .onFailure { Log.w("OpenLinks", "Could not hand the link to MainActivity", it) }
        finish()
    }

    companion object {
        /**
         * The intent that came in, with everything it carried, for [target] alone.
         *
         * Android adds FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS to the intent of an activity whose manifest
         * says excludeFromRecents, as this one's does, and an intent handed on with it would start the
         * app's own task with it too: the app would stay out of the recents after a link (seen on an
         * emulator, flags 0x10800000 on MainActivity). It goes before the hand over, with setFlags: removeFlags
         * is Android 8 and later, and the app runs from Android 7.
         */
        internal fun handedOn(intent: Intent, target: ComponentName): Intent =
            Intent(intent).apply { flags = flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS.inv() }.setComponent(target)
    }
}
