/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.dd3boh.outertune.R
import com.dd3boh.outertune.db.entities.Playlist
import com.dd3boh.outertune.db.entities.PlaylistSong
import com.dd3boh.outertune.ui.component.items.PlaylistThumbnail
import com.dd3boh.outertune.ui.utils.DialogAboveKeyboard
import com.dd3boh.outertune.ui.utils.LocalLandscape
import com.dd3boh.outertune.utils.PlaylistCovers
import com.dd3boh.outertune.utils.getThumbnailModel
import java.io.File
import kotlin.math.roundToInt

/** The two big choices' picture, and the strip's. */
private val BigTile = 110.dp
private val StripTile = 68.dp

/** Round the picture of a choice, inside the cell that is selected with it: room for its ring and its badge. */
private val CellMargin = 4.dp

/**
 * The choice of a playlist's cover (Unreleased.PLAYLIST_COVER), a sheet that comes up from the
 * bottom, as the sleep timer's does: Automatic, a photo from the phone, or the cover of one of the
 * playlist's own songs, each under its own words, the current one ringed and ticked. Each pick
 * applies at once and the sheet stays up, so that the cover above it can be watched changing; there
 * is nothing to save.
 *
 * It only offers the choice. What happens on a pick (the photo picker, the write) is the caller's.
 * [songs] are read in the playlist's own order, whatever order the page shows them in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistCoverSheet(
    playlist: Playlist,
    songs: List<PlaylistSong>,
    onAutomatic: () -> Unit,
    onPhoto: () -> Unit,
    onSong: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val landscape = LocalLandscape.current
    val files = LocalContext.current.filesDir
    val stored = playlist.playlist.thumbnailUrl
    // The photo chosen, when its file is there. One that is gone counts as none chosen, which is what is drawn.
    val photo = remember(stored, files) { PlaylistCovers.fileOf(files, stored)?.takeIf { it.isFile } }
    val options = remember(songs) {
        PlaylistCovers.options(
            songs.sortedBy { it.map.position }.map {
                val song = it.song.song
                PlaylistCovers.CoverSource(PlaylistCovers.coverOf(song.thumbnailUrl, song.isLocal, song.localPath), it.song.title)
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // On its side a sheet is no wider than a menu is there, and the sides of the window are
        // left to the camera: see SleepTimerDialog.
        sheetMaxWidth = landscape.panelWidth(BottomSheetDefaults.SheetMaxWidth),
        contentWindowInsets = {
            if (landscape.active) WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical) else WindowInsets.safeDrawing
        },
    ) {
        // What does not fit a short window scrolls.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.playlist_cover_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .semantics { heading() }
            )
            Text(
                text = stringResource(R.string.playlist_cover_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp)
            )

            // The cells keep a margin round their pictures for the ring, so the row starts a margin
            // before the gutter and the gap between two is two margins less than it looks.
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp - CellMargin * 2),
                modifier = Modifier.padding(start = 24.dp - CellMargin, end = 24.dp - CellMargin, top = 16.dp - CellMargin)
            ) {
                ChoiceCell(
                    selected = stored == null || (PlaylistCovers.isPhoto(stored) && photo == null),
                    onClick = onAutomatic,
                    title = stringResource(R.string.playlist_cover_automatic),
                    note = stringResource(R.string.playlist_cover_automatic_note),
                    tile = BigTile,
                    radius = 12.dp,
                    badge = 24.dp,
                    wide = true,
                ) {
                    // The first song's cover as the playlist has it with none chosen.
                    PlaylistThumbnail(
                        playlist = playlist.playlist.copy(thumbnailUrl = null),
                        thumbnails = playlist.songThumbnails.filterNotNull(),
                        size = BigTile,
                        shape = RoundedCornerShape(12.dp),
                        iconPadding = BigTile / 6,
                        iconTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    )
                }
                ChoiceCell(
                    selected = photo != null,
                    onClick = onPhoto,
                    title = stringResource(R.string.playlist_cover_photo),
                    note = stringResource(R.string.playlist_cover_photo_note),
                    tile = BigTile,
                    radius = 12.dp,
                    badge = 24.dp,
                    wide = true,
                ) {
                    if (photo != null) {
                        PictureOf(photo, BigTile)
                    } else {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Image,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }
                }
            }

            // Nothing to pick from in a playlist whose songs have no cover.
            if (options.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.playlist_cover_songs),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 4.dp)
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp - CellMargin * 2),
                    contentPadding = PaddingValues(horizontal = 24.dp - CellMargin),
                ) {
                    items(options, key = { it.cover }) { option ->
                        ChoiceCell(
                            selected = stored == option.cover,
                            onClick = { onSong(option.cover) },
                            title = option.title,
                            note = null,
                            tile = StripTile,
                            radius = 8.dp,
                            badge = 20.dp,
                            wide = false,
                        ) {
                            SongPicture(option.cover, StripTile)
                        }
                    }
                }
            }
        }
    }
}

/** What the cover and its button take in Edit playlist: the room above the cover, the cover, and the button. */
private val EditorHeight = 16.dp + 88.dp + 40.dp

/** What the rest of Edit playlist takes, as the pencil leaves it (title, field, buttons, spaces), before the font is made larger. */
private val RestOfTheDialog = 200.dp

/** What the dialog's margins, the status bar and a keyboard (about 300dp) leave of the screen's height. */
private val ScreenBesideTheDialog = 372.dp

/**
 * The cover at the top of Edit playlist, in the place of its pencil, with Change cover under it.
 * A tap on either opens the sheet. The picture is left out of what a screen reader reads: the
 * button under it says it all.
 *
 * The cover with its button costs about a hundred dp more than the pencil, and the keyboard is up
 * as the dialog opens. On a screen too short for both (640dp, or a large font) it would push OK
 * under the keyboard, so there the pencil stays and the cover in the playlist's header opens the
 * sheet all the same.
 */
@Composable
fun PlaylistCoverEditor(playlist: Playlist, onChange: () -> Unit) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    if (LocalConfiguration.current.screenHeightDp.dp < EditorHeight + RestOfTheDialog * fontScale + ScreenBesideTheDialog) {
        Icon(imageVector = Icons.Rounded.Edit, contentDescription = null)
        return
    }
    // Upright, a dialog's window keeps its height and is moved up for the field, which would leave
    // OK behind the keyboard. Shrunk to what the keyboard leaves, the dialog stays in view.
    DialogAboveKeyboard(always = true)
    val shape = RoundedCornerShape(12.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(top = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .clip(shape)
                .clickable(onClick = onChange)
                .clearAndSetSemantics { }
        ) {
            PlaylistThumbnail(
                playlist = playlist.playlist,
                thumbnails = playlist.thumbnails,
                size = 88.dp,
                shape = shape,
                iconPadding = 88.dp / 16,
                iconTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            )
        }
        TextButton(onClick = onChange) {
            Text(stringResource(R.string.playlist_cover_change))
        }
    }
}

/**
 * One choice: its picture, its words under it, and the cell round both that is selected with a
 * tap. The one in use has a ring round its picture and a tick at its corner. A margin of
 * [CellMargin] is kept round the picture for them, so that neither is cut off by the cell.
 */
@Composable
private fun ChoiceCell(
    selected: Boolean,
    onClick: () -> Unit,
    title: String,
    note: String?,
    tile: Dp,
    radius: Dp,
    badge: Dp,
    wide: Boolean,
    picture: @Composable BoxScope.() -> Unit,
) {
    val ring = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            // The big choices are as wide as their words, up to a limit, since a note in French is
            // longer than the picture is wide. A song's is one picture wide and its title is cut short.
            .then(
                if (wide) Modifier.widthIn(min = tile + CellMargin * 2, max = 168.dp).width(IntrinsicSize.Max)
                else Modifier.width(tile + CellMargin * 2)
            )
            .clip(RoundedCornerShape(radius + CellMargin))
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(CellMargin)
    ) {
        Box(
            modifier = Modifier
                .size(tile)
                .drawWithContent {
                    drawContent()
                    if (selected) {
                        // Its outer edge a margin outside the picture, a little clear of it.
                        val stroke = 3.dp.toPx()
                        val inset = CellMargin.toPx() - stroke / 2
                        drawRoundRect(
                            color = ring,
                            topLeft = Offset(-inset, -inset),
                            size = Size(size.width + inset * 2, size.height + inset * 2),
                            cornerRadius = CornerRadius(radius.toPx() + inset),
                            style = Stroke(stroke),
                        )
                    }
                }
        ) {
            Box(Modifier.size(tile).clip(RoundedCornerShape(radius)), content = picture)
            if (selected) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = CellMargin, y = -CellMargin)
                        .size(badge)
                        .background(ring, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(badge * 0.7f)
                    )
                }
            }
        }
        Text(
            text = title,
            // A song's title in the strip is set a little tighter than a label, to show more of itself.
            style = if (note != null) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
            color = if (note != null || selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (note != null) 2 else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = if (note != null) 8.dp else 6.dp)
        )
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/** A kept photo, filling its tile. */
@Composable
private fun PictureOf(file: File, tile: Dp) {
    AsyncImage(
        model = file,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(tile)
    )
}

/** A song's cover, as the playlist's own pictures are drawn, filling its tile. */
@Composable
private fun SongPicture(cover: String, tile: Dp) {
    val px = (tile.value * LocalDensity.current.density).roundToInt()
    AsyncImage(
        model = remember(cover, px) { getThumbnailModel(cover, px, px) },
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(tile)
    )
}
