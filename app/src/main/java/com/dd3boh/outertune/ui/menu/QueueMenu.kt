package com.dd3boh.outertune.ui.menu

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.remember
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.models.MultiQueueObject
import com.dd3boh.outertune.ui.component.items.QueueListItem
import com.dd3boh.outertune.ui.dialog.AddToPlaylistDialog
import com.dd3boh.outertune.ui.dialog.AddToQueueDialog
import com.dd3boh.outertune.ui.dialog.EditQueueDialog

@Composable
fun QueueMenu(
    navController: NavController,
    mq: MultiQueueObject?,
    onDismiss: () -> Unit,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val database = LocalDatabase.current

    if (mq == null) {
        onDismiss()
        return
    }
    val songs = mq.getCurrentQueueShuffled()
    var learns by remember(mq.id) { mutableStateOf(mq.learn) }

    var showChoosePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var showChooseQueueDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var showEditDialog by rememberSaveable {
        mutableStateOf(false)
    }

    // queue item
    QueueListItem(queue = mq)

    HorizontalDivider()

    // menu options
    GridMenu(
        contentPadding = PaddingValues(
            start = 8.dp,
            top = 8.dp,
            end = 8.dp,
            bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
        )
    ) {
        GridMenuItem(
            icon = Icons.AutoMirrored.Rounded.QueueMusic,
            title = R.string.add_to_queue
        ) {
            showChooseQueueDialog = true
        }
        GridMenuItem(
            icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
            title = R.string.add_to_playlist
        ) {
            showChoosePlaylistDialog = true
        }
        GridMenuItem(
            icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
            title = R.string.edit
        ) {
            showEditDialog = true
        }
        // Whether what plays here teaches Quick picks. One tap, reversible, and it reaches back
        // over what the queue has already played.
        GridMenuItem(
            icon = if (learns) Icons.Rounded.AutoAwesome else Icons.Outlined.AutoAwesome,
            title = if (learns) R.string.queue_learn_off else R.string.queue_learn_on
        ) {
            learns = !learns
            playerConnection.service.queueBoard.setQueueLearns(mq, learns)
        }
    }

    /**
     * ---------------------------
     * Dialogs
     * ---------------------------
     */
    if (showChoosePlaylistDialog) {
        // A queue holds songs that were only ever listed here, a radio's upcoming ones or a
        // search's, and the song table may not have them: a saved queue has them, one that was
        // not (Persistent queue off) or that changed a moment ago does not, and the playlist's
        // map needs them there (#62). Copied here, on the main thread, because the queue is a
        // list the player changes and the write below walks it on another.
        val queued = songs.toList()
        AddToPlaylistDialog(
            navController = navController,
            songIds = queued.map { it.id },
            onPreAdd = {
                database.transactionNow { queued.forEach { insert(it) } }
                queued.map { it.id }
            },
            onDismiss = {
                showChoosePlaylistDialog = false
            }
        )
    }

    if (showChooseQueueDialog) {
        AddToQueueDialog(
            onAdd = { queueName ->
                val q = playerConnection.service.queueBoard.addQueue(
                    queueName,
                    songs,
                    forceInsert = true,
                    delta = false,
                    origin = PlayOrigin.QUEUE
                )
                q?.let {
                    playerConnection.service.queueBoard.setCurrQueue(it)
                }
            },
            onDismiss = {
                showChooseQueueDialog = false
                onDismiss() // here we dismiss since we switch to the queue anyways
            }
        )
    }

    if (showEditDialog) {
        EditQueueDialog(
            queue = mq,
            onDismiss = {
                showEditDialog = false
                onDismiss()
            }
        )
    }
}