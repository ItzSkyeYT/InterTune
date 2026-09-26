/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.ViewModel
import com.dd3boh.outertune.migration.LibraryImport
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** A window onto the app-wide [LibraryImport], which outlives the screen on purpose. */
@HiltViewModel
class LibraryImportViewModel @Inject constructor(
    val session: LibraryImport,
) : ViewModel() {
    val state = session.state
}
