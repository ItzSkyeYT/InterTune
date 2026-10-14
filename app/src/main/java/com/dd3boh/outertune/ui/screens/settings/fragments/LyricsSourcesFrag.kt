/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.EnableBetterLyricsKey
import com.dd3boh.outertune.constants.EnableKugouKey
import com.dd3boh.outertune.constants.EnableLrcLibKey
import com.dd3boh.outertune.constants.EnableYouTubeCaptionsKey
import com.dd3boh.outertune.constants.EnableYouTubeLyricsKey
import com.dd3boh.outertune.constants.LyricsSourceOrderKey
import com.dd3boh.outertune.constants.ThumbnailCornerRadius
import com.dd3boh.outertune.extensions.move
import com.dd3boh.outertune.lyrics.LyricsSources
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.dialog.ActionPromptDialog
import com.dd3boh.outertune.ui.dialog.InfoLabel
import com.dd3boh.outertune.utils.rememberPreference
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Lyrics sources: one row that opens the sources in the order they are asked in, each with
 * a tick to switch it off and a handle to drag it up or down, the way Arrange tabs does for tabs.
 * Nothing is kept until OK.
 */
@Composable
fun LyricsSourcesEntry(modifier: Modifier = Modifier) {
    val (savedOrder, onSavedOrder) = rememberPreference(LyricsSourceOrderKey, defaultValue = "")
    val (betterLyrics, onBetterLyrics) = rememberPreference(EnableBetterLyricsKey, defaultValue = true)
    val (lrcLib, onLrcLib) = rememberPreference(EnableLrcLibKey, defaultValue = true)
    val (kugou, onKugou) = rememberPreference(EnableKugouKey, defaultValue = true)
    val (youTubeMusic, onYouTubeMusic) = rememberPreference(EnableYouTubeLyricsKey, defaultValue = true)
    val (captions, onCaptions) = rememberPreference(EnableYouTubeCaptionsKey, defaultValue = true)
    val on = mapOf(
        LyricsSources.BETTER_LYRICS to betterLyrics, LyricsSources.YOUTUBE_CAPTIONS to captions, LyricsSources.LRCLIB to lrcLib,
        LyricsSources.KUGOU to kugou, LyricsSources.YOUTUBE_MUSIC to youTubeMusic,
    )
    val switch = mapOf(
        LyricsSources.BETTER_LYRICS to onBetterLyrics, LyricsSources.YOUTUBE_CAPTIONS to onCaptions, LyricsSources.LRCLIB to onLrcLib,
        LyricsSources.KUGOU to onKugou, LyricsSources.YOUTUBE_MUSIC to onYouTubeMusic,
    )
    val names = mapOf(
        LyricsSources.BETTER_LYRICS to stringResource(R.string.lyrics_source_better_lyrics),
        LyricsSources.YOUTUBE_CAPTIONS to stringResource(R.string.lyrics_source_youtube_captions),
        LyricsSources.LRCLIB to stringResource(R.string.lyrics_source_lrclib),
        LyricsSources.KUGOU to stringResource(R.string.lyrics_source_kugou),
        LyricsSources.YOUTUBE_MUSIC to stringResource(R.string.lyrics_source_youtube_music),
    )
    val lines = mapOf(
        LyricsSources.BETTER_LYRICS to stringResource(R.string.lyrics_source_better_lyrics_line),
        LyricsSources.YOUTUBE_CAPTIONS to stringResource(R.string.lyrics_source_youtube_captions_line),
        LyricsSources.LRCLIB to stringResource(R.string.enable_lrclib_description),
        LyricsSources.KUGOU to stringResource(R.string.enable_kugou_description),
        LyricsSources.YOUTUBE_MUSIC to stringResource(R.string.lyrics_source_youtube_music_line),
    )

    var open by rememberSaveable { mutableStateOf(false) }
    // Each source with whether it is ticked, in the order shown.
    val rows = remember { mutableStateListOf<Pair<String, Boolean>>() }
    fun load(order: List<String>, ticked: (String) -> Boolean) {
        rows.clear()
        rows.addAll(order.map { it to ticked(it) })
    }
    LaunchedEffect(open) {
        if (open) load(LyricsSources.order(savedOrder)) { on[it] != false }
    }
    val listState = rememberLazyListState()
    val reorderable = rememberReorderableLazyListState(lazyListState = listState) { from, to ->
        rows.move(from.index, to.index)
    }

    PreferenceEntry(
        modifier = modifier,
        title = { Text(stringResource(R.string.lyrics_sources)) },
        description = LyricsSources.order(savedOrder).filter { on[it] != false }.mapNotNull { names[it] }.joinToString(", "),
        icon = { Icon(Icons.Rounded.Lyrics, null) },
        onClick = { open = true },
    )

    if (open) {
        ActionPromptDialog(
            title = stringResource(R.string.lyrics_sources),
            onDismiss = { open = false },
            onConfirm = {
                onSavedOrder(LyricsSources.encode(rows.map { it.first }))
                rows.forEach { (id, ticked) -> switch[id]?.invoke(ticked) }
                open = false
            },
            onReset = { load(LyricsSources.DEFAULT) { true } },
            onCancel = { open = false },
        ) {
            LazyColumn(
                state = listState,
                // Gives way to the note under it when the dialog is short: the list scrolls.
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(vertical = 12.dp)
                    .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(ThumbnailCornerRadius))
            ) {
                items(rows, key = { it.first }) { row ->
                    ReorderableItem(state = reorderable, key = row.first) {
                        fun flip() {
                            val at = rows.indexOfFirst { it.first == row.first }
                            if (at >= 0) rows[at] = rows[at].copy(second = !rows[at].second)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { flip() }
                                .padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)
                        ) {
                            Checkbox(checked = row.second, onCheckedChange = { flip() })
                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(names[row.first].orEmpty(), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    lines[row.first].orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                imageVector = Icons.Rounded.DragHandle,
                                contentDescription = stringResource(R.string.lyrics_source_move, names[row.first].orEmpty()),
                                modifier = Modifier.draggableHandle()
                            )
                        }
                    }
                }
            }
            InfoLabel(stringResource(R.string.lyrics_sources_hint))
        }
    }
}
