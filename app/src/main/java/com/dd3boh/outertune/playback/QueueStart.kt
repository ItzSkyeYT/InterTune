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
 */
internal class QueueStart(private val service: Target) {

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

        /** QueueBoard.renameQueue. */
        fun renameQueue(queue: MultiQueueObject, title: String)

        /** QueueBoard.setCurrQueue: loads the queue into the player. */
        fun setCurrQueue(queue: MultiQueueObject?, shouldResume: Boolean)

        /** Prepares the player, which does nothing to one already prepared, and sets playWhenReady. */
        fun start(playWhenReady: Boolean)
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
        if (preloadItem != null) {
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
        }

        val initialStatus = withContext(Dispatchers.IO) { queue.getInitialStatus() }
        // The same after the network wait. Nothing below suspends, and onDestroy runs on the same
        // main thread, so it cannot slip in between.
        if (service.destroyed) return
        // do not find a title if an override is provided
        if ((title == null) && initialStatus.title != null) {
            queueTitle = initialStatus.title

            if (preloadItem != null && q != null) {
                service.renameQueue(q, queueTitle)
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
        }

        service.start(playWhenReady)
    }

    companion object {
        // These lines were MusicService's own and are read under its tag when a start is timed.
        private const val TAG = "MusicService"

        /** What the queue of one is called until the queue's answer names it. */
        const val PRELOAD_TITLE = "Radio\u2060temp"
    }
}
