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
 * sets them in the room's sheet (RoomSettingsSwitches), keeps them for its next rooms (TogetherEveryoneControlsKey), and
 * says them in its Here, so everyone hears them at once and every [TogetherMessage.HERE_MS] after. Only the owner's
 * word is taken.
 *
 * Each switch read from a word that does not have it means what rooms did before it: an owner on an older build says
 * nothing ([of] gives null, see [seekHeld]), and a build older than this one ignores the whole field.
 */
data class RoomSettings(
    /**
     * Everyone may control the music, as the host does (his word of 26 Nov 2026, Spotify's Jam: "Guests listen, host can
     * control and can give a toggle to let all guests control"): a guest's play, pause, skip, seek, shuffle, repeat and the
     * songs it starts go to the one in control (TogetherMessage.Control), who makes them for everyone, and its changes to
     * the queue too (TogetherMessage.Arrange). Off by default: guests listen, their transport shown disabled and their
     * play button a mute (RoomListen). It takes the place of the two switches before it, "Let everyone move in the song"
     * and "Let everyone change the queue": either on read as this one on ([of], TogetherEveryoneControlsKey).
     */
    val everyoneControls: Boolean = false,
) {
    /** Said with the two older fields as well, so that a guest on a build before this one moves and arranges as allowed. */
    fun toJson(): JsonObject = buildJsonObject {
        put("control", everyoneControls)
        put("seek", everyoneControls)
        put("arrange", everyoneControls)
    }

    companion object {
        /**
         * What an owner's word says, or null when it says nothing of them (an older build) or nothing readable. An owner
         * from before 26 Nov 2026 says only the two older switches: either on is control for everyone.
         */
        fun of(json: JsonElement?): RoomSettings? {
            val o = json as? JsonObject ?: return null
            val control = (o["control"] as? JsonPrimitive)?.booleanOrNull
                ?: ((o["seek"] as? JsonPrimitive)?.booleanOrNull == true || (o["arrange"] as? JsonPrimitive)?.booleanOrNull == true)
            return RoomSettings(everyoneControls = control)
        }

        /**
         * The host's switch as kept on this phone: the new one once set, else the two before it, either on meaning on
         * (read once: the switch keeps the new one from its first change).
         */
        fun kept(controls: Boolean?, seeks: Boolean?, arranges: Boolean?): Boolean = controls ?: (seeks == true || arranges == true)
    }
}

/**
 * Whether this phone follows the room now: the leader's word heard, not the one in control, not casting. Its queue, shuffle and repeat are then the room's, which the next word from the person in control puts back
 * whatever is changed here (TogetherSession.matchQueue, takeRepeat): its shuffle and repeat buttons change nothing and say
 * who decides (22 Nov 2026).
 */
internal fun followsRoom(status: TogetherSession.Status): Boolean =
    !status.leading && !status.ownWay && !status.waitingForHost && !status.casting

/**
 * Whether this phone only listens in the room (RoomListen, his word of 26 Nov 2026): a guest, not in control, not casting,
 * and the host has not let everyone control the music. Its shuffle, previous, next, repeat and seek bar are then shown
 * disabled where they are, at their size, and its play button is a mute: the room's music goes on here in step, silent
 * until unmuted. Also while it waits to hear the room, or nobody plays for it: nothing it does here is the room's.
 */
fun listensOnly(status: TogetherSession.Status): Boolean =
    !status.leading && !status.casting && !(status.settings?.everyoneControls == true && status.leaderControls)

/**
 * Whether this phone, a guest, controls the music for everyone: it follows the room, the host lets everyone control it,
 * and the one in control takes a guest's moves ([TogetherSession.Status.leaderControls], 27 Nov 2026). A build before
 * this one drops them, so a guest under one in control on such a build listens, and the room's sheet says why.
 */
internal fun guestControls(status: TogetherSession.Status): Boolean =
    followsRoom(status) && status.settings?.everyoneControls == true && status.leaderControls

/**
 * Whether the room's sheet says that the one in control is on an older InterTune, which drops a guest's moves: the host
 * lets everyone control the music, and this phone follows someone whose word never said it takes them.
 */
fun controlFromOlder(status: TogetherSession.Status): Boolean =
    !status.leading && !status.waitingForHost && status.settings?.everyoneControls == true && !status.leaderControls

/**
 * Whether this phone's moves in the song are held: it only listens ([listensOnly]). The seek bar then does not move at
 * all (RoomOnBar.kt), and a move made another way, from the notification, a watch or a lyric line, is put back where the
 * room is (TogetherSession.seekHeldHere). The person in control always moves the room's song, and a guest the host lets
 * control the music moves it for everyone (TogetherMessage.Control).
 */
internal fun seekHeld(status: TogetherSession.Status): Boolean = listensOnly(status)

/**
 * Whether this phone may change the room's queue: it follows the room ([followsRoom]), the host lets everyone control the
 * music, and the person in control takes a guest's changes ([TogetherSession.Status.leaderArranges]). A build before this
 * one drops them, so with one in control the queue stays the room's to look at.
 */
internal fun guestArranges(status: TogetherSession.Status): Boolean =
    followsRoom(status) && status.settings?.everyoneControls == true && status.leaderArranges

/** Why a move in the song did nothing: who decides where the song is, by name, or the host while no name is known yet. */
fun seekHeldLine(context: Context, status: TogetherSession.Status): String =
    status.leaderName?.let { context.getString(R.string.together_seek_held, it) } ?: context.getString(R.string.together_seek_held_host)

/** Why shuffle or repeat did nothing on a phone that follows the room ([followsRoom]): who decides, as [seekHeldLine]. */
fun orderHeldLine(context: Context, status: TogetherSession.Status): String =
    status.leaderName?.let { context.getString(R.string.together_order_held, it) } ?: context.getString(R.string.together_order_held_host)

/** Why a move of a guest's who listens did nothing (a notification's Next, a watch): who controls the music, as [seekHeldLine]. */
fun controlHeldLine(context: Context, status: TogetherSession.Status): String =
    status.leaderName?.let { context.getString(R.string.together_control_held, it) } ?: context.getString(R.string.together_control_held_host)
