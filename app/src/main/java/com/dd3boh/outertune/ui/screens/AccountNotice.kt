/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.ui.dialog.DefaultDialog
import com.zionhuang.innertube.utils.parseCookieString

/**
 * Whether somebody is still to be told what signing in can cost.
 *
 * Setup says it on its sign-in page and the Account page in Settings says it beside the way in,
 * so whoever signs in from now on has read it first. That leaves the people who were signed in
 * before either said anything: they are told once, on opening the app. Nobody is told who has no
 * account here, and nobody in the middle of setup, which is about to tell them.
 */
fun accountNoticeOwed(setupDone: Boolean, signedIn: Boolean, seen: Boolean): Boolean =
    setupDone && signedIn && !seen

/** Whether the stored cookie is an account's, told the way setup and Settings tell it. */
fun isAccountCookie(cookie: String): Boolean = "SAPISID" in parseCookieString(cookie)

/**
 * The notice for somebody already signed in: what setup and Settings say, and where signing out
 * is, since they are the only ones for whom that is the next step.
 *
 * It closes on its button alone. A tap beside it or the back gesture would mark it read for good
 * without it having been, and it is shown once.
 */
@Composable
fun AccountNoticeDialog(onDone: () -> Unit) {
    DefaultDialog(
        onDismiss = {},
        horizontalAlignment = Alignment.Start,
        // Padded to line up with the text below, as AskFirstDialog does.
        title = {
            Text(
                text = stringResource(R.string.account_notice_title),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            )
        },
        buttons = {
            TextButton(onClick = onDone) {
                Text(stringResource(R.string.announcement_done))
            }
        },
    ) {
        // The button is the only way out, so the words give way to it: on a phone on its side or
        // with large text they scroll, and "Got it" stays on screen.
        Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Text(
                text = stringResource(R.string.account_notice_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.account_notice_signed_in, stringResource(R.string.grp_account_sync)),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}
