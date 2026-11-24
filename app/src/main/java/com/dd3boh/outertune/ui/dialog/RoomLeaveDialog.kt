/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.together.RoomLeaveChoice

/**
 * The host's Leave (his find of 23 Nov 2026, hosting three testers: "there's no way to leave the room without closing
 * it"). Two ways out, plainly, each saying what it does to the others: hand the room to the one who gets it, named
 * (together/RoomHandOn), or close it for everyone as before. With nobody to hand it to, closing is asked as it always
 * was, and why when everyone else is on an older build.
 */
@Composable
internal fun RoomLeaveDialog(choice: RoomLeaveChoice, onHandOn: () -> Unit, onClose: () -> Unit, onDismiss: () -> Unit) {
    val heir = choice.heir
    if (heir == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null) },
            title = { Text(stringResource(R.string.together_close_room_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.together_close_room_text))
                    if (choice.olderOnly) {
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.together_close_room_older))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onClose) { Text(stringResource(R.string.together_close_room), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null) },
        title = { Text(stringResource(R.string.together_leave_title)) },
        text = {
            Column {
                // First, as the way that keeps everyone listening: the host's star goes to them.
                LeaveWay(
                    icon = Icons.Rounded.Star,
                    tint = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.together_hand_on, heir),
                    titleColor = MaterialTheme.colorScheme.onSurface,
                    onClick = onHandOn,
                )
                LeaveWay(
                    icon = Icons.Rounded.Close,
                    tint = MaterialTheme.colorScheme.error,
                    title = stringResource(R.string.together_close_room),
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = onClose,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/** One way out, by its name alone (24 Nov 2026: the lines under the two ways only repeated them). The whole row is one button, one stop for TalkBack. */
@Composable
private fun LeaveWay(icon: ImageVector, tint: Color, title: String, titleColor: Color, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
        }
    }
}
