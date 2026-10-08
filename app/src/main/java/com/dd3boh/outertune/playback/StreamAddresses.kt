/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

/**
 * The stream address each song was last given, kept so that the rest of a song comes from the
 * address its start came from. No Android in here, so it is tested.
 *
 * The player opens a song more than once: its start, then the rest, and again for a seek or a
 * retry of its own. Each time it asks the resolver in MusicService, and without this each would
 * walk the chain of clients again: more requests to YouTube for a song it has already answered
 * for, and an answer that may be another format than the bytes already fetched.
 *
 * An address is kept until the time YouTube gives it, about six hours, and nothing used to take
 * one out before that. So a song the player failed on kept the address it had failed on, and Play
 * handed the player that address again, every time, until the listener skipped. Seen on the
 * emulator on 8 Oct 2026, in every run, after a spell with every connection to a stream refused:
 * no address could be checked, so the last client's was taken unchecked (see StreamCheck.accept),
 * and that was IOS's, which answers 403. With connections working again every Play got the 403
 * and the song after it played at once.
 *
 * So the player failing on a song forgets the song's address, whatever the failure was, and the
 * next try asks for one afresh. That costs one walk of the chain, after an error only, and offline
 * the walk fails at its first request as it always has.
 *
 * An address is not judged before it is kept. One that could not be checked is kept like any
 * other: the player is about to open it, the rest of the song has to come from where its start
 * did, and four tries by the player in four seconds are not worth four walks of the chain. What
 * the player makes of it is the check. One that did pass its check is forgotten on a failure all
 * the same, since it can stop working later: it names the address it was issued to (see
 * StreamCheck.urlForLog), and a phone that leaves Wi-Fi for mobile data has another. That case
 * was not run.
 *
 * Asked by the threads that load songs, two of them while the next song is loaded ahead, and told
 * of a failure by the main one, so every call takes the lock. The map this replaces had none.
 */
class StreamAddresses {
    private class Kept(val address: String, val goodUntil: Long)

    private val kept = HashMap<String, Kept>()

    /** The address [mediaId] was given, or null when it has none that is still good at [now]. */
    @Synchronized
    fun of(mediaId: String, now: Long): String? = kept[mediaId]?.takeIf { it.goodUntil > now }?.address

    /** [address] is where [mediaId] is fetched from until [goodUntil], unless the player fails on it. */
    @Synchronized
    fun keep(mediaId: String, address: String, goodUntil: Long) {
        kept[mediaId] = Kept(address, goodUntil)
    }

    /**
     * The player failed on [mediaId], null when the failure names no song. Returns whether there
     * was an address to forget: a local file, a download and a song that never got an address
     * have none.
     */
    @Synchronized
    fun playerFailedOn(mediaId: String?): Boolean = mediaId != null && kept.remove(mediaId) != null
}
