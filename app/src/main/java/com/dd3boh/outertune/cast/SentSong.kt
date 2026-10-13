/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

/** What one report from the receiver calls for, about the song sent to it last ([SentSong.onReport]). */
enum class ReportAction {
    /** About another song, or about a failure already acted on: nothing to do. */
    IGNORE,

    /** Given to the playback rules as it is (RemotePlayback.onReceiver). */
    FOLLOW,

    /** The song failed as it is: it is resolved afresh and sent once more, as AAC. */
    RETRY_AS_AAC,

    /** The song failed for good: it is resolved afresh on any later request, and the playback rules are told. */
    FAILED,
}

/**
 * The song the remote player sent last, in what form, and what each report about it calls for.
 * The decision is made here and carried out by RemotePlayer, so that it can be tested without
 * Android.
 *
 * A failure is acted on once for each song sent. A receiver's status says FAILED until the next
 * song, and it is sent again whenever anything else in it changes: a DLNA renderer's failed song
 * came again with every poll, and an FCast receiver sends its whole status when its own volume
 * changes. Acted on each time, the one retry as AAC went out a second time whenever the phone took
 * longer to get the AAC copy than the next report took to come, and the renderer started the song
 * over.
 *
 * Not thread safe: the remote player reads reports on the main thread only.
 */
class SentSong<T>(
    val load: RemoteCommand.Load<T>,
    /** The link's number for this song, which its reports carry ([ReceiverStatus.load]). */
    val linkLoad: Int,
    /** The song's type as the app has it, whichever form was sent. */
    val mimeType: String,
    val choice: FormatChoice,
    /** A song on the phone, which has no AAC copy to fall back on. */
    val local: Boolean,
    /** Whether this is the one retry as AAC after the song failed as it is. */
    val retry: Boolean,
) {
    private var failureTaken = false

    /**
     * What [status] calls for. [latestTicket] is the player's latest load, since a song already
     * skipped past is not worth a retry, and [support] is what the receiver said it plays.
     */
    fun onReport(status: ReceiverStatus, latestTicket: Int, support: FormatSupport): ReportAction {
        if (status.load != linkLoad) return ReportAction.IGNORE
        if (status.state != ReceiverState.FAILED) return ReportAction.FOLLOW
        if (failureTaken) return ReportAction.IGNORE
        failureTaken = true
        val worthRetry = load.ticket == latestTicket &&
            CastFormats.retryAsAac(support, choice, mimeType, canFetchAac = !local)
        return if (worthRetry) ReportAction.RETRY_AS_AAC else ReportAction.FAILED
    }

    /**
     * The type this receiver turned out not to play, once [status] says that this retry as AAC
     * plays where the song as it is failed; null for anything else.
     */
    fun refusedType(status: ReceiverStatus): String? {
        val playing = status.state == ReceiverState.PLAYING || status.state == ReceiverState.PAUSED
        return if (retry && status.load == linkLoad && playing) CastMime.bare(mimeType) else null
    }
}
