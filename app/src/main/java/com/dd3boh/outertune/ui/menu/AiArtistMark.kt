/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.menu

import android.content.Context
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SmartToy
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AiMarkedArtistsKey
import com.dd3boh.outertune.constants.AiNotArtistsKey
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.models.AiArtistList
import com.dd3boh.outertune.models.AiFilter
import com.dd3boh.outertune.utils.dataStore

/**
 * The artist menus' row for the AI filter, in a build with Unreleased.AI_FILTER and while the
 * filter is on: Not AI on an artist it counts as AI, Mark as AI on one it does not. A YouTube
 * artist's only, whose id is a channel id, the ids the list is made of.
 */
fun LazyGridScope.aiArtistMenuItem(artistId: String, filter: AiFilter, onClick: () -> Unit) {
    if (!Unreleased.AI_FILTER || filter.isOff || !AiArtistList.isChannelId(artistId)) return
    val ai = filter.isAiArtist(artistId)
    GridMenuItem(
        icon = if (ai) Icons.Rounded.Person else Icons.Rounded.SmartToy,
        title = if (ai) R.string.ai_filter_not_ai else R.string.ai_filter_mark_ai,
        onClick = onClick,
    )
}

/** One tap on that row: the listener's two lists, kept on the phone, changed as AiFilter.toggle says. */
suspend fun toggleAiArtist(context: Context, artistId: String) {
    val listed = artistId in AiFilter.current.listed
    context.dataStore.edit { prefs ->
        val (marked, notAi) = AiFilter.toggle(artistId, listed, prefs[AiMarkedArtistsKey].orEmpty(), prefs[AiNotArtistsKey].orEmpty())
        prefs[AiMarkedArtistsKey] = marked
        prefs[AiNotArtistsKey] = notAi
    }
}
