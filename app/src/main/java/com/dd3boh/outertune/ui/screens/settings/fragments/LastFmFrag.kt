package com.dd3boh.outertune.ui.screens.settings.fragments

import android.content.Intent
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.dd3boh.outertune.LocalScrobbler
import com.dd3boh.outertune.LocalSnackbarHostState
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.LastFmScrobbleKey
import com.dd3boh.outertune.constants.LastFmUsernameKey
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.launch

/**
 * Connecting a Last.fm account.
 *
 * Two taps rather than one, because that is how the browser flow works and pretending otherwise
 * would mean asking for a password. The first tap opens last.fm to approve InterTune; the second,
 * after coming back, exchanges the approved token for a session key. The token is held only in
 * memory between the two, so an abandoned login leaves nothing behind.
 *
 * The whole fragment hides itself when the build has no Last.fm API key, since every button in it
 * would fail.
 */
@Composable
fun ColumnScope.LastFmFrag() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrobbler = LocalScrobbler.current
    val snackbar = LocalSnackbarHostState.current

    if (!scrobbler.isAvailable) return

    val (username, onUsernameChange) = rememberPreference(LastFmUsernameKey, "")
    val (scrobbling, onScrobblingChange) = rememberPreference(LastFmScrobbleKey, true)
    var pendingToken by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val connected = username.isNotBlank()

    when {
        connected -> {
            PreferenceEntry(
                title = { Text(stringResource(R.string.lastfm_connected_as, username)) },
                description = stringResource(R.string.lastfm_disconnect_description),
                icon = { Icon(Icons.Rounded.LinkOff, null) },
                onClick = {
                    scope.launch {
                        scrobbler.logout()
                        onUsernameChange("")
                        pendingToken = null
                    }
                }
            )
            SwitchPreference(
                title = { Text(stringResource(R.string.lastfm_scrobble)) },
                description = stringResource(R.string.lastfm_scrobble_description),
                icon = { Icon(Icons.Rounded.Album, null) },
                checked = scrobbling,
                onCheckedChange = onScrobblingChange,
            )
        }

        pendingToken != null -> {
            PreferenceEntry(
                title = { Text(stringResource(R.string.lastfm_finish)) },
                description = stringResource(R.string.lastfm_finish_description),
                icon = { Icon(Icons.Rounded.Link, null) },
                isEnabled = !busy,
                onClick = {
                    val token = pendingToken ?: return@PreferenceEntry
                    busy = true
                    scope.launch {
                        scrobbler.completeLogin(token)
                            .onSuccess { name ->
                                onUsernameChange(name)
                                pendingToken = null
                                snackbar.showSnackbar(context.getString(R.string.lastfm_connected_as, name))
                            }
                            .onFailure {
                                snackbar.showSnackbar(context.getString(R.string.lastfm_not_approved))
                            }
                        busy = false
                    }
                }
            )
        }

        else -> {
            PreferenceEntry(
                title = { Text(stringResource(R.string.lastfm_connect)) },
                description = stringResource(R.string.lastfm_connect_description),
                icon = { Icon(Icons.Rounded.Link, null) },
                isEnabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        scrobbler.beginLogin()
                            .onSuccess { (token, url) ->
                                pendingToken = token
                                context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                            }
                            .onFailure {
                                snackbar.showSnackbar(context.getString(R.string.lastfm_connect_failed))
                            }
                        busy = false
                    }
                }
            )
        }
    }
}
