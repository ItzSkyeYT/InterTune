/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DoNotDisturbOn
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.ListItemHeight
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.models.ArtistBlock
import com.dd3boh.outertune.models.BlockedArtist
import com.dd3boh.outertune.models.ContentFilter
import com.dd3boh.outertune.ui.dialog.DefaultDialog
import com.dd3boh.outertune.ui.dialog.ListDialog
import com.dd3boh.outertune.ui.utils.resize
import com.dd3boh.outertune.utils.ArtistBlocking
import com.zionhuang.innertube.models.Artist
import kotlinx.coroutines.flow.map

/** The icon of Block artist: a ring with a bar, set apart from Not this song's slashed ring. */
val BlockArtistIcon = Icons.Outlined.DoNotDisturbOn

/** The blocked artists as they stand, followed. */
@Composable
fun rememberArtistBlock(): ArtistBlock {
    val flow = remember { ContentFilter.changes.map { it.blocked } }
    val block by flow.collectAsState(ContentFilter.current.blocked)
    return block
}

/** The artists a stored song's Block artist offers: each with a name, once. */
@JvmName("blockChoicesOfEntities")
fun blockChoices(artists: List<ArtistEntity>): List<BlockedArtist> =
    artists.filter { it.name.isNotBlank() }.map { BlockedArtist(it.id, it.name, it.thumbnailUrl) }.distinctBy { it.id }

/**
 * The artists a song from YouTube offers: those its credits link to, as View artist has them. A
 * credit that links nowhere is often not an artist at all ("60M plays" under a Quick picks card).
 */
@JvmName("blockChoicesOfCredits")
fun blockChoices(artists: List<Artist>): List<BlockedArtist> =
    artists.filter { it.id != null && it.name.isNotBlank() }.map { BlockedArtist(it.id, it.name) }.distinctBy { it.id }

/**
 * Block artist from a song's menu: which artist first when the song has several, then the
 * question. [onClose] is called when it is left without blocking, [onBlocked] once it blocked.
 */
@Composable
fun BlockArtistFlow(
    artists: List<BlockedArtist>,
    onClose: () -> Unit,
    onBlocked: () -> Unit,
) {
    var chosen by rememberSaveable { mutableStateOf<Int?>(if (artists.size == 1) 0 else null) }
    val artist = chosen?.let(artists::getOrNull)
    if (artist == null) {
        BlockArtistChoice(artists = artists, onPick = { chosen = artists.indexOf(it) }, onDismiss = onClose)
    } else {
        BlockArtistConfirm(artist = artist, onDismiss = onClose, onBlocked = onBlocked)
    }
}

/** Which of a song's artists to block. */
@Composable
fun BlockArtistChoice(
    artists: List<BlockedArtist>,
    onPick: (BlockedArtist) -> Unit,
    onDismiss: () -> Unit,
) {
    // Pictures only when there are some to show: a song from YouTube names its artists with none.
    val pictured = artists.any { it.thumbnail != null }
    ListDialog(onDismiss = onDismiss) {
        item {
            Text(
                text = stringResource(R.string.block_artist_which),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
            )
        }
        items(artists) { artist ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillParentMaxWidth()
                    .height(ListItemHeight)
                    .clickable { onPick(artist) }
                    .padding(horizontal = 24.dp),
            ) {
                if (pictured) ArtistPicture(artist.thumbnail, Modifier.size(40.dp))
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The question before blocking [artist], which says what blocking does and where to undo it. */
@Composable
fun BlockArtistConfirm(
    artist: BlockedArtist,
    onDismiss: () -> Unit,
    onBlocked: () -> Unit,
) {
    val context = LocalContext.current
    DefaultDialog(
        onDismiss = onDismiss,
        modifier = Modifier.padding(16.dp),
        horizontalAlignment = Alignment.Start,
        icon = { Icon(BlockArtistIcon, contentDescription = null) },
        title = {
            Text(
                text = stringResource(R.string.block_artist_title, artist.name),
                textAlign = TextAlign.Center,
            )
        },
        buttons = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            TextButton(onClick = {
                ArtistBlocking.block(context, artist)
                onBlocked()
            }) { Text(stringResource(R.string.block_artist_confirm)) }
        },
    ) {
        // The words give way to the buttons where the window is short: they scroll.
        Text(
            text = stringResource(R.string.block_artist_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        )
    }
}

/**
 * Where something of a blocked artist would have been: one quiet line that says so, and Unblock.
 * Search for their name and their page show it.
 */
@Composable
fun BlockedNote(
    artist: BlockedArtist,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // At the inset of the page's headings and chips, under the bar.
    Column(modifier = modifier.padding(horizontal = 12.dp, vertical = 16.dp)) {
        Text(
            text = stringResource(R.string.blocked_search_note, artist.name),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // The button's own padding would push its word off the line above: drawn as a word, with
        // the room to press it round it.
        Text(
            text = stringResource(R.string.unblock),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 4.dp)
                .clip(MaterialTheme.shapes.small)
                .clickable { ArtistBlocking.unblock(context, artist) }
                .padding(vertical = 8.dp),
        )
    }
}

/** An artist's picture in a circle, or a person on a disc where there is none. */
@Composable
fun ArtistPicture(thumbnail: String?, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        if (thumbnail != null) {
            AsyncImage(
                model = thumbnail.resize(144, 144),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
