/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import com.dd3boh.outertune.cast.CastRoom
import com.dd3boh.outertune.cast.CastRoomFeed
import com.dd3boh.outertune.cast.QrPng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The room this phone is in, as InterTune's own TV receiver shows it while casting: the room's
 * name, who is in it, and its invite as a QR code for a phone beside the TV to scan. It is put on
 * [CastRoomFeed], which the cast session reads, so a cast that starts in a room shows it at once and
 * one that ends the room puts it away.
 */
object RoomOnTv {
    /** Whoever is in the room, by name, the owner first: the others heard in it, and this phone, named [me]. */
    fun namesOf(me: String, iOwn: Boolean, others: List<TogetherSession.Member>): List<String> {
        val them = others.sortedByDescending { it.owner }.map { it.name }
        return if (iOwn) listOf(me) + them else them + me
    }

    /** The owner's name, or null while it is not known (a guest that has not heard the owner say who it is). */
    fun ownerName(me: String, iOwn: Boolean, others: List<TogetherSession.Member>): String? =
        (if (iOwn) me else others.firstOrNull { it.owner }?.name)?.trim()?.takeIf { it.isNotEmpty() }

    /** The card the TV draws for [status] of a room with [qr]; [title] says the room's name for the owner's name, or for none. */
    fun card(me: String, iOwn: Boolean, status: TogetherSession.Status, qr: String?, title: (String?) -> String): CastRoom =
        CastRoom.of(title(ownerName(me, iOwn, status.members)), namesOf(me, iOwn, status.members), qr)

    /**
     * Keeps [CastRoomFeed] as [together], the service's room, is: filled while there is one, changed
     * as people come and go, emptied when it closes. The QR code is made once for each room.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun follow(scope: CoroutineScope, together: StateFlow<TogetherSession?>, title: (String?) -> String): Job = scope.launch {
        together.flatMapLatest { session ->
            if (session == null) {
                flowOf(null)
            } else {
                val qr = runCatching { QrPng.dataUrl(RoomQr.modules(session.code.appLink)) }.getOrNull()
                session.status.map { card(session.name, session.owner, it, qr, title) }
            }
        }.distinctUntilChanged().collect { CastRoomFeed.set(it) }
    }
}
