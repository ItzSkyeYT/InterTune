package com.dd3boh.outertune.ui.dialog

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.extensions.toMediaItem
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject
import com.dd3boh.outertune.ui.component.items.ListItem
import com.dd3boh.outertune.ui.component.items.QueueListItem
import com.dd3boh.outertune.utils.Toaster
import kotlinx.coroutines.launch


/**
 * Add to queue: the choice of this phone's queues for [songs], or a new one, which [onAdd] adds them to. Every menu's
 * Add to queue opens it.
 *
 * A phone that follows a listening room as a guest has no choice to make (his word of 27 Nov 2026, Spotify's Jam): its
 * queues are the room's copy, put back by the room's next word, and the ones from before the room, out of sight. The
 * songs go to the end of the room's queue with the room's own words, as its Play next goes to the room
 * (PlayerConnection.enqueueEnd). Decided here, as it opens, so that no menu goes round it.
 */
@Composable
fun AddToQueueDialog(
    initialTextFieldValue: String? = null,
    songs: suspend () -> List<MediaMetadata>,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val playerConnection = LocalPlayerConnection.current
    val guest = remember { playerConnection?.takeIf { it.addsToRoom } }
    if (guest != null) {
        LaunchedEffect(Unit) {
            // On the connection's scope: the menu may close before a playlist's songs have come.
            guest.scope.launch {
                val items = runCatching { songs() }.getOrDefault(emptyList()).map { it.toMediaItem() }
                // Nothing came (a playlist YouTube did not give, a list not read yet): said, rather than nothing at all.
                if (items.isEmpty()) Toaster.show(guest.service, R.string.together_suggested_none)
                else guest.enqueueEnd(items)
            }
            onDismiss()
        }
        return
    }
    var queues by remember {
        mutableStateOf(emptyList<MultiQueueObject>())
    }
    var showCreateQueueDialog by rememberSaveable {
        mutableStateOf(false)
    }


    LaunchedEffect(Unit) {
        queues = playerConnection?.service?.queueBoard?.getAllQueues()?.reversed() ?: emptyList()
    }

    ListDialog(
        onDismiss = onDismiss
    ) {
        item {
            ListItem(
                title = stringResource(R.string.create_queue),
                thumbnailContent = {
                    Image(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
                        modifier = Modifier.size(ListThumbnailSize)
                    )
                },
                modifier = Modifier.clickable {
                    showCreateQueueDialog = true
                }
            )
        }

        // add queue. The newest queue is listed first with the highest number; the number comes from
        // the item's place, since the list may draw its items again at any time.
        itemsIndexed(queues) { i, queue ->
            QueueListItem(
                queue = queue,
                number = queues.size - i,
                modifier = Modifier.clickable {
                    onAdd(queue.title)
                    onDismiss()
                }
            )
        }
    }

    if (showCreateQueueDialog) {
        TextFieldDialog(
            icon = { Icon(imageVector = Icons.Rounded.Add, contentDescription = null) },
            title = { Text(text = stringResource(R.string.create_queue)) },
            initialTextFieldValue = TextFieldValue(initialTextFieldValue ?: ""),
            onDismiss = { showCreateQueueDialog = false },
            onDone = { queuetName ->
                onAdd(queuetName)
                onDismiss()
            },
        )
    }
}

@Composable
fun EditQueueDialog(
    queue: MultiQueueObject,
    onDismiss: () -> Unit,
) {
    val playerConnection = LocalPlayerConnection.current

    TextFieldDialog(
        icon = { Icon(imageVector = Icons.Rounded.Edit, contentDescription = null) },
        title = { Text(text = stringResource(R.string.edit_playlist)) },
        onDismiss = onDismiss,
        initialTextFieldValue = TextFieldValue(
            queue.title,
            TextRange(queue.title.length)
        ),
        onDone = { name ->
            onDismiss()
            playerConnection?.service?.queueBoard?.renameQueue(queue, name)
        }
    )
}