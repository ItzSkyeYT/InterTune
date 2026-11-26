/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.playback.PlayerConnection
import com.dd3boh.outertune.together.TogetherSession
import com.dd3boh.outertune.together.listensOnly
import com.dd3boh.outertune.together.orderHeldLine
import com.dd3boh.outertune.together.seekHeld
import com.dd3boh.outertune.utils.Toaster
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** One yes or no from [room]'s status, and only when it changes: the whole status changes four times a second while a guest follows. */
@Composable
internal fun rememberRoomFlag(room: TogetherSession?, of: (TogetherSession.Status) -> Boolean): State<Boolean> =
    remember(room) { room?.status?.map(of)?.distinctUntilChanged() ?: flowOf(false) }
        .collectAsState(initial = room?.status?.value?.let(of) ?: false)

/**
 * Whether [room]'s seek bar is held on this phone: it follows the room, and the host lets only the person in control
 * move in the song (seekHeld). The bar then keeps its place, size and look, moves nothing, and says why when touched
 * (Player.kt's SeekBar).
 */
@Composable
internal fun rememberSeekHeld(room: TogetherSession?): State<Boolean> = rememberRoomFlag(room, ::seekHeld)

/**
 * Whether shuffle and repeat are the room's on this phone, the player's and the queue's buttons alike: it only listens
 * (listensOnly, 26 Nov 2026), so they are shown disabled, keeping their place and size. A guest the host lets control the
 * music changes them for everyone (PlayerConnection.triggerShuffle, toggleRepeat).
 */
@Composable
internal fun rememberOrderHeld(connection: PlayerConnection): State<Boolean> {
    val room by connection.service.together.collectAsState()
    return rememberRoomFlag(room, ::listensOnly)
}

/** A tap on shuffle or repeat while they are the room's ([rememberOrderHeld]): who decides, in one short line. */
internal fun sayOrderHeld(context: Context, connection: PlayerConnection) {
    val status = connection.service.together.value?.status?.value ?: return
    Toaster.show(context, orderHeldLine(context, status))
}
