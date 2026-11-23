/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R

/**
 * A small, quiet tag on the player's cover while this phone, following a room, plays YouTube Music's version of a song
 * from the files of the one in control (TogetherSession.Status.songMatched; his word of 22 Nov 2026: "a little note or
 * something saying this is a version from YTM and there's a chance it isnt what the other person is hosting"). Drawn
 * over the cover, inside its square, so that nothing of the player moves or changes size; a tap says the same as the
 * room's sheet. [shownId] is the song whose cover it sits on: the swipe strip draws its neighbours' covers too.
 */
@Composable
fun RoomVersionTag(shownId: String?, modifier: Modifier = Modifier) {
    val room = LocalPlayerConnection.current?.service?.together?.collectAsState()?.value ?: return
    val status by room.status.collectAsState()
    if (!status.songMatched || status.leading || status.waitingForHost || shownId == null || shownId != status.hostSongId) return
    var explaining by remember { mutableStateOf(false) }
    val label = stringResource(R.string.together_song_version)
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = modifier
            .padding(12.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClickLabel = label, role = Role.Button) { explaining = true }
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
    if (explaining) {
        AlertDialog(
            onDismissRequest = { explaining = false },
            text = { Text(stringResource(R.string.together_song_matched, status.leaderName ?: stringResource(R.string.together_someone))) },
            confirmButton = { TextButton(onClick = { explaining = false }) { Text(stringResource(android.R.string.ok)) } },
        )
    }
}
