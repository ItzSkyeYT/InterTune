/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.ui.component.PreferenceEntry

/**
 * The search of Settings: a field over the list, and in the list's place while something is
 * typed, the settings it finds on every screen (SETTINGS_INDEX), updated with each letter.
 *
 * A result opens the screen it is on, scrolls to its row and lights it for a moment
 * (SettingJump.show). A screen whose name is typed is a result too, and opens as from the list.
 */
@Composable
internal fun rememberSettingsSearch(): SettingsSearchIndex {
    // In the app's language, which the resources follow when it changes.
    val resources = LocalResources.current
    return remember(resources) { SettingsSearchIndex.of(text = { resources.getString(it) }) }
}

/** The key a result is known by while it is the one picked: its screen and its title. */
internal val SettingHit.key: String get() = "$route|${entry?.title ?: ""}"

/** Opens what [hit] leads to: its row, scrolled to and lit, or its screen. */
internal fun openHit(hit: SettingHit, openScreen: (String) -> Unit) {
    val entry = hit.entry
    if (entry == null) openScreen(hit.route) else SettingJump.show(entry.jump())
}

/**
 * The field, the width of the cards under it: a pill with a magnifier, the words, and a way to
 * empty it once there is something in it. The keyboard's action closes the keyboard: the
 * results are already there.
 */
@Composable
internal fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    val placeholder = stringResource(R.string.search_settings)
    val style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 16.dp, end = 4.dp),
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 14.dp)
                .semantics { contentDescription = placeholder },
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = style,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    field()
                }
            },
        )
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.search_settings_clear))
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

/** What a result is shown with: the picture of the screen it is on, or of Settings. */
private fun SettingHit.icon() = settingsPlace(route).first?.icon ?: Icons.Rounded.Settings

/** The plain line said when nothing is found. */
@Composable
private fun NothingFound(query: String) {
    Text(
        text = stringResource(R.string.search_settings_none, query.trim()),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/**
 * The results, for the list upright: one card of rows like the settings themselves, each with
 * the screen it is on under its name.
 */
@Composable
internal fun SettingsSearchResults(
    hits: List<SettingHit>,
    query: String,
    onPick: (SettingHit) -> Unit,
) {
    if (hits.isEmpty()) {
        NothingFound(query)
        return
    }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        for (hit in hits) {
            PreferenceEntry(
                title = { Text(hit.title) },
                description = hit.place.ifEmpty { null },
                icon = { Icon(hit.icon(), null) },
                onClick = { onPick(hit) },
            )
        }
    }
}

/**
 * The results in the list of the two panes, in the place of its entries and like them: a
 * picture and a name, the screen under it, and the one opened on the right filled.
 */
@Composable
internal fun SettingsSearchPaneResults(
    hits: List<SettingHit>,
    query: String,
    picked: String?,
    onPick: (SettingHit) -> Unit,
) {
    if (hits.isEmpty()) {
        NothingFound(query)
        return
    }
    val shape = RoundedCornerShape(16.dp)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (hit in hits) {
            val chosen = hit.key == picked
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(if (chosen) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .selectable(selected = chosen, role = Role.Button, onClick = { onPick(hit) })
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Icon(
                    imageVector = hit.icon(),
                    contentDescription = null,
                    tint = if (chosen) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = hit.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (chosen) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (hit.place.isNotEmpty()) {
                        Text(
                            text = hit.place,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (chosen) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
