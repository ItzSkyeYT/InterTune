package com.dd3boh.outertune.ui.screens

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.LocalMenuState
import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.constants.StatPeriod
import com.dd3boh.outertune.constants.SwipeToQueueKey
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.stats.ListeningInsights
import com.dd3boh.outertune.ui.component.ChipsRow
import com.dd3boh.outertune.ui.component.EmptyPlaceholder
import com.dd3boh.outertune.ui.component.FloatingTopBar
import com.dd3boh.outertune.ui.component.LazyColumnScrollbar
import com.dd3boh.outertune.ui.component.NavigationTitle
import com.dd3boh.outertune.ui.component.items.AlbumGridItem
import com.dd3boh.outertune.ui.component.items.ArtistGridItem
import com.dd3boh.outertune.ui.component.items.SongListItem
import com.dd3boh.outertune.ui.menu.AlbumMenu
import com.dd3boh.outertune.ui.menu.ArtistMenu
import com.dd3boh.outertune.ui.menu.SongMenu
import com.dd3boh.outertune.utils.joinByBullet
import com.dd3boh.outertune.utils.makeTimeString
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.viewmodels.StatsViewModel
import java.io.File
import kotlin.math.roundToInt
import com.dd3boh.outertune.ui.utils.itemsInColumns
import com.dd3boh.outertune.ui.utils.rememberListColumns

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StatsScreen(
    navController: NavController,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val density = LocalDensity.current
    val menuState = LocalMenuState.current
    val playerConnection = LocalPlayerConnection.current ?: return

    val swipeEnabled by rememberPreference(SwipeToQueueKey, true)

    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val statPeriod by viewModel.statPeriod.collectAsState()
    val mostPlayedSongs by viewModel.mostPlayedSongs.collectAsState()
    val mostPlayedArtists by viewModel.mostPlayedArtists.collectAsState()
    val mostPlayedAlbums by viewModel.mostPlayedAlbums.collectAsState()
    val insights by viewModel.insights.collectAsState()
    val found by viewModel.found.collectAsState()
    var showAllInsights by rememberSaveable { mutableStateOf(false) }
    // Two cards to a row once there is room for two, as on a phone turned sideways.
    val wide = LocalConfiguration.current.screenWidthDp >= 600

    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()
    // The numbers of the three lists are written as the cards above write theirs.
    val format = rememberStatsFormat()
    // Where they sit: the page's own look, or in a debug build another one, to be photographed.
    val context = LocalContext.current
    val look = remember { StatsNumbersLook.of(context) }

    val mostPlayedSongTitle = stringResource(R.string.most_played_songs)
    val statsTitle = stringResource(R.string.stats)
    // A song in a finding does what it does in the list below: tapped, it plays, or pauses if it
    // is the one playing; held, it opens the song menu. An artist opens the artist's page.
    val insightActions = InsightActions(
        activeSongId = mediaMetadata?.id,
        isPlaying = isPlaying,
        onSong = { song ->
            if (song.id == mediaMetadata?.id) {
                playerConnection.togglePlayPause()
            } else {
                playerConnection.playQueue(
                    ListQueue(title = statsTitle, items = listOf(song.toMediaMetadata())),
                    origin = PlayOrigin.STATS,
                )
            }
        },
        onSongMenu = { song ->
            menuState.show {
                SongMenu(
                    originalSong = song,
                    navController = navController,
                    onDismiss = menuState::dismiss,
                )
            }
        },
        onArtist = { artist -> navController.navigate("artist/${artist.id}") },
    )

    // Two rows abreast when the phone is on its side, one otherwise (Landscape.kt).
    val columns = rememberListColumns()
    LazyColumn(
        state = lazyListState,
        contentPadding = LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
            .asPaddingValues(),
        modifier = Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top))
    ) {
        item {
            ChipsRow(
                chips = listOf(
                    StatPeriod.`1_WEEK` to pluralStringResource(R.plurals.n_week, 1, 1),
                    StatPeriod.`1_MONTH` to pluralStringResource(R.plurals.n_month, 1, 1),
                    StatPeriod.`3_MONTH` to pluralStringResource(R.plurals.n_month, 3, 3),
                    StatPeriod.`6_MONTH` to pluralStringResource(R.plurals.n_month, 6, 6),
                    StatPeriod.`1_YEAR` to pluralStringResource(R.plurals.n_year, 1, 1),
                    StatPeriod.ALL to stringResource(R.string.filter_all)
                ),
                currentValue = statPeriod,
                onValueUpdate = { viewModel.statPeriod.value = it },
                isLoading = { it == statPeriod && insights?.period != statPeriod },
            )
        }

        insights?.let { shown ->
            statsInsights(
                shown = shown,
                loading = shown.period != statPeriod,
                wide = wide,
                expanded = showAllInsights,
                onExpandedChange = { showAllInsights = it },
                actions = insightActions,
                found = found,
            )
        }

        // Left out when the period has no plays, as Most played artists already is: a heading over
        // nothing read as a list that failed to load.
        if (mostPlayedSongs.isNotEmpty()) {
            item(key = "mostPlayedSongs") {
                NavigationTitle(
                    title = stringResource(R.string.most_played_songs),
                    modifier = Modifier.animateItem()
                )
            }
        }

        val thumbnailSize = (ListThumbnailSize.value * density.density).roundToInt()
        itemsInColumns(
            items = mostPlayedSongs,
            columns = columns,
            key = { _, played -> played.song.id }
        ) { index, played ->
            val song = played.song
            val plays = playsText(played.plays, format)
            SongListItem(
                song = song,
                navController = navController,

                isActive = song.song.id == mediaMetadata?.id,
                isPlaying = isPlaying,
                inSelectMode = false,
                isSelected = false,
                onSelectedChange = {},
                swipeEnabled = swipeEnabled,

                thumbnailSize = thumbnailSize,
                onPlay = {
                    playerConnection.playQueue(
                        ListQueue(
                            title = mostPlayedSongTitle,
                            items = mostPlayedSongs.map { it.song.toMediaMetadata() },
                            // The song tapped, not the top of the list.
                            startIndex = index,
                            songPicked = true,
                        ),
                        origin = PlayOrigin.STATS,
                    )
                },
                // Its plays in the period, at the end of the row, where History has the time of a
                // play: the line under the title stays the artist and the length, as on every list.
                trailingText = plays.takeIf { look == StatsNumbersLook.A },
                // The looks that put it in that line instead, after the length or in its place.
                caption = when (look) {
                    StatsNumbersLook.B -> joinByBullet(makeTimeString(song.song.duration * 1000L), plays)
                    StatsNumbersLook.C -> plays
                    else -> null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItem()
            )
        }

        if (mostPlayedArtists.isNotEmpty()) {
            item(key = "mostPlayedArtists") {
                NavigationTitle(
                    title = stringResource(R.string.most_played_artists),
                    modifier = Modifier.animateItem()
                )

                LazyRow(
                    modifier = Modifier.animateItem()
                ) {
                    items(
                        items = mostPlayedArtists,
                        key = { it.artist.id }
                    ) { played ->
                        val artist = played.artist
                        val plays = playsText(played.plays, format)
                        // Under a minute the time would read "0 min", and is left out.
                        val time = format.duration(played.playTime).takeIf { played.playTime >= ListeningInsights.MINUTE_MS }
                        ArtistGridItem(
                            artist = artist,
                            // The plays that rank the row and the time listened, one under the other
                            // below the count of songs: a tile is too narrow for the two side by
                            // side. In the looks B and C the plays take the place of that count.
                            subtitle = plays.takeIf { look == StatsNumbersLook.B || look == StatsNumbersLook.C },
                            lines = when (look) {
                                StatsNumbersLook.NONE -> emptyList()
                                StatsNumbersLook.A -> listOfNotNull(plays, time)
                                else -> listOfNotNull(time)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        navController.navigate("artist/${artist.id}")
                                    },
                                    onLongClick = {
                                        menuState.show {
                                            ArtistMenu(
                                                originalArtist = artist,
                                                coroutineScope = coroutineScope,
                                                onDismiss = menuState::dismiss
                                            )
                                        }
                                    }
                                )
                                .animateItem()
                        )
                    }
                }
            }
        }

        if (mostPlayedAlbums.isNotEmpty()) {
            item(key = "mostPlayedAlbums") {
                NavigationTitle(
                    title = stringResource(R.string.most_played_albums),
                    modifier = Modifier.animateItem()
                )

                LazyRow(
                    modifier = Modifier.animateItem()
                ) {
                    items(
                        items = mostPlayedAlbums,
                        key = { it.album.id }
                    ) { played ->
                        val album = played.album
                        AlbumGridItem(
                            album = album,
                            lines = if (look == StatsNumbersLook.NONE) emptyList() else listOf(playsText(played.plays, format)),
                            isActive = album.id == mediaMetadata?.album?.id,
                            isPlaying = isPlaying,
                            coroutineScope = coroutineScope,
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        navController.navigate("album/${album.id}")
                                    },
                                    onLongClick = {
                                        menuState.show {
                                            AlbumMenu(
                                                originalAlbum = album,
                                                navController = navController,
                                                onDismiss = menuState::dismiss
                                            )
                                        }
                                    }
                                )
                                .animateItem()
                        )
                    }
                }
            }
        }

        // A period with no plays had nothing at all under the chips, and a blank page reads as
        // one that is still loading. Said once this period's numbers are in, so it does not
        // show while they are on their way.
        val nothingPlayed = insights?.let { it.period == statPeriod && it.stats.summary == null } == true &&
                mostPlayedSongs.isEmpty() && mostPlayedArtists.isEmpty() && mostPlayedAlbums.isEmpty()
        if (nothingPlayed) {
            item(key = "nothingPlayed") {
                EmptyPlaceholder(
                    icon = Icons.AutoMirrored.Rounded.TrendingUp,
                    text = stringResource(R.string.stats_nothing_played),
                    modifier = Modifier.animateItem()
                )
            }
        }
    }
    LazyColumnScrollbar(
        state = lazyListState,
    )

    FloatingTopBar(title = stringResource(R.string.stats), navController = navController)
}

/**
 * Where the numbers of the Most played lists sit. [A] is the page's look. The others are here to
 * be photographed beside it while the choice is open (experiments/features/stats-more/sheet.png),
 * and go with this switch once it is made. A debug build takes the letter in the file [FILE] of
 * its files folder, read when the page opens; any other build, and a debug build without the
 * file, has [A].
 */
internal enum class StatsNumbersLook {
    /** The page as it was, without the numbers: the picture of before, from the same build. */
    NONE,

    /** Nothing gives way: a song's plays at the end of its row, an artist's plays and time under the count of songs. */
    A,

    /** In the lines there are: "Artist • 4:04 • 12 plays", and an artist's plays in place of the count of songs. */
    B,

    /** As [B], with a song's plays in place of its length: "Artist • 12 plays". */
    C;

    companion object {
        const val FILE = "stats_numbers_look"

        fun of(context: Context): StatsNumbersLook {
            if (!BuildConfig.DEBUG) return A
            val letter = runCatching { File(context.filesDir, FILE).readText().trim() }.getOrNull()
            return StatsNumbersLook.entries.firstOrNull { it.name == letter } ?: A
        }
    }
}

/** "12 plays", the count in the phone's own digits and grouping, as the cards above write one. */
@Composable
private fun playsText(plays: Int, format: StatsFormat): String =
    pluralStringResource(R.plurals.stats_plays, plays, format.count(plays))
