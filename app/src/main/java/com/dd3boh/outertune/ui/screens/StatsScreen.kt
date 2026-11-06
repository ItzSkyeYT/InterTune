package com.dd3boh.outertune.ui.screens

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.stats.FoundHere
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
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
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.viewmodels.StatsViewModel
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

        // Found with InterTune (his idea of 6 Nov 2026): all time, whatever period is picked above,
        // and left out until something qualifies, as the lists below are.
        found?.takeIf { it.total > 0 }?.let { f ->
            item(key = "foundWithInterTune") {
                FoundWithInterTune(f, Modifier.animateItem())
            }
        }

        insights?.let { shown ->
            statsInsights(
                shown = shown,
                loading = shown.period != statPeriod,
                wide = wide,
                expanded = showAllInsights,
                onExpandedChange = { showAllInsights = it },
                actions = insightActions,
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
            key = { _, song -> song.id }
        ) { index, song ->
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
                            items = mostPlayedSongs.map { it.toMediaMetadata() },
                            // The song tapped, not the top of the list.
                            startIndex = index,
                            songPicked = true,
                        ),
                        origin = PlayOrigin.STATS,
                    )
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
                        key = { it.id }
                    ) { artist ->
                        ArtistGridItem(
                            artist = artist,
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
                        key = { it.id }
                    ) { album ->
                        AlbumGridItem(
                            album = album,
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
 * Found with InterTune: the songs the listener liked that InterTune played them first, the count big
 * beside its words, then where those first plays came from, a row each with its share as a bar.
 */
@Composable
private fun FoundWithInterTune(found: FoundHere.Result, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val since = remember(found.since, locale) {
        val day = Instant.ofEpochMilli(found.since).atZone(ZoneId.systemDefault()).toLocalDate()
        val skeleton = if (day.year == LocalDate.now().year) "dMMM" else "dMMMy"
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(day)
    }
    val most = found.byWay.maxOf { it.second }.coerceAtLeast(1)
    Column(modifier = modifier) {
        NavigationTitle(title = stringResource(R.string.found_with_intertune))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
        ) {
            Text(
                text = found.total.toString(),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = pluralStringResource(R.plurals.found_liked_songs, found.total),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = pluralStringResource(R.plurals.found_first_heard_since, found.total, since),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        for ((way, n) in found.byWay) {
            val label = when (way) {
                FoundHere.Way.QUICK_PICKS -> stringResource(R.string.quick_picks)
                FoundHere.Way.RADIOS -> stringResource(R.string.found_way_radios)
                FoundHere.Way.DISCOVER -> stringResource(R.string.discover_something_new)
                FoundHere.Way.HOME -> stringResource(R.string.found_way_home)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    // Read as one: the way and its count.
                    .semantics(mergeDescendants = true) {},
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        text = n.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(n.toFloat() / most)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
    }
}

