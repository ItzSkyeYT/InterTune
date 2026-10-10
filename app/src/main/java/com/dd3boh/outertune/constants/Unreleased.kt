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
     * (WelcomeBack.kt, Walkthrough.kt). It does not open by itself: a card at the top of Home
     * says how many things are new and opens it (welcomeCardFor), and About has it as well.
     * Without the flag a returning user gets the tour's new stops, as before. With it the
     * tutorial's walk ends on a question, whether to look closer at the settings, and the tour
     * that follows a yes goes into each screen of Settings (SETTINGS_CLOSER_LOOK), also started
     * from About. Not agreed for a release yet. When it is: set this to true or delete it with
     * the checks that read it, in Walkthrough.kt, AboutScreen and AppNavGraph, and make its
     * strings translatable.
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
     * A shorter first-run setup (ShortSetup.kt): three pages in place of six. What the app is,
     * with the way to restore a backup; signing in, or not; and the five questions of the old
     * exit page as five switches with one Done (SetupChoices). Look and feel, local media and the
     * downloads folder are not asked about: they stay in Settings, at the defaults the old pages
     * left them on.
     *
     * With it, access to the music on the device is asked for where somebody has gone that needs
     * it, Folders or the local scanner, or as local media is turned on, and no longer by the
     * automatic scan at start, which put the system's prompt over whatever was on screen at the
     * first start after setup (MediaPermissionAsk).
     *
     * A first cut to look at on a phone, not agreed for a release: the wording, and whether
     * "Tell me about updates" starts on (SetupChoices.UPDATES_DEFAULT), are still to decide.
     * Without it setup is the old wizard, untouched. When it is agreed: set this to true, or
     * delete it with the checks that read it, in AppNavGraph and MediaPermissionAsk, delete
     * SetupWizard.kt with the strings only it uses, and make the setup_ strings translatable.
     */
    val SHORT_SETUP = BuildConfig.DEBUG

    /**
     * A song whose own id YouTube calls gone, played from the id the same recording has now, and
     * kept out of the recommendation rows when no copy of it plays (StandIns, GoneSongs).
     *
     * Seen working on the emulator with made-up dead ids, and once on a real one: a liked song
     * taken off YouTube, on a phone signed in, played from the id it has now a second and a half
     * after the tap (9 Oct 2026). It sits on the path every song takes, and the hard part,
     * telling a copy from another take of the song, has met that one song of a real library and
     * no more. Without it a song that is gone fails as it always has, and nothing is searched
     * for, noted or left out. When it is agreed: set this to true, or delete it with the one
     * check that reads it, in StandIns, and make exclusion_reason_gone and
     * error_cached_part_stale translatable.
     */
    val STAND_INS = BuildConfig.DEBUG

    /**
     * The sign-in page says when the sign-in is made and goes back by itself to where it was
     * opened from, setup or the account's settings (LoginScreen, SignedIn). Without it the page
     * stays on YouTube Music's own site, signed in, and the way back is for the user to find.
     *
     * With it the page is also opened signed out each time, its cookies cleared first, so that a
     * sign-in made as the wrong account can be made again as another: without it the page comes
     * up at once as the account before. And a page that reaches YouTube Music signed out no
     * longer replaces the sign-in the app holds.
     *
     * Its decisions are tested as logic, and seen on a Pixel 5: with a made-up sign-in cookie put
     * into the page, going back to setup, going back to the settings, and the line with Done; and
     * with one real sign-in, from setup, on 9 Oct 2026. Not agreed for a release yet. When it is:
     * set this to true, or delete it with the one check that reads it, in LoginScreen, and make
     * login_signed_in_as, login_signed_in and login_going_back translatable.
     */
    val LOGIN_RETURNS = BuildConfig.DEBUG

    /**
     * A setting named in an explanation is a link to it: a tap closes the explanation, opens the
     * screen the setting is on, scrolls to its row and flashes it (SettingJumps, SettingJumpHost).
     * The names an explanation writes in quotation marks are what can be links, and they are the
     * ones the list knows the place of: every setting the tour stops at, a few rows marked for
     * this alone, and the choices of three settings.
     *
     * A first cut to look at on a phone, not agreed for a release. Without it an explanation is
     * plain text, as before. When it is agreed: set this to true, or delete it with the one check
     * that reads it, in Explain.kt.
     */
    val EXPLANATION_LINKS = BuildConfig.DEBUG

    /**
     * "Ask YouTube Music's web client first" in the developer options: an experiment for the day
     * VISIONOS, the one client that serves whole songs, stops. With the switch on every song is
     * asked of WEB_REMIX before the chain as it is, with the signature timestamp and a po token
     * (YTPlayerUtils.TRIAL_CLIENT), and the chain is asked as ever when that gives no stream.
     *
     * Off unless somebody switches it on, and never in a release. It is not a feature to agree
     * on. On 9 Oct 2026 it showed where that road ends today: WEB_REMIX answers, and the address
     * it gives is in a cipher NewPipeExtractor can no longer undo. So with the switch on that
     * address goes to a solver instead (utils/cipher): yt-dlp's, run in a WebView. Everything
     * round the solver is built and tested with a stand-in for it. The solver's own two files
     * are not in the tree yet, and until they are the switch does what it did that day.
     * Delete it with the two checks that read it, in MusicService and DeveloperFrag, and with
     * utils/cipher if the solver is not kept, once it has served its purpose.
     */
    val WEB_CLIENT_FIRST = BuildConfig.DEBUG

    /**
     * YouTube Music's web client as one of the stream chain's own, written after VISIONOS
     * (YTPlayerUtils.WEB_FALLBACK_CLIENT): the day VISIONOS gives no stream it is asked by
     * itself, and it is first for the songs after that for as long as it serves.
     *
     * It played on a phone on 10 Oct 2026, three songs asked first by the switch above. In the
     * chain it is asked only once VISIONOS has been refused, also with a new visitorData, so
     * while VISIONOS serves nothing about a song changes: no player script is fetched, no po
     * token made, no WebView opened.
     *
     * Needs the solver's files in the build (assets/solver) and does nothing without them.
     * To ship: this to true, and the files' place in the tree agreed.
     */
    val WEB_FALLBACK = BuildConfig.DEBUG

    /**
     * VISIONOS under the two other versions and user agents other projects ask it with, written
     * in the stream chain right after VISIONOS itself (YTPlayerUtils.VISIONOS_IDENTITIES). Asked
     * only when VISIONOS gave no stream, a request each, and whichever serves is asked first from
     * then on. Both played whole songs on 9 and 10 Oct 2026 when the stream probe asked them.
     *
     * Needs nothing else in the build. To ship: this to true.
     */
    val VISIONOS_IDENTITIES = BuildConfig.DEBUG

    /**
     * The theme takes as much colour from a cover as the cover has (CoverAccent). A black and
     * white cover with a tint in it gives controls that are nearly grey, where it gave them in
     * full colour, and a cover of greys gives grey ones, where it gave blue. A cover in colour
     * gives what it always did.
     *
     * Its numbers were set on fourteen covers and looked at on a Pixel 5. Without it the theme
     * is the cover's hue at full strength, as before. When it is agreed: set this to true, or
     * delete it with the two checks that read it, both in Theme.kt.
     */
    val COVER_ACCENT = BuildConfig.DEBUG
}
