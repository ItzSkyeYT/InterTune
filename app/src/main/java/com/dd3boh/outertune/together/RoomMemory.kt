/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import java.io.File

/**
 * The room this phone is in, kept in a file of its own so that it comes back by itself (15 Nov 2026).
 *
 * A room lives in the music service, and the service stops by itself once the app is left with nothing playing: a host
 * who went to another app to share the code came back to a start page, and the room was gone. Now the room is kept
 * (its code, whether this phone made it, and the key this phone signs as in it, so that a host that comes back is the
 * same owner and not a stranger who claims to be) and it is joined again, in the same role, by whatever service the
 * app gets next, or by the app after Android has ended it. It is forgotten when the person leaves it or closes it, when
 * the room is over, and after [FORGET_AFTER_MS] away.
 *
 * In the app's no-backup storage: the key must never be restored on another phone.
 */
class RoomMemory(private val file: File) {
    // One at a time: the service touches the file off the main thread (MusicService.roomAlive), and saves, loads and
    // forgets it on the main one.

    /** What is kept, and when the room was last alive: for a guest, when it last heard the host (HostAbsence.aliveAt). */
    data class Saved(val code: String, val owner: Boolean, val identity: String, val aliveAt: Long)

    @Synchronized
    fun save(code: RoomCode, owner: Boolean, identity: String, now: Long) {
        write(Saved(code.raw, owner, identity, now))
    }

    /** The room that is kept, or null: none, one that was last alive more than [FORGET_AFTER_MS] ago, or a file that cannot be read. */
    @Synchronized
    fun load(now: Long): Saved? {
        val saved = read() ?: return null
        if (now - saved.aliveAt > FORGET_AFTER_MS) {
            forget()
            return null
        }
        return saved
    }

    /**
     * The room [code] is alive at [now]: a long absence is counted from the last time it was. Only if it is still the room
     * kept: a touch that comes late, off the main thread, leaves alone a room joined meanwhile, and keeps nothing after a
     * leave.
     */
    @Synchronized
    fun touch(code: RoomCode, now: Long) {
        read()?.takeIf { it.code == code.raw }?.let { write(it.copy(aliveAt = now)) }
    }

    @Synchronized
    fun forget() {
        runCatching { file.delete() }
    }

    private fun read(): Saved? = runCatching {
        val words = file.readLines().mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        Saved(
            code = words.getValue("code").also { require(RoomCode.parse(it) != null) },
            owner = words.getValue("owner").toBooleanStrict(),
            identity = words.getValue("identity").also { key -> require(key.length == 64 && key.all { it in "0123456789abcdef" }) },
            aliveAt = words.getValue("aliveAt").toLong(),
        )
    }.getOrNull()

    private fun write(saved: Saved) {
        runCatching {
            val temp = File(file.absoluteFile.parentFile, file.name + ".tmp")
            temp.writeText("code=${saved.code}\nowner=${saved.owner}\nidentity=${saved.identity}\naliveAt=${saved.aliveAt}\n")
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }

    companion object {
        const val FILE = "room.txt"

        /** A room not alive for this long is forgotten: six hours. */
        const val FORGET_AFTER_MS = 6 * 60 * 60 * 1000L
    }
}

/**
 * When a guest says the host is away, and when the room is over for it. The host speaks every
 * [TogetherMessage.HERE_MS]; silent for [AWAY_MS], two beats and a half, it is away, and the guest goes back to normal the
 * moment it is heard again. Away for [GONE_MS] it is not coming back (the host's own phone forgets the room after as
 * long, RoomMemory.FORGET_AFTER_MS).
 */
internal object HostAbsence {
    const val AWAY_MS = 25_000L
    const val GONE_MS = RoomMemory.FORGET_AFTER_MS

    fun away(now: Long, lastSign: Long): Boolean = now - lastSign > AWAY_MS
    fun gone(now: Long, lastSign: Long): Boolean = now - lastSign > GONE_MS

    /**
     * The host's last sign for a guest: its last word ([ownerHeardAt], 0 before it is heard), else [since], when the guest
     * came, or for a room that comes back the time it kept. Counted from the session's own start, a room whose host had
     * closed it while the guest's app was off came back for six more hours at every start (18 Nov 2026).
     */
    fun lastSign(ownerHeardAt: Long, since: Long): Long = maxOf(ownerHeardAt, since)

    /**
     * What a room keeps as its last sign of life (RoomMemory.touch), or null for nothing: [now] for its host, and for a
     * guest the last time it heard the host. Never a guest's own beat: that kept a dead room alive for as long as the
     * guest opened the app.
     */
    fun aliveAt(owner: Boolean, now: Long, ownerHeardAt: Long): Long? = if (owner) now else ownerHeardAt.takeIf { it > 0 }
}
