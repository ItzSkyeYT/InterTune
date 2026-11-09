/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.menu

/**
 * Which menu a grid is, for what has been hidden from it (Unreleased.MENU_CHOICES). Menus that show the
 * same kind of thing share one: hiding Share from a song's menu hides it whether the song came from the
 * library or from YouTube Music, and the playing song's menu is its own.
 */
enum class MenuKind(val key: String) {
    SONG("song"),
    PLAYER("player"),
    SELECTION("selection"),
    QUEUE("queue"),
    ALBUM("album"),
    ARTIST("artist"),
    PLAYLIST("playlist"),
    FOLDER("folder"),
    LYRICS("lyrics"),
}

/**
 * Entries hidden from the three-dot menus, as stored: one preference, "kind:name" pairs one a line, the
 * name being the entry's text resource's name (R.string.share is "share"), which stays the same across
 * versions and languages. Nothing here touches Android, so the rules are tested on the JVM.
 */
object MenuChoices {
    fun parse(stored: String?): Set<String> =
        stored.orEmpty().lineSequence().map { it.trim() }.filter { it.isNotEmpty() && ':' in it }.toSet()

    fun write(hidden: Set<String>): String = hidden.sorted().joinToString("\n")

    /** The names hidden from menus of [kind]. */
    fun hiddenIn(hidden: Set<String>, kind: MenuKind): Set<String> =
        hidden.mapNotNullTo(mutableSetOf()) { if (it.startsWith(kind.key + ":")) it.substringAfter(':') else null }

    /**
     * Entries whose text follows a state, under the name of their other state, so that hiding one hides the
     * entry whatever it says: Download is also Remove download and Downloading, Add to library is also
     * Remove from library, Don't play this song is also Allow this song again.
     */
    private val SAME_ENTRY = mapOf(
        "remove_download" to "action_download",
        "downloading" to "action_download",
        "remove_from_library" to "add_to_library",
        "remove_all_from_library" to "add_all_to_library",
        "menu_dont_play_lift" to "menu_dont_play_song",
        "ai_filter_not_ai" to "ai_filter_mark_ai",
        "lyrics_share_picture_or_video" to "lyrics_share_picture",
        "mix_edit_last_transition" to "mix_edit_transition",
        "mix_menu_off" to "mix_menu_on",
    )

    /** The name an entry is hidden under, from its text's resource name. */
    fun entryName(resourceName: String): String = SAME_ENTRY[resourceName] ?: resourceName

    /** [hidden] with [name] moved: out of [kind]'s menus if it was in them, back in if it was out. */
    fun toggle(hidden: Set<String>, kind: MenuKind, name: String): Set<String> {
        val entry = kind.key + ":" + name
        return if (entry in hidden) hidden - entry else hidden + entry
    }
}

/**
 * The menu whose grid is being laid out, while its content runs. A menu's entries are added by
 * GridMenuItem, an extension of the grid's own scope, which Compose does not let an app wrap; GridMenu
 * sets this around its content instead, so every entry can ask whether it is hidden, and the hidden ones
 * that editing shows under Hidden are kept here to be laid out after the rest. Lazy grids run their
 * content on the main thread only, one at a time; null outside a menu that can be edited.
 */
internal class MenuBuild(
    val nameOf: (Int) -> String,
    val hidden: Set<String>,
    val editing: Boolean,
    val startEditing: () -> Unit,
    val toggle: (String) -> Unit,
) {
    /** Hidden entries this menu would have shown now: counted under the menu, laid out under Hidden while editing. */
    val held = mutableListOf<androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit>()
    var hiddenHere = 0

    companion object {
        var current: MenuBuild? = null
    }
}
