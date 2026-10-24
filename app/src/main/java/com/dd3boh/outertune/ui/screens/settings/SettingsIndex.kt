/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onSizeChanged
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.ENABLE_FFMETADATAEX
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.ui.screens.walkthrough.Tour
import com.dd3boh.outertune.ui.screens.walkthrough.TourTargets
import java.text.Normalizer

/**
 * One setting as the search of Settings knows it: the screen it is on, and its row's title.
 *
 * @param description the line under the row, when it is the same words whatever the setting is
 *   set to. A line that says the value, or what is going on, is not one to search.
 * @param words other words it is found by: a string of search words (search_words_*), which is
 *   never shown, or the names of its choices.
 * @param fold the heading of the fold it is under (SettingsClickToReveal), opened on the way to it.
 * @param under the title of the row to light instead when this one is not on its screen: the
 *   switch it hangs from, or the row that stands in its place.
 * @param there false where this build has no such row: its flag is off.
 */
data class SettingEntry(
    val route: String,
    @StringRes val title: Int,
    @StringRes val description: Int? = null,
    val words: List<Int> = emptyList(),
    @StringRes val fold: Int? = null,
    @StringRes val under: Int? = null,
    val there: Boolean = true,
)

/** The screens further in that settings live on, with their titles. Those of the list are its categories. */
private val FURTHER_PAGES = mapOf(
    "settings/recommendations/doing" to R.string.recommendations_doing_title,
    "settings/recommendations/data" to R.string.recommendations_data_title,
    "settings/player/equalizer" to R.string.equalizer,
)

/** The screen of the list that [route] is on or under, and its title further in, if it is further in. */
internal fun settingsPlace(route: String): Pair<SettingsCategory?, Int?> {
    val listed = settingsGroups().flatten()
    val own = listed.firstOrNull { it.route == route }
    if (own != null) return own to null
    val home = listed.filter { route.startsWith(it.route + "/") }.maxByOrNull { it.route.length }
    return home to FURTHER_PAGES[route]
}

/**
 * Every setting on every screen of Settings, in the order the screens and their rows come.
 *
 * Written out by hand, and held to the screens by SettingsIndexTest, which reads them: a row with
 * no entry here fails it, and so does an entry with no row, one on the wrong screen, a description
 * that is not the row's, a fold or a flag that is not the row's. Each row carries the mark of its
 * title ([settingRow]), and that is how a result finds it on the screen.
 *
 * Left out on purpose, and named in the test with why: rows that only show something (a status,
 * a count, the version on the way to an update), the steps of an update that has been found, and
 * the developer's own settings, which are hidden behind a switch and never translated.
 *
 * One entry a line, so that the test can read the flags off the line an entry is written on. A
 * flag that is on in every build is not written: it holds nothing back.
 */
val SETTINGS_INDEX: List<SettingEntry> = listOf(
    // Account and sync
    SettingEntry(Tour.ROUTE_ACCOUNT, R.string.login, words = listOf(R.string.search_words_login)),
    SettingEntry(Tour.ROUTE_ACCOUNT, R.string.action_logout, under = R.string.login, words = listOf(R.string.search_words_logout)),
    SettingEntry(Tour.ROUTE_ACCOUNT, R.string.token_hidden, words = listOf(R.string.search_words_token)),
    SettingEntry(Tour.ROUTE_ACCOUNT, R.string.use_login_for_browse, R.string.use_login_for_browse_desc),
    SettingEntry(Tour.ROUTE_ACCOUNT, R.string.lastfm_connect, R.string.lastfm_connect_description, under = R.string.lastfm_scrobble, words = listOf(R.string.search_words_lastfm)),
    SettingEntry(Tour.ROUTE_ACCOUNT, R.string.lastfm_scrobble, R.string.lastfm_scrobble_description, under = R.string.lastfm_connect, words = listOf(R.string.search_words_lastfm)),
    SettingEntry(Tour.ROUTE_ACCOUNT, R.string.ytm_sync, words = listOf(R.string.search_words_ytm_sync)),
    SettingEntry(Tour.ROUTE_ACCOUNT, R.string.scanner_manual_btn, words = listOf(R.string.search_words_sync_now)),
    SettingEntry(Tour.ROUTE_ACCOUNT, R.string.sync_mode),
    SettingEntry(Tour.ROUTE_ACCOUNT, R.string.sync_conflict_title),

    // Library and content
    SettingEntry(Tour.ROUTE_LIBRARY, R.string.quick_picks_source, words = listOf(R.string.recommendations_engine_title, R.string.recommendations_row_try_both, R.string.quick_picks_source_youtube, R.string.quick_picks_source_library)),
    SettingEntry(Tour.ROUTE_LIBRARY, R.string.share_link_kind_title, words = listOf(R.string.search_words_share_links), there = Unreleased.SHARE_PAGE),
    SettingEntry(Tour.ROUTE_LIBRARY, R.string.hide_explicit, R.string.hide_explicit_description, words = listOf(R.string.search_words_explicit)),
    SettingEntry(Tour.ROUTE_LIBRARY, R.string.hide_video_songs, R.string.hide_video_songs_description),
    SettingEntry(Tour.ROUTE_LIBRARY, R.string.content_language, words = listOf(R.string.search_words_language)),
    SettingEntry(Tour.ROUTE_LIBRARY, R.string.content_country, words = listOf(R.string.search_words_country)),
    SettingEntry(Tour.ROUTE_LIBRARY, R.string.show_liked_and_downloaded_playlist, R.string.show_liked_and_downloaded_playlist_description, fold = R.string.advanced),
    SettingEntry(Tour.ROUTE_LIBRARY, R.string.flat_subfolders_title, R.string.flat_subfolders_description, fold = R.string.advanced),

    // Local media
    SettingEntry(Tour.ROUTE_LOCAL, R.string.local_library_enable_title, R.string.local_library_enable_description, words = listOf(R.string.search_words_local)),
    SettingEntry(Tour.ROUTE_LOCAL, R.string.auto_scanner_title, R.string.auto_scanner_description),
    SettingEntry(Tour.ROUTE_LOCAL, R.string.scan_paths_title, under = R.string.local_library_enable_title, words = listOf(R.string.search_words_folders)),
    SettingEntry(Tour.ROUTE_LOCAL, R.string.scanner_sensitivity_title, under = R.string.local_library_enable_title),
    SettingEntry(Tour.ROUTE_LOCAL, R.string.scanner_strict_file_name_title, R.string.scanner_strict_file_name_description, under = R.string.local_library_enable_title),
    SettingEntry(Tour.ROUTE_LOCAL, R.string.scanner_strict_file_paths_title, R.string.scanner_strict_file_paths_description, under = R.string.local_library_enable_title),
    SettingEntry(Tour.ROUTE_LOCAL, R.string.scanner_type_title, under = R.string.local_library_enable_title),

    // Look and feel
    SettingEntry(Tour.ROUTE_LOOK_AND_FEEL, R.string.back_animations, R.string.back_animations_description, words = listOf(R.string.search_words_back_animations)),
    SettingEntry(Tour.ROUTE_LOOK_AND_FEEL, R.string.enable_dynamic_theme, R.string.enable_dynamic_theme_description, words = listOf(R.string.search_words_dynamic_theme)),
    SettingEntry(Tour.ROUTE_LOOK_AND_FEEL, R.string.high_contrast, R.string.high_contrast_description, under = R.string.enable_dynamic_theme),
    SettingEntry(Tour.ROUTE_LOOK_AND_FEEL, R.string.dark_theme, words = listOf(R.string.search_words_dark_theme)),
    SettingEntry(Tour.ROUTE_LOOK_AND_FEEL, R.string.pure_black, R.string.pure_black_description, words = listOf(R.string.search_words_pure_black)),
    SettingEntry(Tour.ROUTE_LOOK_AND_FEEL, R.string.player_background_style, words = listOf(R.string.player_background_blur, R.string.player_background_living, R.string.player_background_gradient)),
    SettingEntry(Tour.ROUTE_LOOK_AND_FEEL, R.string.player_living_colours, under = R.string.player_background_style),
    SettingEntry(Tour.ROUTE_LOOK_AND_FEEL, R.string.player_living_strength, R.string.player_living_strength_description, under = R.string.player_background_style),
    SettingEntry(Tour.ROUTE_LOOK_AND_FEEL, R.string.player_living_smoothing, R.string.player_living_smoothing_description, under = R.string.player_background_style),
    SettingEntry(Tour.ROUTE_LOOK_AND_FEEL, R.string.player_liquid_glass, R.string.player_liquid_glass_description, words = listOf(R.string.search_words_glass)),
    SettingEntry(Tour.ROUTE_LOOK_AND_FEEL, R.string.grouped_player_controls, R.string.grouped_player_controls_description),
    SettingEntry(Tour.ROUTE_LOOK_AND_FEEL, R.string.player_glass_intensity, R.string.player_glass_intensity_description, under = R.string.player_liquid_glass),
    SettingEntry(Tour.ROUTE_LOOK_AND_FEEL, R.string.player_buttons_style, R.string.player_buttons_style_description),
    SettingEntry(Tour.ROUTE_LOOK_AND_FEEL, R.string.queue_button, R.string.queue_button_description),
    SettingEntry(Tour.ROUTE_LOOK_AND_FEEL, R.string.queue_back_closes_player, R.string.queue_back_closes_player_description),
    SettingEntry(Tour.ROUTE_LOOK_AND_FEEL, R.string.media_control_buttons, words = listOf(R.string.search_words_media_buttons)),
    SettingEntry(Tour.ROUTE_LOOK_AND_FEEL, R.string.android_auto, words = listOf(R.string.search_words_car)),
    SettingEntry(Tour.ROUTE_LOOK_AND_FEEL, R.string.tab_arrangement, words = listOf(R.string.search_words_tabs)),
    SettingEntry(Tour.ROUTE_LOOK_AND_FEEL, R.string.filter_arrangement),
    SettingEntry(Tour.ROUTE_LOOK_AND_FEEL, R.string.default_open_tab, words = listOf(R.string.search_words_start_tab)),
    SettingEntry(Tour.ROUTE_LOOK_AND_FEEL, R.string.slim_navbar_title, R.string.slim_navbar_description),
    SettingEntry(Tour.ROUTE_LOOK_AND_FEEL, R.string.tablet_ui_title, R.string.tablet_ui_title_description, words = listOf(R.string.search_words_tablet)),
    SettingEntry(Tour.ROUTE_LOOK_AND_FEEL, R.string.swipe2Queue, R.string.swipe2Queue_description, words = listOf(R.string.search_words_gesture)),
    SettingEntry(Tour.ROUTE_LOOK_AND_FEEL, R.string.swipe_to_skip_title, R.string.swipe_to_skip_description, words = listOf(R.string.search_words_gesture)),
    SettingEntry(Tour.ROUTE_LOOK_AND_FEEL, R.string.swipe_to_dismiss_player, R.string.swipe_to_dismiss_player_description, words = listOf(R.string.search_words_gesture)),

    // Player and audio
    SettingEntry(Tour.ROUTE_PLAYER, R.string.auto_load_more, R.string.auto_load_more_desc),
    SettingEntry(Tour.ROUTE_PLAYER, R.string.seek_increment, words = listOf(R.string.search_words_seek)),
    SettingEntry(Tour.ROUTE_PLAYER, R.string.audio_quality, words = listOf(R.string.search_words_audio_quality)),
    SettingEntry(Tour.ROUTE_PLAYER, R.string.adaptive_queue, R.string.adaptive_radio_description, words = listOf(R.string.search_words_radio)),
    SettingEntry(Tour.ROUTE_PLAYER, R.string.transition_fade, R.string.transition_fade_description, words = listOf(R.string.search_words_crossfade)),
    SettingEntry(Tour.ROUTE_PLAYER, R.string.transition_fade_length, under = R.string.transition_fade),
    SettingEntry(Tour.ROUTE_PLAYER, R.string.spatial_audio, words = listOf(R.string.spatial_audio_headphones, R.string.spatial_audio_surround, R.string.search_words_spatial)),
    SettingEntry(Tour.ROUTE_PLAYER, R.string.head_tracking, under = R.string.spatial_audio, words = listOf(R.string.search_words_head_tracking)),
    SettingEntry(Tour.ROUTE_PLAYER, R.string.head_tracking_response, under = R.string.head_tracking),
    SettingEntry(Tour.ROUTE_PLAYER, R.string.head_tracking_3d, R.string.head_tracking_3d_description, under = R.string.head_tracking),
    SettingEntry(Tour.ROUTE_PLAYER, R.string.head_tracking_lead, under = R.string.head_tracking),
    SettingEntry(Tour.ROUTE_PLAYER, R.string.head_tracking_calibrate, under = R.string.head_tracking),
    SettingEntry(Tour.ROUTE_PLAYER, R.string.proximity_volume, R.string.proximity_volume_description),
    SettingEntry(Tour.ROUTE_PLAYER, R.string.audio_normalization, R.string.audio_normalization_description, words = listOf(R.string.search_words_normalization)),
    SettingEntry(Tour.ROUTE_PLAYER, R.string.skip_silence, R.string.skip_silence_description),
    SettingEntry(Tour.ROUTE_PLAYER, R.string.loudness_repair, words = listOf(R.string.search_words_normalization)),
    SettingEntry(Tour.ROUTE_PLAYER, R.string.equalizer, words = listOf(R.string.search_words_equalizer)),
    SettingEntry(Tour.ROUTE_PLAYER, R.string.auto_skip_next_on_error, R.string.auto_skip_next_on_error_desc),
    SettingEntry(Tour.ROUTE_PLAYER, R.string.stop_music_on_task_clear, R.string.stop_music_on_task_clear_description, words = listOf(R.string.search_words_recents)),
    SettingEntry(Tour.ROUTE_PLAYER, R.string.share_audio_focus, R.string.share_audio_focus_description, words = listOf(R.string.search_words_audio_focus)),
    SettingEntry(Tour.ROUTE_PLAYER, R.string.resume_on_bluetooth, R.string.resume_on_bluetooth_description, words = listOf(R.string.search_words_bluetooth)),
    SettingEntry(Tour.ROUTE_PLAYER, R.string.sleep_timer_fade, R.string.sleep_timer_fade_description, words = listOf(R.string.search_words_sleep)),
    SettingEntry(Tour.ROUTE_PLAYER, R.string.sleep_timer_fade_duration, under = R.string.sleep_timer_fade),
    SettingEntry(Tour.ROUTE_PLAYER, R.string.sleep_schedule, words = listOf(R.string.search_words_sleep)),
    SettingEntry(Tour.ROUTE_PLAYER, R.string.sleep_schedule_start, under = R.string.sleep_schedule),
    SettingEntry(Tour.ROUTE_PLAYER, R.string.sleep_schedule_length, under = R.string.sleep_schedule),
    SettingEntry(Tour.ROUTE_PLAYER, R.string.persistent_queue, R.string.persistent_queue_desc_ot, fold = R.string.advanced),
    SettingEntry(Tour.ROUTE_PLAYER, R.string.resume_on_launch, R.string.resume_on_launch_desc, fold = R.string.advanced),
    SettingEntry(Tour.ROUTE_PLAYER, R.string.max_queues_title, R.string.max_queues_description, fold = R.string.advanced),
    SettingEntry(Tour.ROUTE_PLAYER, R.string.playback_auth_mode, fold = R.string.advanced, words = listOf(R.string.search_words_account_playback)),
    SettingEntry(Tour.ROUTE_PLAYER, R.string.stage_width, fold = R.string.advanced, under = R.string.spatial_audio),
    SettingEntry(Tour.ROUTE_PLAYER, R.string.spatial_strength, fold = R.string.advanced, under = R.string.spatial_audio, there = Unreleased.SPATIAL_TUNING),
    SettingEntry(Tour.ROUTE_PLAYER, R.string.spatial_room, fold = R.string.advanced, under = R.string.spatial_audio, there = Unreleased.SPATIAL_TUNING),
    SettingEntry(Tour.ROUTE_PLAYER, R.string.spatial_bass, R.string.spatial_bass_description, fold = R.string.advanced, under = R.string.spatial_audio, there = Unreleased.SPATIAL_TUNING),
    SettingEntry(Tour.ROUTE_PLAYER, R.string.spatial_ambience, fold = R.string.advanced, under = R.string.spatial_audio, there = Unreleased.SPATIAL_TUNING && Unreleased.DIRECT_AMBIENT),
    SettingEntry(Tour.ROUTE_PLAYER, R.string.audio_decoder_preference, fold = R.string.advanced, there = ENABLE_FFMETADATAEX),
    SettingEntry(Tour.ROUTE_PLAYER, R.string.keep_alive_title, R.string.keep_alive_description, fold = R.string.advanced, words = listOf(R.string.search_words_keep_alive)),
    SettingEntry("settings/player/equalizer", R.string.equalizer_headphones, words = listOf(R.string.search_words_autoeq)),

    // Song recognition
    SettingEntry(Tour.ROUTE_RECOGNITION, R.string.recognise_keep_listening, R.string.recognise_keep_listening_desc, words = listOf(R.string.search_words_recognition)),
    SettingEntry(Tour.ROUTE_RECOGNITION, R.string.recognise_pause_title, R.string.recognise_pause_desc),
    SettingEntry(Tour.ROUTE_RECOGNITION, R.string.recognise_auto_add, R.string.recognise_auto_add_desc),
    SettingEntry(Tour.ROUTE_RECOGNITION, R.string.recognise_keep_awake, R.string.recognise_keep_awake_desc),
    SettingEntry(Tour.ROUTE_RECOGNITION, R.string.recognise_listen_seconds),
    SettingEntry(Tour.ROUTE_RECOGNITION, R.string.recognise_clear_history, R.string.recognise_clear_history_desc),

    // Lyrics
    SettingEntry(Tour.ROUTE_LYRICS, R.string.lyrics_sources, words = listOf(R.string.search_words_lyrics_sources), there = Unreleased.LYRICS_SOURCES),
    SettingEntry(Tour.ROUTE_LYRICS, R.string.enable_lrclib, R.string.enable_lrclib_description, there = !Unreleased.LYRICS_SOURCES),
    SettingEntry(Tour.ROUTE_LYRICS, R.string.enable_kugou, R.string.enable_kugou_description, there = !Unreleased.LYRICS_SOURCES),
    SettingEntry(Tour.ROUTE_LYRICS, R.string.lyrics_prefer_local, R.string.lyrics_prefer_local_description),
    SettingEntry(Tour.ROUTE_LYRICS, R.string.lyrics_multiline_title, R.string.lyrics_multiline_description),
    SettingEntry(Tour.ROUTE_LYRICS, R.string.lyrics_trim_title),
    SettingEntry(Tour.ROUTE_LYRICS, R.string.lyrics_text_position),
    SettingEntry(Tour.ROUTE_LYRICS, R.string.lyrics_font_Size, words = listOf(R.string.search_words_text_size)),
    SettingEntry(Tour.ROUTE_LYRICS, R.string.lyrics_latin_title, R.string.lyrics_latin_description, words = listOf(R.string.search_words_latin)),
    SettingEntry(Tour.ROUTE_LYRICS, R.string.lyrics_synced_clickable, fold = R.string.advanced),
    SettingEntry(Tour.ROUTE_LYRICS, R.string.lyrics_auto_scroll, R.string.lyrics_auto_scroll_description, fold = R.string.advanced),
    SettingEntry(Tour.ROUTE_LYRICS, R.string.lyrics_karaoke_title, R.string.lyrics_karaoke_description, fold = R.string.advanced),
    SettingEntry(Tour.ROUTE_LYRICS, R.string.lyrics_karaoke_hz_title, fold = R.string.advanced),

    // Storage and downloads
    SettingEntry(Tour.ROUTE_STORAGE, R.string.download_on_wifi_only_title, R.string.download_on_wifi_only_description, words = listOf(R.string.search_words_mobile_data)),
    SettingEntry(Tour.ROUTE_STORAGE, R.string.like_autodownload, words = listOf(R.string.search_words_offline)),
    SettingEntry(Tour.ROUTE_STORAGE, R.string.liked_autodownload_backfill_title, under = R.string.like_autodownload, words = listOf(R.string.search_words_offline)),
    SettingEntry(Tour.ROUTE_STORAGE, R.string.dl_main_path_title, words = listOf(R.string.search_words_download_folder)),
    SettingEntry(Tour.ROUTE_STORAGE, R.string.clear_all_downloads),
    SettingEntry(Tour.ROUTE_STORAGE, R.string.dl_extra_path_title, R.string.dl_extra_path_description, fold = R.string.advanced),
    SettingEntry(Tour.ROUTE_STORAGE, R.string.dl_rescan_title, R.string.dl_rescan_description, fold = R.string.advanced),
    SettingEntry(Tour.ROUTE_STORAGE, R.string.dl_migrate_title, R.string.dl_migrate_description, fold = R.string.advanced, words = listOf(R.string.search_words_download_folder)),
    SettingEntry(Tour.ROUTE_STORAGE, R.string.song_cache_max_size, words = listOf(R.string.search_words_cache)),
    SettingEntry(Tour.ROUTE_STORAGE, R.string.clear_song_cache, words = listOf(R.string.search_words_cache)),
    SettingEntry(Tour.ROUTE_STORAGE, R.string.image_cache_max_size, words = listOf(R.string.search_words_cache)),
    SettingEntry(Tour.ROUTE_STORAGE, R.string.clear_image_cache, words = listOf(R.string.search_words_cache)),

    // Backup and restore
    SettingEntry(Tour.ROUTE_BACKUP, R.string.action_backup, words = listOf(R.string.search_words_backup)),
    SettingEntry(Tour.ROUTE_BACKUP, R.string.action_restore, words = listOf(R.string.search_words_restore)),
    SettingEntry(Tour.ROUTE_BACKUP, R.string.auto_backup, R.string.auto_backup_description, words = listOf(R.string.search_words_backup)),
    SettingEntry(Tour.ROUTE_BACKUP, R.string.auto_backup_folder, under = R.string.auto_backup),
    SettingEntry(Tour.ROUTE_BACKUP, R.string.auto_backup_interval, under = R.string.auto_backup),
    SettingEntry(Tour.ROUTE_BACKUP, R.string.auto_backup_keep, under = R.string.auto_backup),
    SettingEntry(Tour.ROUTE_BACKUP, R.string.auto_backup_now, under = R.string.auto_backup),
    SettingEntry(Tour.ROUTE_BACKUP, R.string.export_playlists_m3u, R.string.export_playlists_m3u_description),
    SettingEntry(Tour.ROUTE_BACKUP, R.string.import_library_title, R.string.import_library_entry, words = listOf(R.string.search_words_import)),

    // Privacy and history
    SettingEntry(Tour.ROUTE_PRIVACY, R.string.pause_listen_history, R.string.pause_listen_history_description, words = listOf(R.string.search_words_privacy)),
    SettingEntry(Tour.ROUTE_PRIVACY, R.string.pause_remote_listen_history, R.string.pause_remote_listen_history_description),
    SettingEntry(Tour.ROUTE_PRIVACY, R.string.min_playback_duration),
    SettingEntry(Tour.ROUTE_PRIVACY, R.string.clear_listen_history, R.string.clear_listen_history_description),
    SettingEntry(Tour.ROUTE_PRIVACY, R.string.pause_search_history, R.string.pause_search_history_description),
    SettingEntry(Tour.ROUTE_PRIVACY, R.string.clear_search_history, R.string.clear_search_history_description),
    SettingEntry(Tour.ROUTE_PRIVACY, R.string.polls_enabled, R.string.polls_enabled_description, words = listOf(R.string.search_words_polls)),
    SettingEntry(Tour.ROUTE_PRIVACY, R.string.news_enabled, R.string.news_enabled_description),
    SettingEntry(Tour.ROUTE_PRIVACY, R.string.polls_forget, R.string.polls_forget_description, under = R.string.polls_enabled),
    SettingEntry(Tour.ROUTE_PRIVACY, R.string.usage_count_enabled, R.string.usage_count_enabled_description, words = listOf(R.string.search_words_usage_count)),
    SettingEntry(Tour.ROUTE_PRIVACY, R.string.usage_count_forget, R.string.usage_count_forget_description, under = R.string.usage_count_enabled),
    SettingEntry(Tour.ROUTE_PRIVACY, R.string.enable_proxy, R.string.enable_proxy_description, fold = R.string.advanced, words = listOf(R.string.search_words_proxy)),
    SettingEntry(Tour.ROUTE_PRIVACY, R.string.proxy_type, fold = R.string.advanced, under = R.string.enable_proxy),
    SettingEntry(Tour.ROUTE_PRIVACY, R.string.proxy_url, fold = R.string.advanced, under = R.string.enable_proxy),

    // Updates
    SettingEntry(Tour.ROUTE_UPDATES, R.string.update_check, words = listOf(R.string.search_words_updates)),
    SettingEntry(Tour.ROUTE_UPDATES, R.string.update_auto, R.string.update_auto_description),
    SettingEntry(Tour.ROUTE_UPDATES, R.string.background_check_interval),
    SettingEntry(Tour.ROUTE_UPDATES, R.string.check_for_update, words = listOf(R.string.search_words_updates)),
    SettingEntry(Tour.ROUTE_UPDATES, R.string.update_installed_version, words = listOf(R.string.search_words_version)),

    // Recommendations
    SettingEntry(Tour.ROUTE_RECOMMENDATIONS, R.string.tidy_home_rows, R.string.tidy_home_rows_description),
    SettingEntry(Tour.ROUTE_RECOMMENDATIONS, R.string.rank_with_listening, R.string.rank_with_listening_description),
    SettingEntry(Tour.ROUTE_RECOMMENDATIONS, R.string.discover_something_new, R.string.discover_row_description),
    SettingEntry(Tour.ROUTE_RECOMMENDATIONS, R.string.quick_picks_lean),
    SettingEntry(Tour.ROUTE_RECOMMENDATIONS, R.string.show_reasons, R.string.show_reasons_description),
    SettingEntry(Tour.ROUTE_RECOMMENDATIONS, R.string.adventurousness),
    SettingEntry(Tour.ROUTE_RECOMMENDATIONS, R.string.familiarity),
    SettingEntry(Tour.ROUTE_RECOMMENDATIONS, R.string.new_songs_only, R.string.new_songs_only_description),
    SettingEntry(Tour.ROUTE_RECOMMENDATIONS, R.string.similar_source, words = listOf(R.string.similar_source_youtube, R.string.similar_source_lastfm)),
    SettingEntry(Tour.ROUTE_RECOMMENDATIONS, R.string.learn_from_listening, R.string.learn_from_listening_description),
    SettingEntry(Tour.ROUTE_RECOMMENDATIONS, R.string.rest_songs_i_skip, R.string.rest_songs_i_skip_description),
    SettingEntry(Tour.ROUTE_RECOMMENDATIONS, R.string.rests_everywhere, R.string.rests_everywhere_description, under = R.string.rest_songs_i_skip),
    SettingEntry(Tour.ROUTE_RECOMMENDATIONS, R.string.exclusions),
    SettingEntry(Tour.ROUTE_RECOMMENDATIONS, R.string.recommendations_doing_title, R.string.recommendations_doing_description),
    SettingEntry(Tour.ROUTE_RECOMMENDATIONS, R.string.recommendations_data_title, R.string.recommendations_data_description),
    SettingEntry("settings/recommendations/doing", R.string.shadow_comparison),
    SettingEntry("settings/recommendations/doing", R.string.engine_developer, R.string.engine_developer_description),
    SettingEntry("settings/recommendations/data", R.string.forget_last_session, R.string.forget_last_session_description),
    SettingEntry("settings/recommendations/data", R.string.forget_today, R.string.forget_today_description),
    SettingEntry("settings/recommendations/data", R.string.recommendations_reset_weights, R.string.recommendations_reset_weights_description),
    SettingEntry("settings/recommendations/data", R.string.recommendations_rebuild_weights, R.string.recommendations_rebuild_weights_description),
    SettingEntry("settings/recommendations/data", R.string.export_engine_data, R.string.export_engine_data_description),
    SettingEntry("settings/recommendations/data", R.string.import_engine_data, R.string.import_engine_data_description),

    // Advanced
    SettingEntry(Tour.ROUTE_ADVANCED, R.string.flush_image_cache_title, R.string.flush_image_cache_description, words = listOf(R.string.search_words_cache)),
    SettingEntry(Tour.ROUTE_ADVANCED, R.string.dev_settings_title, R.string.dev_settings_description, words = listOf(R.string.search_words_developer)),

    // About
    SettingEntry(Tour.ROUTE_ABOUT, R.string.attribution_title),
    SettingEntry(Tour.ROUTE_ABOUT, R.string.oss_licenses_title, words = listOf(R.string.search_words_licenses)),
    SettingEntry(Tour.ROUTE_ABOUT, R.string.walkthrough_start, words = listOf(R.string.search_words_tutorial)),
    SettingEntry(Tour.ROUTE_ABOUT, R.string.welcome_back_entry, words = listOf(R.string.search_words_whats_new)),
    SettingEntry(Tour.ROUTE_ABOUT, R.string.tour_closer_entry, words = listOf(R.string.search_words_tutorial)),
    SettingEntry(Tour.ROUTE_ABOUT, R.string.help_bug_report_action, words = listOf(R.string.search_words_bug)),
    SettingEntry(Tour.ROUTE_ABOUT, R.string.help_support_forum, words = listOf(R.string.search_words_help)),
    SettingEntry(Tour.ROUTE_ABOUT, R.string.help_discord),
    SettingEntry(Tour.ROUTE_ABOUT, R.string.help_translate, words = listOf(R.string.search_words_translate)),
    SettingEntry(Tour.ROUTE_ABOUT, R.string.support_title, words = listOf(R.string.search_words_donate)),
    SettingEntry(Tour.ROUTE_ABOUT, R.string.help_contact_email_inquiries, words = listOf(R.string.search_words_contact)),
)

/**
 * The mark a row of settings carries, made from its title: the same title on two screens is two
 * rows, but never two rows on the screen that is open, so the title alone finds the row.
 */
fun settingMark(@StringRes title: Int): String = "setting_row_$title"

/** Where a result leads: its screen, the mark of its row, and what is on the way. */
internal fun SettingEntry.jump(): Jump = Jump(
    target = settingMark(title),
    route = route,
    fold = fold,
    instead = under?.let(::settingMark),
)

/**
 * Marks a row of settings with its title, as SETTINGS_INDEX knows it, so that a search result
 * can bring it into view and light it. Every row of every screen of settings carries one, and
 * SettingsIndexTest reads the screens and fails on one without it.
 *
 * Quiet until it is asked for. It says that it is there and how big it is, and no more: it does
 * not report where it is on every frame of a scroll, as the tour's marks do. The light is the
 * row's own, drawn behind its words, so it moves with the row, as a linked song's on its album.
 */
@Composable
fun Modifier.settingRow(@StringRes title: Int): Modifier {
    val id = settingMark(title)
    val bringer = remember { BringIntoViewRequester() }
    DisposableEffect(id) {
        TourTargets.arrive(id, bringer)
        onDispose { TourTargets.leave(id, bringer) }
    }
    val colour = MaterialTheme.colorScheme.primary
    return bringIntoViewRequester(bringer)
        .onSizeChanged { TourTargets.sized(id, bringer, it) }
        .drawBehind {
            if (SettingJump.lit == id) drawRect(colour.copy(alpha = ROW_LIGHT_ALPHA * SettingJump.light.value))
        }
}

/** As bright as the album's light on a linked song. */
private const val ROW_LIGHT_ALPHA = 0.18f

// Searching

/**
 * [text] as search compares it: in lower case, with its accents off and its ligatures spelled
 * out, so that "ecran" finds "Écran" and "oe" finds "œ"; anything that is not a letter or a digit
 * is a space, so that "Last.fm" is two words and "l'égaliseur" is "l egaliseur".
 */
fun searchForm(text: String): String {
    val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
    val out = StringBuilder(decomposed.length)
    for (c in decomposed) {
        when {
            Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> Unit
            c == 'œ' || c == 'Œ' -> out.append("oe")
            c == 'æ' || c == 'Æ' -> out.append("ae")
            c == 'ß' -> out.append("ss")
            c.isLetterOrDigit() -> out.append(c.lowercaseChar())
            else -> out.append(' ')
        }
    }
    return out.toString()
}

/** The words of [text] in search form. */
fun searchWords(text: String): List<String> = searchForm(text).split(' ').filter { it.isNotEmpty() }

// Words a query can hold that say nothing about what it is looking for: a query of "mode de
// lecture" finds rows without a "de" in them. Only left out when there is something else to go by.
private val SMALL_WORDS = setOf(
    "the", "a", "an", "of", "to", "in", "on", "for", "and", "my",
    "le", "la", "les", "l", "de", "des", "du", "d", "un", "une", "et", "en", "au", "aux",
)

/** What was typed, as words to look for. */
fun queryWords(query: String): List<String> {
    val words = searchWords(query)
    val telling = words.filter { it !in SMALL_WORDS }
    return telling.ifEmpty { words }
}

/** One thing to search: a title, and the other words it is found by, both as [searchWords]. */
class Searchable(val title: List<String>, val rest: List<String>)

private const val AT_TITLE_START = 8
private const val IN_TITLE_WORD = 6
private const val INSIDE_TITLE_WORD = 3
private const val IN_OTHER_WORD = 2
private const val INSIDE_OTHER_WORD = 1

/** How well [word] matches one of [words]: from the start of one, or, from three letters on, inside one. */
private fun bestIn(word: String, words: List<String>, start: Int, inside: Int): Int {
    var best = 0
    for (w in words) {
        if (w.startsWith(word)) return start
        if (word.length >= 3 && best == 0 && w.contains(word)) best = inside
    }
    return best
}

/**
 * How well the query's [words] find [item]: each of them must start a word of its title or of
 * the rest, or stand inside one from three letters on, and the score adds up how well, a word of
 * the title counting most and the title's first word more. Null when one of them is not there.
 *
 * A word that is not there as typed is tried without its plural s, so "downloads" finds
 * "Download on Wi-Fi only".
 */
fun searchScore(words: List<String>, item: Searchable): Int? {
    if (words.isEmpty()) return null
    var score = 0
    for (typed in words) {
        val tries = if (typed.length > 3 && typed.endsWith('s')) listOf(typed, typed.dropLast(1)) else listOf(typed)
        var best = 0
        for (word in tries) {
            val first = item.title.firstOrNull()
            val inTitle = if (first != null && first.startsWith(word)) AT_TITLE_START
            else bestIn(word, item.title, IN_TITLE_WORD, INSIDE_TITLE_WORD)
            best = maxOf(best, inTitle, bestIn(word, item.rest, IN_OTHER_WORD, INSIDE_OTHER_WORD))
            if (best > 0) break
        }
        if (best == 0) return null
        score += best
    }
    return score
}

/** A result: a setting, or a screen of settings when [entry] is null, under the words it is shown with. */
class SettingHit(
    val entry: SettingEntry?,
    val route: String,
    val title: String,
    val place: String,
    val score: Int,
)

/**
 * Everything search goes through, in the app's language: the settings of [entries] that are
 * [SettingEntry.there], and the screens of the list themselves, by their names.
 *
 * @param text a string resource as the app writes it now
 */
internal class SettingsSearchIndex(entries: List<SettingEntry>, categories: List<SettingsCategory>, text: (Int) -> String) {
    private class Item(val entry: SettingEntry?, val route: String, val title: String, val place: String, val searchable: Searchable)

    private val items: List<Item> = buildList {
        for (category in categories) {
            val title = text(category.title)
            add(Item(null, category.route, title, "", Searchable(searchWords(title), emptyList())))
        }
        val there = entries.filter { it.there }
        val places = there.associateWith { place(it, categories, text) }
        // Two settings of one name on one screen ("Fade length", of the fade between songs and of
        // the sleep timer's) are told apart by the setting each of them hangs from.
        val twice = there.groupBy { text(it.title) to places[it] }.filterValues { it.size > 1 }.values.flatten().toSet()
        for (entry in there) {
            val title = text(entry.title)
            val rest = buildString {
                entry.description?.let { append(text(it)).append(' ') }
                for (word in entry.words) append(text(word)).append(' ')
            }
            val place = places[entry]!! + (entry.under?.takeIf { entry in twice }?.let { " › " + text(it) } ?: "")
            add(Item(entry, entry.route, title, place, Searchable(searchWords(title), searchWords(rest))))
        }
    }

    private fun place(entry: SettingEntry, categories: List<SettingsCategory>, text: (Int) -> String): String {
        val (home, further) = settingsPlace(entry.route)
        val parts = listOfNotNull(
            (categories.firstOrNull { it.route == home?.route } ?: home)?.let { text(it.title) },
            further?.let(text),
            entry.fold?.let(text),
        )
        return parts.joinToString(" › ")
    }

    /**
     * The results for [query], the best first: a screen whose name matches before its settings,
     * and otherwise in the order of the screens and of their rows. Nothing for a blank query.
     */
    fun search(query: String, limit: Int = 60): List<SettingHit> {
        val words = queryWords(query)
        if (words.isEmpty()) return emptyList()
        return items.withIndex()
            .mapNotNull { (at, item) -> searchScore(words, item.searchable)?.let { Triple(at, item, it) } }
            .sortedWith(compareByDescending<Triple<Int, Item, Int>> { it.third }.thenBy { it.first })
            .take(limit)
            .map { (_, item, score) -> SettingHit(item.entry, item.route, item.title, item.place, score) }
    }

    companion object {
        /** In the app's language now. */
        fun of(text: (Int) -> String, engine: Boolean = Unreleased.ENGINE) =
            SettingsSearchIndex(SETTINGS_INDEX, settingsGroups(engine).flatten(), text)
    }
}
