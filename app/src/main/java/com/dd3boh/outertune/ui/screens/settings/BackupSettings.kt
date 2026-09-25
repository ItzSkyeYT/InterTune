/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.FloatingTopBar
import com.dd3boh.outertune.ui.dialog.InfoLabel
import com.dd3boh.outertune.ui.screens.settings.fragments.BackupAndRestoreFrag
import com.dd3boh.outertune.viewmodels.BackupRestoreViewModel

/**
 * Backup and restore, a category of its own again.
 *
 * It sat at the bottom of Storage and downloads for a while, when it was two rows. With automatic
 * backups, their interval, folder and history, and playlist export beside the two buttons, it is
 * the thing somebody goes looking for before changing phones or reinstalling, and it was the one
 * setting that had to be found by scrolling past three caches.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: BackupRestoreViewModel = hiltViewModel(),
) {
    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .padding(horizontal = 16.dp)
    ) {
        BackupAndRestoreFrag(viewModel)
        Spacer(modifier = Modifier.height(16.dp))
        InfoLabel(stringResource(R.string.import_innertune_tooltip))
        Spacer(modifier = Modifier.height(8.dp))
        InfoLabel(stringResource(R.string.restore_lm_tooltip))
    }

    FloatingTopBar(title = stringResource(R.string.backup_restore), navController = navController)
}
