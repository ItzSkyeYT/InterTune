/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.ExplainedPreference
import com.dd3boh.outertune.ui.component.FloatingTopBar
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
import com.dd3boh.outertune.viewmodels.RecommendationsViewModel

/**
 * The buttons that change what the engine keeps: take a session or a day back, reset or rebuild
 * the weights, and write them to a file or read them back. Each does exactly what it did on the
 * Recommendations page before it moved here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecommendationsDataSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: RecommendationsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    // A file, not a share sheet full of text. The reason anyone wants this is to move it to
    // another device or keep it before a reset, and neither is served by several kilobytes of
    // JSON pasted into a chat.
    var engineIoResult by remember { mutableStateOf<String?>(null) }
    val exportEngineLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) viewModel.exportTo(uri) { ok ->
            engineIoResult = context.getString(
                if (ok) R.string.engine_data_export_done else R.string.engine_data_failed
            )
        }
    }
    val importEngineLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importFrom(uri) { count ->
            engineIoResult = if (count > 0) {
                context.resources.getQuantityString(R.plurals.engine_data_import_done, count, count)
            } else {
                context.getString(R.string.engine_data_failed)
            }
        }
    }

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier.padding(horizontal = 16.dp)
    ) {
        Text(
            text = stringResource(R.string.recommendations_data_promise),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        PreferenceGroupTitle(title = stringResource(R.string.recommendations_data_forget))
        ExplainedPreference(
            title = stringResource(R.string.forget_last_session),
            explanation = stringResource(R.string.forget_last_session_info),
            description = stringResource(R.string.forget_last_session_description),
            onClick = { viewModel.forgetLastSession() },
        )
        ExplainedPreference(
            title = stringResource(R.string.forget_today),
            explanation = stringResource(R.string.forget_today_info),
            description = stringResource(R.string.forget_today_description),
            onClick = { viewModel.forgetToday() },
        )
        Spacer(Modifier.height(16.dp))

        PreferenceGroupTitle(title = stringResource(R.string.recommendations_data_start_over))
        ExplainedPreference(
            title = stringResource(R.string.recommendations_reset_weights),
            explanation = stringResource(R.string.recommendations_reset_weights_info),
            description = stringResource(R.string.recommendations_reset_weights_description),
            onClick = { viewModel.resetWeights() },
        )
        ExplainedPreference(
            title = stringResource(R.string.recommendations_rebuild_weights),
            explanation = stringResource(R.string.recommendations_rebuild_weights_info),
            description = stringResource(R.string.recommendations_rebuild_weights_description),
            onClick = { viewModel.rebuildWeights() },
        )
        Spacer(Modifier.height(16.dp))

        PreferenceGroupTitle(title = stringResource(R.string.recommendations_data_copy))
        ExplainedPreference(
            title = stringResource(R.string.export_engine_data),
            explanation = stringResource(R.string.export_engine_data_info),
            description = engineIoResult ?: stringResource(R.string.export_engine_data_description),
            onClick = {
                val stamp = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                exportEngineLauncher.launch("InterTune_engine_$stamp.json")
            },
        )
        ExplainedPreference(
            title = stringResource(R.string.import_engine_data),
            explanation = stringResource(R.string.import_engine_data_info),
            description = stringResource(R.string.import_engine_data_description),
            onClick = { importEngineLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
        )
        Spacer(Modifier.height(24.dp))
    }

    FloatingTopBar(
        title = stringResource(R.string.recommendations_data_title),
        navController = navController,
        windowInsets = TopAppBarDefaults.windowInsets,
    )
}
