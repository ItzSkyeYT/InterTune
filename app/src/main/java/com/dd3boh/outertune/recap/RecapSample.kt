/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.constants.EndReason
import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.constants.RecapPopUpShownKey
import com.dd3boh.outertune.constants.RecapRevealedEarlyKey
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.constants.ViewedPollIdsKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Listen
import com.dd3boh.outertune.engine.ContextChip
import com.dd3boh.outertune.migration.ImportSearch
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.recognition.Heard
import com.dd3boh.outertune.recognition.RecognitionHistory
import com.dd3boh.outertune.utils.dataStore
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * A made-up year of listening, to look at the Recap on an emulator, whose own install has next to
 * no history. A debug build only, and only from adb:
 *
 *     adb shell am broadcast -a dev.skye.intertune.debug.RECAP_SAMPLE -p dev.skye.intertune.debug [--ez reveal true] [--ez open true]
 *
 * It finds about thirty well-known songs by name with the import's own anonymous search (a
 * search each, a second and a half apart), keeps the songs each search lists after the first as
 * well, and writes listens of them from June to yesterday: an evening habit, a long streak, a
 * number one found in July and played twelve times in a row that day, a song Auto found in
 * September, a hundred more Auto brought, two songs named by ear, skips. Nobody's listening:
 * everything is drawn from a fixed seed, so two runs write the same year. With reveal, today is
 * the reveal day and the pop-up is owed again, so the next start of the app opens the Recap. With
 * open, the Recap opens as soon as the year is written. The log says "done" when it is.
 *
 * Only the shell can send it (android.permission.DUMP, as CastProbe), only a debug build listens
 * (not the Preview, which holds a library on the test phone), and it refuses any database that
 * already holds more than [MOST_EXISTING_LISTENS] listens: a phone that has been listened on keeps
 * its history as it is.
 */
object RecapSample {
    private const val TAG = "RecapSample"

    /** More listens than this and the database is someone's: nothing is written. */
    const val MOST_EXISTING_LISTENS = 50

    private val action = "${BuildConfig.APPLICATION_ID}.RECAP_SAMPLE"

    /** The songs, by name. Famous ones, for covers anybody might know. */
    private val NAMES = listOf(
        "Daft Punk One More Time", "Sub Focus Solar System", "Dusky Ingrid Is A Hybrid", "C418 Sweden",
        "Fred again.. Delilah (pull me out of this)", "Disclosure Latch", "Bicep Glue", "Avicii Levels",
        "Swedish House Mafia Don't You Worry Child", "Peggy Gou (It Goes Like) Nanana", "Four Tet Baby",
        "ODESZA A Moment Apart", "Justice D.A.N.C.E.", "The Chemical Brothers Galvanize", "Overmono So U Kno",
        "Flume Never Be Like You", "Royksopp Eple", "Deorro Five Hours", "Prospa Prayer", "John Summit Where You Are",
        "berlioz jazz is for ordinary people", "Kx5 Escape", "Calvin Harris Feel So Close", "Chase & Status Baddadan",
        "Daft Punk Get Lucky", "Sub Focus Vibration", "C418 Wet Hands", "Fred again.. Marea (we've lost dancing)",
    )

    private val running = Mutex()

    fun offered() = BuildConfig.BUILD_TYPE == "debug" && Unreleased.RECAP

    /** Listens for the broadcast until [stop]; null where it is not offered. */
    fun listen(context: Context, database: MusicDatabase): BroadcastReceiver? {
        if (!offered()) return null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val reveal = intent.getBooleanExtra("reveal", false)
                val open = intent.getBooleanExtra("open", false)
                scope.launch {
                    if (!running.tryLock()) {
                        Log.w(TAG, "already writing a sample")
                        return@launch
                    }
                    try {
                        if (write(app, database, reveal) && open) RecapDoor.open(LocalDate.now().year)
                    } catch (e: Exception) {
                        Log.w(TAG, "failed: ${e.message}", e)
                    } finally {
                        running.unlock()
                    }
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), Manifest.permission.DUMP, null, ContextCompat.RECEIVER_EXPORTED)
        Log.i(TAG, "Listening for $action")
        return receiver
    }

    fun stop(context: Context, receiver: BroadcastReceiver?) {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
    }

    /** Writes the year; false when it was refused. Each write waits for the one before (transactionNow). */
    private suspend fun write(context: Context, database: MusicDatabase, reveal: Boolean): Boolean {
        val existing = database.listenCount().first()
        if (existing > MOST_EXISTING_LISTENS) {
            Log.w(TAG, "refused: this database already holds $existing listens")
            return false
        }
        // A made-up year is never counted: its Recap is written down as counted before anything
        // else, so opening it sends nothing (PollChecker.recapOpened sends only a first opening).
        val thisYear = LocalDate.now().year
        context.dataStore.edit { it[ViewedPollIdsKey] = it[ViewedPollIdsKey].orEmpty() + RecapCount.id(thisYear - 1) + RecapCount.id(thisYear) }
        Log.i(TAG, "finding ${NAMES.size} songs by name")
        val named = ArrayList<SongItem>()
        val extras = ArrayList<SongItem>()
        for (name in NAMES) {
            val found = runCatching { ImportSearch.songs(name) }
                .onFailure { Log.w(TAG, "search failed for \"$name\": ${it.message}") }
                .getOrNull().orEmpty()
                .filter { (it.duration ?: 0) in 60..900 }
            found.firstOrNull()?.let { first ->
                if (named.none { it.id == first.id }) named += first
                found.drop(1).take(8).filterTo(extras) { e -> named.none { it.id == e.id } && extras.none { it.id == e.id } }
            }
            delay(1500)
        }
        if (named.size < 8) {
            Log.w(TAG, "refused: only ${named.size} songs found, too few for a year")
            return false
        }
        database.transactionNow {
            (named + extras).forEach { insert(it.toMediaMetadata()) }
        }
        // The pictures of the named songs' artists, as opening their pages would store them.
        for (artistId in named.mapNotNull { it.artists.firstOrNull()?.id }.distinct()) {
            val artist = database.artistById(artistId) ?: continue
            YouTube.artist(artistId).onSuccess { page -> database.update(artist, page) }
            delay(1000)
        }

        val zone = ZoneId.systemDefault()
        val year = Year(LocalDate.now(zone), zone, named, extras)
        val listens = year.listens()
        database.transactionNow { listens.forEach { insert(it) } }
        val liked = year.liked()
        database.transactionNow {
            for (id in liked) songRow(id)?.let { update(it.copy(liked = true, likedDate = LocalDateTime.now())) }
        }
        val history = RecognitionHistory(context)
        for (heard in year.heard()) history.add(heard)
        if (reveal) context.dataStore.edit {
            it[RecapRevealedEarlyKey] = true
            it.remove(RecapPopUpShownKey)
        }
        Log.i(TAG, "done: ${named.size} named songs, ${extras.size} more, ${listens.size} listens, ${liked.size} liked, reveal=$reveal")
        return true
    }

    /** The year itself, drawn from a fixed seed. */
    private class Year(val today: LocalDate, val zone: ZoneId, val named: List<SongItem>, val extras: List<SongItem>) {
        private val random = Random(2026)
        private val listens = ArrayList<Listen>()
        private val start: LocalDate = today.minusDays(100).let { early ->
            val june = LocalDate.of(today.year, 6, 2)
            val from = if (june.isBefore(early)) june else early
            if (from.year < today.year) LocalDate.of(today.year, 1, 1) else from
        }
        private val end = today.minusDays(1)

        /** The number one, found on its day and played on and on from then. */
        private val hit = named[0]
        private val hitFound = start.plusDays(38)
        /** What Auto found on its day, and played a lot after. */
        private val stuck = named[5]
        private val stuckFound = end.minusDays(48)
        /** The songs named by ear, both on one day. */
        private val byEar = listOf(named[6], named[7])
        private val byEarDay = end.minusDays(29)
        /** A hundred and more that Auto brought, from Quick picks, after the live log began. */
        private val autoBrought = extras.filterIndexed { i, _ -> i % 2 == 0 }.take(120)
        private val autoFrom = start.plusDays(60)

        private fun at(day: LocalDate, minuteOfDay: Int): Long =
            day.atStartOfDay(zone).toInstant().toEpochMilli() + minuteOfDay * 60_000L

        private fun offset(at: Long) = zone.rules.getOffset(Instant.ofEpochMilli(at)).totalSeconds / 60

        private fun add(song: SongItem, startAt: Long, session: Long, origin: PlayOrigin, skip: Boolean = false): Long {
            val duration = (song.duration ?: 180) * 1000L
            val played = if (skip) (10_000L + random.nextLong(30_000)) else duration
            listens += Listen(
                songId = song.id,
                startedAt = startAt,
                endedAt = startAt + played,
                tzOffsetMin = offset(startAt),
                playedMs = played,
                durationMs = duration,
                ratio = played.toFloat() / duration,
                endReason = if (skip) EndReason.SKIPPED else EndReason.ENDED,
                origin = origin.code,
                originSlot = -1,
                queueId = 0,
                autoplayDepth = 0,
                sessionId = session,
                counted = !skip,
                contextChip = ContextChip.AUTO,
            )
            return startAt + played + 2_000
        }

        /** A song drawn by weight: the first named songs far more than the rest, the number one once found. */
        private fun pick(day: LocalDate): SongItem {
            val pool = named.indices.filter { i -> !(i == 0 && day.isBefore(hitFound)) && !(i == 5 && day.isBefore(stuckFound)) && i !in 6..7 }
            val weights = pool.map { i ->
                when (i) {
                    0 -> 9.0
                    5 -> 6.0
                    else -> 8.0 / (i + 1)
                }
            }
            var r = random.nextDouble() * weights.sum()
            for ((k, w) in weights.withIndex()) {
                r -= w
                if (r <= 0) return named[pool[k]]
            }
            return named[pool.last()]
        }

        fun listens(): List<Listen> {
            val streakFrom = end.minusDays(72)
            val streakTo = end.minusDays(9)
            var autoNext = 0
            var day = start
            while (!day.isAfter(end)) {
                val special = day == hitFound || day == stuckFound || day == byEarDay
                val active = special || (!day.isBefore(streakFrom) && !day.isAfter(streakTo)) || random.nextDouble() < 0.6
                if (active) {
                    // Evenings most of all, some mornings and lunchtimes, and now and then past midnight.
                    val sessions = buildList {
                        add(18 * 60 + random.nextInt(50))
                        if (random.nextDouble() < 0.4) add(8 * 60 + random.nextInt(40))
                        if (random.nextDouble() < 0.3) add(13 * 60 + random.nextInt(30))
                        if (random.nextDouble() < 0.12) add(60 + random.nextInt(150))
                    }.sorted()
                    for (minute in sessions) {
                        val session = at(day, minute)
                        var t = session
                        if (day == hitFound && minute == sessions.last()) {
                            repeat(12) { t = add(hit, t, session, PlayOrigin.SEARCH) }
                            continue
                        }
                        if (day == stuckFound && minute == sessions.last()) {
                            t = add(stuck, t, session, PlayOrigin.QUICK_PICKS)
                            repeat(3) { t = add(stuck, t, session, PlayOrigin.QUICK_PICKS) }
                        }
                        if (day == byEarDay && minute == sessions.last()) {
                            for (song in byEar) t = add(song, t, session, PlayOrigin.RECOGNISED)
                        }
                        // Auto's finds, one or two a session from the day the live log began.
                        if (!day.isBefore(autoFrom) && autoNext < autoBrought.size) {
                            repeat(1 + random.nextInt(2)) {
                                if (autoNext < autoBrought.size) {
                                    val found = autoBrought[autoNext++]
                                    t = add(found, t, session, PlayOrigin.QUICK_PICKS, skip = random.nextDouble() < 0.3)
                                    // Some stay: played again on later days.
                                    if (autoNext % 7 == 0 && !day.plusDays(3).isAfter(end)) repeat(3) { k ->
                                        val later = at(day.plusDays(k + 1L), 23 * 60 + 40)
                                        add(found, later, later, PlayOrigin.LIBRARY)
                                    }
                                }
                            }
                        }
                        repeat(4 + random.nextInt(9)) {
                            val origin = listOf(PlayOrigin.SEARCH, PlayOrigin.PLAYLIST, PlayOrigin.ALBUM, PlayOrigin.RADIO, PlayOrigin.LIBRARY)[random.nextInt(5)]
                            t = add(pick(day), t, session, origin, skip = random.nextDouble() < 0.15)
                        }
                    }
                }
                day = day.plusDays(1)
            }
            // Extras the radios played now and then, once each, for covers from all over the year.
            extras.filterIndexed { i, _ -> i % 2 == 1 }.forEachIndexed { i, song ->
                val d = start.plusDays((i * 3L) % (end.toEpochDay() - start.toEpochDay() + 1))
                val s = at(d, 7 * 60 + i % 50)
                add(song, s, s, PlayOrigin.RADIO)
            }
            return listens
        }

        /** The number one, the song Auto found, and a few of Auto's other finds. */
        fun liked(): List<String> = listOf(hit.id, stuck.id) + autoBrought.filterIndexed { i, _ -> i % 9 == 4 }.map { it.id }

        fun heard(): List<Heard> = byEar.mapIndexed { i, song ->
            Heard(
                title = song.title,
                artist = song.artists.joinToString(", ") { it.name },
                videoId = song.id,
                at = at(byEarDay, 17 * 60 + 50 + i * 4),
                checked = true,
            )
        }
    }
}
