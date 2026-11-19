package com.dd3boh.outertune.ui.screens.settings.fragments

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalScrobbler
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.LastFmScrobbleKey
import com.dd3boh.outertune.constants.LastFmUsernameKey
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.utils.LastFmEnd
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.launch
import com.dd3boh.outertune.ui.screens.walkthrough.Tour
import com.dd3boh.outertune.ui.screens.walkthrough.tourTarget
import com.dd3boh.outertune.ui.screens.settings.settingRow
import androidx.compose.ui.Modifier

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
 *
 * From 1 December 2026 Last.fm takes nothing from this app (LastFmEnd), and the card is one row
 * saying so in place of all of that.
 */
@Composable
fun ColumnScope.LastFmFrag(navController: NavController) {
    val scope = rememberCoroutineScope()
    val scrobbler = LocalScrobbler.current
    val context = LocalContext.current

    // Last.fm has closed: why, in one row with nothing to tap. Before the row for a build without
    // a key, since the key reads as empty then too. The settings are held as a state so the switch
    // in Developer turns the card at once. No tour target here: the tour's Last.fm stop finds no
    // row and is left out by itself (TourState.leaveOut).
    val settings by context.dataStore.data.collectAsState()
    if (LastFmEnd.closed(settings)) {
        PreferenceEntry(
            title = { Text(stringResource(R.string.lastfm_ended)) },
            description = stringResource(R.string.lastfm_ended_description),
            icon = { Icon(Icons.Rounded.LinkOff, null) },
            onClick = null,
        )
        return
    }

    // A build with no Last.fm key cannot log anyone in, but saying nothing leaves the section
    // heading sitting above an empty card, which reads as broken rather than as unavailable.
    if (!scrobbler.isAvailable) {
        PreferenceEntry(
            title = { Text(stringResource(R.string.lastfm_unavailable)) },
            description = stringResource(R.string.lastfm_unavailable_description),
            icon = { Icon(Icons.Rounded.LinkOff, null) },
            isEnabled = false,
            onClick = null,
        )
        return
    }

    val (username, onUsernameChange) = rememberPreference(LastFmUsernameKey, "")
    val (scrobbling, onScrobblingChange) = rememberPreference(LastFmScrobbleKey, true)

    val connected = username.isNotBlank()

    if (connected) {
        PreferenceEntry(
            title = { Text(stringResource(R.string.lastfm_connected_as, username)) },
            description = stringResource(R.string.lastfm_disconnect_description),
            icon = { Icon(Icons.Rounded.LinkOff, null) },
            onClick = {
                scope.launch {
                    scrobbler.logout()
                    onUsernameChange("")
                }
            }
        )
        SwitchPreference(
            modifier = Modifier.settingRow(R.string.lastfm_scrobble),
            title = { Text(stringResource(R.string.lastfm_scrobble)) },
            description = stringResource(R.string.lastfm_scrobble_description),
            icon = { Icon(Icons.Rounded.Album, null) },
            checked = scrobbling,
            onCheckedChange = onScrobblingChange,
        )
    } else {
        PreferenceEntry(
            modifier = Modifier.tourTarget(Tour.SETTING_LASTFM).settingRow(R.string.lastfm_connect),
            title = { Text(stringResource(R.string.lastfm_connect)) },
            description = stringResource(R.string.lastfm_connect_description),
            icon = { Icon(Icons.Rounded.Link, null) },
            onClick = { navController.navigate("lastfm_login") }
        )
    }
}
