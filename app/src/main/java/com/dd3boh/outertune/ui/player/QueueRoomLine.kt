/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.ListThumbnailSize

/**
 * Whether the queue on this phone is the room's (Listen together): a guest that follows plays the queue of the person
 * in control, and each of their words puts their order back here (TogetherSession.matchQueue), at the latest every
 * 30 s. A song a guest moved or swiped away came back, and only that guest had seen it move ("it was next for like
 * 20 seconds and now its back to 3rd", "it was only client side", #testers 22 Nov 2026). In control, or playing on its
 * own until Catch up, the queue is this phone's own, to change as ever.
 */
internal fun queueIsTheRooms(inRoom: Boolean, leading: Boolean, ownWay: Boolean): Boolean = inRoom && !leading && !ownWay

/**
 * The line over the queue's songs while they are the room's ([queueIsTheRooms]): who chooses the music, by the name
 * their phone goes by in rooms ([leaderName], the host until it is known), and how to add a song. Its picture is the
 * room's own, centred where the rows' covers are, and its words start where their titles do.
 */
@Composable
internal fun QueueRoomLine(leaderName: String?, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            // A row's margin and its cover's (ListItem): 8 and 6.
            .padding(start = 14.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
    ) {
        Box(Modifier.width(ListThumbnailSize), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Rounded.Group,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(20.dp),
            )
        }
        // The cover's padding and the title's, 6 and 6.
        Spacer(Modifier.width(12.dp))
        Text(
            text = if (leaderName != null) stringResource(R.string.together_queue_theirs, leaderName)
            else stringResource(R.string.together_queue_host),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
