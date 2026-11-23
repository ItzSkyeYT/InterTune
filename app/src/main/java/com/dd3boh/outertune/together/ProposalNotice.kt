/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import com.dd3boh.outertune.together.TogetherSession.Proposal

/**
 * A guest's proposal as a notification, for the one in control while InterTune is not in front (his pick of 22 Nov 2026:
 * "Also a notification"): who proposes which song, with Not now and Play it, which do what the card's buttons do. Never
 * without Android's leave to post (Android 13 and later ask for it): the card at the top of the app waits either way.
 * Gone once the proposal is answered anywhere or control moves (TogetherSession.syncNotice, ProposalLine.noticeStep).
 */
internal class ProposalNotice(private val context: Context) {
    /** The proposal the notification shows, or null. */
    var showing: Proposal? = null
        private set

    /** InterTune on screen and in front: the card asks there, and no notification is needed. */
    fun inFront(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    fun show(proposal: Proposal) {
        // Android 13 and later: only with the person's leave. Before 13, whether notifications are on for the app.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "No leave to post notifications: the proposal waits on the card alone")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Made each time, so its name in Android's settings is in the app's language now (ChannelNames). High: it pops
            // up, as a message from someone does, since it waits for an answer.
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.together_proposals_channel), NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val song = proposal.songs.first()
        // A tap on it opens the app, where the card asks too, with the songs after it to tick.
        val open = PendingIntent.getActivity(context, REQUEST_OPEN, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(context.getString(R.string.together_proposal_notice, proposal.by, song.title.ifBlank { song.id }))
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .addAction(0, context.getString(R.string.together_proposal_later), answer(NOT_NOW, proposal))
            .addAction(0, context.getString(R.string.together_proposal_play), answer(PLAY, proposal))
        if (song.artist.isNotBlank()) builder.setContentText(song.artist)
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
            showing = proposal
        } catch (e: Exception) {
            // The leave taken back a moment ago, or a refusal of the system's: the card still asks.
            Log.w(TAG, "The proposal could not be posted", e)
        }
    }

    /** Taken down: answered, control moved, or the room is over. One from before a restart of the app goes too. */
    fun hide() {
        showing = null
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    /** A button's broadcast to the room's own receiver ([action]), naming the proposal it answers by when it came. */
    private fun answer(what: String, proposal: Proposal): PendingIntent = PendingIntent.getBroadcast(
        context,
        if (what == PLAY) REQUEST_PLAY else REQUEST_NOT_NOW,
        Intent(action(context)).setPackage(context.packageName).putExtra(EXTRA_ANSWER, what).putExtra(EXTRA_AT, proposal.receivedAt),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_ID = "together_proposals"

        /** Its own: no other notification of the app has it (NotificationIdsTest). */
        const val NOTIFICATION_ID = 4261

        /** What a button answers, [PLAY] or [NOT_NOW], and the proposal it answers, by [Proposal.receivedAt]. */
        const val EXTRA_ANSWER = "answer"
        const val EXTRA_AT = "at"
        const val PLAY = "play"
        const val NOT_NOW = "not_now"

        private const val REQUEST_OPEN = 4261
        private const val REQUEST_PLAY = 4262
        private const val REQUEST_NOT_NOW = 4263
        private const val TAG = "Together"

        /** The buttons' broadcast, per build: the debug and preview apps have their own package. */
        fun action(context: Context): String = context.packageName + ".ROOM_PROPOSAL"
    }
}
