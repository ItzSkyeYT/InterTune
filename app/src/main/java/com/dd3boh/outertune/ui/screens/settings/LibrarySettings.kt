/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */
package com.dd3boh.outertune.ui.screens.settings

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
