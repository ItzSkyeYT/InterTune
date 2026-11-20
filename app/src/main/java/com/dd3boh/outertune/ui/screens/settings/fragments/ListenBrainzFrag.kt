/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dd3boh.listenbrainz.ListenBrainzException
import com.dd3boh.listenbrainz.models.TokenCheck
import com.dd3boh.outertune.LocalScrobbler
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.Links
import com.dd3boh.outertune.constants.ListenBrainzRefusedAtKey
import com.dd3boh.outertune.constants.ListenBrainzScrobbleKey
import com.dd3boh.outertune.constants.ListenBrainzTokenKey
import com.dd3boh.outertune.constants.ListenBrainzUserKey
import com.dd3boh.outertune.ui.component.ExplainedSwitchPreference
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.dialog.DefaultDialog
import com.dd3boh.outertune.ui.screens.settings.settingRow
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ListenBrainz, under Last.fm in Account and sync (Unreleased.LISTENBRAINZ).
 *
 * ListenBrainz has no sign-in page for apps: the listener copies the user token from their
 * ListenBrainz settings and pastes it, as Metrolist and Pano Scrobbler have it, and Connect asks
 * ListenBrainz whose it is before keeping it. Once connected the token is never shown again; the
 * row names the account and disconnects, and the switch pauses sending without disconnecting.
 * When ListenBrainz no longer takes the token, the row says so and leads to the dialog again,
 * while the plays keep waiting for a token that works.
 */
@Composable
fun ColumnScope.ListenBrainzFrag() {
    val scope = rememberCoroutineScope()
    val sender = LocalScrobbler.current.listenBrainz

    val (token, _) = rememberPreference(ListenBrainzTokenKey, "")
    val (user, _) = rememberPreference(ListenBrainzUserKey, "")
    val (refusedAt, _) = rememberPreference(ListenBrainzRefusedAtKey, 0L)
    val (scrobbling, onScrobblingChange) = rememberPreference(ListenBrainzScrobbleKey, true)
    var connecting by rememberSaveable { mutableStateOf(false) }

    if (connecting) {
        ListenBrainzConnectDialog(
            onDismiss = { connecting = false },
            connect = { sender.connect(it) },
        )
    }

    if (token.isBlank()) {
        PreferenceEntry(
            modifier = Modifier.settingRow(R.string.listenbrainz_connect),
            title = { Text(stringResource(R.string.listenbrainz_connect)) },
            description = stringResource(R.string.listenbrainz_connect_description),
            icon = { Icon(Icons.Rounded.Link, null) },
            onClick = { connecting = true },
        )
        return
    }

    if (refusedAt > 0L) {
        PreferenceEntry(
            title = { Text(stringResource(R.string.listenbrainz_refused)) },
            description = stringResource(R.string.listenbrainz_refused_description),
            icon = { Icon(Icons.Rounded.LinkOff, null) },
            onClick = { connecting = true },
        )
    } else {
        PreferenceEntry(
            title = { Text(stringResource(R.string.listenbrainz_connected_as, user)) },
            description = stringResource(R.string.listenbrainz_disconnect_description),
            icon = { Icon(Icons.Rounded.LinkOff, null) },
            onClick = { scope.launch { sender.disconnect() } },
        )
    }
    ExplainedSwitchPreference(
        modifier = Modifier.settingRow(R.string.listenbrainz_scrobble),
        title = stringResource(R.string.listenbrainz_scrobble),
        explanation = stringResource(R.string.listenbrainz_scrobble_explain),
        description = stringResource(R.string.listenbrainz_scrobble_description),
        icon = { Icon(Icons.Rounded.Album, null) },
        checked = scrobbling,
        onCheckedChange = onScrobblingChange,
    )
}

/**
 * The token, pasted. Connect asks ListenBrainz whose it is, so a token copied wrong is said here
 * and never kept, and the dialog closes once ListenBrainz knows it. Get my token opens the
 * settings page in the phone's browser, where people are signed in already or can make an account.
 */
@Composable
private fun ListenBrainzConnectDialog(
    onDismiss: () -> Unit,
    connect: suspend (String) -> Result<TokenCheck>,
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var pasted by rememberSaveable { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    // What went wrong, as the string that says so; null while nothing has.
    var problem by rememberSaveable { mutableStateOf<Int?>(null) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        // As TextFieldDialog waits: asked at once, the keyboard does not come up.
        delay(300)
        focusRequester.requestFocus()
    }

    fun tryConnect() {
        if (pasted.isBlank() || checking) return
        checking = true
        problem = null
        scope.launch {
            // No answer is not a token refused, and says so: offline is the phone's side, a slow or
            // busy ListenBrainz is theirs (6 to 40 s an answer on 20 Nov 2026), and each says which.
            val result = connect(pasted)
            val check = result.getOrNull()
            val failure = result.exceptionOrNull()
            when {
                check?.valid == true -> onDismiss()
                check != null -> problem = R.string.listenbrainz_token_unknown
                failure is ListenBrainzException.Unreachable && !failure.slow -> problem = R.string.listenbrainz_unreachable
                else -> problem = R.string.listenbrainz_slow
            }
            checking = false
        }
    }

    DefaultDialog(
        onDismiss = onDismiss,
        horizontalAlignment = Alignment.Start,
        scrollable = true,
        title = {
            Text(
                stringResource(R.string.listenbrainz_dialog_title),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            )
        },
        buttonsStart = {
            TextButton(onClick = { uriHandler.openUri(Links.LISTENBRAINZ_SETTINGS) }) {
                Text(stringResource(R.string.listenbrainz_get_token))
            }
        },
        buttons = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            TextButton(onClick = { tryConnect() }, enabled = pasted.isNotBlank() && !checking) {
                Text(stringResource(R.string.listenbrainz_connect_button))
            }
        },
    ) {
        Text(
            text = stringResource(R.string.listenbrainz_dialog_body),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(16.dp))
        // While it asks, it says so: the answer can take a while on ListenBrainz's slow days.
        val said = problem?.let { stringResource(it) } ?: if (checking) stringResource(R.string.listenbrainz_checking) else null
        TextField(
            value = pasted,
            onValueChange = {
                pasted = it
                problem = null
            },
            label = { Text(stringResource(R.string.listenbrainz_token)) },
            singleLine = true,
            enabled = !checking,
            isError = problem != null,
            supportingText = if (said != null) ({ Text(said) }) else null,
            // A password's keyboard, so the keyboard neither suggests the token nor learns it.
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { tryConnect() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .focusRequester(focusRequester),
        )
    }
}
