/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import android.net.Uri
import androidx.navigation.NavController

/** The editor's route: the two songs in play order, by their exact ids. */
const val MIX_EDITOR_ROUTE = "mix_edit/{from}/{to}"

/** Opens the editor for the change from song [from] into song [to]. */
fun NavController.openMixEditor(from: String, to: String) {
    navigate("mix_edit/${Uri.encode(from)}/${Uri.encode(to)}")
}
