/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 O​u​t​er​Tu​ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.navigation

import android.provider.Settings
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.dd3boh.outertune.ui.component.TopBarGlassDestination
import com.dd3boh.outertune.ui.screens.AccountScreen
import com.dd3boh.outertune.ui.screens.AlbumScreen
import com.dd3boh.outertune.ui.screens.BrowseScreen
import com.dd3boh.outertune.ui.screens.HistoryScreen
import com.dd3boh.outertune.ui.screens.HomeScreen
import com.dd3boh.outertune.ui.screens.LastFmLoginScreen
import com.dd3boh.outertune.ui.screens.LoginScreen
import com.dd3boh.outertune.ui.screens.MoodAndGenresScreen
import com.dd3boh.outertune.ui.screens.RecognitionHistoryScreen
import com.dd3boh.outertune.ui.screens.RecognitionScreen
import com.dd3boh.outertune.ui.screens.Screens
import com.dd3boh.outertune.ui.screens.SetupWizard
import com.dd3boh.outertune.ui.screens.StatsScreen
import com.dd3boh.outertune.ui.screens.YouTubeBrowseScreen
import com.dd3boh.outertune.ui.screens.artist.ArtistAlbumsScreen
import com.dd3boh.outertune.ui.screens.artist.ArtistItemsScreen
import com.dd3boh.outertune.ui.screens.artist.ArtistScreen
import com.dd3boh.outertune.ui.screens.artist.ArtistSongsScreen
import com.dd3boh.outertune.ui.screens.library.FolderScreen
import com.dd3boh.outertune.ui.screens.library.LibraryAlbumsScreen
import com.dd3boh.outertune.ui.screens.library.LibraryArtistsScreen
import com.dd3boh.outertune.ui.screens.library.LibraryFoldersScreen
import com.dd3boh.outertune.ui.screens.library.LibraryPlaylistsScreen
import com.dd3boh.outertune.ui.screens.library.LibraryScreen
import com.dd3boh.outertune.ui.screens.library.LibrarySongsScreen
import com.dd3boh.outertune.ui.screens.playlist.AutoPlaylistScreen
import com.dd3boh.outertune.ui.screens.playlist.LocalPlaylistScreen
import com.dd3boh.outertune.ui.screens.playlist.OnlinePlaylistScreen
import com.dd3boh.outertune.ui.screens.search.OnlineSearchResult
import com.dd3boh.outertune.ui.screens.search.SearchBarContainer
import com.dd3boh.outertune.ui.screens.settings.AboutScreen
import com.dd3boh.outertune.ui.screens.settings.AccountSyncSettings
import com.dd3boh.outertune.ui.screens.settings.AdvancedSettings
import com.dd3boh.outertune.ui.screens.settings.AttributionScreen
import com.dd3boh.outertune.ui.screens.settings.EngineDeveloperSettings
import com.dd3boh.outertune.ui.screens.settings.ExclusionsSettings
import com.dd3boh.outertune.ui.screens.settings.LibrariesScreen
import com.dd3boh.outertune.ui.screens.settings.LibrarySettings
import com.dd3boh.outertune.ui.screens.settings.LocalPlayerSettings
import com.dd3boh.outertune.ui.screens.settings.LookAndFeelSettings
import com.dd3boh.outertune.ui.screens.settings.LyricsSettings
import com.dd3boh.outertune.ui.screens.settings.PlayerSettings
import com.dd3boh.outertune.ui.screens.settings.PrivacySettings
import com.dd3boh.outertune.ui.screens.settings.RecognitionSettings
import com.dd3boh.outertune.ui.screens.settings.RecommendationsSettings
import com.dd3boh.outertune.ui.screens.settings.SettingsScreen
import com.dd3boh.outertune.ui.screens.settings.StorageSettings
import com.dd3boh.outertune.ui.screens.settings.UpdateSettings
import com.dd3boh.outertune.ui.screens.walkthrough.Tour
import com.dd3boh.outertune.ui.screens.walkthrough.TourState
import com.dd3boh.outertune.ui.screens.walkthrough.TourTargets
import com.dd3boh.outertune.ui.screens.walkthrough.tourAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Every destination in the app's nav host. Moved out of MainActivity, which builds the NavHost
 * around it; [searchActive] is read through a function so the "search" destination sees the
 * current value rather than the one the graph was built with.
 */
@OptIn(ExperimentalMaterial3Api::class)
fun NavGraphBuilder.appDestinations(
    navController: NavHostController,
    scrollBehavior: TopAppBarScrollBehavior,
    searchActive: () -> Boolean,
    onSearchActiveChange: (Boolean) -> Unit,
    tourState: TourState,
) {
        screen(Screens.Home.route) {
            HomeScreen(navController)
        }
        screen(Screens.Songs.route, floating = false) {
            LibrarySongsScreen(navController)
        }
        screen(Screens.Folders.route, floating = false) {
            LibraryFoldersScreen(navController, scrollBehavior)
        }
        screen(
            route = "${Screens.Folders.route}/{path}",
            arguments = listOf(
                navArgument("path") {
                    type = NavType.StringType
                }
            )
        ) {
            FolderScreen(navController, scrollBehavior)
        }
        screen(Screens.Artists.route, floating = false) {
            LibraryArtistsScreen(navController)
        }
        screen(Screens.Albums.route, floating = false) {
            LibraryAlbumsScreen(navController)
        }
        screen(Screens.Playlists.route) {
            LibraryPlaylistsScreen(navController)
        }
        screen(Screens.Library.route) {
            LibraryScreen(navController, scrollBehavior)
        }
        screen("history") {
            HistoryScreen(navController)
        }
        screen("stats") {
            StatsScreen(navController)
        }
        screen("mood_and_genres") {
            MoodAndGenresScreen(navController, scrollBehavior)
        }
        screen("account") {
            AccountScreen(navController, scrollBehavior)
        }

        screen(
            route = "browse/{browseId}",
            arguments = listOf(
                navArgument("browseId") {
                    type = NavType.StringType
                }
            )
        ) {
            BrowseScreen(
                navController,
                scrollBehavior,
                it.arguments?.getString("browseId")
            )
        }
        screen(
            route = "search",
            floating = false,
        ) {
            SearchBarContainer(navController, scrollBehavior, searchActive()) { onSearchActiveChange(it) }
        }
        screen(
            route = "search/{query}",
            floating = false,
            arguments = listOf(
                // nullable because androidx.navigation reserves the
                // literal string "null" as its null marker: StringType
                // parses the path segment "null" into an actual null,
                // and a non-nullable arg then fails verification and
                // throws. Searching the word "null" crashed the app,
                // upstream #1190. No amount of encoding avoids it, the
                // segment is decoded before parsing.
                navArgument("query") {
                    type = NavType.StringType
                    nullable = true
                }
            )
        ) {
            OnlineSearchResult(navController)
        }
        screen(
            route = "album/{albumId}",
            arguments = listOf(
                navArgument("albumId") {
                    type = NavType.StringType
                },
            )
        ) {
            AlbumScreen(navController, scrollBehavior)
        }
        screen(
            route = "artist/{artistId}",
            arguments = listOf(
                navArgument("artistId") {
                    type = NavType.StringType
                }
            )
        ) {
            ArtistScreen(navController, scrollBehavior)
        }
        screen(
            route = "artist/{artistId}/songs",
            arguments = listOf(
                navArgument("artistId") {
                    type = NavType.StringType
                }
            )
        ) {
            ArtistSongsScreen(navController, scrollBehavior)
        }
        screen(
            route = "artist/{artistId}/albums",
            arguments = listOf(
                navArgument("artistId") {
                    type = NavType.StringType
                }
            )
        ) {
            ArtistAlbumsScreen(navController, scrollBehavior)
        }
        screen(
            route = "artist/{artistId}/items?browseId={browseId}?params={params}",
            arguments = listOf(
                navArgument("artistId") {
                    type = NavType.StringType
                },
                navArgument("browseId") {
                    type = NavType.StringType
                    nullable = true
                },
                navArgument("params") {
                    type = NavType.StringType
                    nullable = true
                }
            )
        ) {
            ArtistItemsScreen(navController, scrollBehavior)
        }
        screen(
            route = "online_playlist/{playlistId}",
            arguments = listOf(
                navArgument("playlistId") {
                    type = NavType.StringType
                }
            )
        ) {
            OnlinePlaylistScreen(navController, scrollBehavior)
        }
        screen(
            route = "local_playlist/{playlistId}",
            arguments = listOf(
                navArgument("playlistId") {
                    type = NavType.StringType
                }
            )
        ) {
            LocalPlaylistScreen(navController, scrollBehavior)
        }
        screen(
            route = "auto_playlist/{playlistId}",
            arguments = listOf(
                navArgument("playlistId") {
                    type = NavType.StringType
                }
            )
        ) {
            AutoPlaylistScreen(navController, scrollBehavior)
        }
        screen(
            route = "youtube_browse/{browseId}?params={params}",
            arguments = listOf(
                navArgument("browseId") {
                    type = NavType.StringType
                    nullable = true
                },
                navArgument("params") {
                    type = NavType.StringType
                    nullable = true
                }
            )
        ) {
            YouTubeBrowseScreen(navController, scrollBehavior)
        }
        screen("settings/recognition") {
            RecognitionSettings(navController, scrollBehavior)
        }
        screen("walkthrough", floating = false) {
            // Starts the tour and gets out of the way. The tour points
            // at controls that live on Home and in the bars around it,
            // none of which exist while Settings is on screen, so it
            // has to send the user back there before it can point at
            // anything.
            LaunchedEffect(Unit) {
                navController.popBackStack(
                    navController.graph.startDestinationId,
                    inclusive = false,
                )
                // Wait for the destination to report where its
                // controls are, rather than betting on a delay. The
                // tour drops stops whose target has not been measured,
                // so a slow frame after popping back used to drop all
                // of them, and the menu entry then did nothing at all
                // with nothing on screen to say why.
                withTimeoutOrNull(4000) {
                    while (TourTargets[Tour.SEARCH_BAR] == null) delay(50)
                }
                delay(150)
                tourState.start(tourAll())
            }
        }
        screen("recognition") {
            RecognitionScreen(navController, scrollBehavior)
        }
        screen("recognition/history") {
            RecognitionHistoryScreen(navController, scrollBehavior)
        }
        screen("settings") {
            SettingsScreen(navController, scrollBehavior)
        }
        screen("settings/appearance") {
            LookAndFeelSettings(navController, scrollBehavior)
        }
        screen("settings/library") {
            LibrarySettings(navController, scrollBehavior)
        }
        screen("settings/library/lyrics") {
            LyricsSettings(navController, scrollBehavior)
        }
        screen("settings/account_sync") {
            AccountSyncSettings(navController, scrollBehavior)
        }
        screen("settings/player") {
            PlayerSettings(navController, scrollBehavior)
        }
        screen("settings/storage") {
            StorageSettings(navController, scrollBehavior)
        }
        screen("settings/local") {
            LocalPlayerSettings(navController, scrollBehavior)
        }
        screen("settings/advanced") {
            AdvancedSettings(navController, scrollBehavior)
        }
        screen("settings/privacy") {
            PrivacySettings(navController, scrollBehavior)
        }
        screen("settings/recommendations") {
            RecommendationsSettings(navController, scrollBehavior)
        }
        screen("settings/recommendations/exclusions") {
            ExclusionsSettings(navController, scrollBehavior)
        }
        screen("settings/recommendations/developer") {
            EngineDeveloperSettings(navController, scrollBehavior)
        }
        screen("settings/updates") {
            UpdateSettings(navController, scrollBehavior)
        }
        screen("settings/about") {
            AboutScreen(navController, scrollBehavior)
        }
        screen("settings/about/attribution") {
            AttributionScreen(navController, scrollBehavior)
        }
        screen("settings/about/oss_licenses") {
            LibrariesScreen(navController, scrollBehavior)
        }
        screen("lastfm_login") {
            LastFmLoginScreen(navController)
        }
        screen("login") {
            LoginScreen(navController)
        }

        screen("setup_wizard", floating = false) {
            SetupWizard(navController)
        }
}

/**
 * A destination, wrapped so its floating top bar can be glass: the screen gets a backdrop of its
 * own and the bar is drawn outside it. See TopBarGlassDestination. [floating] is false for the
 * destinations with nothing floating over them (no top bar, no floating button), which skip the
 * glass layer.
 */
private fun NavGraphBuilder.screen(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    floating: Boolean = true,
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) = composable(route = route, arguments = arguments) { entry ->
    TopBarGlassDestination(floating) { content(entry) }
}
