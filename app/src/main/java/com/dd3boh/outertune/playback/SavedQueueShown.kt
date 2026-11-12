/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

/**
 * What the screens show of the saved queue before anything plays, decided apart from them so it
 * can be tested.
 *
 * After a start the player is empty, and the mini player shows the saved queue's song until Play
 * loads that queue (PlayerConnection). Both of these keep it from showing a song that nothing can
 * load: a tester's mini player sat on one old song at one old point each time Android had closed
 * the app, and Play, Next and Previous did nothing (10 Nov 2026).
 */
object SavedQueueShown {

    /**
     * Whether the saved queue's song stands in while the player holds none.
     *
     * Not with Persistent queue off. The queue board then starts with no queue
     * (MusicService.initQueue) and saves none, but the rows saved before the switch went off are
     * still in the database, as they were that day. Shown from them, the song was one the board
     * could not load, at the point it had reached back then.
     */
    fun song(playerHoldsSong: Boolean, persistentQueue: Boolean): Boolean = !playerHoldsSong && persistentQueue

    /**
     * Whether the player's sheet is up with nothing to play under it: the queues have been read,
     * there is none, and no song is shown.
     *
     * The sheet comes up by itself only when there is a queue, but Android brings a closed app's
     * screen back with the sheet where it was left. Over a board with no queue that was a mini
     * player whose buttons did nothing, so it goes back down. After a force stop there is nothing
     * to bring back, which is why a force stop never showed it.
     *
     * @param sheetUp where the sheet was last sent, not where it is drawn: one brought back is
     * drawn at the bottom for its first frames
     */
    fun sheetOverNothing(queuesRead: Boolean, queues: Int, songShown: Boolean, sheetUp: Boolean): Boolean =
        sheetUp && queuesRead && queues == 0 && !songShown
}
