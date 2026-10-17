/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.dialog

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.playback.PlayerConnection
import com.dd3boh.outertune.constants.TogetherRelaysKey
import com.dd3boh.outertune.together.RoomCode
import com.dd3boh.outertune.together.TogetherSession
import com.dd3boh.outertune.utils.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Listen together, step 1 (Unreleased.LISTEN_TOGETHER): start a room and share its code, or type a
 * friend's; while in one, its code, who leads, and how close this phone is to the host.
 */
@Composable
fun TogetherDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val service = playerConnection.service
    val session by service.together.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var typed by rememberSaveable { mutableStateOf("") }

    fun open(code: RoomCode, host: Boolean) {
        scope.launch { service.startTogether(code, host, togetherRelays(context), Build.MODEL) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Group, contentDescription = null) },
        title = { Text(stringResource(R.string.together_title)) },
        text = {
            Column(Modifier.sizeIn(minWidth = 280.dp, maxWidth = 560.dp)) {
                val room = session
                if (room == null) {
                    Text(
                        stringResource(R.string.together_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { open(RoomCode.generate(), host = true) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.together_start))
                    }
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        label = { Text(stringResource(R.string.together_code)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val parsed = RoomCode.parse(typed)
                    TextButton(enabled = parsed != null, onClick = { parsed?.let { open(it, host = false) } }) {
                        Text(stringResource(R.string.together_join))
                    }
                } else {
                    val status by room.status.collectAsState()
                    Text(
                        room.code.pretty,
                        style = MaterialTheme.typography.headlineMedium,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 2.sp,
                    )
                    Row {
                        TextButton(onClick = {
                            clipboard.nativeClipboard.setPrimaryClip(ClipData.newPlainText(room.code.pretty, room.code.pretty))
                        }) { Text(stringResource(R.string.together_copy)) }
                        val invite = stringResource(R.string.together_invite_text, room.code.pretty, room.code.appLink)
                        TextButton(onClick = {
                            context.startActivity(
                                Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, invite), null)
                            )
                        }) { Text(stringResource(R.string.together_share)) }
                    }
                    Spacer(Modifier.height(8.dp))
                    val line = when {
                        room.host -> stringResource(R.string.together_hosting)
                        status.waitingForHost -> stringResource(R.string.together_waiting)
                        status.ownWay -> stringResource(R.string.together_own_way)
                        else -> stringResource(R.string.together_following, status.hostSong.orEmpty())
                    }
                    Text(line, style = MaterialTheme.typography.bodyLarge)
                    if (!room.host && status.ownWay) {
                        Button(onClick = { room.catchUp() }) { Text(stringResource(R.string.together_catch_up)) }
                    }
                    if (!room.host) status.driftMs?.let { drift ->
                        Text(
                            stringResource(if (abs(drift) <= 25) R.string.together_in_step else R.string.together_catching_up, abs(drift).toInt()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        stringResource(R.string.together_others, status.others),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.together_relays, status.relaysConnected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (session != null) {
                TextButton(onClick = { service.stopTogether() }) { Text(stringResource(R.string.together_leave)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.together_close)) }
            }
        },
        dismissButton = if (session != null) {
            { TextButton(onClick = onDismiss) { Text(stringResource(R.string.together_close)) } }
        } else null,
    )
}

/** The relays a room uses: the public ones, or in a debug build those of TogetherRelaysKey when set. */
suspend fun togetherRelays(context: Context): List<String> =
    context.dataStore.data.first()[TogetherRelaysKey]
        ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }
        ?: TogetherSession.PUBLIC_RELAYS

/**
 * An invite opened from a link (intertune://together/<code>): asked once, then joined as a guest.
 * Handed the player rather than reading LocalPlayerConnection: it is shown where links are opened,
 * above where that is provided (the first two-phone run crashed on it, 17 Oct 2026).
 */
@Composable
fun TogetherInviteDialog(code: RoomCode, playerConnection: PlayerConnection?, onDone: () -> Unit) {
    val context = LocalContext.current
    playerConnection ?: return
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDone,
        icon = { Icon(Icons.Rounded.Group, contentDescription = null) },
        title = { Text(stringResource(R.string.together_join_title)) },
        text = {
            Text(code.pretty, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    playerConnection.service.startTogether(code, host = false, relays = togetherRelays(context), name = Build.MODEL)
                    onDone()
                }
            }) { Text(stringResource(R.string.together_join)) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(android.R.string.cancel)) } },
    )
}
