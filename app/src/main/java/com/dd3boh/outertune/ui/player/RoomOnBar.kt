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
import com.dd3boh.outertune.together.followsRoom
import com.dd3boh.outertune.together.orderHeldLine
import com.dd3boh.outertune.together.seekHeld
import com.dd3boh.outertune.utils.Toaster
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Whether a guest's seek bar says that it plays on its own, with Catch up beside it.
 *
 * A guest's own seek, where the host lets everyone move in the song (RoomSettings), takes it out of step: it plays on
 * its own until Catch up (TogetherSession.listenerChose), and only the room's sheet said so (Ti-Die, #testers 22 Nov
 * 2026: "wait when i change the timeline does it do it for everyone"). Now the times' row under the bar says it where the
 * eyes are after a seek, or after another song of its own ([OwnWayOnBar]).
 *
 * As the room's sheet offers Catch up (TogetherDialog's OwnWay): never while this phone casts, which catches up by
 * itself once the cast ends, nor while the host is away or not heard yet.
 */
internal fun ownWayOnBar(status: TogetherSession.Status): Boolean =
    !status.leading && status.ownWay && !status.casting && !status.waitingForHost && !status.hostAway

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
 * Whether shuffle and repeat are the room's on this phone, the player's and the queue's buttons alike: it follows the
 * room (followsRoom), so a tap changes nothing and says who decides ([sayOrderHeld]). The buttons keep their place,
 * size and look.
 */
@Composable
internal fun rememberOrderHeld(connection: PlayerConnection): State<Boolean> {
    val room by connection.service.together.collectAsState()
    return rememberRoomFlag(room, ::followsRoom)
}

/** A tap on shuffle or repeat while they are the room's ([rememberOrderHeld]): who decides, in one short line. */
internal fun sayOrderHeld(context: Context, connection: PlayerConnection) {
    val status = connection.service.together.value?.status?.value ?: return
    Toaster.show(context, orderHeldLine(context, status))
}

/**
 * The middle of the times' row under the seek bar while this phone plays on its own in a room ([ownWayOnBar]): "On your
 * own" in the times' own style and [color], and Catch up beside it. Nothing of it counts in the row's height, so nothing
 * under the bar moves as it comes and goes: the words are as tall as the times, and Catch up's pill, with a button's
 * room for a finger round it ([CATCH_UP_REACH]), stands over the row's middle line, up into the bar's empty foot and
 * down into the gap above the controls. Laid after the bar, it takes a tap there before the bar does: a tap meant for
 * Catch up a little high would otherwise have moved this phone further on its own.
 */
@Composable
internal fun OwnWayOnBar(room: TogetherSession?, color: Color, modifier: Modifier = Modifier) {
    val shown by rememberRoomFlag(room, ::ownWayOnBar)
    Box(modifier, contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible = shown && room != null, enter = fadeIn(), exit = fadeOut()) {
            val haptics = LocalHapticFeedback.current
            val press = remember { MutableInteractionSource() }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.together_on_your_own),
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        // No height of its own in the row: centred on the row's middle line, whatever its size.
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                            layout(placeable.width, 0) { placeable.place(0, -placeable.height / 2) }
                        }
                        .clickable(interactionSource = press, indication = null, role = Role.Button) {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            room?.catchUp()
                        }
                        .padding(vertical = CATCH_UP_REACH),
                ) {
                    Text(
                        stringResource(R.string.together_catch_up),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiary,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.tertiary)
                            .indication(press, ripple())
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

/** Catch up takes a finger this far above and below its pill: about a button's 48 dp all told. */
private val CATCH_UP_REACH = 13.dp
