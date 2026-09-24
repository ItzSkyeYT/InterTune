package com.dd3boh.outertune.ui.screens.settings.fragments

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Output
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.DpSize
import kotlin.math.roundToInt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AutoBackupEnabledKey
import com.dd3boh.outertune.constants.AutoBackupFolderKey
import com.dd3boh.outertune.constants.AutoBackupIntervalHoursKey
import com.dd3boh.outertune.constants.AutoBackupKeepKey
import com.dd3boh.outertune.constants.AutoBackupLastResultKey
import com.dd3boh.outertune.constants.AutoBackupLastRunKey
import com.dd3boh.outertune.constants.PlaylistFilter
import com.dd3boh.outertune.constants.PlaylistSortType
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.extensions.tryOrNull
import com.dd3boh.outertune.ui.component.ListPreference
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.utils.AutoBackup
import com.dd3boh.outertune.utils.M3u
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.utils.reportException
import com.dd3boh.outertune.viewmodels.BackupRestoreViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

// The expressive slider track, the one with the gap beside the thumb, is still marked
// experimental in Material 3 1.4. It is what the rest of the system draws, so it is the right
// look rather than an adventurous one.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColumnScope.BackupAndRestoreFrag(viewModel: BackupRestoreViewModel) {
    val context = LocalContext.current

    val backupLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) {
                viewModel.backup(uri)
            }
        }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.restore(uri)
        }
    }

    // Every library playlist as an .m3u in a folder of the user's choosing. A one-off write, so
    // the picker's grant is used here and now and never persisted.
    val database = LocalDatabase.current
    val exportPlaylistsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val appContext = context.applicationContext
            // Its own scope rather than the screen's: leaving Settings must not stop the writes
            // half way through the library.
            CoroutineScope(Dispatchers.IO).launch {
                val exported = try {
                    exportPlaylistsAsM3u(appContext, database, uri)
                } catch (e: Exception) {
                    reportException(e)
                    0
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        appContext,
                        if (exported == 0) appContext.getString(R.string.no_playlists_to_export)
                        else appContext.resources.getQuantityString(R.plurals.exported_playlists, exported, exported),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

    // Automatic backups. Inert until the switch is on and a folder is chosen: AutoBackup.schedule
    // cancels rather than enqueues in that state, so nothing runs for anyone who has not opted in.
    val (autoBackupEnabled, onAutoBackupEnabledChange) =
        rememberPreference(AutoBackupEnabledKey, defaultValue = false)
    val (autoBackupFolder, onAutoBackupFolderChange) =
        rememberPreference(AutoBackupFolderKey, defaultValue = "")
    val (autoBackupHours, onAutoBackupHoursChange) =
        rememberPreference(AutoBackupIntervalHoursKey, defaultValue = AutoBackup.DEFAULT_INTERVAL_HOURS)
    val (autoBackupKeep, onAutoBackupKeepChange) =
        rememberPreference(AutoBackupKeepKey, defaultValue = AutoBackup.DEFAULT_KEEP)
    val autoBackupLastRun by rememberPreference(AutoBackupLastRunKey, defaultValue = 0L)
    val autoBackupLastResult by rememberPreference(AutoBackupLastResultKey, defaultValue = "")

    // Set when the switch is what opened the picker, so that choosing a folder is what turns it
    // on. A switch that is on with nowhere to write would sit there doing nothing.
    var turnOnAfterPick by rememberSaveable { mutableStateOf(false) }

    val folderLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) {
                turnOnAfterPick = false
                return@rememberLauncherForActivityResult
            }
            // The grant from the picker dies with this activity. Persisting it is what lets the
            // worker write there next week, and after a reboot.
            // A third party picker can hand back a grant that cannot be persisted. Not a reason
            // to crash the settings screen: the worker reports the folder as unavailable instead.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }.onFailure { reportException(it) }
            val folder = uri.toString()
            onAutoBackupFolderChange(folder)
            val enabled = turnOnAfterPick || autoBackupEnabled
            if (turnOnAfterPick) onAutoBackupEnabledChange(true)
            turnOnAfterPick = false
            // Handed the values just chosen rather than left to re-read preferences that have
            // not landed yet, the same trap BackgroundCheckWorker.schedule documents.
            AutoBackup.schedule(context, enabled = enabled, folder = folder)
        }

    // The display name is a content provider query, so it stays off the main thread and is only
    // asked again when the folder changes.
    val folderName by produceState<String?>(initialValue = null, autoBackupFolder) {
        value = if (autoBackupFolder.isBlank()) null
        else withContext(Dispatchers.IO) { folderLabel(context, autoBackupFolder) }
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        // A spinner while it writes, because on a large library this is several seconds during
        // which the row looked like it had ignored the tap, which is when people tap it again.
        val backingUp by viewModel.backupInProgress.collectAsState()
        PreferenceEntry(
            title = { Text(stringResource(R.string.action_backup)) },
            description = if (backingUp) stringResource(R.string.backup_in_progress) else null,
            icon = { Icon(Icons.Rounded.Backup, null) },
            trailingContent = if (!backingUp) null else {
                { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
            },
            isEnabled = !backingUp,
            onClick = {
                val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                backupLauncher.launch(
                    "${context.getString(R.string.app_name)}_${MusicDatabase.MUSIC_DATABASE_VERSION}_${
                        LocalDateTime.now().format(formatter)
                    }.backup"
                )
            }
        )
    }
    Spacer(modifier = Modifier.height(16.dp))

    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        PreferenceEntry(
            title = { Text(stringResource(R.string.action_restore)) },
            icon = { Icon(Icons.Rounded.Restore, null) },
            onClick = {
                restoreLauncher.launch(arrayOf("application/octet-stream"))
            }
        )
    }
    Spacer(modifier = Modifier.height(16.dp))

    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        SwitchPreference(
            title = { Text(stringResource(R.string.auto_backup)) },
            description = stringResource(R.string.auto_backup_description),
            icon = { Icon(Icons.Rounded.Schedule, null) },
            checked = autoBackupEnabled,
            onCheckedChange = { on ->
                if (on && autoBackupFolder.isBlank()) {
                    // Ask where first. The picker's result is what flips the switch.
                    turnOnAfterPick = true
                    folderLauncher.launch(null)
                } else {
                    onAutoBackupEnabledChange(on)
                    AutoBackup.schedule(context, enabled = on)
                }
            }
        )

        PreferenceEntry(
            title = { Text(stringResource(R.string.auto_backup_folder)) },
            description = folderName ?: stringResource(R.string.auto_backup_choose_folder),
            icon = { Icon(Icons.Rounded.Folder, null) },
            onClick = {
                turnOnAfterPick = false
                folderLauncher.launch(null)
            }
        )

        ListPreference(
            title = { Text(stringResource(R.string.auto_backup_interval)) },
            selectedValue = autoBackupHours,
            values = AutoBackup.INTERVAL_CHOICES,
            valueText = {
                when (it) {
                    6 -> stringResource(R.string.auto_backup_every_6h)
                    24 -> stringResource(R.string.auto_backup_every_day)
                    168 -> stringResource(R.string.auto_backup_every_week)
                    720 -> stringResource(R.string.auto_backup_every_month)
                    4380 -> stringResource(R.string.auto_backup_every_6mo)
                    else -> stringResource(R.string.auto_backup_every_year)
                }
            },
            onValueSelected = {
                onAutoBackupHoursChange(it)
                AutoBackup.schedule(context, hours = it)
            }
        )

        // A slider rather than a few fixed choices, because how many backups are worth keeping
        // depends on the size of the library and the space to hand, and neither is ours to guess.
        // The Material 3 track: a gap between thumb and track, rounded inside corners, and the
        // stop indicator at the far end. No tick marks, since twenty of them is a row of dots
        // nobody reads; the number is in the line above instead.
        // The line follows the finger, not the stored value: writing to DataStore on every step of
        // the drag and waiting for it to come back would leave the number trailing the thumb, and
        // would write twenty times for one drag. The preference is set when the finger lifts, and
        // re-seeds this when it changes from anywhere else.
        var keepShown by remember(autoBackupKeep) { mutableIntStateOf(autoBackupKeep) }
        PreferenceEntry(
            title = { Text(stringResource(R.string.auto_backup_keep)) },
            description = pluralStringResource(R.plurals.auto_backup_keep_count, keepShown, keepShown),
            onClick = null,
        )
        val keepInteraction = remember { MutableInteractionSource() }
        Slider(
            value = keepShown.toFloat(),
            onValueChange = { keepShown = it.roundToInt() },
            // Nothing to reschedule: the worker reads this the next time it runs, so all the
            // release has to do is store the number the drag ended on.
            onValueChangeFinished = { onAutoBackupKeepChange(keepShown) },
            valueRange = AutoBackup.KEEP_MIN.toFloat()..AutoBackup.KEEP_MAX.toFloat(),
            steps = AutoBackup.KEEP_MAX - AutoBackup.KEEP_MIN - 1,
            interactionSource = keepInteraction,
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = keepInteraction,
                    thumbSize = DpSize(4.dp, 44.dp),
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(16.dp),
                    thumbTrackGapSize = 6.dp,
                    trackInsideCornerSize = 6.dp,
                    drawTick = { _, _ -> },
                )
            },
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        PreferenceEntry(
            title = { Text(stringResource(R.string.auto_backup_now)) },
            icon = { Icon(Icons.Rounded.Backup, null) },
            isEnabled = autoBackupFolder.isNotBlank(),
            onClick = { AutoBackup.runNow(context) }
        )

        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp)
        ) {
            Text(
                text = if (autoBackupLastRun > 0L) {
                    stringResource(
                        R.string.auto_backup_last,
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
                            .format(Date(autoBackupLastRun))
                    )
                } else {
                    stringResource(R.string.auto_backup_last_never)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            // "ok" is the worker's own word for a clean run and is not worth a line; anything
            // else is the reason the last run did not happen, in the worker's words.
            if (autoBackupLastResult.isNotBlank() && autoBackupLastResult != AutoBackup.RESULT_OK) {
                Text(
                    text = autoBackupLastResult,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(16.dp))

    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        PreferenceEntry(
            title = { Text(stringResource(R.string.export_playlists_m3u)) },
            description = stringResource(R.string.export_playlists_m3u_description),
            icon = { Icon(Icons.Rounded.Output, null) },
            onClick = { exportPlaylistsLauncher.launch(null) }
        )
    }
}

/**
 * Writes one .m3u per library playlist into the folder at [treeUri] and returns how many were
 * written. Playlists with no songs are skipped: an empty file would only be clutter. A playlist
 * whose songs cannot be read or whose file cannot be created or written is reported and skipped
 * so that one bad playlist does not stop the rest, and the count stays true to the files that did
 * land in the folder. Room is read through its flows, which run on the query executor, so the
 * caller only has to be off the main thread for the file writes.
 */
/**
 * What to call the chosen folder on screen.
 *
 * The provider's display name when it will give one. When it will not, which happens the moment
 * the folder is deleted or its card is pulled out, the path inside the tree id still reads as a
 * folder to a person: "primary:Backups/InterTune" is where they put it. What it showed before in
 * that case was the raw content uri, which is a string nobody should have to read.
 */
private fun folderLabel(context: Context, uri: String): String {
    val parsed = tryOrNull { uri.toUri() } ?: return uri
    tryOrNull { DocumentFile.fromTreeUri(context, parsed)?.name }
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }
    val id = tryOrNull { DocumentsContract.getTreeDocumentId(parsed) }
    return id?.substringAfter(':')?.trim('/')?.takeIf { it.isNotBlank() } ?: uri
}

private suspend fun exportPlaylistsAsM3u(context: Context, database: MusicDatabase, treeUri: Uri): Int {
    val folder = DocumentFile.fromTreeUri(context, treeUri) ?: return 0
    val playlists = database.playlists(PlaylistFilter.LIBRARY, PlaylistSortType.NAME, true).first()
    val used = HashSet<String>()
    var exported = 0
    for (playlist in playlists) {
        // Everything for one playlist sits in one try, and the catch is wide on purpose: a grant
        // that died with the activity surfaces as a SecurityException, not an IOException, and
        // letting that escape would throw away the count of files already written.
        try {
            val songs = database.playlistSongs(playlist.id).first().map { it.song }
            if (songs.isEmpty()) continue
            val name = M3u.fileName(playlist.playlist.name, used)
            used += name
            // DocumentFile swallows whatever the provider threw and hands back null, so the null
            // is the only trace of a refused file and has to be reported here or it is lost.
            val file = folder.createFile("audio/x-mpegurl", name)
            if (file == null) {
                reportException(IOException("Could not create $name in $treeUri"))
                continue
            }
            val out = context.contentResolver.openOutputStream(file.uri)
            if (out == null) {
                reportException(IOException("Could not open $name for writing"))
                continue
            }
            out.use { it.write(M3u.playlist(songs).toByteArray(Charsets.UTF_8)) }
            exported++
        } catch (e: Exception) {
            reportException(e)
        }
    }
    return exported
}
