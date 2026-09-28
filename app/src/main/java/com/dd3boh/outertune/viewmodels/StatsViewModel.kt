package com.dd3boh.outertune.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.constants.StatPeriod
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Album
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.stats.ListeningInsights
import com.dd3boh.outertune.stats.ListeningStats
import com.dd3boh.outertune.stats.StatsInput
import com.dd3boh.outertune.utils.reportException
import com.zionhuang.innertube.YouTube
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZonedDateTime
import javax.inject.Inject

/** What the page says about one period, with the songs and artists it names, read once. */
data class PeriodInsights(
    val period: StatPeriod,
    val stats: ListeningStats,
    val songs: Map<String, Song>,
    val artists: Map<String, ArtistEntity>,
)

/**
 * Whether a most played album's page is fetched for Stats: when it has no songs, as a saved album
 * arrives, or no artist, as an album made from songs has until its page is written. After
 * LgAlbumRepair that is most of them, and Stats listed them with nothing under their titles. Never
 * a local album, as in LibraryAlbumsViewModel: it has no page, and YouTube answers its id with an
 * HTTP 400.
 */
internal fun albumPageWanted(album: Album): Boolean =
    !album.album.isLocal && (album.album.songCount == 0 || album.artists.isEmpty())

/**
 * Whether a most played album whose page YouTube no longer has is deleted: only one with no songs,
 * the only kind this fetched before. One with songs, as LgAlbumRepair made them, keeps them
 * together until someone opens it, and AlbumViewModel decides then.
 */
internal fun albumDeletedWhenGone(album: Album): Boolean = album.album.songCount == 0

// redoing this whole feature later, plz ignore the slop code
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    val database: MusicDatabase,
) : ViewModel() {
    private val TAG = StatsViewModel::class.simpleName.toString()

    val statPeriod = MutableStateFlow(StatPeriod.`1_WEEK`)

    val mostPlayedSongs = statPeriod.flatMapLatest { period ->
        database.mostPlayedSongs(period.toTimeMillis())
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Counted from the play events over the period itself, as the songs above and the albums
    // below are, and not from the monthly play counts, which could only start at the first of a
    // month: 1 week was the month so far.
    //
    // The YouTube-artist filter lives in StatsSql.MOST_PLAYED_ARTISTS itself, applied before its
    // LIMIT: filtering here, after the row is already cut to 6, would drop local artists and
    // leave the row short, or empty, while more played YouTube artists sit outside that top 6.
    val mostPlayedArtists = statPeriod.flatMapLatest { period ->
        database.mostPlayedArtistsSince(period.toTimeMillis())
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())


    val mostPlayedAlbums = statPeriod.flatMapLatest { period ->
        database.mostPlayedAlbums(period.toTimeMillis())
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Touched only from the collector below, which runs on the main thread.
    private val insightsCache = HashMap<StatPeriod, PeriodInsights>()

    /**
     * What the page says about the period picked, above the lists.
     *
     * One pass the first time each period is picked, kept for as long as this view model lives,
     * and made from plain reads of the listen log rather than Room flows. The log is written on
     * every play, and this view model lives on while the stats screen sits on the back stack with
     * the phone asleep, so anything observing it would work the whole page out again after every
     * song; the passes in init below are what that cost here before. Picking a period is a tap,
     * and a tap is when this runs. Null until the first pass is done. A read that failed shows
     * nothing and is not kept, so picking the period again tries again.
     */
    val insights: StateFlow<PeriodInsights?> = statPeriod.mapLatest { period ->
        insightsCache[period] ?: readInsights(period)?.also { insightsCache[period] = it }
            ?: PeriodInsights(period, ListeningStats.EMPTY, emptyMap(), emptyMap())
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    private suspend fun readInsights(period: StatPeriod): PeriodInsights? = withContext(Dispatchers.IO) {
        try {
            val started = System.nanoTime()
            val now = ZonedDateTime.now()
            val nowMs = now.toInstant().toEpochMilli()
            val start = period.startMillis(now)
            val listens = database.statsListens(start)
            val stats = ListeningInsights.compute(
                StatsInput(
                    now = nowMs,
                    nowOffsetMin = now.offset.totalSeconds / 60,
                    periodStart = start,
                    listens = listens,
                    before = if (start > 0) database.statsSongsBefore(start) else emptyList(),
                    artists = database.statsSongArtists().associate { it.songId to it.artistId },
                    previous = if (start > 0) database.statsTotals(start - (nowMs - start), start) else null,
                    bounds = database.statsBounds(),
                )
            )
            // Only what the findings name: a handful of songs and artists, for pictures and taps.
            val songIds = stats.insights.mapNotNull { it.songId }.distinct()
            val songs = if (songIds.isEmpty()) emptyMap() else database.songsByIds(songIds).first().associateBy { it.id }
            val artists = stats.insights.mapNotNull { it.artistId }.distinct()
                .mapNotNull { database.artistById(it) }
                .associateBy { it.id }
            Log.d(TAG, "Insights for $period: ${listens.size} listens, ${stats.insights.size} findings, ${(System.nanoTime() - started) / 1_000_000} ms")
            PeriodInsights(period, stats, songs, artists)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportException(e)
            null
        }
    }

    init {
        // fetch missing artist metadata
        //
        // Once for each period the user picks, not on every change to the list. This used to
        // collect mostPlayedArtists, which is a Room query that runs again whenever the artist,
        // song, song_artist_map or event table is written, for as long as this view model
        // lives, and that includes while the stats screen is open or on the back stack with the
        // phone screen off. Every counted play adds an event and can reorder the list, and each
        // new list was rescanned and every artist that still qualified was fetched again,
        // including one whose page kept failing. An artist whose page has no picture kept it
        // going with no playback at all: the fetch stamps lastUpdateTime, which is a change to
        // the list, and the picture is still missing, so it qualified again and was fetched
        // again, round and round.
        //
        // It reads the Room query itself because mostPlayedArtists is a StateFlow seeded with
        // an empty list, and right after the period changes it still holds the old period's
        // artists. The query itself keeps to YouTube artists, and the isYouTubeArtist check
        // below is only a guard, since a local artist has no page to fetch. Picking a period is
        // a tap, not a write, so it makes one pass. Whatever fails this time, or has no picture
        // on YouTube, is tried again when its period is picked again or the next time this view
        // model is created.
        viewModelScope.launch {
            statPeriod.collect { period ->
                database.mostPlayedArtistsSince(period.toTimeMillis()).first()
                    .map { it.artist }
                    .filter {
                        it.isYouTubeArtist && (it.thumbnailUrl == null || Duration.between(it.lastUpdateTime, LocalDateTime.now()) > Duration.ofDays(10))
                    }
                    .forEach { artist ->
                        YouTube.artist(artist.id).onSuccess { artistPage ->
                            database.query {
                                update(artist, artistPage)
                            }
                        }
                    }
            }
        }
        // fetch missing album metadata
        //
        // Once per period as well, for the same reasons. This list also carries each album's
        // artists, so every time the pass above stamped an artist credited on one of these
        // albums it changed and started another pass here, and so did every play that
        // reordered it. An album whose page really has no songs is written back with a song
        // count of 0, so it qualified on every one of those passes and was fetched again each
        // time.
        //
        // One with no artist is fetched too (see albumPageWanted). One whose page names no artist
        // either is fetched again when its period is picked again, and no more often. The page is
        // written over the row as it is when written, not as read before the fetch, as in
        // AlbumViewModel: update writes the whole row, so a heart tapped meanwhile would be undone,
        // and there are many more of these fetches now. A page YouTube no longer has deletes only
        // what it deleted before (see albumDeletedWhenGone).
        viewModelScope.launch {
            statPeriod.collect { period ->
                database.mostPlayedAlbums(period.toTimeMillis()).first().filter(::albumPageWanted).forEach { album ->
                    YouTube.album(album.id).onSuccess { albumPage ->
                        database.transaction {
                            albumById(album.id)?.let { current -> update(current, albumPage) }
                        }
                    }.onFailure {
                        reportException(it)
                        if (it.message?.contains("NOT_FOUND") == true && albumDeletedWhenGone(album)) {
                            database.query {
                                delete(album.album)
                            }
                        }
                    }
                }
            }
        }
    }
}
