/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.util.Log
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject
import com.dd3boh.outertune.playback.queues.Queue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The steps of MusicService.playQueue, from the tap to the queue being in the player.
 *
 * They are here, apart from the service, so that a test can hold their order to account: the
 * queue board and the player both need a live MusicService, and [Target] is the little of the two
 * that these steps use.
 *
 * A queue that names the tapped song ([Queue.preloadItem], which a song tapped on Home or in
 * search gives) puts that song in the player alone, as a queue of one, and then asks for the rest
 * over the network: the radio's other songs, which join around it when they arrive.
 *
 * The song is started as soon as it is in the player, beside that request and not after it. A
 * player that has played before is prepared already and has always loaded the song from that
 * moment. One that has not, for the first song after a start or the one after an error, used to
 * sit idle until the answer came: 1.7 s of the 5.0 and 6.5 s from the tap to sound measured on
 * 7 Oct 2026, where later songs took 2.1 to 3.0 s. And when the answer failed the song never
 * started at all.
 *
 * The last tap wins. The answer is a second or two away, longer on a poor connection, and another
 * song or list can be asked for in that time. Each run takes a number as it begins, and one that
 * comes back from a wait to find a later number asks nothing more of the board or the player. An
 * earlier tap's answer used to be loaded whenever it came: over the song tapped after it, which
 * its own answer then started again from the top, or for good when the answers came the other
 * way round.
 *
 * A pause stands. The song started at the tap can be paused again before the answer comes, by the
 * listener, by the sleep timer or by another app taking the sound for good. The answer then loads
 * the rest of the queue round it and leaves it paused; it used to set playWhenReady once more,
 * and the song played on a second after it was stopped. A song the app itself stopped because it
 * failed is the exception and gets its second try ([wantsToPlay]): with Skip on error on it has
 * no next song to skip to yet, and that try is what gets that listener their music.
 */
internal class QueueStart(private val service: Target) {

    /**
     * The number of the run that began last. Read and written on the main thread only, where every
     * run begins and where it comes back to after each wait, in the order the taps were made.
     */
    private var lastStart = 0L

    /** What the steps ask of the service. Every call is made on the main thread. */
    interface Target {
        /** True once the service is being torn down: nothing more is asked of it after that. */
        val destroyed: Boolean

        /** The title of a queue that neither the caller nor the queue's answer has named. */
        val untitled: String

        /** Returns once there is a queue board to add to. */
        suspend fun boardReady()

        /** QueueBoard.addQueue. */
        fun addQueue(
            title: String,
            items: List<MediaMetadata>,
            shuffled: Boolean,
            replace: Boolean,
            startIndex: Int,
            continuationEndpoint: String?,
        ): MultiQueueObject?

        /** Whether the board holds a queue of this title. */
        fun hasQueue(title: String): Boolean

        /** QueueBoard.deleteQueue, for the queue of this title when the board holds one. */
        fun dropQueue(title: String)

        /** QueueBoard.renameQueue. */
        fun renameQueue(queue: MultiQueueObject, title: String)

        /** QueueBoard.setCurrQueue: loads the queue into the player. */
        fun setCurrQueue(queue: MultiQueueObject?, shouldResume: Boolean)

        /** Prepares the player, which does nothing to one already prepared, and sets playWhenReady. */
        fun start(playWhenReady: Boolean)

        /** Whether the player means to play now: see [wantsToPlay]. */
        val wantsToPlay: Boolean
    }

    /**
     * @param origin A PlayOrigin code.
     * @throws Exception whatever the queue's answer failed with. The caller reports it.
     */
    suspend fun run(
        queue: Queue,
        playWhenReady: Boolean,
        shouldResume: Boolean,
        replace: Boolean,
        isRadio: Boolean,
        title: String?,
        origin: Int,
        originSlot: Int,
        runId: Long,
    ) {
        val number = ++lastStart
        var queueTitle = title
        var q: MultiQueueObject? = null
        val preloadItem = queue.preloadItem
        // Suspends here instead of blocking the caller, usually a click handler on the main
        // thread; ahead of the preload addQueue so the board exists before anything is added to
        // it.
        service.boardReady()
        // The board can wait for another caller's load or for a pending queue save, and the
        // service can be torn down meanwhile.
        if (service.destroyed) return
        // Or something else was asked for meanwhile, and this one's song is never loaded.
        if (number != lastStart) return
        if (preloadItem != null) {
            // The temporary title is this tap's alone, so a queue still under it goes first. It is
            // an earlier tap's: one whose answer failed, which leaves its song there alone, or
            // one whose answer has yet to come and will be dropped when it does. Found by that
            // title, it used to be given this song as well, and as the board keeps one resume
            // point for a queue, this song was loaded where the other had stopped: 1:35 into a
            // song tapped a moment ago, or past its end, where it ended as it started.
            if (title == null) service.dropQueue(PRELOAD_TITLE)
            q = service.addQueue(
                queueTitle ?: PRELOAD_TITLE,
                listOf(preloadItem),
                shuffled = queue.startShuffled,
                replace = replace,
                startIndex = 0,
                continuationEndpoint = null // fulfilled later on after initial status
            )
            q?.origin = origin
            q?.originSlot = originSlot
            q?.runId = runId
            service.setCurrQueue(q, true)
            // Started now, while the rest is asked for. Only here, with a song of this queue in
            // the player: a queue that names none leaves the player holding the queue before it,
            // and starting that would play the old song.
            service.start(playWhenReady)
        }

        val initialStatus = try {
            withContext(Dispatchers.IO) { queue.getInitialStatus() }
        } catch (e: Exception) {
            // An answer that failed for something no longer wanted is not the listener's to hear
            // about: what they asked for after it reports for itself.
            if (number != lastStart) return
            throw e
        }
        // The same after the network wait. Nothing below suspends, and onDestroy runs on the same
        // main thread, so it cannot slip in between.
        if (service.destroyed) return
        // Nor can another tap. One made during the wait has the player now, or will have it when
        // its own answer comes, and this answer is dropped whole: it is not loaded, and it leaves
        // no queue of its own on the board.
        if (number != lastStart) {
            Log.d(TAG, "playQueue: Queue initial status dropped, another queue was asked for since")
            return
        }
        // The queue of one goes once the full queue is loaded, where a queue of the answer's title
        // is on the board already: the board fills that one, and renaming the queue of one as
        // well left two queues of one name, one of them a single song. A radio started twice
        // was enough. Only when there are songs to fill it with: an empty answer leaves the queue
        // of one as the song's own, and it takes the title as it always has.
        var spent = false
        // do not find a title if an override is provided
        if ((title == null) && initialStatus.title != null) {
            queueTitle = initialStatus.title

            if (preloadItem != null && q != null) {
                spent = !initialStatus.items.isEmpty() && service.hasQueue(queueTitle)
                if (!spent) service.renameQueue(q, queueTitle)
            }
        }

        Log.d(TAG, "playQueue: Queue initial status item count: ${initialStatus.items.size}")
        if (!initialStatus.items.isEmpty()) {
            val (items, start) = initialStatus.withPreload(preloadItem)
            val full = service.addQueue(
                queueTitle ?: service.untitled,
                items,
                shuffled = queue.startShuffled,
                replace = replace || preloadItem != null,
                startIndex = start,
                continuationEndpoint = if (isRadio) items.takeLast(4).shuffled().first().id else null // yq?.getContinuationEndpoint()
            )
            full?.origin = origin
            full?.originSlot = originSlot
            full?.runId = runId
            service.setCurrQueue(full, shouldResume)
            // After the load, so the song is never without a queue.
            if (spent) service.dropQueue(PRELOAD_TITLE)
        }

        // For a queue that had no song to start with, this is its start. After an early start the
        // player is prepared already, and a song that failed to load meanwhile gets its second try
        // here. What it does not do then is play a song paused since the tap: see the class note.
        service.start(if (preloadItem != null) playWhenReady && service.wantsToPlay else playWhenReady)
    }

    companion object {
        // These lines were MusicService's own and are read under its tag when a start is timed.
        private const val TAG = "MusicService"

        /** What the queue of one is called until the queue's answer names it. */
        const val PRELOAD_TITLE = "Radio\u2060temp"

        /**
         * Whether a player means to play. One the app paused because a song failed still does:
         * to the player that pause and the listener's are the same playWhenReady false, and only
         * the listener's is to be left alone.
         */
        fun wantsToPlay(playWhenReady: Boolean, stoppedByError: Boolean) = playWhenReady || stoppedByError
    }
}
