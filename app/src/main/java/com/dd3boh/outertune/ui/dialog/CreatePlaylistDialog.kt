/*
 * Copyright (C) 2025 O⁠ute⁠rTu⁠ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.dialog

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.R
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.utils.ErrorText
import com.dd3boh.outertune.utils.mayPushToYouTube
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

@Composable
fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    initialTextFieldValue: String? = null,
    allowSyncing: Boolean = true,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    var syncedPlaylist by remember { mutableStateOf(false) }

    TextFieldDialog(
        icon = { Icon(imageVector = Icons.Rounded.Add, contentDescription = null) },
        title = { Text(text = stringResource(R.string.create_playlist)) },
        initialTextFieldValue = TextFieldValue(initialTextFieldValue ?: ""),
        onDismiss = onDismiss,
        onDone = { playlistName ->
            val appContext = context.applicationContext
            // On a scope of its own: the dialog's goes as soon as it closes, which is now.
            CoroutineScope(Dispatchers.IO).launch {
                val browseId = if (syncedPlaylist) {
                    // Offline or throttled this used to throw straight out of the coroutine and
                    // crash the app. Now nothing is created, rather than a synced playlist with
                    // no YouTube side, and the person is told.
                    YouTube.createPlaylist(playlistName).getOrElse {
                        Log.w("CreatePlaylistDialog", "Could not create the playlist on YouTube Music", ErrorText.forLog(it))
                        withContext(Dispatchers.Main) {
                            Toast.makeText(appContext, R.string.create_sync_playlist_failed, Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }
                } else null

                database.query {
                    insert(
                        PlaylistEntity(
                            name = playlistName,
                            browseId = browseId,
                            bookmarkedAt = LocalDateTime.now(),
                            isEditable = true,
                            isLocal = !syncedPlaylist // && check that all songs are non-local
                        )
                    )
                }
            }
        },
        extraContent = {
            // Only where the account may be changed: in "Read only" a synced playlist would have
            // to be created in the account, which that setting promises never to do.
            if (allowSyncing && context.mayPushToYouTube()) {
                Row(
                    modifier = Modifier.padding(vertical = 16.dp, horizontal = 40.dp)
                ) {
                    Column() {
                        Text(
                            text = stringResource(R.string.create_sync_playlist),
                            style = MaterialTheme.typography.titleLarge,
                        )

                        Text(
                            text = stringResource(R.string.create_sync_playlist_description),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(0.7f)
                        )
                    }
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Switch(
                            checked = syncedPlaylist,
                            onCheckedChange = {
                                syncedPlaylist = !syncedPlaylist
                            },
                        )
                    }
                }
            }
        }
    )
}