package com.dd3boh.outertune.ui.screens.settings.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.LocalLoudnessRepair
import com.dd3boh.outertune.utils.LoudnessRepair
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AudioNormalizationKey
import com.dd3boh.outertune.constants.AudioQuality
import com.dd3boh.outertune.constants.AudioQualityKey
import com.dd3boh.outertune.constants.AutoLoadMoreKey
import com.dd3boh.outertune.constants.KeepAliveKey
import com.dd3boh.outertune.constants.SeekIncrement
import com.dd3boh.outertune.constants.SeekIncrementKey
import com.dd3boh.outertune.constants.SkipOnErrorKey
import com.dd3boh.outertune.constants.SkipSilenceKey
import com.dd3boh.outertune.constants.StopMusicOnTaskClearKey
import com.dd3boh.outertune.constants.minPlaybackDurKey
import com.dd3boh.outertune.constants.SleepTimerDefaults
import com.dd3boh.outertune.constants.SleepTimerFadeDurationKey
import com.dd3boh.outertune.constants.SleepTimerFadeKey
import com.dd3boh.outertune.ui.component.EnumListPreference
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.ui.dialog.CounterDialog
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference

@Composable
fun PlayerGeneralFrag() {
    val (autoLoadMore, onAutoLoadMoreChange) = rememberPreference(AutoLoadMoreKey, defaultValue = true)

    val context = LocalContext.current
    val (seekIncrement, onSeekIncrementChange) = rememberEnumPreference(
        key = SeekIncrementKey,
        defaultValue = SeekIncrement.OFF
    )

    SwitchPreference(
        title = { Text(stringResource(R.string.auto_load_more)) },
        description = stringResource(R.string.auto_load_more_desc),
        icon = { Icon(Icons.Rounded.Autorenew, null) },
        checked = autoLoadMore,
        onCheckedChange = onAutoLoadMoreChange
    )
    EnumListPreference(
        title = { Text(stringResource(R.string.seek_increment))},
        icon = { Icon(Icons.Rounded.FastForward, null) },
        selectedValue = seekIncrement,
        onValueSelected = onSeekIncrementChange,
        valueText = {
            seekIncrement -> SeekIncrement.getString(context, seekIncrement)
        }
    )
}

@Composable
fun PlayerServiceFrag() {

}

@Composable
fun AudioQualityFrag() {
    val (audioQuality, onAudioQualityChange) = rememberEnumPreference(
        key = AudioQualityKey,
        defaultValue = AudioQuality.AUTO
    )

    EnumListPreference(
        title = { Text(stringResource(R.string.audio_quality)) },
        icon = { Icon(Icons.Rounded.GraphicEq, null) },
        selectedValue = audioQuality,
        onValueSelected = onAudioQualityChange,
        valueText = {
            when (it) {
                AudioQuality.AUTO -> stringResource(R.string.audio_quality_auto)
                AudioQuality.HIGH -> stringResource(R.string.audio_quality_high)
                AudioQuality.LOW -> stringResource(R.string.audio_quality_low)
            }
        }
    )

}

@Composable
fun AudioEffectsFrag() {
    val (skipSilence, onSkipSilenceChange) = rememberPreference(key = SkipSilenceKey, defaultValue = false)

    val (audioNormalization, onAudioNormalizationChange) = rememberPreference(
        key = AudioNormalizationKey,
        defaultValue = true
    )

    SwitchPreference(
        title = { Text(stringResource(R.string.audio_normalization)) },
        icon = { Icon(Icons.AutoMirrored.Rounded.VolumeUp, null) },
        checked = audioNormalization,
        onCheckedChange = onAudioNormalizationChange
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.skip_silence)) },
        icon = { Icon(painterResource(R.drawable.skip_next), null) },
        checked = skipSilence,
        onCheckedChange = onSkipSilenceChange
    )

    LoudnessRepairEntry()
}

/**
 * Looks up the real loudness for songs that are missing it.
 *
 * Sits under the normalisation switch because it only matters to someone who has normalisation on
 * and is still hearing uneven volumes. Deliberately NOT hidden when normalisation is off: the
 * person most likely to have switched it off is the person the uneven volumes drove away from it.
 */
@Composable
fun LoudnessRepairEntry() {
    val database = LocalDatabase.current
    val repair = LocalLoudnessRepair.current
    val state by repair.state.collectAsState()

    // The result lives on a singleton, so without this a finished run would still be reported the
    // next time the screen is opened, days later, alongside a count that has moved on since.
    DisposableEffect(Unit) {
        onDispose { repair.acknowledge() }
    }
    val missing by database.countFormatsMissingLoudness().collectAsState(initial = 0)

    val description = when (val s = state) {
        is LoudnessRepair.State.Running ->
            stringResource(R.string.loudness_repair_running, s.done, s.total, s.repaired)

        is LoudnessRepair.State.Finished -> when {
            s.stoppedEarly -> stringResource(R.string.loudness_repair_stopped, s.repaired)
            s.unavailable > 0 ->
                stringResource(R.string.loudness_repair_done_partial, s.repaired, s.unavailable)

            else -> stringResource(R.string.loudness_repair_done, s.repaired)
        }

        is LoudnessRepair.State.Blocked -> stringResource(R.string.loudness_repair_blocked, s.repaired)
        LoudnessRepair.State.Offline -> stringResource(R.string.loudness_repair_offline)
        LoudnessRepair.State.NothingToDo -> stringResource(R.string.loudness_repair_none)
        LoudnessRepair.State.Idle ->
            if (missing > 0) stringResource(R.string.loudness_repair_available, missing)
            else stringResource(R.string.loudness_repair_none)
    }

    PreferenceEntry(
        title = { Text(stringResource(R.string.loudness_repair)) },
        description = description,
        icon = { Icon(Icons.Rounded.GraphicEq, null) },
        // Nothing to do is not a failure, but it should not be a button either.
        isEnabled = state is LoudnessRepair.State.Running || missing > 0,
        onClick = {
            if (repair.isRunning) repair.cancel() else repair.start()
        }
    )
}

@Composable
fun PlaybackBehaviourFrag() {
    val keepAlive by rememberPreference(key = KeepAliveKey, defaultValue = false)
    val (minPlaybackDur, onMinPlaybackDurChange) = rememberPreference(minPlaybackDurKey, defaultValue = 30)
    val (skipOnErrorKey, onSkipOnErrorChange) = rememberPreference(key = SkipOnErrorKey, defaultValue = false)
    val (stopMusicOnTaskClear, onStopMusicOnTaskClearChange) = rememberPreference(
        key = StopMusicOnTaskClearKey,
        defaultValue = false
    )

    val (sleepTimerFade, onSleepTimerFadeChange) = rememberPreference(
        key = SleepTimerFadeKey,
        defaultValue = SleepTimerDefaults.FADE_ENABLED
    )
    val (sleepTimerFadeDuration, onSleepTimerFadeDurationChange) = rememberPreference(
        key = SleepTimerFadeDurationKey,
        defaultValue = SleepTimerDefaults.FADE_DURATION_SECONDS
    )

    var showMinPlaybackDur by remember {
        mutableStateOf(false)
    }
    var showSleepTimerFadeDur by remember {
        mutableStateOf(false)
    }

    PreferenceEntry(
        title = { Text(stringResource(R.string.min_playback_duration)) },
        icon = { Icon(Icons.Rounded.Sync, null) },
        onClick = { showMinPlaybackDur = true }
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.auto_skip_next_on_error)) },
        description = stringResource(R.string.auto_skip_next_on_error_desc),
        icon = { Icon(Icons.Rounded.SkipNext, null) },
        checked = skipOnErrorKey,
        onCheckedChange = onSkipOnErrorChange
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.stop_music_on_task_clear)) },
        icon = { Icon(Icons.Rounded.ClearAll, null) },
        isEnabled = !keepAlive,
        checked = stopMusicOnTaskClear,
        onCheckedChange = onStopMusicOnTaskClearChange,
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.sleep_timer_fade)) },
        description = stringResource(R.string.sleep_timer_fade_description),
        icon = { Icon(Icons.Rounded.Bedtime, null) },
        checked = sleepTimerFade,
        onCheckedChange = onSleepTimerFadeChange,
    )
    PreferenceEntry(
        title = { Text(stringResource(R.string.sleep_timer_fade_duration)) },
        description = stringResource(R.string.sleep_timer_fade_duration_value, sleepTimerFadeDuration),
        icon = { Icon(Icons.Rounded.Timer, null) },
        isEnabled = sleepTimerFade,
        onClick = { showSleepTimerFadeDur = true }
    )

    /**
     * ---------------------------
     * Dialogs
     * ---------------------------
     */


    if (showMinPlaybackDur) {
        CounterDialog(
            title = stringResource(R.string.min_playback_duration),
            description = stringResource(R.string.min_playback_duration_description),
            initialValue = minPlaybackDur,
            upperBound = 100,
            lowerBound = 0,
            unitDisplay = "%",
            onDismiss = { showMinPlaybackDur = false },
            onConfirm = {
                showMinPlaybackDur = false
                onMinPlaybackDurChange(it)
            },
            onCancel = {
                showMinPlaybackDur = false
            }
        )
    }

    if (showSleepTimerFadeDur) {
        CounterDialog(
            title = stringResource(R.string.sleep_timer_fade_duration),
            description = stringResource(R.string.sleep_timer_fade_duration_description),
            initialValue = sleepTimerFadeDuration,
            upperBound = SleepTimerDefaults.FADE_DURATION_RANGE.last,
            lowerBound = SleepTimerDefaults.FADE_DURATION_RANGE.first,
            unitDisplay = " s",
            onDismiss = { showSleepTimerFadeDur = false },
            onConfirm = {
                showSleepTimerFadeDur = false
                onSleepTimerFadeDurationChange(it)
            },
            onCancel = {
                showSleepTimerFadeDur = false
            }
        )
    }
}