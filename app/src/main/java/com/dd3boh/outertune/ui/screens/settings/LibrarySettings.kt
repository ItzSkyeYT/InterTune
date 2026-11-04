/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */
package com.dd3boh.outertune.ui.screens.settings

import com.dd3boh.outertune.ui.menu.rememberArtistBlock
import com.dd3boh.outertune.ui.menu.BlockArtistIcon
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.screens.settings.fragments.ContentFiltersFrag
import com.dd3boh.outertune.ui.component.FloatingTopBar

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.FlatSubfoldersKey
import com.dd3boh.outertune.constants.ShowLikedAndDownloadedPlaylist
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
import com.dd3boh.outertune.ui.component.SettingsClickToReveal
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.ui.screens.settings.fragments.LocalizationFrag
import com.dd3boh.outertune.ui.screens.settings.fragments.OpenLinksFrag
import com.dd3boh.outertune.ui.screens.settings.fragments.QuickPicksSourceFrag
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.ui.screens.settings.fragments.ShareLinksFrag
import com.dd3boh.outertune.constants.Unreleased

/** The first few blocked artists' names, and how many more, or null when there are none. */
internal fun blockedSummary(names: List<String>): String? = when {
    names.isEmpty() -> null
    names.size <= 3 -> names.joinToString(", ")
    else -> names.take(3).joinToString(", ") + " +" + (names.size - 3)
}

/**
 * What the app fills itself with.
 *
 * The screen used to open with a card of three links to Local media, Lyrics and Storage, all
 * three of which are now entries on the settings index itself, so the card was one extra tap on
 * the way to somewhere already listed. The history and proxy rows that were also here have moved
 * to Privacy and history.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val (showLikedAndDownloadedPlaylist, onShowLikedAndDownloadedPlaylistChange) = rememberPreference(
        key = ShowLikedAndDownloadedPlaylist,
        defaultValue = true
    )
    val (flatSubfolders, onFlatSubfoldersChange) = rememberPreference(FlatSubfoldersKey, defaultValue = true)

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .padding(horizontal = 16.dp)
    ) {
        PreferenceGroupTitle(
            title = stringResource(R.string.content)
        )

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            QuickPicksSourceFrag()
            // What Share sends for a song or an album: content going out, not how the app looks.
            if (Unreleased.SHARE_PAGE) ShareLinksFrag()
            // The links that come in, beside the ones that go out: what this install does with a
            // YouTube Music or intertune.app link tapped elsewhere.
            OpenLinksFrag()
            ContentFiltersFrag()
            // Beside the switches that hide things, the artists hidden altogether (ArtistBlock).
            if (Unreleased.ARTIST_BLOCK) {
                val blocked = rememberArtistBlock().artists
                PreferenceEntry(
                    modifier = Modifier.settingRow(R.string.blocked_artists),
                    title = { Text(stringResource(R.string.blocked_artists)) },
                    description = blockedSummary(blocked.map { it.name }) ?: stringResource(R.string.blocked_artists_none),
                    icon = { Icon(BlockArtistIcon, null) },
                    onClick = { navController.toSettings("settings/library/blocked_artists") },
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        PreferenceGroupTitle(
            title = stringResource(R.string.grp_localization)
        )

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            LocalizationFrag()
        }

        SettingsClickToReveal(stringResource(R.string.advanced)) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                SwitchPreference(
                    modifier = Modifier.settingRow(R.string.show_liked_and_downloaded_playlist),
                    title = { Text(stringResource(R.string.show_liked_and_downloaded_playlist)) },
                    description = stringResource(R.string.show_liked_and_downloaded_playlist_description),
                    icon = { Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null) },
                    checked = showLikedAndDownloadedPlaylist,
                    onCheckedChange = onShowLikedAndDownloadedPlaylistChange
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            ElevatedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                SwitchPreference(
                    modifier = Modifier.settingRow(R.string.flat_subfolders_title),
                    title = { Text(stringResource(R.string.flat_subfolders_title)) },
                    description = stringResource(R.string.flat_subfolders_description),
                    icon = { Icon(Icons.Rounded.FolderCopy, null) },
                    checked = flatSubfolders,
                    onCheckedChange = onFlatSubfoldersChange
                )
            }
        }
    }

    FloatingTopBar(title = stringResource(R.string.grp_library_and_content), navController = navController)
}
