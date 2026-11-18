/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */
package com.dd3boh.outertune.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.ui.dialog.DefaultDialog

/**
 * A word when the phone's battery saver puts the app on Saver (his ask of 18 Nov 2026): what
 * pauses while it is on, and the way to Battery use, where "Follow the phone's battery saver" is.
 * Twice at most for an install, and once each time the saver comes on: it is said again only
 * after the app has seen the saver go off. A saver turned off while the app was not running is
 * not seen, so that second time can be skipped, never doubled. Battery use's own row says it
 * every time ("Saver" now: the phone's battery saver is on), so nothing is lost after the second.
 */
object SaverNote {
    /** How many times an install is told at most. */
    const val MOST = 2

    /**
     * Whether the note is owed now. [saverFromPhone] is BatteryUse.saverFromPhone: Saver because
     * of the phone, not because Saver was picked. [shown] counts the times it was said, and
     * [waitingForOff] is set when it is said and cleared once the app sees the saver off.
     */
    fun owed(saverFromPhone: Boolean, shown: Int, waitingForOff: Boolean): Boolean =
        saverFromPhone && shown < MOST && !waitingForOff
}

@Composable
fun SaverNoteDialog(onBatteryUse: () -> Unit, onDone: () -> Unit) {
    DefaultDialog(
        // A small word, not a question: a tap beside it puts it away like Got it.
        onDismiss = onDone,
        horizontalAlignment = Alignment.Start,
        // Padded to line up with the text below, as the account notice is.
        title = {
            Text(
                text = stringResource(R.string.saver_note_title),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            )
        },
        buttons = {
            TextButton(onClick = onBatteryUse) {
                Text(stringResource(R.string.battery_use))
            }
            TextButton(onClick = onDone) {
                Text(stringResource(R.string.announcement_done))
            }
        },
    ) {
        // With large text or the phone on its side the words scroll and the buttons stay.
        Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Text(
                text = stringResource(R.string.saver_note_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}
