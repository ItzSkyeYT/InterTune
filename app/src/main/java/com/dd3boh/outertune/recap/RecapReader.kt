/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.recognition.RecognitionHistory
import com.dd3boh.outertune.ui.utils.resize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** A song as the Recap shows it. [cover] is what the image loader is handed; [durationS] is -1 when unknown. */
data class RecapSongShown(val id: String, val title: String, val artists: String, val cover: Any?, val durationS: Int)

data class RecapArtistShown(val id: String, val name: String, val picture: String?)

/** A year's Recap with the names and covers its slides show. */
data class RecapShown(
    val recap: Recap,
    val songs: Map<String, RecapSongShown>,
    val artists: Map<String, RecapArtistShown>,
) {
    fun song(id: String?): RecapSongShown? = id?.let(songs::get)

    /**
     * Covers for the opening screen: songs from the year that give nothing away, never the top
     * five, the songs of the slides before them first.
     */
    val mosaic: List<Any> by lazy {
        val top = recap.topSongs.mapTo(HashSet()) { it.songId }
        (listOfNotNull(recap.firstSong.songId, recap.mostTime?.songId, recap.peakSong.songId, recap.streak?.songId, recap.auto?.stuck?.songId) +
            recap.topArtists.map { it.topSongId } + recap.alsoPlayed)
            .filter { it !in top }
            .distinct()
            .mapNotNull { songs[it]?.cover }
    }
}

/**
 * Reads a year's Recap from the database and recognition's history, on the IO dispatcher, and
 * names what it found. Nothing leaves the phone.
 */
object RecapReader {
    /** Covers are asked for at this size: the largest on screen is about 290 dp, on a card 780 px. */
    const val COVER_PX = 800
    private const val FACE_PX = 360

    /**
     * Where to start reading for [year]: the start of the 1st of January in [zone], less a day,
     * so that the rows just before the year say what was heard before it.
     */
    private fun from(year: Int, zone: ZoneId) =
        LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli() - Recaps.DAY_MS

    /**
     * Whether [year] holds enough for a Recap, by one quick count: the pop-up and the banner ask
     * this before anything is worked out. Near enough: the Recap itself may still find too little.
     */
    suspend fun hasEnough(database: MusicDatabase, year: Int, zone: ZoneId = ZoneId.systemDefault()): Boolean = withContext(Dispatchers.IO) {
        val start = LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val amount = database.recapAmount(start, end)
        amount.plays >= Recaps.MIN_PLAYS && amount.playedMs >= Recaps.MIN_LISTENING_MS
    }

    suspend fun read(
        database: MusicDatabase,
        history: RecognitionHistory,
        year: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): RecapShown? = withContext(Dispatchers.IO) {
        val now = ZonedDateTime.now(zone)
        val from = from(year, zone)
        val input = RecapInput(
            year = year,
            now = now.toInstant().toEpochMilli(),
            nowOffsetMin = now.offset.totalSeconds / 60,
            listens = database.recapListens(from),
            credits = database.recapCredits(from),
            firstCredits = database.statsSongArtists().associate { it.songId to it.artistId },
            before = database.statsSongsBefore(from),
            liked = database.recapLiked(from).toHashSet(),
            bounds = database.statsBounds(),
            heard = history.entries.first().map { RecapHeard(it.at, it.videoId, it.title, it.artist) },
        )
        val recap = Recaps.compute(input) ?: return@withContext null
        val songIds = buildList {
            add(recap.firstSong.songId)
            recap.mostTime?.let { add(it.songId) }
            add(recap.peakSong.songId)
            recap.streak?.let { add(it.songId) }
            recap.topSongs.mapTo(this) { it.songId }
            recap.topArtists.mapTo(this) { it.topSongId }
            recap.auto?.let { add(it.stuck.songId) }
            recap.recognition?.let { addAll(it.songIds) }
            recap.newArtists?.bestFind?.let { add(it.topSongId) }
            addAll(recap.alsoPlayed)
        }.distinct()
        val songs = database.songsByIds(songIds).first().associate { it.id to shown(it) }
        val artists = (recap.topArtists.map { it.artistId } + listOfNotNull(recap.newArtists?.bestFind?.artistId))
            .distinct()
            .mapNotNull { database.artistById(it) }
            .associate { it.id to shown(it) }
        RecapShown(recap, songs, artists)
    }

    private fun shown(song: Song) = RecapSongShown(
        id = song.id,
        title = song.song.title,
        artists = song.artists.joinToString(", ") { it.name },
        cover = if (song.song.isLocal) song.song.getThumbnailModel(COVER_PX, COVER_PX) else song.song.thumbnailUrl?.resize(COVER_PX, COVER_PX),
        durationS = song.song.duration,
    )

    private fun shown(artist: ArtistEntity) = RecapArtistShown(artist.id, artist.name, artist.thumbnailUrl?.resize(FACE_PX, FACE_PX))
}
