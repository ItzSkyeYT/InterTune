/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import android.content.Context
import com.dd3boh.outertune.R
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The host's room settings: who may do what in the room. dvd in #testers, 22 Nov 2026: "maybe the host should have a
 * settings button to change the lobby settings, to configure who can do what", and his answer: "Agreed". The owner
 * sets them in the room's sheet (RoomSettingsSwitches), keeps them for its next rooms (TogetherEveryoneSeeksKey), and
 * says them in its Here, so everyone hears them at once and every [TogetherMessage.HERE_MS] after. Only the owner's
 * word is taken.
 *
 * Each switch read from a word that does not have it means what rooms did before it: an owner on an older build says
 * nothing ([of] gives null, see [seekHeld]), and a build older than this one ignores the whole field.
 */
data class RoomSettings(
    /**
     * Everyone may move in the song, which takes them out of step, on their own until Catch up, as before. Off by
     * default (his pick of 22 Nov 2026, "Lock, host can unlock"): only the person in control moves in it, and it moves
     * for everyone.
     */
    val everyoneSeeks: Boolean = false,
    /**
     * Everyone may change the queue: a guest following the room moves and takes out songs, all but the one playing, and
     * each change goes to the person in control (TogetherMessage.Arrange), who makes it for everyone. Off by default (his
     * word of 22 Nov 2026: "guests only, but they can be given perms to do so"): a guest's queue is the room's to look at.
     */
    val everyoneArranges: Boolean = false,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("seek", everyoneSeeks)
        put("arrange", everyoneArranges)
    }

    companion object {
        /** What an owner's word says, or null when it says nothing of them (an older build) or nothing readable. */
        fun of(json: JsonElement?): RoomSettings? {
            val o = json as? JsonObject ?: return null
            return RoomSettings(
                everyoneSeeks = (o["seek"] as? JsonPrimitive)?.booleanOrNull ?: true,
                // No guest changed the room's queue before the switch.
                everyoneArranges = (o["arrange"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }
    }
}

/**
 * Whether this phone follows the room now: the leader's word heard, not the one in control, not on its own way, not
 * casting. Its queue, shuffle and repeat are then the room's, which the next word from the person in control puts back
 * whatever is changed here (TogetherSession.matchQueue, takeRepeat): its shuffle and repeat buttons change nothing and say
 * who decides (22 Nov 2026).
 */
internal fun followsRoom(status: TogetherSession.Status): Boolean =
    !status.leading && !status.ownWay && !status.waitingForHost && !status.casting

/**
 * Whether this phone's moves in the song are held: it follows the room ([followsRoom]), and the owner lets only the
 * person in control move in the song. The seek bar then does not move at all (RoomOnBar.kt), and a move made another
 * way, from the notification, a watch or a lyric line, is put back where the room is (TogetherSession.seekHeldHere). The
 * person in control always moves the room's song; a phone on its own way moves in its own song as it likes; a room whose
 * owner said nothing of its settings is as rooms were.
 */
internal fun seekHeld(status: TogetherSession.Status): Boolean =
    followsRoom(status) && status.settings?.everyoneSeeks == false

/**
 * Whether this phone may change the room's queue: it follows the room ([followsRoom]), the owner lets everyone change
 * it, and the person in control takes a guest's changes ([TogetherSession.Status.leaderArranges]). A build before this
 * one drops them, so with one in control the queue stays the room's to look at.
 */
internal fun guestArranges(status: TogetherSession.Status): Boolean =
    followsRoom(status) && status.settings?.everyoneArranges == true && status.leaderArranges

/** Why a move in the song did nothing: who decides where the song is, by name, or the host while no name is known yet. */
fun seekHeldLine(context: Context, status: TogetherSession.Status): String =
    status.leaderName?.let { context.getString(R.string.together_seek_held, it) } ?: context.getString(R.string.together_seek_held_host)

/** Why shuffle or repeat did nothing on a phone that follows the room ([followsRoom]): who decides, as [seekHeldLine]. */
fun orderHeldLine(context: Context, status: TogetherSession.Status): String =
    status.leaderName?.let { context.getString(R.string.together_order_held, it) } ?: context.getString(R.string.together_order_held_host)
