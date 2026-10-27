/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.walkthrough

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.BluetoothAudio
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Recommend
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.ui.graphics.vector.ImageVector
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.Unreleased

/**
 * The build 0.11 ships as.
 *
 * Named rather than repeated, because every step added for this release has to carry the same
 * number and one of them being wrong is invisible until somebody is shown the wrong thing.
 */
private const val V0_11 = 88

/**
 * The tour, as stops that point at real controls.
 *
 * This replaced a slideshow. Showing somebody a picture of a feature and showing them where the
 * button is are different jobs, and only the second one survives them closing the app.
 */
val TOUR_STOPS = listOf(
    TourStop(
        id = "welcome",
        targetId = null,
        route = null,
        title = R.string.walkthrough_title_first,
        body = R.string.walkthrough_description,
        sinceVersionCode = 0,
    ),
    TourStop(
        id = "search",
        targetId = Tour.SEARCH_BAR,
        route = null,
        title = R.string.tour_search_title,
        body = R.string.tour_search_body,
        sinceVersionCode = 0,
    ),
    TourStop(
        id = "quick_picks",
        targetId = Tour.QUICK_PICKS_CHIPS,
        route = null,
        title = R.string.tour_quickpicks_title,
        body = R.string.tour_quickpicks_body,
        sinceVersionCode = V0_11,
    ),
    TourStop(
        id = "recognise",
        targetId = Tour.RECOGNISE,
        route = null,
        title = R.string.walkthrough_recognition_title,
        body = R.string.tour_recognise_body,
        sinceVersionCode = V0_11,
    ),
    TourStop(
        id = "library",
        targetId = Tour.NAV_LIBRARY,
        route = null,
        title = R.string.tour_library_title,
        body = R.string.tour_library_body,
        sinceVersionCode = 0,
    ),
    TourStop(
        id = "settings",
        targetId = Tour.SETTINGS,
        route = null,
        title = R.string.walkthrough_settings_title,
        body = R.string.tour_settings_body,
        sinceVersionCode = 0,
    ),
)

/**
 * The first build 0.11.5 can ship as. Nothing below it has what its stops point at. 92 was taken
 * by 0.11.1, the fixes alone, and somebody who had a tour on that build has to be shown what
 * 0.11.5 brought: counted from 92 they would be shown none of it.
 */
private const val V0_11_5 = 93

/** 0.11.6: the fixes after 0.11.5, and two things from 0.12 that were ready (his word, 16 Oct 2026). */
private const val V0_11_6 = 94

/**
 * The first build 0.12 can ship as: what is held back for it is listed under it, so that somebody
 * on 0.11.5 is shown it once 0.12 brings it. Raise it if a fix release comes between: 0.11.6,
 * fixes only, took 94, and counted from 94 somebody who had a tour on 0.11.6 would be shown none
 * of what 0.12 brings.
 */
private const val V0_12 = 95

/**
 * The first build 0.12.5 can ship as. 0.12.1, fixes only, took 96, so what 0.12.5 brings is
 * counted from 97: somebody who had a tour on 0.12.1 is then shown it. Raise it if another fix
 * release comes between.
 */
private const val V0_12_5 = 97

/**
 * A walk round Settings: its four groups, one bubble each.
 *
 * Settings is thirteen screens and the rows already say what is in each. What they do not give is
 * the shape of the whole, which is what somebody who is lost wants: four groups, and which of them
 * to open for what.
 */
val SETTINGS_TOUR = listOf(
    TourStop("settings_you", Tour.SETTINGS_YOU, Tour.ROUTE_SETTINGS, R.string.tour_settings_you_title, R.string.tour_settings_you_body, 0),
    TourStop("settings_look_and_sound", Tour.SETTINGS_LOOK_AND_SOUND, Tour.ROUTE_SETTINGS, R.string.tour_settings_look_title, R.string.tour_settings_look_body, 0),
    TourStop("settings_kept", Tour.SETTINGS_KEPT, Tour.ROUTE_SETTINGS, R.string.tour_settings_kept_title, R.string.tour_settings_kept_body, 0),
    TourStop("settings_rest", Tour.SETTINGS_REST, Tour.ROUTE_SETTINGS, R.string.tour_settings_rest_title, R.string.tour_settings_rest_body, 0),
)

/**
 * The question the tutorial ends on, after its walk round Settings: whether to go on into the
 * settings themselves.
 *
 * A stop with nothing to point at, like the welcome, and an offer like it: "Not now" ends the tour
 * as it ended before there was a question, "Show me" starts [SETTINGS_CLOSER_LOOK] in its place.
 * Asked over the list of settings, where the walk leaves somebody.
 */
val CLOSER_LOOK_QUESTION = TourStop(
    id = "closer_look_question",
    targetId = null,
    route = Tour.ROUTE_SETTINGS,
    title = R.string.tour_closer_question_title,
    body = R.string.tour_closer_question_body,
    sinceVersionCode = 0,
)

/**
 * The screens the Settings list leads to, in the order they are on it. The closer look goes
 * through them in this order, and SettingsCloserLookTest reads the list off SettingsScreen and
 * fails when the two differ, so that a screen added there is not forgotten here.
 */
val SETTINGS_CATEGORIES = listOfNotNull(
    Tour.ROUTE_ACCOUNT,
    Tour.ROUTE_LIBRARY,
    Tour.ROUTE_LOCAL,
    Tour.ROUTE_LOOK_AND_FEEL,
    Tour.ROUTE_PLAYER,
    Tour.ROUTE_RECOGNITION,
    Tour.ROUTE_LYRICS,
    Tour.ROUTE_STORAGE,
    Tour.ROUTE_BATTERY.takeIf { Unreleased.BATTERY_MODES },
    Tour.ROUTE_BACKUP,
    Tour.ROUTE_PRIVACY,
    Tour.ROUTE_UPDATES,
    Tour.ROUTE_RECOMMENDATIONS,
    Tour.ROUTE_ADVANCED,
    Tour.ROUTE_ABOUT,
)

/**
 * The screens the closer look does not go into, and why. Named, so that every screen on the list
 * has either stops or a reason: the test fails on one with neither.
 */
val CLOSER_LOOK_LEFT_OUT = mapOf(
    Tour.ROUTE_BATTERY to "One choice, and the line under it says what each mode turns down: a bubble would only say it again.",
    Tour.ROUTE_ADVANCED to "Emptying the image cache and the switch for the developer's own settings: upkeep, with nothing to choose between.",
    Tour.ROUTE_ABOUT to "Nothing on it is a setting. It holds the way back into this tour.",
)

/** A stop of the closer look: [title] is the string its row is titled with, [body] the sentence. */
private fun closer(id: String, target: String, route: String, @StringRes title: Int, @StringRes body: Int) =
    TourStop("closer_$id", target, route, title, body, 0)

/**
 * A closer look at the settings: into each screen of [SETTINGS_CATEGORIES] and, there, to the
 * settings people change, one bubble each, with a sentence on what the setting does and when
 * somebody would want it. The tutorial offers it at its end and About starts it again.
 *
 * Not every row. Left out: what only cleans up or is there for developers, a row that opens
 * another screen, what sits folded away under Advanced, and a row whose own description already
 * says all there is. Where a described row is stopped at all the same, because it is one people
 * go looking for, the bubble says what the description does not, and is short.
 *
 * A stop is titled with the string its row is titled with, so the two cannot drift apart, and
 * the test reads both ends to hold them to it. Some of these rows are not on every install:
 * head tracking is only there under spatial audio for headphones, and the Last.fm row is another
 * row once connected. Such a stop costs nothing. It is taken on trust here and left out when the
 * tour gets to its screen and finds it missing (TourState.leaveOut).
 */
val SETTINGS_CLOSER_LOOK = listOf(
    closer("login", Tour.SETTING_LOGIN, Tour.ROUTE_ACCOUNT, R.string.login, R.string.tour_closer_login),
    closer("lastfm", Tour.SETTING_LASTFM, Tour.ROUTE_ACCOUNT, R.string.lastfm_connect, R.string.tour_closer_lastfm),
    closer("ytm_sync", Tour.SETTING_YTM_SYNC, Tour.ROUTE_ACCOUNT, R.string.ytm_sync, R.string.tour_closer_ytm_sync),
    closer("sync_mode", Tour.SETTING_SYNC_MODE, Tour.ROUTE_ACCOUNT, R.string.sync_mode, R.string.tour_closer_sync_mode),

    closer("quick_picks_source", Tour.SETTING_QUICK_PICKS_SOURCE, Tour.ROUTE_LIBRARY, R.string.quick_picks_source, R.string.tour_closer_quick_picks_source),
    closer("share_links", Tour.SETTING_SHARE_LINKS, Tour.ROUTE_LIBRARY, R.string.share_link_kind_title, R.string.new_share_links_here),
    closer("content_language", Tour.SETTING_CONTENT_LANGUAGE, Tour.ROUTE_LIBRARY, R.string.content_language, R.string.tour_closer_content_language),
    closer("content_country", Tour.SETTING_CONTENT_COUNTRY, Tour.ROUTE_LIBRARY, R.string.content_country, R.string.tour_closer_content_country),

    closer("local_media", Tour.SETTING_LOCAL_MEDIA, Tour.ROUTE_LOCAL, R.string.local_library_enable_title, R.string.tour_closer_local_media),
    closer("scan_paths", Tour.SETTING_SCAN_PATHS, Tour.ROUTE_LOCAL, R.string.scan_paths_title, R.string.tour_closer_scan_paths),
    closer("scanner_sensitivity", Tour.SETTING_SCANNER_SENSITIVITY, Tour.ROUTE_LOCAL, R.string.scanner_sensitivity_title, R.string.tour_closer_scanner_sensitivity),

    closer("dark_theme", Tour.SETTING_DARK_THEME, Tour.ROUTE_LOOK_AND_FEEL, R.string.dark_theme, R.string.tour_closer_dark_theme),
    closer("player_background", Tour.SETTING_PLAYER_BACKGROUND, Tour.ROUTE_LOOK_AND_FEEL, R.string.player_background_style, R.string.tour_closer_player_background),
    closer("liquid_glass", Tour.SETTING_LIQUID_GLASS, Tour.ROUTE_LOOK_AND_FEEL, R.string.player_liquid_glass, R.string.tour_closer_liquid_glass),
    closer("media_buttons", Tour.SETTING_MEDIA_BUTTONS, Tour.ROUTE_LOOK_AND_FEEL, R.string.media_control_buttons, R.string.tour_closer_media_buttons),
    closer("tab_arrangement", Tour.SETTING_TAB_ARRANGEMENT, Tour.ROUTE_LOOK_AND_FEEL, R.string.tab_arrangement, R.string.tour_closer_tab_arrangement),
    closer("default_tab", Tour.SETTING_DEFAULT_TAB, Tour.ROUTE_LOOK_AND_FEEL, R.string.default_open_tab, R.string.tour_closer_default_tab),
    // The sentence the welcome back page's own tour has for it, which already says what it does.

    closer("auto_load_more", Tour.SETTING_AUTO_LOAD_MORE, Tour.ROUTE_PLAYER, R.string.auto_load_more, R.string.tour_closer_auto_load_more),
    closer("audio_quality", Tour.SETTING_AUDIO_QUALITY, Tour.ROUTE_PLAYER, R.string.audio_quality, R.string.tour_closer_audio_quality),
    closer("transition_fade", Tour.SETTING_TRANSITION_FADE, Tour.ROUTE_PLAYER, R.string.transition_fade, R.string.tour_closer_transition_fade),
    closer("spatial_audio", Tour.SETTING_SPATIAL_AUDIO, Tour.ROUTE_PLAYER, R.string.spatial_audio, R.string.tour_closer_spatial_audio),
    closer("head_tracking", Tour.SETTING_HEAD_TRACKING, Tour.ROUTE_PLAYER, R.string.head_tracking, R.string.tour_closer_head_tracking),

    closer("recognise_keep", Tour.SETTING_RECOGNISE_KEEP_LISTENING, Tour.ROUTE_RECOGNITION, R.string.recognise_keep_listening, R.string.tour_closer_recognise_keep),
    closer("recognise_pause", Tour.SETTING_RECOGNISE_PAUSE, Tour.ROUTE_RECOGNITION, R.string.recognise_pause_title, R.string.tour_closer_recognise_pause),
    closer("recognise_auto_add", Tour.SETTING_RECOGNISE_AUTO_ADD, Tour.ROUTE_RECOGNITION, R.string.recognise_auto_add, R.string.tour_closer_recognise_auto_add),
    closer("recognise_seconds", Tour.SETTING_RECOGNISE_SECONDS, Tour.ROUTE_RECOGNITION, R.string.recognise_listen_seconds, R.string.tour_closer_recognise_seconds),

    closer("lyrics_sources", Tour.SETTING_LYRICS_SOURCES, Tour.ROUTE_LYRICS, R.string.enable_lrclib, R.string.tour_closer_lyrics_sources),
    closer("lyrics_prefer_local", Tour.SETTING_LYRICS_PREFER_LOCAL, Tour.ROUTE_LYRICS, R.string.lyrics_prefer_local, R.string.tour_closer_lyrics_prefer_local),
    closer("lyrics_position", Tour.SETTING_LYRICS_POSITION, Tour.ROUTE_LYRICS, R.string.lyrics_text_position, R.string.tour_closer_lyrics_position),
    closer("lyrics_font_size", Tour.SETTING_LYRICS_FONT_SIZE, Tour.ROUTE_LYRICS, R.string.lyrics_font_Size, R.string.tour_closer_lyrics_font_size),

    closer("liked_autodownload", Tour.SETTING_LIKED_AUTODOWNLOAD, Tour.ROUTE_STORAGE, R.string.like_autodownload, R.string.tour_closer_liked_autodownload),
    closer("download_folder", Tour.SETTING_DOWNLOAD_FOLDER, Tour.ROUTE_STORAGE, R.string.dl_main_path_title, R.string.tour_closer_download_folder),
    closer("song_cache", Tour.SETTING_SONG_CACHE, Tour.ROUTE_STORAGE, R.string.song_cache_max_size, R.string.tour_closer_song_cache),

    closer("backup", Tour.SETTING_BACKUP, Tour.ROUTE_BACKUP, R.string.action_backup, R.string.tour_closer_backup),
    closer("restore", Tour.SETTING_RESTORE, Tour.ROUTE_BACKUP, R.string.action_restore, R.string.tour_closer_restore),
    closer("auto_backup", Tour.SETTING_AUTO_BACKUP, Tour.ROUTE_BACKUP, R.string.auto_backup, R.string.tour_closer_auto_backup),

    closer("pause_history", Tour.SETTING_PAUSE_HISTORY, Tour.ROUTE_PRIVACY, R.string.pause_listen_history, R.string.tour_closer_pause_history),
    closer("pause_remote_history", Tour.SETTING_PAUSE_REMOTE_HISTORY, Tour.ROUTE_PRIVACY, R.string.pause_remote_listen_history, R.string.tour_closer_pause_remote_history),
    closer("polls", Tour.SETTING_POLLS, Tour.ROUTE_PRIVACY, R.string.polls_enabled, R.string.tour_closer_polls),

    closer("update_check", Tour.SETTING_UPDATE_CHECK, Tour.ROUTE_UPDATES, R.string.update_check, R.string.tour_closer_update_check),
    closer("background_check", Tour.SETTING_BACKGROUND_CHECK, Tour.ROUTE_UPDATES, R.string.background_check_interval, R.string.tour_closer_background_check),
    closer("check_now", Tour.SETTING_CHECK_NOW, Tour.ROUTE_UPDATES, R.string.check_for_update, R.string.tour_closer_check_now),

    closer("quick_picks_lean", Tour.SETTING_QUICK_PICKS_LEAN, Tour.ROUTE_RECOMMENDATIONS, R.string.quick_picks_lean, R.string.tour_closer_quick_picks_lean),
    closer("adventurousness", Tour.SETTING_ADVENTUROUSNESS, Tour.ROUTE_RECOMMENDATIONS, R.string.adventurousness, R.string.tour_closer_adventurousness),
    closer("learn_from_listening", Tour.SETTING_LEARN_FROM_LISTENING, Tour.ROUTE_RECOMMENDATIONS, R.string.learn_from_listening, R.string.tour_closer_learn_from_listening),
    closer("exclusions", Tour.SETTING_EXCLUSIONS, Tour.ROUTE_RECOMMENDATIONS, R.string.exclusions, R.string.tour_closer_exclusions),
)

/** The tour a question leads to on "Show me", or null for a stop that is not a question. */
fun tourOfferedBy(stop: TourStop): List<TourStop>? =
    if (stop.id == CLOSER_LOOK_QUESTION.id) SETTINGS_CLOSER_LOOK else null

/** What "Show me" does for a [NewThing]. */
enum class NewThingAction {
    /** Go to where it lives and point at it: [NewThing.stops]. */
    SHOW,

    /** Ask the launcher to put the widget on the home screen. */
    ADD_WIDGET,

    /**
     * Ask the system to put song recognition's tile in Quick Settings: from Android 13 it can be
     * asked for in one tap. Before that the home screen button is offered instead, the other way
     * in that came with it.
     */
    ADD_TILE,

    /**
     * No button: the card says where it is, and there is nowhere in Settings to take somebody.
     * Casting lives under the player's controls, and the tours only walk through Settings.
     */
    NONE,
}

/**
 * Something that is new, as the welcome back page lists it.
 *
 * @param release the version it came with, as people know it. The page groups by it.
 * @param stops where it lives, as the way there: for a setting, the row to open in Settings and
 *   then the setting itself, because half of explaining a setting is showing which menu it is in.
 *   Each of those bubbles says what the setting does, in a sentence, unless what it points at
 *   already says so on screen: the row in Settings lists other things than this one, and most
 *   settings are a name and a value with no description under them. A bubble that only says
 *   "it is in here" or "choose it here" leaves somebody at a switch they cannot place.
 * @param there false where this build or this install does not have it, and it is then not
 *   listed: a card whose button has nowhere to lead is worse than no card.
 */
class NewThing(
    val id: String,
    val release: String,
    val sinceVersionCode: Int,
    @StringRes val title: Int,
    @StringRes val body: Int,
    val icon: ImageVector,
    val action: NewThingAction = NewThingAction.SHOW,
    val stops: List<TourStop> = emptyList(),
    val there: (Install) -> Boolean = { true },
)

/**
 * How this install is set up, for the things that are only there on some of them.
 *
 * @param quickPicksChips whether Quick picks has its row of chips, which it only has while it
 *   draws from Best recommendations or Try both (QuickPicksSource.hasChips). Somebody on
 *   YouTube's row or their library's has nothing there to be shown.
 */
class Install(val quickPicksChips: Boolean = true)

/**
 * Song recognition's own picture wherever it is: the sound bars of the button beside the search
 * bar, also on its tile and home screen button (ic_recognise), so that it is known at a glance
 * (his word, 16 Oct 2026). Nothing else on this page may wear them.
 */
val RECOGNITION_ICON get() = Icons.Rounded.GraphicEq

/** Newest first: what somebody sees at the top is what they have never seen before. */
val NEW_THINGS = listOf(
    // 0.12.5's own: no tour, the card says where each is (his yes of 27 Oct 2026).
    NewThing(
        id = "widget_styles",
        release = "0.12.5",
        sinceVersionCode = V0_12_5,
        title = R.string.new_widget_styles_title,
        body = R.string.new_widget_styles_body,
        icon = Icons.Rounded.Widgets,
        action = NewThingAction.NONE,
    ),
    NewThing(
        id = "player_pins",
        release = "0.12.5",
        sinceVersionCode = V0_12_5,
        title = R.string.new_player_pins_title,
        body = R.string.new_player_pins_body,
        icon = Icons.Rounded.PushPin,
        action = NewThingAction.NONE,
    ),
    NewThing(
        id = "settings_search",
        release = "0.12.5",
        sinceVersionCode = V0_12_5,
        title = R.string.new_settings_search_title,
        body = R.string.new_settings_search_body,
        icon = Icons.Rounded.Search,
        action = NewThingAction.NONE,
        there = { Unreleased.SETTINGS_SEARCH },
    ),
    NewThing(
        id = "battery_use",
        release = "0.12.5",
        sinceVersionCode = V0_12_5,
        title = R.string.battery_use,
        body = R.string.new_battery_use_body,
        icon = Icons.Rounded.BatterySaver,
        action = NewThingAction.NONE,
        there = { Unreleased.BATTERY_MODES },
    ),
    NewThing(
        id = "share_links",
        release = "0.12.5",
        sinceVersionCode = V0_12_5,
        title = R.string.new_share_links_title,
        body = R.string.new_share_links_body,
        icon = Icons.Rounded.Share,
        stops = listOf(
            TourStop("share_links_row", Tour.ROW_LIBRARY, Tour.ROUTE_SETTINGS, R.string.grp_library_and_content, R.string.new_share_links_way, V0_12_5),
            TourStop("share_links_setting", Tour.SETTING_SHARE_LINKS, Tour.ROUTE_LIBRARY, R.string.share_link_kind_title, R.string.new_share_links_here, V0_12_5),
        ),
        there = { Unreleased.SHARE_PAGE },
    ),
    NewThing(
        id = "cast",
        release = "0.12",
        sinceVersionCode = V0_12,
        title = R.string.new_cast_title,
        body = R.string.new_cast_body,
        icon = Icons.Rounded.Cast,
        action = NewThingAction.NONE,
        there = { Unreleased.CASTING },
    ),
    NewThing(
        id = "radios",
        release = "0.12",
        sinceVersionCode = V0_12,
        title = R.string.new_radios_title,
        body = R.string.new_radios_body,
        icon = Icons.Rounded.AutoAwesome,
        stops = listOf(
            TourStop("radios_row", Tour.ROW_PLAYER, Tour.ROUTE_SETTINGS, R.string.player_and_audio, R.string.new_radios_way, V0_12),
            TourStop("radios_setting", Tour.SETTING_ADAPTIVE_QUEUE, Tour.ROUTE_PLAYER, R.string.adaptive_queue, R.string.new_radios_here, V0_12),
        ),
    ),
    NewThing(
        id = "living_blur",
        release = "0.12",
        sinceVersionCode = V0_12,
        title = R.string.new_living_blur_title,
        body = R.string.new_living_blur_body,
        icon = Icons.Rounded.BlurOn,
        stops = listOf(
            TourStop("living_blur_row", Tour.ROW_LOOK_AND_FEEL, Tour.ROUTE_SETTINGS, R.string.look_and_feel, R.string.new_living_blur_way, V0_12),
            TourStop("living_blur_setting", Tour.SETTING_PLAYER_BACKGROUND, Tour.ROUTE_LOOK_AND_FEEL, R.string.player_background_style, R.string.new_living_blur_here, V0_12),
        ),
        there = { Unreleased.LIVING_BACKGROUND },
    ),
    NewThing(
        id = "sleep_schedule",
        release = "0.12",
        sinceVersionCode = V0_12,
        title = R.string.new_sleep_schedule_title,
        body = R.string.new_sleep_schedule_body,
        icon = Icons.Rounded.EventRepeat,
        stops = listOf(
            TourStop("sleep_schedule_row", Tour.ROW_PLAYER, Tour.ROUTE_SETTINGS, R.string.player_and_audio, R.string.new_sleep_schedule_way, V0_12),
            TourStop("sleep_schedule_setting", Tour.SETTING_SLEEP_SCHEDULE, Tour.ROUTE_PLAYER, R.string.sleep_schedule, R.string.new_sleep_schedule_here, V0_12),
        ),
    ),
    NewThing(
        id = "content_filters",
        release = "0.12",
        sinceVersionCode = V0_12,
        title = R.string.new_content_filters_title,
        body = R.string.new_content_filters_body,
        icon = Icons.Rounded.FilterAlt,
        stops = listOf(
            TourStop("content_filters_row", Tour.ROW_LIBRARY, Tour.ROUTE_SETTINGS, R.string.grp_library_and_content, R.string.new_content_filters_way, V0_12),
            TourStop("content_filters_setting", Tour.SETTING_CONTENT_FILTERS, Tour.ROUTE_LIBRARY, R.string.new_content_filters_title, R.string.new_content_filters_here, V0_12),
        ),
    ),
    NewThing(
        id = "equalizer",
        release = "0.12",
        sinceVersionCode = V0_12,
        title = R.string.new_equalizer_title,
        body = R.string.new_equalizer_body,
        icon = Icons.Rounded.Equalizer,
        stops = listOf(
            TourStop("equalizer_row", Tour.ROW_PLAYER, Tour.ROUTE_SETTINGS, R.string.player_and_audio, R.string.new_equalizer_way, V0_12),
            TourStop("equalizer_setting", Tour.SETTING_EQUALIZER, Tour.ROUTE_PLAYER, R.string.equalizer, R.string.new_equalizer_here, V0_12),
        ),
        there = { Unreleased.EQUALIZER },
    ),
    NewThing(
        id = "resume_bluetooth",
        release = "0.11.6",
        sinceVersionCode = V0_11_6,
        title = R.string.new_resume_bluetooth_title,
        body = R.string.new_resume_bluetooth_body,
        icon = Icons.Rounded.BluetoothAudio,
        stops = listOf(
            TourStop("resume_bluetooth_row", Tour.ROW_PLAYER, Tour.ROUTE_SETTINGS, R.string.player_and_audio, R.string.new_resume_bluetooth_way, V0_11_6),
            TourStop("resume_bluetooth_setting", Tour.SETTING_RESUME_BLUETOOTH, Tour.ROUTE_PLAYER, R.string.resume_on_bluetooth, R.string.new_resume_bluetooth_here, V0_11_6),
        ),
    ),
    NewThing(
        id = "recognition_tile",
        release = "0.11.6",
        sinceVersionCode = V0_11_6,
        title = R.string.new_recognition_tile_title,
        body = R.string.new_recognition_tile_body,
        icon = RECOGNITION_ICON,
        action = NewThingAction.ADD_TILE,
    ),
    NewThing(
        id = "library_import",
        release = "0.11.5",
        sinceVersionCode = V0_11_5,
        title = R.string.import_library_title,
        body = R.string.new_import_body,
        icon = Icons.Rounded.LibraryAdd,
        stops = listOf(
            TourStop("import_row", Tour.ROW_BACKUP, Tour.ROUTE_SETTINGS, R.string.backup_restore, R.string.new_import_way, V0_11_5),
            TourStop("import_setting", Tour.SETTING_IMPORT, Tour.ROUTE_BACKUP, R.string.import_library_title, R.string.new_import_here, V0_11_5),
        ),
    ),
    NewThing(
        id = "explain_pictures",
        release = "0.11.5",
        sinceVersionCode = V0_11_5,
        title = R.string.new_explain_pictures_title,
        body = R.string.new_explain_pictures_body,
        icon = Icons.Rounded.Info,
        stops = listOf(
            TourStop("explain_pictures_row", Tour.ROW_PLAYER, Tour.ROUTE_SETTINGS, R.string.player_and_audio, R.string.new_explain_pictures_way, V0_11_5),
            TourStop("explain_pictures_setting", Tour.SETTING_TRANSITION_FADE, Tour.ROUTE_PLAYER, R.string.transition_fade, R.string.new_explain_pictures_here, V0_11_5),
        ),
    ),
    NewThing(
        id = "problem_report",
        release = "0.11.5",
        sinceVersionCode = V0_11_5,
        title = R.string.problem_report_title,
        body = R.string.new_report_body,
        icon = Icons.Rounded.BugReport,
        stops = listOf(
            TourStop("problem_report_row", Tour.ROW_ABOUT, Tour.ROUTE_SETTINGS, R.string.about, R.string.new_report_way, V0_11_5),
            TourStop("problem_report_setting", Tour.SETTING_BUG_REPORT, Tour.ROUTE_ABOUT, R.string.help_bug_report_action, R.string.new_report_here, V0_11_5),
        ),
    ),
    NewThing(
        id = "quick_picks",
        release = "0.11",
        sinceVersionCode = V0_11,
        title = R.string.new_quick_picks_title,
        body = R.string.tour_quickpicks_body,
        icon = Icons.Rounded.Recommend,
        stops = TOUR_STOPS.filter { it.id == "quick_picks" },
        there = { it.quickPicksChips },
    ),
    NewThing(
        id = "recognise",
        release = "0.11",
        sinceVersionCode = V0_11,
        title = R.string.walkthrough_recognition_title,
        body = R.string.new_recognise_body,
        icon = RECOGNITION_ICON,
        stops = TOUR_STOPS.filter { it.id == "recognise" },
    ),
    NewThing(
        id = "widget",
        release = "0.11",
        sinceVersionCode = V0_11,
        title = R.string.new_widget_title,
        body = R.string.new_widget_body,
        icon = Icons.Rounded.Widgets,
        action = NewThingAction.ADD_WIDGET,
    ),
    NewThing(
        id = "history",
        release = "0.11",
        sinceVersionCode = V0_11,
        title = R.string.new_history_title,
        body = R.string.new_history_body,
        icon = Icons.Rounded.History,
        stops = listOf(
            TourStop("history_tile", Tour.HISTORY, null, R.string.history, R.string.new_history_here, V0_11),
        ),
    ),
    NewThing(
        id = "spatial_audio",
        release = "0.11",
        sinceVersionCode = V0_11,
        title = R.string.spatial_audio,
        body = R.string.new_spatial_body,
        icon = Icons.Rounded.SurroundSound,
        stops = listOf(
            TourStop("spatial_row", Tour.ROW_PLAYER, Tour.ROUTE_SETTINGS, R.string.player_and_audio, R.string.new_spatial_way, V0_11),
            TourStop("spatial_setting", Tour.SETTING_SPATIAL_AUDIO, Tour.ROUTE_PLAYER, R.string.spatial_audio, R.string.new_spatial_here, V0_11),
        ),
    ),
)

/**
 * What the welcome back page lists for somebody whose last walkthrough was at [seenVersionCode]:
 * everything that came after it and that this build and this [install] have. Nothing for a first
 * install, which gets the tour instead, and with [everything] the lot, for the entry in Settings.
 */
fun newThingsFor(
    seenVersionCode: Int,
    buildVersionCode: Int = BuildConfig.VERSION_CODE,
    everything: Boolean = false,
    install: Install = Install(),
): List<NewThing> {
    val here = NEW_THINGS.filter { it.there(install) }
    if (everything) return here
    if (seenVersionCode <= 0) return emptyList()
    return here.filter { it.sinceVersionCode in (seenVersionCode + 1)..buildVersionCode }
}

/**
 * The release a build numbered [versionCode] belongs to, as people know it ("0.11"), or null for
 * a build from before any release the list of new things knows.
 *
 * Read off that list and not off a table of its own. Every release that brought something has an
 * entry there with its first build, and a second list of numbers to keep in step with it is one
 * more to forget.
 */
fun releaseAt(versionCode: Int): String? =
    NEW_THINGS.filter { it.sinceVersionCode <= versionCode }.maxByOrNull { it.sinceVersionCode }?.release

/**
 * What the card at the top of Home says: how many things are new, and since which release, as
 * people know it. [since] is null for somebody who came from before any release the list knows,
 * and the card then gives the number alone.
 */
data class WelcomeCard(val count: Int, val since: String?)

/**
 * The card at the top of Home for somebody who has come back to an update, or null for none.
 *
 * The welcome back page used to open by itself at the first launch after an update: a whole page
 * between somebody and their music. It waits now, and this is all that says it is there. One
 * line among the cards Home already has at its top, to open the page from or to dismiss, and
 * nothing that has to be answered.
 *
 * There is one exactly where the page would have opened: for somebody who has had the tutorial
 * and whom this build has something to show (newThingsFor), which is never a first install. It
 * counts what is still to be looked at ([lookedAt]), and goes once all of it has been, or when it
 * is dismissed: both mark the build as seen, so it does not come back until a later build has
 * something new. Closing the page does not, since that is not having seen everything (his word,
 * 16 Oct 2026). It stands aside while something else has the screen: the setup, the questions
 * asked after an update, a tour, the page itself.
 */
fun welcomeCardFor(
    seenVersionCode: Int,
    buildVersionCode: Int = BuildConfig.VERSION_CODE,
    install: Install = Install(),
    enabled: Boolean = Unreleased.WELCOME_BACK,
    setupDone: Boolean = true,
    questionsOpen: Boolean = false,
    tourUp: Boolean = false,
    pageOpen: Boolean = false,
    lookedAt: Set<String> = emptySet(),
): WelcomeCard? {
    if (!enabled || !setupDone || questionsOpen || tourUp || pageOpen) return null
    // A card with no button is read on the page and has nothing more to give, so it never keeps
    // the card on Home waiting for a look that cannot come.
    val things = newThingsFor(seenVersionCode, buildVersionCode, install = install)
        .filter { it.action != NewThingAction.NONE && it.id !in lookedAt }
    if (things.isEmpty()) return null
    return WelcomeCard(count = things.size, since = releaseAt(seenVersionCode))
}

/** Every stop there is, for bringing a running tour back after the activity was recreated. */
val ALL_TOUR_STOPS: List<TourStop> get() = (TOUR_STOPS + SETTINGS_TOUR + CLOSER_LOOK_QUESTION + SETTINGS_CLOSER_LOOK + NEW_THINGS.flatMap { it.stops }).distinctBy { it.id }

/**
 * The same rule as [walkthroughFor], applied to the tour.
 *
 * A first install is walked round the app and then, with [settingsWalk], round Settings. The
 * tour's last stop points at the way in, and ending there left the one place people get lost in
 * as the one place nobody showed them. The walk ends on a question, whether to go on into the
 * settings themselves ([settingsWalkAndQuestion]). Somebody who has had the tour gets only the
 * stops that are new since, never the walk: the welcome back page offers it, and they can say no.
 */
fun tourFor(
    seenVersionCode: Int,
    buildVersionCode: Int = BuildConfig.VERSION_CODE,
    settingsWalk: Boolean = Unreleased.WELCOME_BACK,
): List<TourStop> {
    val shipped = TOUR_STOPS.filter { it.sinceVersionCode <= buildVersionCode }
    return if (seenVersionCode <= 0) shipped + (if (settingsWalk) settingsWalkAndQuestion() else emptyList())
    else shipped.filter { it.sinceVersionCode > seenVersionCode }
}

/**
 * The walk round Settings as the tutorial gives it: its four groups, then the question. The walk
 * asked for from the welcome back page is the four groups alone ([SETTINGS_TOUR]). Whoever asks
 * there has asked for the walk, and is brought back to the page when it is done.
 */
fun settingsWalkAndQuestion(): List<TourStop> = SETTINGS_TOUR + CLOSER_LOOK_QUESTION

/** Every stop, for the entry in settings. */
fun tourAll(): List<TourStop> = TOUR_STOPS
