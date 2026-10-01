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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.engine.CountForm
import com.dd3boh.outertune.engine.countForm
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.ExplainedPreference
import com.dd3boh.outertune.ui.component.FloatingTopBar
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
import com.dd3boh.outertune.ui.dialog.DefaultDialog
import com.dd3boh.outertune.viewmodels.AskNote
import com.dd3boh.outertune.viewmodels.DataAction
import com.dd3boh.outertune.viewmodels.DataAsk
import com.dd3boh.outertune.viewmodels.DataResult
import com.dd3boh.outertune.viewmodels.RecommendationsViewModel
import com.dd3boh.outertune.viewmodels.askNotes
import com.dd3boh.outertune.viewmodels.asksFirst
import java.text.DateFormat
import java.util.Date

/**
 * The buttons that change what the engine keeps: take a session or a day back, reset or rebuild
 * what it learned, and write it to a file or read it back. Every one that changes what it has
 * learned asks first, saying what will happen and how much; then each says what it did in place
 * of its description. Saving a copy changes nothing, so it does not ask.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecommendationsDataSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: RecommendationsViewModel = hiltViewModel(),
) {
    // Both live in the view model, so a rotation keeps the open dialog and the lines, and a result
    // that lands from the work running past the page still shows.
    val results by viewModel.dataResults.collectAsState()
    val asking by viewModel.asking.collectAsState()

    // A file, not a share sheet full of text. The reason anyone wants this is to move it to
    // another device or keep it before a reset, and neither is served by several kilobytes of
    // JSON pasted into a chat.
    val exportEngineLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) viewModel.exportTo(uri) }
    val importEngineLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.importFrom(uri) }

    fun tap(action: DataAction) {
        if (asksFirst(action)) {
            viewModel.ask(action)
        } else {
            val stamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
            exportEngineLauncher.launch("InterTune_engine_$stamp.json")
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
            description = results[DataAction.FORGET_SESSION]?.let { resultText(it) } ?: stringResource(R.string.forget_last_session_description),
            onClick = { tap(DataAction.FORGET_SESSION) },
        )
        ExplainedPreference(
            title = stringResource(R.string.forget_today),
            explanation = stringResource(R.string.forget_today_info),
            description = results[DataAction.FORGET_TODAY]?.let { resultText(it) } ?: stringResource(R.string.forget_today_description),
            onClick = { tap(DataAction.FORGET_TODAY) },
        )
        Spacer(Modifier.height(16.dp))

        PreferenceGroupTitle(title = stringResource(R.string.recommendations_data_start_over))
        ExplainedPreference(
            title = stringResource(R.string.recommendations_reset_weights),
            explanation = stringResource(R.string.recommendations_reset_weights_info),
            description = results[DataAction.RESET]?.let { resultText(it) } ?: stringResource(R.string.recommendations_reset_weights_description),
            onClick = { tap(DataAction.RESET) },
        )
        ExplainedPreference(
            title = stringResource(R.string.recommendations_rebuild_weights),
            explanation = stringResource(R.string.recommendations_rebuild_weights_info),
            description = results[DataAction.REBUILD]?.let { resultText(it) } ?: stringResource(R.string.recommendations_rebuild_weights_description),
            onClick = { tap(DataAction.REBUILD) },
        )
        Spacer(Modifier.height(16.dp))

        PreferenceGroupTitle(title = stringResource(R.string.recommendations_data_copy))
        ExplainedPreference(
            title = stringResource(R.string.export_engine_data),
            explanation = stringResource(R.string.export_engine_data_info),
            description = results[DataAction.SAVE]?.let { resultText(it) } ?: stringResource(R.string.export_engine_data_description),
            onClick = { tap(DataAction.SAVE) },
        )
        ExplainedPreference(
            title = stringResource(R.string.import_engine_data),
            explanation = stringResource(R.string.import_engine_data_info),
            description = results[DataAction.LOAD]?.let { resultText(it) } ?: stringResource(R.string.import_engine_data_description),
            onClick = { tap(DataAction.LOAD) },
        )
        Spacer(Modifier.height(24.dp))
    }

    asking?.let { ask ->
        AskFirstDialog(
            ask = ask,
            onDismiss = viewModel::dismissAsk,
            onConfirm = {
                // The yes to loading is a yes to choosing the file that replaces what it learned.
                // Only the first yes: a second tap before the dialog closed would open a second picker.
                if (viewModel.confirm(ask) && ask is DataAsk.Load) {
                    importEngineLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                }
            },
        )
    }

    FloatingTopBar(
        title = stringResource(R.string.recommendations_data_title),
        navController = navController,
        windowInsets = TopAppBarDefaults.windowInsets,
    )
}

/** What a button did, in its own words: resolved here, from resources, not in the callback. */
@Composable
private fun resultText(result: DataResult): String = when (result) {
    DataResult.Working -> stringResource(R.string.recommendations_data_working)
    is DataResult.Forgot ->
        if (result.listens == 0) stringResource(R.string.recommendations_nothing_to_forget)
        else pluralStringResource(R.plurals.recommendations_forgot, result.listens, result.listens)
    DataResult.ResetDone -> stringResource(R.string.recommendations_reset_done)
    DataResult.AlreadyAtStart -> stringResource(R.string.recommendations_already_at_start)
    // Nought and one in words: "Rebuilt from 0 cards" read as if something had failed.
    is DataResult.Rebuilt -> when (countForm(result.cards)) {
        CountForm.NONE -> stringResource(R.string.recommendations_rebuilt_nothing)
        CountForm.ONE -> stringResource(R.string.recommendations_rebuilt_single)
        CountForm.MANY -> pluralStringResource(R.plurals.recommendations_rebuilt, result.cards, result.cards)
    }
    DataResult.Saved -> stringResource(R.string.engine_data_export_done)
    DataResult.Loaded -> stringResource(R.string.engine_data_import_done)
    DataResult.Failed -> stringResource(R.string.recommendations_data_failed)
    DataResult.FileFailed -> stringResource(R.string.engine_data_failed)
}

/**
 * Before anything that changes what it has learned: what happens, how much, and whether it can be
 * undone. Laid out as the Keep dialog under Backup is, with a button that repeats the verb of the
 * title. Cancel, Back and a tap outside all leave everything as it was. Each names Best
 * recommendations once, so "it" has something to stand for, and says a loaded copy goes only when
 * there is one (see askNotes).
 */
@Composable
private fun AskFirstDialog(ask: DataAsk, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val title: String
    val what: String
    val confirm: String
    when (ask) {
        is DataAsk.ForgetSession -> {
            val began = Date(ask.began)
            title = pluralStringResource(R.plurals.recommendations_forget_title, ask.listens, ask.listens)
            what = stringResource(
                R.string.recommendations_forget_session_text,
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(began),
                DateFormat.getTimeInstance(DateFormat.SHORT).format(began),
            )
            confirm = stringResource(R.string.recommendations_forget_confirm)
        }
        is DataAsk.ForgetToday -> {
            title = pluralStringResource(R.plurals.recommendations_forget_title, ask.listens, ask.listens)
            what = stringResource(R.string.recommendations_forget_today_text)
            confirm = stringResource(R.string.recommendations_forget_confirm)
        }
        is DataAsk.Reset -> {
            title = stringResource(R.string.recommendations_reset_title)
            what = when (countForm(ask.cards)) {
                CountForm.NONE -> stringResource(R.string.recommendations_reset_text_nothing)
                CountForm.ONE -> stringResource(R.string.recommendations_reset_text_single)
                CountForm.MANY -> pluralStringResource(R.plurals.recommendations_reset_text, ask.cards, ask.cards)
            }
            confirm = stringResource(R.string.recommendations_reset_confirm)
        }
        is DataAsk.Rebuild -> {
            title = stringResource(R.string.recommendations_rebuild_title)
            what = when (countForm(ask.cards)) {
                CountForm.NONE -> stringResource(R.string.recommendations_rebuild_text_nothing)
                CountForm.ONE -> stringResource(R.string.recommendations_rebuild_text_single)
                CountForm.MANY -> pluralStringResource(R.plurals.recommendations_rebuild_text, ask.cards, ask.cards)
            }
            confirm = stringResource(R.string.recommendations_rebuild_confirm)
        }
        DataAsk.Load -> {
            title = stringResource(R.string.recommendations_load_title)
            what = stringResource(R.string.recommendations_load_text)
            confirm = stringResource(R.string.recommendations_load_confirm)
        }
    }
    val text = (listOf(what) + askNotes(ask).map { note ->
        when (note) {
            AskNote.COPY_REPLACED -> stringResource(R.string.recommendations_ask_copy_replaced)
            AskNote.COPY_GOES -> stringResource(R.string.recommendations_ask_copy_goes)
            AskNote.CANNOT_UNDO -> stringResource(R.string.recommendations_ask_cannot_undo)
        }
    }).joinToString(" ")
    DefaultDialog(
        onDismiss = onDismiss,
        horizontalAlignment = Alignment.Start,
        // Padded to line up with the text below, as ExplainDialog does.
        title = {
            Text(title, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
        },
        buttons = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
            TextButton(onClick = onConfirm) {
                Text(confirm)
            }
        },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}
