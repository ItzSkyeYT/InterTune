/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.LocalUpdateChecker
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.ui.component.FloatingTopBar
import com.dd3boh.outertune.ui.component.LocalTopBarBack
import com.dd3boh.outertune.ui.component.LocalTopBarGlassHost
import com.dd3boh.outertune.ui.screens.walkthrough.Tour
import com.dd3boh.outertune.ui.screens.walkthrough.tourTarget
import com.dd3boh.outertune.ui.utils.LocalLandscape
import com.dd3boh.outertune.ui.utils.headerPaneInsets
import com.dd3boh.outertune.ui.utils.paneInsets

/** One screen of settings as the list names it: where it is, what it is called, its picture. */
internal data class SettingsCategory(val route: String, @StringRes val title: Int, val icon: ImageVector)

/**
 * The screens of settings in the list's four groups, the ones the cards of the upright list are:
 * your music, how the app presents it, what it keeps on the device, the app itself.
 */
internal fun settingsGroups(engine: Boolean = Unreleased.ENGINE): List<List<SettingsCategory>> = listOf(
    listOf(
        SettingsCategory("settings/account_sync", R.string.grp_account_sync, Icons.Rounded.AccountCircle),
        SettingsCategory("settings/library", R.string.grp_library_and_content, Icons.AutoMirrored.Rounded.LibraryBooks),
        SettingsCategory("settings/local", R.string.local_player_settings_title, Icons.Rounded.SdCard),
    ),
    listOf(
        SettingsCategory(Tour.ROUTE_LOOK_AND_FEEL, R.string.look_and_feel, Icons.Rounded.Palette),
        SettingsCategory("settings/player", R.string.player_and_audio, Icons.Rounded.PlayArrow),
        SettingsCategory("settings/recognition", R.string.recognise_settings, Icons.Rounded.GraphicEq),
        SettingsCategory("settings/library/lyrics", R.string.lyrics_settings_title, Icons.Rounded.Lyrics),
    ),
    listOf(
        SettingsCategory("settings/storage", R.string.grp_storage_and_downloads, Icons.Rounded.Storage),
        SettingsCategory("settings/backup", R.string.backup_restore, Icons.Rounded.Backup),
        SettingsCategory("settings/privacy", R.string.grp_privacy_and_history, Icons.Rounded.Shield),
    ),
    listOfNotNull(
        SettingsCategory("settings/updates", R.string.grp_updates, Icons.Rounded.Update),
        SettingsCategory("settings/recommendations", R.string.recommendations, Icons.Rounded.AutoAwesome).takeIf { engine },
        SettingsCategory("settings/advanced", R.string.advanced, Icons.Rounded.Tune),
        SettingsCategory("settings/about", R.string.about, Icons.Rounded.Info),
    ),
)

/** The window width from which the list of settings and one of its screens stand side by side. */
internal val SettingsTwoPaneMinWidth = 700.dp

/** The list's share of a window [windowWidth] wide: a good third, never so narrow that a name is cut. */
internal fun settingsListWidth(windowWidth: Dp): Dp = (windowWidth * 0.34f).coerceIn(264.dp, 340.dp)

/**
 * Settings in a wide window, a phone on its side or a tablet: the list of screens stays in view
 * on the left and the one chosen is open on the right, as Samsung's settings are on a tablet.
 * Upright the list is the whole screen and each entry leads to a screen of its own.
 *
 * The screen on the right is the very one the entry leads to upright, drawn here with the mini
 * player's room under it and none of its own at the sides, and with its title but no way back:
 * back leaves the settings, as it does from the list. What such a screen leads on to (Lyrics from
 * Library, the pages of Recommendations) opens over the whole window, as it always has.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTwoPane(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val groups = settingsGroups()
    var chosen by rememberSaveable { mutableStateOf(groups.first().first().route) }
    val pendingUpdate by LocalUpdateChecker.current.available.collectAsState()
    val updateBadgeLabel = stringResource(R.string.update_available_title)
    val groupTargets = listOf(Tour.SETTINGS_YOU, Tour.SETTINGS_LOOK_AND_SOUND, Tour.SETTINGS_KEPT, Tour.SETTINGS_REST)

    Row(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal))
    ) {
        // The list, with the bar that has the way back over it and no wider than it: a bar the
        // width of the window fades what is under it, and would dim the other pane's title.
        Box(
            modifier = Modifier
                .width(settingsListWidth(LocalLandscape.current.windowWidth))
                .fillMaxHeight()
        ) {
        // Under the top bar. On a phone on its side the mini player stands under the other half
        // and the list runs down to the gesture bar; in a tall window, a tablet's, the mini player
        // is the width of the window and the list keeps clear of it too.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(if (LocalLandscape.current.active) headerPaneInsets() else paneInsets())
                .padding(start = 12.dp, end = 4.dp, bottom = 12.dp),
        ) {
            groups.forEachIndexed { at, group ->
                if (at > 0) Spacer(Modifier.height(10.dp))
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.tourTarget(groupTargets[at]),
                ) {
                    for (category in group) {
                        SettingsCategoryRow(
                            category = category,
                            chosen = category.route == chosen,
                            badge = updateBadgeLabel.takeIf { category.route == "settings/updates" && pendingUpdate != null },
                            modifier = when (category.route) {
                                Tour.ROUTE_LOOK_AND_FEEL -> Modifier.tourTarget(Tour.ROW_LOOK_AND_FEEL)
                                "settings/player" -> Modifier.tourTarget(Tour.ROW_PLAYER)
                                else -> Modifier
                            },
                            onClick = { chosen = category.route },
                        )
                    }
                }
            }
        }
        FloatingTopBar(title = stringResource(R.string.settings), navController = navController)
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            CompositionLocalProvider(
                // Its sides are this pane's, which the row above has already kept clear.
                LocalPlayerAwareWindowInsets provides paneInsets(),
                LocalTopBarBack provides false,
                // Drawn in place: the screen's one glass bar is the list's, with the way back.
                LocalTopBarGlassHost provides null,
            ) {
                key(chosen) { SettingsPane(chosen, navController, scrollBehavior) }
            }
        }
    }
}

/** One entry of the list: its picture and its name on one line, filled when it is the one open. */
@Composable
private fun SettingsCategoryRow(
    category: SettingsCategory,
    chosen: Boolean,
    badge: String?,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (chosen) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .selectable(selected = chosen, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        BadgedBox(
            badge = { if (badge != null) Badge(modifier = Modifier.semantics { contentDescription = badge }) }
        ) {
            Icon(
                imageVector = category.icon,
                contentDescription = null,
                tint = if (chosen) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(category.title),
            style = MaterialTheme.typography.titleSmall,
            color = if (chosen) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The screen an entry of the list leads to, by its route, as AppNavGraph has them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPane(route: String, navController: NavController, scrollBehavior: TopAppBarScrollBehavior) {
    when (route) {
        "settings/account_sync" -> AccountSyncSettings(navController, scrollBehavior)
        "settings/library" -> LibrarySettings(navController, scrollBehavior)
        "settings/local" -> LocalPlayerSettings(navController, scrollBehavior)
        Tour.ROUTE_LOOK_AND_FEEL -> LookAndFeelSettings(navController, scrollBehavior)
        "settings/player" -> PlayerSettings(navController, scrollBehavior)
        "settings/recognition" -> RecognitionSettings(navController, scrollBehavior)
        "settings/library/lyrics" -> LyricsSettings(navController, scrollBehavior)
        "settings/storage" -> StorageSettings(navController, scrollBehavior)
        "settings/backup" -> BackupSettings(navController, scrollBehavior)
        "settings/privacy" -> PrivacySettings(navController, scrollBehavior)
        "settings/updates" -> UpdateSettings(navController, scrollBehavior)
        "settings/recommendations" -> RecommendationsSettings(navController, scrollBehavior)
        "settings/advanced" -> AdvancedSettings(navController, scrollBehavior)
        "settings/about" -> AboutScreen(navController, scrollBehavior)
    }
}
