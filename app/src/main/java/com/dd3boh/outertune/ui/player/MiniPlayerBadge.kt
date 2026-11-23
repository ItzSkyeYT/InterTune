/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.playback.CastSession
import kotlinx.coroutines.flow.MutableStateFlow

/** What the mini player's cover wears while the music is not this phone's alone (his pick of 15 Nov 2026). */
internal enum class MiniBadge { ROOM, CAST }

/**
 * The badge for a phone [inRoom] (Listen together) and [casting] (a receiver plays): the room's
 * while one is on, since what is pressed then reaches other people, the cast's while casting, and
 * none on this phone alone. Connecting is not casting yet.
 */
internal fun miniBadge(inRoom: Boolean, casting: Boolean): MiniBadge? = when {
    inRoom -> MiniBadge.ROOM
    casting -> MiniBadge.CAST
    else -> null
}

/** The badge's circle, its ring in the panel's colour, and the icon in it. */
internal val MiniBadgeSize = 20.dp
internal val MiniBadgeRing = 2.dp
internal val MiniBadgeIcon = 14.dp

/**
 * A small badge on the bottom right corner of the mini player's cover while a room or a cast is on:
 * a room's people, a cast's screen, in the accent colour with a ring of the panel's colour round it,
 * so the sign is there wherever the app is (his pick, "C with B's badges"). It stands a little past
 * the cover's corner, over the cover's own margin, so the cover and the title keep their places.
 */
@Composable
internal fun BoxScope.MiniPlayerBadge() {
    val playerConnection = LocalPlayerConnection.current ?: return
    val room by playerConnection.service.together.collectAsState()
    val none = remember { MutableStateFlow<CastSession.State>(CastSession.State.Off) }
    val castState by (playerConnection.service.castSession?.state ?: none).collectAsState()
    val casting = castState as? CastSession.State.Casting
    val badge = miniBadge(inRoom = room != null, casting = casting != null)
    // The last one shown, so that it fades out with its icon still in it.
    var shown by remember { mutableStateOf(badge) }
    if (badge != null) shown = badge

    AnimatedVisibility(
        visible = badge != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .offset(x = 8.dp, y = 4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(MiniBadgeSize + MiniBadgeRing * 2)
                .background(MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp), CircleShape)
                .padding(MiniBadgeRing)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        ) {
            when (shown) {
                MiniBadge.ROOM -> Icon(
                    imageVector = Icons.Rounded.Group,
                    contentDescription = stringResource(R.string.together_in_room),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(MiniBadgeIcon),
                )
                MiniBadge.CAST -> Icon(
                    imageVector = Icons.Rounded.CastConnected,
                    contentDescription = casting?.let { stringResource(R.string.cast_playing_on, it.device.name) },
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(MiniBadgeIcon),
                )
                null -> Unit
            }
        }
    }
}
