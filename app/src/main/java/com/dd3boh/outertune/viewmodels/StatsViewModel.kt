package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.constants.StatPeriod
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.utils.reportException
import com.zionhuang.innertube.YouTube
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime
import javax.inject.Inject

// redoing this whole feature later, plz ignore the slop code
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    val database: MusicDatabase,
) : ViewModel() {
    val statPeriod = MutableStateFlow(StatPeriod.`1_WEEK`)

    val mostPlayedSongs = statPeriod.flatMapLatest { period ->
        database.mostPlayedSongs(period.toTimeMillis())
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val mostPlayedArtists = statPeriod.flatMapLatest { period ->
        val time = period.toLocalDateTime()
        database.mostPlayedArtists(time.year, time.month.value).map { artists ->
            artists.filter { it.artist.isYouTubeArtist }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())


    val mostPlayedAlbums = statPeriod.flatMapLatest { period ->
        database.mostPlayedAlbums(period.toTimeMillis())
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        // fetch missing artist metadata
        //
        // Once for each period the user picks, not on every change to the list. This used to
        // collect mostPlayedArtists, which is a Room query that runs again whenever the artist,
        // song, song_artist_map or playCount table is written, for as long as this view model
        // lives, and that includes while the stats screen is open or on the back stack with the
        // phone screen off. Every counted play adds one to a playCount row and can reorder the
        // list, and each new list was rescanned and every artist that still qualified was
        // fetched again, including one whose page kept failing. An artist whose page has no
        // picture kept it going with no playback at all: the fetch stamps lastUpdateTime, which
        // is a change to the list, and the picture is still missing, so it qualified again and
        // was fetched again, round and round.
        //
        // It reads the Room query itself because mostPlayedArtists is a StateFlow seeded with
        // an empty list, and right after the period changes it still holds the old period's
        // artists. That list only has YouTube artists in it, so the same filter is applied
        // here: a local artist has no page to fetch. Picking a period is a tap, not a write, so
        // it makes one pass. Whatever fails this time, or has no picture on YouTube, is tried
        // again when its period is picked again or the next time this view model is created.
        viewModelScope.launch {
            statPeriod.collect { period ->
                val time = period.toLocalDateTime()
                database.mostPlayedArtists(time.year, time.month.value).first()
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
        viewModelScope.launch {
            statPeriod.collect { period ->
                database.mostPlayedAlbums(period.toTimeMillis()).first().filter {
                    it.album.songCount == 0
                }.forEach { album ->
                    YouTube.album(album.id).onSuccess { albumPage ->
                        database.query {
                            update(album.album, albumPage)
                        }
                    }.onFailure {
                        reportException(it)
                        if (it.message?.contains("NOT_FOUND") == true) {
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
