/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.dialog

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.dd3boh.outertune.R
import com.dd3boh.outertune.playback.PlayerConnection

/**
 * A song or a list started on a phone that only listens in a room (his word of 26 Nov 2026, Spotify's Jam: guests listen,
 * and to listen alone you leave): "Leave the room to play this?", Leave and play or Cancel. The host who gave control to
 * someone else is asked to take it back instead, as leaving would close the room or hand it on. Wherever the app is, over
 * the player too (MainActivity), as the start may come from any screen.
 */
@Composable
internal fun RoomLeaveToPlay(connection: PlayerConnection?) {
    val ask by (connection ?: return).leaveToPlay.collectAsState()
    val now = ask ?: return
    val dismiss = { connection.leaveToPlay.value = null }
    AlertDialog(
        onDismissRequest = dismiss,
        icon = { Icon(if (now.host) Icons.Rounded.Star else Icons.AutoMirrored.Rounded.Logout, contentDescription = null) },
        title = { Text(stringResource(if (now.host) R.string.together_take_to_play else R.string.together_leave_to_play)) },
        text = { Text(stringResource(if (now.host) R.string.together_take_to_play_body else R.string.together_leave_to_play_body)) },
        confirmButton = {
            TextButton(onClick = {
                connection.leaveToPlay.value = null
                now.play()
            }) { Text(stringResource(if (now.host) R.string.together_take_and_play else R.string.together_leave_and_play)) }
        },
        dismissButton = {
            TextButton(onClick = dismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
