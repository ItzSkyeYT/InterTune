/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.RecapRevealedEarlyKey
import com.dd3boh.outertune.recap.RecapCalendar
import com.dd3boh.outertune.recap.RecapNote
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** One row of the notes page: a year's notes opened by its Recap, or those still sealed for one to come. */
internal sealed interface NotesShelf {
    /** The Recap of [recapYear] has opened them: their words, in the order written. */
    data class Opened(val recapYear: Int, val notes: List<RecapNote.Note>) : NotesShelf

    /** For the Recap of [recapYear], not out yet: how many, never their words. */
    data class Sealed(val recapYear: Int, val count: Int) : NotesShelf
}

/**
 * The notes page's rows, newest Recap first, from how many notes each year keeps ([counts], by the
 * year whose Recap sealed them: their words are for the next year's). Those whose Recap is out on
 * [onShow] (RecapCalendar.yearOnShow) are opened with [open], off the main thread; the rest stay
 * sealed and only counted, and [open] is never asked for them.
 */
internal fun notesShelf(counts: Map<Int, Int>, onShow: Int?, open: (Int) -> List<RecapNote.Note>): List<NotesShelf> =
    counts.filterValues { it > 0 }.keys.sortedDescending().mapNotNull { kept ->
        val recap = kept + 1
        if (onShow != null && recap <= onShow) open(kept).takeIf { it.isNotEmpty() }?.let { NotesShelf.Opened(recap, it) }
        else NotesShelf.Sealed(recap, counts.getValue(kept))
    }

/**
 * "Your notes" (his ask of 22 Nov 2026: "letting you open notes and it shows all your past notes"),
 * over whatever opened it, from the Recap's start page or its banner on the Stats page: every note
 * a Recap has opened, newest Recap first, on the same taped papers, and those sealed for a Recap
 * still to come as a line that says how many. Read off the main thread; Back closes it.
 */
@Composable
fun RecapNotesDialog(onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        DisposableEffect(view) {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = true
                    isAppearanceLightNavigationBars = true
                }
            }
            onDispose { }
        }
        RecapNotesPage(onClose)
    }
}

@Composable
private fun RecapNotesPage(onClose: () -> Unit) {
    val context = LocalContext.current
    val revealedEarly by rememberPreference(RecapRevealedEarlyKey, defaultValue = false)
    var shelf by remember { mutableStateOf<List<NotesShelf>?>(null) }
    LaunchedEffect(revealedEarly) {
        val onShow = RecapCalendar.yearOnShow(LocalDate.now(), revealedEarly && BuildConfig.PREVIEW_FEATURES)
        shelf = withContext(Dispatchers.IO) { notesShelf(RecapNote.years(context), onShow) { RecapNote.read(context, it) } }
    }
    // Days with their year, whatever the year: these come from several.
    val words = remember(context) { RecapWords(context, 0) }
    val paper = paperOf(SlideKind.NOTE_READ)
    val density = LocalDensity.current.density
    val grain = remember(density) { PaperGrain.brush(density) }
    Box(Modifier.fillMaxSize().background(paper.paper)) {
        Canvas(Modifier.fillMaxSize()) { drawRect(grain) }
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.recap_notes_title), style = RecapType.h2, color = paper.ink, modifier = Modifier.weight(1f))
                CloseButton(onClose, tint = paper.ink)
            }
            Column(Modifier.padding(end = 12.dp).widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    shelf == null -> Unit
                    shelf.orEmpty().isEmpty() -> Text(
                        stringResource(R.string.recap_notes_none), style = RecapType.line, color = paper.ink2, modifier = Modifier.padding(top = 18.dp),
                    )
                    else -> shelf.orEmpty().forEach { row ->
                        when (row) {
                            is NotesShelf.Opened -> {
                                Text(
                                    stringResource(R.string.recap_notes_opened_by, row.recapYear.toString()), style = RecapType.kick, color = paper.ink2,
                                    modifier = Modifier.padding(top = 26.dp),
                                )
                                row.notes.forEachIndexed { i, note -> ReadPaper(null, words, note, at = 0f, turn = if (i % 2 == 1) -1.2f else 1.4f, fr = 0f) }
                            }
                            is NotesShelf.Sealed -> SealedLine(row, paper)
                        }
                    }
                }
            }
        }
    }
}

/** Notes for a Recap still to come: an envelope's worth of words, never theirs. */
@Composable
private fun SealedLine(row: NotesShelf.Sealed, paper: SlidePaper) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(top = 22.dp)
            .fillMaxWidth()
            .shadow(6.dp, shape, ambientColor = RecapInk.Shade, spotColor = RecapInk.Shade)
            .background(RecapInk.White, shape)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Box(Modifier.size(34.dp).background(RecapRed, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Lock, null, tint = RecapInk.Cream, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(
            pluralStringResource(R.plurals.recap_note_sealed_count, row.count, row.count.toString(), row.recapYear.toString()),
            style = RecapType.line, color = RecapInk.Ink,
        )
    }
    Spacer(Modifier.height(2.dp))
}
