package com.dd3boh.outertune.ui.menu

import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.LocalNetworkConnected
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.ArtistSongSortType
import com.dd3boh.outertune.db.entities.Artist
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.component.items.ArtistListItem
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun ArtistMenu(
    originalArtist: Artist,
    coroutineScope: CoroutineScope,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val isNetworkConnected = LocalNetworkConnected.current
    val artistState = database.artist(originalArtist.id).collectAsState(initial = originalArtist)
    val artist = artistState.value ?: originalArtist

    ArtistListItem(
        artist = artist,
        badges = {},
        trailingContent = {
            IconButton(
                onClick = {
                    database.transaction {
                        update(artist.artist.toggleLike())
                    }
                }
            ) {
                Icon(
                    painter = painterResource(if (artist.artist.bookmarkedAt != null) R.drawable.favorite else R.drawable.favorite_border),
                    tint = if (artist.artist.bookmarkedAt != null) MaterialTheme.colorScheme.error else LocalContentColor.current,
                    contentDescription = null
                )
            }
        }
    )

    HorizontalDivider()

    GridMenu(
        contentPadding = PaddingValues(
            start = 8.dp,
            top = 8.dp,
            end = 8.dp,
            bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
        )
    ) {
        if (artist.songCount > 0) {
            GridMenuItem(
                icon = Icons.Rounded.PlayArrow,
                title = R.string.play
            ) {
                val artistId = artist.id
                val title = artist.artist.name
                val mixFrom = artistId.takeIf { artist.artist.isYouTubeArtist }
                coroutineScope.launch {
                    val songs = withContext(Dispatchers.IO) {
                        database.artistSongs(artistId, ArtistSongSortType.CREATE_DATE, true).first()
                            .map { it.toMediaMetadata() }
                    }

                    val playlistId = withContext(Dispatchers.IO) { artistMixId(mixFrom) }

                    playerConnection.playQueue(
                        ListQueue(
                            title = title,
                            items = songs,
                            playlistId = playlistId
                        ),
                        origin = PlayOrigin.ARTIST,
                    )
                }
                onDismiss()
            }
            GridMenuItem(
                icon = Icons.Rounded.Shuffle,
                title = R.string.shuffle
            ) {
                val artistId = artist.id
                val title = artist.artist.name
                val mixFrom = artistId.takeIf { artist.artist.isYouTubeArtist }
                coroutineScope.launch {
                    val songs = withContext(Dispatchers.IO) {
                        database.artistSongs(artistId, ArtistSongSortType.CREATE_DATE, true).first()
                            .map { it.toMediaMetadata() }
                            .shuffled()
                    }

                    val playlistId = withContext(Dispatchers.IO) { artistMixId(mixFrom) }

                    playerConnection.playQueue(
                        ListQueue(
                            title = title,
                            items = songs,
                            playlistId = playlistId
                        ),
                        origin = PlayOrigin.ARTIST,
                    )
                }
                onDismiss()
            }
        }
        if (artist.artist.isYouTubeArtist) {
            GridMenuItem(
                icon = Icons.Rounded.Share,
                title = R.string.share
            ) {
                onDismiss()
                val intent = Intent().apply {
                    action = Intent.ACTION_SEND
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "https://music.youtube.com/channel/${artist.id}")
                }
                context.startActivity(Intent.createChooser(intent, null))
            }
        }
    }
}

/**
 * The artist's YouTube mix, which only carries playback on once their songs run out, so it gets
 * three seconds and the songs play either way, as on the artist's songs page. Play and Shuffle
 * here waited for it with no limit: offline, the tap sat there until the request timed out, and
 * a local artist, which YouTube does not know, paid a failed round trip every time. Null [artistId]
 * for one that is not a YouTube artist.
 */
private suspend fun artistMixId(artistId: String?): String? = artistId?.let { id ->
    withTimeoutOrNull(3_000) {
        YouTube.artist(id).getOrNull()?.artist?.shuffleEndpoint?.playlistId
    }
}
