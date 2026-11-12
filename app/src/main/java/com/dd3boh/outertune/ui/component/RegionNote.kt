/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import com.dd3boh.outertune.ui.screens.settings.SETTINGS_INDEX
import com.dd3boh.outertune.ui.screens.settings.SettingJump
import com.dd3boh.outertune.ui.screens.settings.jump
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Public
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.RegionNoteDoneKey
import com.dd3boh.outertune.utils.YouTubeRegion
import com.dd3boh.outertune.utils.rememberPreference

/**
 * Why songs, albums and mixes are missing where YouTube Music has no free plan (#68): YouTube
 * shows them only to Premium members there, by the connection, so the way back is Premium or a
 * connection through another country. Proxy settings opens the app's own proxy switch.
 *
 * The shape and colour of [AutoBlockerNote], since it is the same kind of thing: a word from the
 * app itself, with its buttons under the words so they wrap in a large font or a long language
 * ([NoteCard], which the notes of [WhyStoppedNote] share). [onDone] null leaves Done out, for the
 * album page, where the note is that page's answer.
 */
@Composable
fun RegionNote(
    onProxy: () -> Unit,
    onDone: (() -> Unit)?,
    modifier: Modifier = Modifier,
    margin: Dp = NoteMargin,
) {
    NoteCard(
        icon = Icons.Rounded.Public,
        title = stringResource(R.string.region_note_title),
        body = stringResource(R.string.region_note_body),
        modifier = modifier,
        margin = margin,
    ) {
        NoteButton(stringResource(R.string.region_note_proxy), onProxy)
        if (onDone != null) NoteButton(stringResource(R.string.action_done), onDone)
    }
}

/**
 * The app's proxy switch, opened and lit as an explanation's link to a setting is: through its
 * entry in the index, so the Advanced fold it is in opens on the way.
 */
fun openProxySetting() = SettingJump.go(SETTINGS_INDEX.first { it.title == R.string.enable_proxy }.jump())

/**
 * [RegionNote] on Home and above search results, while YouTube sees the connection in a country
 * with no free plan and until Done is tapped there. Done is kept with the country it was tapped
 * for, so the note comes back only for another such country.
 */
@Composable
fun RegionNoteIfClosed(
    modifier: Modifier = Modifier,
) {
    val seen by YouTubeRegion.seen.collectAsState()
    var done by rememberPreference(RegionNoteDoneKey, defaultValue = "")
    if (!YouTubeRegion.closed(seen) || done == seen) return
    RegionNote(
        onProxy = ::openProxySetting,
        onDone = { done = seen.orEmpty() },
        modifier = modifier,
    )
}
