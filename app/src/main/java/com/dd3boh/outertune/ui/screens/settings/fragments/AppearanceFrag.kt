/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.MenuHiddenEntriesKey
import com.dd3boh.outertune.constants.SlimNavBarKey
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.menu.MenuChoices
import com.dd3boh.outertune.constants.TabletUiKey
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.ui.screens.settings.settingRow
import androidx.compose.ui.Modifier
import com.dd3boh.outertune.utils.rememberPreference

@Composable
fun ColumnScope.AppearanceMiscFrag() {
    val (slimNav, onSlimNavChange) = rememberPreference(SlimNavBarKey, defaultValue = false)
    val (tabletUi, onTabletUiChange) = rememberPreference(TabletUiKey, defaultValue = false)

    SwitchPreference(
        modifier = Modifier.settingRow(R.string.slim_navbar_title),
        title = { Text(stringResource(R.string.slim_navbar_title)) },
        description = stringResource(R.string.slim_navbar_description),
        icon = { Icon(Icons.Rounded.MoreHoriz, null) },
        checked = slimNav,
        onCheckedChange = onSlimNavChange
    )

    // Moved off the Experimental screen. Forcing the wide layout is a preference about how the
    // app looks on this device, not an experiment, and nobody hunting for it would think to look
    // behind a warning triangle.
    SwitchPreference(
        modifier = Modifier.settingRow(R.string.tablet_ui_title),
        title = { Text(stringResource(R.string.tablet_ui_title)) },
        description = stringResource(R.string.tablet_ui_title_description),
        icon = { Icon(Icons.Rounded.Devices, null) },
        checked = tabletUi,
        onCheckedChange = onTabletUiChange
    )

    // Choose what's in the three-dot menus: the way in is a hold on any entry of a menu, so this row
    // says so, and brings back at once whatever was hidden from every menu.
    if (Unreleased.MENU_CHOICES) {
        val (hiddenEntries, onHiddenEntriesChange) = rememberPreference(MenuHiddenEntriesKey, defaultValue = "")
        val anyHidden = MenuChoices.parse(hiddenEntries).isNotEmpty()
        PreferenceEntry(
            modifier = Modifier.settingRow(R.string.menu_choices_title),
            title = { Text(stringResource(R.string.menu_choices_title)) },
            description = stringResource(R.string.menu_choices_description),
            icon = { Icon(Icons.Rounded.MoreVert, null) },
            trailingContent = if (anyHidden) {
                { TextButton(onClick = { onHiddenEntriesChange("") }) { Text(stringResource(R.string.menu_choices_show_all)) } }
            } else null,
            onClick = null,
        )
    }
}