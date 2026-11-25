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
import com.dd3boh.outertune.ui.screens.recap.NoteSeal
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
 *     adb shell am broadcast -a dev.skye.intertune.debug.RECAP_SAMPLE -p dev.skye.intertune.debug [--ez reveal true] [--ez open true] [--ez long true]
 *
 * With `--es note "<text>|<text>"` instead, it only seals those texts as last year's notes to this
 * year's Recap (RecapNote), a day apart up to a year ago today, and opens the Recap with open: the
 * slide "A note from your past self" can be seen on a phone that has none. With `--ei slow 3` it only slows the
 * sealing of a note down (NoteSeal), to film it on an emulator that draws few frames a second.
 *
 * It finds about thirty well-known songs by name with the import's own anonymous search (a
 * search each, a second and a half apart), keeps the songs each search lists after the first as
 * well, and writes listens of them from June to yesterday: an evening habit, a long streak, a
 * number one found in July and played twelve times in a row that day, a song Auto found in
 * September, a hundred more Auto brought, two songs named by ear, skips; and, for the slides of
 * 24 Nov 2026 (RecapMoments), a song that the nights after midnight go to and one the mornings do, a
 * week in which one song plays seven times a day, and a song loved in its first weeks, dropped for a
 * hundred days and found again. With long, the year begins on 5 January, so that it has all four
 * seasons. Nobody's listening:
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
                // A year with nothing only InterTune does: no Auto finds, nothing named by ear (WhereFromSlide).
                val plain = intent.getBooleanExtra("plain", false)
                // Only the songs named by ear, no Auto finds: Only on InterTune has no count for its paper flower to sit on (22 Nov 2026).
                val ear = intent.getBooleanExtra("ear", false)
                // A year of the phone's own files, those already scanned, instead of songs found on YouTube (local listeners, 22 Nov 2026).
                val local = intent.getBooleanExtra("local", false)
                // A year from 5 January, with all four seasons (RecapMoments' seasons, 24 Nov 2026).
                val long = intent.getBooleanExtra("long", false)
                // Notes sealed for last year's Recap, split on "|", written a day apart up to a year ago today, so that this
                // year's opens with them (NOTE_READ): nothing else is written.
                intent.getStringExtra("note")?.let { all ->
                    scope.launch {
                        val year = LocalDate.now().year - 1
                        val texts = all.split('|').map { it.trim() }.filter { it.isNotEmpty() }
                        val last = LocalDate.now().minusYears(1)
                        val kept = texts.withIndex().count { (i, text) -> RecapNote.save(app, year, text, last.minusDays((texts.size - 1 - i).toLong()).toEpochDay()) }
                        Log.i(TAG, "notes for $year: $kept of ${texts.size} sealed: done")
                        if (kept > 0 && open) RecapDoor.open(LocalDate.now().year)
                    }
                    return
                }
                // The note's seal three times slower (`--ei slow 3`, 1 to 10; 1 again with `--ei slow 1`), to film it on an
                // emulator that draws few frames a second (NoteSeal.slow). Nothing is written.
                if (intent.hasExtra("slow")) {
                    NoteSeal.slow = intent.getIntExtra("slow", 1).coerceIn(1, 10).toFloat()
                    Log.i(TAG, "seal: ${NoteSeal.slow.toInt()} times slower")
                    return
                }
                // One file's loudest stretch, for the emulator (RecapChorus): nothing is written.
                intent.getStringExtra("chorus")?.let { path ->
                    scope.launch {
                        val heard = RecapChorus.envelope(path)
                        val start = heard?.let { (e, d) -> RecapChorus.startMs(e, RecapChorus.STEP_MS, intent.getLongExtra("pieceMs", 10_000L), d) }
                        Log.i(TAG, "chorus of $path: ${heard?.first?.size ?: 0} places read, ${heard?.second ?: 0} ms long, the loudest stretch from ${start ?: "nowhere"} ms")
                    }
                    return
                }
                scope.launch {
                    if (!running.tryLock()) {
                        Log.w(TAG, "already writing a sample")
                        return@launch
                    }
                    try {
                        if (write(app, database, reveal, plain, local, ear, long) && open) RecapDoor.open(LocalDate.now().year)
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
    private suspend fun write(context: Context, database: MusicDatabase, reveal: Boolean, plain: Boolean = false, local: Boolean = false, ear: Boolean = false, long: Boolean = false): Boolean {
        val existing = database.listenCount().first()
        if (existing > MOST_EXISTING_LISTENS) {
            Log.w(TAG, "refused: this database already holds $existing listens")
            return false
        }
        // A made-up year is never counted: its Recap is written down as counted before anything
        // else, so opening it sends nothing (PollChecker.recapOpened sends only a first opening).
        val thisYear = LocalDate.now().year
        context.dataStore.edit { it[ViewedPollIdsKey] = it[ViewedPollIdsKey].orEmpty() + RecapCount.id(thisYear - 1) + RecapCount.id(thisYear) }
        val named = ArrayList<SongItem>()
        val extras = ArrayList<SongItem>()
        if (local) {
            // The files already in the library, as songs of the year; nothing is searched for and nothing is written to the song table.
            database.allLocalSongs().sortedBy { it.song.title }.mapTo(named) { SongItem(it.id, it.song.title, emptyList(), duration = it.song.duration, thumbnail = "") }
            Log.i(TAG, "a year of ${named.size} files on this phone")
        }
        if (!local) Log.i(TAG, "finding ${NAMES.size} songs by name")
        for (name in if (local) emptyList() else NAMES) {
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
        if (!local) database.transactionNow {
            (named + extras).forEach { insert(it.toMediaMetadata()) }
        }
        // The pictures of the named songs' artists, as opening their pages would store them.
        for (artistId in if (local) emptyList() else named.mapNotNull { it.artists.firstOrNull()?.id }.distinct()) {
            val artist = database.artistById(artistId) ?: continue
            YouTube.artist(artistId).onSuccess { page -> database.update(artist, page) }
            delay(1000)
        }

        val zone = ZoneId.systemDefault()
        val year = Year(LocalDate.now(zone), zone, named, extras, plain, ear, long)
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
        Log.i(TAG, "done: ${named.size} named songs, ${extras.size} more, ${listens.size} listens, ${liked.size} liked, reveal=$reveal, plain=$plain, local=$local, long=$long")
        return true
    }

    /** The year itself, drawn from a fixed seed. */
    private class Year(
        val today: LocalDate, val zone: ZoneId, val named: List<SongItem>, val extras: List<SongItem>, val plain: Boolean = false, val ear: Boolean = false,
        val long: Boolean = false,
    ) {
        private val random = Random(2026)
        private val listens = ArrayList<Listen>()
        private val start: LocalDate = if (long && today.isAfter(LocalDate.of(today.year, 3, 1))) LocalDate.of(today.year, 1, 5) else today.minusDays(100).let { early ->
            val june = LocalDate.of(today.year, 6, 2)
            val from = if (june.isBefore(early)) june else early
            if (from.year < today.year) LocalDate.of(today.year, 1, 1) else from
        }
        private val end = today.minusDays(1)

        /** The named songs that have a part in the year already: the number one, Auto's find and the two named by ear (below). */
        private val used = mutableSetOf(0, 5, 6, 7)

        /** Named song [want] for a part of its own, or the first one with none yet, where [want] has one or there are too few songs. */
        private fun planted(want: Int): SongItem? {
            val i = (listOf(want) + named.indices).firstOrNull { it in named.indices && it !in used } ?: return null
            used += i
            return named[i]
        }

        /** What the nights after midnight go to, and the mornings (RecapMoments.nightAndMorning). */
        private val nightSong = planted(26)
        private val morningSong = planted(9)
        /** Seven days in which one song plays seven times a day, at ten past nine in the evening (RecapMoments.onRepeat). */
        private val weekSong = planted(2)
        private val weekFrom = end.minusDays(40)
        /** Loved in the first weeks, dropped for a hundred days, found again (RecapMoments.cameBack): where the year is long enough. */
        private val backSong = planted(15)
        private val goneFrom = start.plusDays(20)
        private val backOn = start.plusDays(120).takeIf { !it.isAfter(end.minusDays(20)) }

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
                // Plain: what Quick picks played comes from a playlist, what was named by ear from a search.
                origin = (if (!plain && !ear) origin else when (origin) {
                    PlayOrigin.QUICK_PICKS -> PlayOrigin.PLAYLIST
                    PlayOrigin.RECOGNISED -> if (plain) PlayOrigin.SEARCH else origin
                    else -> origin
                }).code,
                originSlot = -1,
                queueId = 0,
                autoplayDepth = 0,
                sessionId = session,
                counted = !skip,
                contextChip = ContextChip.AUTO,
            )
            return startAt + played + 2_000
        }

        /**
         * A song drawn by weight: the first named songs far more than the rest, the number one once found. After midnight the
         * night's song more than half the time, in the morning the morning's a fifth of the time; the song found again never
         * while it is away.
         */
        private fun pick(day: LocalDate, minute: Int = 18 * 60): SongItem {
            if (minute < RecapMoments.DAY_BEGINS_MIN && nightSong != null && random.nextDouble() < 0.55) return nightSong
            if (minute in RecapMoments.DAY_BEGINS_MIN until RecapMoments.MORNING_ENDS_MIN && morningSong != null && random.nextDouble() < 0.2) return morningSong
            val away = backOn != null && !day.isBefore(goneFrom) && day.isBefore(backOn)
            val pool = named.indices.filter { i ->
                !(i == 0 && day.isBefore(hitFound)) && !(i == 5 && day.isBefore(stuckFound)) && i !in 6..7 && !(away && named[i] == backSong)
            }
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
                            t = add(pick(day, minute), t, session, origin, skip = random.nextDouble() < 0.15)
                        }
                    }
                }
                // The week one song took over: seven plays of it each evening, on top of the rest.
                if (weekSong != null && !day.isBefore(weekFrom) && day.isBefore(weekFrom.plusDays(7)) && !day.isAfter(end)) {
                    val session = at(day, 21 * 60 + 10)
                    var t = session
                    repeat(7) { t = add(weekSong, t, session, PlayOrigin.LIBRARY) }
                }
                // The song found again: twice every third day in its first weeks, then from the day it comes back.
                if (backSong != null && backOn != null) {
                    val first = (day.toEpochDay() - start.plusDays(4).toEpochDay()).let { it in 0L..15L && it % 3 == 0L }
                    val again = (day.toEpochDay() - backOn.toEpochDay()).let { it in 0L..9L && it % 3 == 0L }
                    if (first || again) {
                        val session = at(day, 20 * 60 + 30)
                        var t = session
                        repeat(2) { t = add(backSong, t, session, PlayOrigin.LIBRARY) }
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

        fun heard(): List<Heard> = if (plain) emptyList() else byEar.mapIndexed { i, song ->
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
