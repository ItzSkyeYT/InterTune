/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WebAssetOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.dd3boh.outertune.R
import com.dd3boh.outertune.utils.WebViewCheck

/**
 * In place of a sign-in page on a phone whose web view cannot start ([WebViewCheck], #221): why,
 * and the store's page for the web view, whose update fixes it on most phones.
 */
@Composable
fun NoWebViewNote(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    NoteCard(
        icon = Icons.Rounded.WebAssetOff,
        title = stringResource(R.string.no_web_view_title),
        body = stringResource(R.string.no_web_view_body),
        modifier = modifier,
    ) {
        NoteButton(stringResource(R.string.no_web_view_store)) { WebViewCheck.openStore(context) }
    }
}
