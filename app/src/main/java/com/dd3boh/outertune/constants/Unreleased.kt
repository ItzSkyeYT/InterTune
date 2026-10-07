/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.constants

import com.dd3boh.outertune.BuildConfig

/**
 * Finished, on the branch, and not announced yet.
 *
 * 0.11 is the recommendations release. 0.10.8 is a small one that happens to be cut from the same
 * branch, because the small things were built on top of the large ones and unpicking them would
 * mean two branches and two sets of fixes. So the engine's own row, the widget and everything
 * that only makes sense beside them are held behind this one switch instead, and the branch stays
 * single.
 *
 * What still ships in 0.10.8 is the part that has to: the listening log. It starts recording now
 * so that the engine has something to learn from on the day it arrives, and Pause listening
 * history under Settings > Library and content turns it off. The ledger of what it recorded stays
 * visible for the same reason, because a log nobody can see is not one anybody should accept.
 *
 * Debug builds have it on, release builds do not. The debug build is the one used for daily
 * listening precisely because it has the engine, and a gate that took that away every time a test
 * build was installed was a gate that had outgrown its job. The release is what 0.10.8 is about,
 * and `assembleCoreRelease` is what proves the gate still holds.
 *
 * For 0.11: the widget is on already (both components enabled in AndroidManifest.xml), and [ENGINE]
 * is on in releases too, decided on 27 Sep 2026. The file stays for what is still held back.
 */
object Unreleased {
    /**
     * The engine as a Quick picks source: Best recommendations, Try both, the context chips that
     * only appear above its row, and the settings that only steer it.
     *
     * It also gated Last.fm for similar songs. Decided for 0.11: it stays opt in. Nothing goes to
     * Last.fm until the listener says yes, and the question (LastFmSimilarOptInCard) is put to them
     * in setup and, after the update, in the catch-up screen; lastFmQuestionAskable reads this.
     */
    val ENGINE = true

    /**
     * Import from another service, under Settings > Backup and restore: an export file from
     * Exportify, TuneMyMusic, Soundiiz or Apple Music, matched against YouTube with TrackMatch,
     * with a review screen for what it is unsure of, made into local playlists.
     *
     * Not tied to any release yet. When one takes it, set this to true or delete it along with the
     * two checks that read it, in BackupSettings and the nav graph.
     */
    val LIBRARY_IMPORT = BuildConfig.DEBUG

    /**
     * "Links you share" under Look and feel: a song or an album can go out as a link to the share
     * page (ShareLinks.PAGE), where whoever opens it picks their own music app. Everything is in
     * place but the page itself is not public yet. When it is: set this to true, or delete it
     * along with the two checks that read it, in LookAndFeelSettings and rememberShareLink.
     */
    val SHARE_PAGE = BuildConfig.DEBUG

    /**
     * "Living blur" among the player background styles: the cover reduced to patches of colour
     * that breathe with the music (LivingBackground, LevelTap). A prototype to look at, not agreed
     * for any release. When it is: set this to true or delete it with the two checks that read
     * it, in Player.kt and ThemeFrag, and make player_background_living translatable.
     */
    val LIVING_BACKGROUND = BuildConfig.DEBUG

    /**
     * The welcome back page: after an update, what is new since the version somebody came from,
     * each thing with a "Show me" that leads to where it lives, and a walk round Settings
     * (WelcomeBack.kt, Walkthrough.kt). Without it a returning user gets the tour's new stops, as
     * before. Not agreed for a release yet. When it is: set this to true or delete it with the
     * checks that read it, in MainActivity and AboutScreen, and make its strings translatable.
     */
    val WELCOME_BACK = BuildConfig.DEBUG

    /**
     * The widget's settings with the home screen's wallpaper behind the preview instead of a patch
     * of colour, so a see-through widget can be judged against what it will sit on
     * (WidgetConfigActivity.showWallpaperBehind). Written without a phone to look at it on: how
     * One UI draws it, in light and dark, is not known yet. When it is agreed: set this to true,
     * or delete it with the check that reads it and move the three window settings into
     * Theme.InterTune.WidgetConfig.
     */
    val WIDGET_ON_WALLPAPER = BuildConfig.DEBUG

    /**
     * Similar artists in the Favourite artists mix, and the "Favourites only" switch on that page
     * that keeps them out (FavouritesGuests, FavouritesSql.SIMILAR_TO_BOOKMARKED_ARTISTS). Built
     * for 0.11.5 and tested as logic, but never looked at on a phone, and whether the switch
     * starts on or off is not decided. Without it the mix is the bookmarked artists only, as
     * before. When it is agreed: set this to true or delete it with the two checks that read it,
     * in AutoPlaylistViewModel and AutoPlaylistScreen, and make its strings translatable.
     */
    val FAVOURITES_GUESTS = BuildConfig.DEBUG

    /**
     * A layout for a phone on its side (Landscape.kt): the navigation rail whenever the window is
     * short, the search pill and the mini player at their upright width instead of the width of
     * the screen, lists two rows abreast, and an album with its cover beside its songs. A first
     * cut, not agreed for a release, and it does not reach every screen yet. When it is: set this
     * to true, or delete it with the one check that reads it, the default of Landscape.enabled.
     */
    val LANDSCAPE = BuildConfig.DEBUG

    /**
     * A play reported to YouTube's history with an address that was asked for as the account. The
     * address has always come from a visitor's /player request, which cannot name a brand
     * account's channel, and somebody with such an account says nothing reaches the history
     * (ListenReporting.addressRequests, YTPlayerUtils.playerResponseAsAccount). With this on, a
     * signed-in listener whose playback may use the account asks as the account first and falls
     * back to the visitor's address when that gives none. Never tried against YouTube: whether the
     * TV client answers a cookie here, and whether its address changes whose history the play
     * lands in, is what a brand account has to show. When it is agreed: set this to true, or
     * delete it with the one check that reads it, in MusicService.onPlaybackStatsReady.
     */
    val HISTORY_AS_ACCOUNT = BuildConfig.DEBUG
}
