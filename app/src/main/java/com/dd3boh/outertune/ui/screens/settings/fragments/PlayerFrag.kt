package com.dd3boh.outertune.ui.screens.settings.fragments

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import android.os.Build
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Timer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.LocalPlayerConnection
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
import com.dd3boh.outertune.constants.HighPrecisionAudioKey
import com.dd3boh.outertune.constants.HeadTracking3dKey
import com.dd3boh.outertune.constants.HeadTrackingCalibrateKey
import com.dd3boh.outertune.constants.HeadTrackingDriftKey
import com.dd3boh.outertune.constants.HeadTrackingKey
import com.dd3boh.outertune.constants.HeadTrackingResponse
import com.dd3boh.outertune.constants.HeadTrackingResponseKey
import com.dd3boh.outertune.constants.ProximityVolumeKey
import com.dd3boh.outertune.constants.SpatialAudioKey
import com.dd3boh.outertune.constants.SpatialAudioMode
import androidx.compose.material.icons.rounded.AutoAwesome
import com.dd3boh.outertune.constants.AdaptiveQueueModeKey
import com.dd3boh.outertune.constants.AdaptiveQueueMode
import androidx.compose.material.icons.rounded.AccountCircle
import com.dd3boh.outertune.constants.PlaybackAuthModeKey
import com.dd3boh.outertune.constants.PlaybackAuthMode
import com.dd3boh.outertune.constants.AutoLoadMoreKey
import com.dd3boh.outertune.constants.KeepAliveKey
import com.dd3boh.outertune.constants.SeekIncrement
import com.dd3boh.outertune.constants.SeekIncrementKey
import com.dd3boh.outertune.constants.SkipOnErrorKey
import com.dd3boh.outertune.constants.SkipSilenceKey
import com.dd3boh.outertune.constants.StopMusicOnTaskClearKey
import com.dd3boh.outertune.constants.SleepTimerDefaults
import com.dd3boh.outertune.constants.SleepTimerFadeDurationKey
import com.dd3boh.outertune.constants.ShareAudioFocusKey
import com.dd3boh.outertune.constants.SleepTimerFadeKey
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import com.dd3boh.outertune.ui.component.ExplainedPreference
import androidx.compose.material3.Slider
import com.dd3boh.outertune.constants.StageWidthKey
import com.dd3boh.outertune.constants.HeadTrackingLeadKey
import com.dd3boh.outertune.playback.BinauralAudioProcessor
import com.dd3boh.outertune.playback.ProximityProbe
import com.dd3boh.outertune.ui.component.EnumListPreference
import com.dd3boh.outertune.ui.component.ExplainButton
import com.dd3boh.outertune.ui.component.ExplainedSwitchPreference
import com.dd3boh.outertune.ui.dialog.InfoLabel
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.ui.dialog.CounterDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference

@Composable
fun ColumnScope.PlayerGeneralFrag() {
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

/**
 * Whether playback may ask YouTube as the signed-in account.
 *
 * Under the audio settings rather than beside the login, because what it changes is how a song is
 * fetched, and the only time anybody goes looking for it is when a song refuses to play.
 */
/**
 * Whether walking away from the phone turns the music down.
 *
 * Needs the scan permission, asked for here rather than at install, because a music player having
 * Bluetooth scanning in its manifest is a thing people are right to be suspicious of. Nothing
 * here wants to know where anyone is, only how strong a signal from headphones already connected
 * happens to be, which is why the permission is declared as never for location.
 */
@Composable
fun ColumnScope.ProximityVolumeFrag() {
    val (enabled, onEnabledChange) = rememberPreference(ProximityVolumeKey, defaultValue = false)
    val scanPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) onEnabledChange(true) }

    ExplainedSwitchPreference(
        title = stringResource(R.string.proximity_volume),
        description = stringResource(R.string.proximity_volume_description),
        explanation = stringResource(R.string.proximity_volume_explain),
        checked = enabled,
        onCheckedChange = { want ->
            if (want && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                scanPermission.launch(android.Manifest.permission.BLUETOOTH_SCAN)
            } else {
                onEnabledChange(want)
            }
        },
    )

    if (!enabled) return

    // Under the setting it belongs to, rather than on the recommendations developer screen where
    // it started. It is a distance measurement for this feature, and nobody would think to look
    // for it beside the engine's tuning constants.
    val context = LocalContext.current
    val probe = remember { ProximityProbe(context) }
    var probeRunning by remember { mutableStateOf(false) }
    var probeStatus by remember { mutableStateOf<String?>(null) }

    val protocol = listOf(
        ProximityProbe.Step(stringResource(R.string.proximity_step_still), 20),
        ProximityProbe.Step(stringResource(R.string.proximity_step_turn), 20),
        ProximityProbe.Step(stringResource(R.string.proximity_step_away), 20),
        ProximityProbe.Step(stringResource(R.string.proximity_step_far), 20),
        ProximityProbe.Step(stringResource(R.string.proximity_step_back), 20),
    )
    val finishedMessage = stringResource(R.string.proximity_step_done)

    DisposableEffect(Unit) { onDispose { probe.stop(); probe.release() } }

    LaunchedEffect(probeRunning) {
        while (probeRunning) {
            probe.tick { probeRunning = false }
            val step = probe.currentStep()
            probeStatus = if (step == null) null
            else context.getString(R.string.proximity_probe_step, step.first.spoken, step.second, probe.status().second)
            delay(400)
        }
    }

    ExplainedPreference(
        title = stringResource(
            if (probeRunning) R.string.proximity_probe_stop else R.string.proximity_probe
        ),
        explanation = stringResource(R.string.proximity_probe_explain),
        description = probeStatus ?: stringResource(R.string.proximity_probe_description),
        onClick = {
            if (probeRunning) {
                probe.stop()
                probeRunning = false
            } else {
                probe.start(protocol, finishedMessage)
                probeRunning = probe.isRunning
            }
        },
    )
}

/**
 * How far apart the binaural renderer's two virtual speakers stand.
 *
 * A knob rather than a choice, so it lives in Advanced, and only where it does anything.
 */
@Composable
fun ColumnScope.StageWidthFrag() {
    val (spatial) = rememberEnumPreference(key = SpatialAudioKey, defaultValue = SpatialAudioMode.OFF)
    if (spatial != SpatialAudioMode.HEADPHONES) return

    val (width, onWidthChange) = rememberPreference(
        StageWidthKey,
        defaultValue = BinauralAudioProcessor.DEFAULT_STAGE_WIDTH.toInt(),
    )
    ExplainedPreference(
        title = stringResource(R.string.stage_width),
        explanation = stringResource(R.string.stage_width_explain),
        description = stringResource(R.string.stage_width_value, width),
    )
    Slider(
        value = width.toFloat(),
        onValueChange = { onWidthChange(it.toInt()) },
        valueRange = BinauralAudioProcessor.MIN_STAGE_WIDTH..BinauralAudioProcessor.MAX_STAGE_WIDTH,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

/** What the audio chain does to the stereo it is given. */
@Composable
fun ColumnScope.SpatialAudioModeFrag() {
    val (mode, onModeChange) = rememberEnumPreference(
        key = SpatialAudioKey,
        defaultValue = SpatialAudioMode.OFF
    )
    val title = stringResource(R.string.spatial_audio)

    EnumListPreference(
        title = { Text(title) },
        icon = { Icon(Icons.Rounded.SurroundSound, null) },
        trailingContent = {
            ExplainButton(title = title, body = stringResource(R.string.spatial_audio_explain))
        },
        selectedValue = mode,
        onValueSelected = onModeChange,
        valueText = {
            when (it) {
                SpatialAudioMode.OFF -> stringResource(R.string.spatial_audio_off)
                SpatialAudioMode.HEADPHONES -> stringResource(R.string.spatial_audio_headphones)
                SpatialAudioMode.SURROUND -> stringResource(R.string.spatial_audio_surround)
            }
        }
    )

    InfoLabel(stringResource(R.string.spatial_audio_description))
}

/**
 * Whether the soundstage stays put when the listener turns their head.
 *
 * Hidden outright unless a tracker is published right now, rather than shown greyed out: on nearly
 * every phone this is not a thing that can be turned on, and a permanently dead switch reads as a
 * bug. Only meaningful alongside the headphone renderer, so it follows that setting too.
 */
@Composable
fun ColumnScope.HeadTrackingFrag() {
    val context = LocalContext.current
    val (spatial) = rememberEnumPreference(key = SpatialAudioKey, defaultValue = SpatialAudioMode.OFF)
    val (enabled, onEnabledChange) = rememberPreference(HeadTrackingKey, defaultValue = false)

    // Dynamic sensors come and go with the headphones, so this is asked on each recomposition of
    // the screen rather than cached for the life of the process.
    val available = remember(spatial) {
        runCatching {
            context.getSystemService(android.hardware.SensorManager::class.java)
                ?.getDynamicSensorList(android.hardware.Sensor.TYPE_HEAD_TRACKER)
                ?.isNotEmpty() == true
        }.getOrDefault(false)
    }

    if (spatial != SpatialAudioMode.HEADPHONES) return

    ExplainedSwitchPreference(
        title = stringResource(R.string.head_tracking),
        description = stringResource(
            if (available) R.string.head_tracking_description else R.string.head_tracking_none
        ),
        explanation = stringResource(R.string.head_tracking_explain),
        checked = enabled && available,
        onCheckedChange = onEnabledChange,
        isEnabled = available,
    )

    if (!enabled || !available) return

    val (response, onResponseChange) = rememberEnumPreference(
        key = HeadTrackingResponseKey,
        defaultValue = HeadTrackingResponse.BALANCED,
    )
    val responseTitle = stringResource(R.string.head_tracking_response)

    EnumListPreference(
        title = { Text(responseTitle) },
        icon = { Icon(Icons.Rounded.Speed, null) },
        trailingContent = {
            ExplainButton(
                title = responseTitle,
                body = stringResource(R.string.head_tracking_response_explain),
            )
        },
        selectedValue = response,
        onValueSelected = onResponseChange,
        valueText = {
            when (it) {
                HeadTrackingResponse.SMOOTH -> stringResource(R.string.head_tracking_response_smooth)
                HeadTrackingResponse.BALANCED -> stringResource(R.string.head_tracking_response_balanced)
                HeadTrackingResponse.QUICK -> stringResource(R.string.head_tracking_response_quick)
                HeadTrackingResponse.INSTANT -> stringResource(R.string.head_tracking_response_instant)
            }
        },
    )

    InfoLabel(stringResource(R.string.head_tracking_response_description))

    val (threeD, onThreeDChange) = rememberPreference(HeadTracking3dKey, defaultValue = false)
    ExplainedSwitchPreference(
        title = stringResource(R.string.head_tracking_3d),
        description = stringResource(R.string.head_tracking_3d_description),
        explanation = stringResource(R.string.head_tracking_3d_explain),
        checked = threeD,
        onCheckedChange = onThreeDChange,
    )

    // The delay between rendering a sample and hearing it is mostly whichever Bluetooth codec got
    // negotiated, and the platform reports no latency for that route, so it cannot be measured
    // from inside the app. Tuned by ear, once, against the hardware in use.
    val (lead, onLeadChange) = rememberPreference(HeadTrackingLeadKey, defaultValue = 260)
    ExplainedPreference(
        title = stringResource(R.string.head_tracking_lead),
        explanation = stringResource(R.string.head_tracking_lead_full_explain),
        description = stringResource(R.string.head_tracking_lead_value, lead),
    )
    Slider(
        value = lead.toFloat(),
        onValueChange = { onLeadChange(it.toInt()) },
        valueRange = 0f..500f,
        modifier = Modifier.padding(horizontal = 16.dp),
    )

    // Measured once rather than guessed or bled away continuously. Thirty seconds of a head that
    // is not moving is, by definition, thirty seconds of drift, and a slope can be subtracted
    // forever after without ever following a real turn.
    val (calibratedAt, onCalibrate) = rememberPreference(HeadTrackingCalibrateKey, defaultValue = 0L)
    val (drift) = rememberPreference(HeadTrackingDriftKey, defaultValue = 0f)
    var elapsed by remember { mutableStateOf(0L) }

    LaunchedEffect(calibratedAt) {
        if (calibratedAt == 0L) return@LaunchedEffect
        while (true) {
            elapsed = System.currentTimeMillis() - calibratedAt
            if (elapsed > CALIBRATION_MS) break
            delay(250)
        }
    }

    val running = calibratedAt != 0L && elapsed in 0..CALIBRATION_MS
    ExplainedPreference(
        title = stringResource(R.string.head_tracking_calibrate),
        explanation = stringResource(R.string.head_tracking_calibrate_explain),
        description = when {
            running -> stringResource(
                R.string.head_tracking_calibrate_running,
                ((CALIBRATION_MS - elapsed) / 1000).toInt(),
            )
            drift != 0f -> stringResource(R.string.head_tracking_calibrate_done, drift)
            else -> stringResource(R.string.head_tracking_calibrate_never)
        },
        onClick = { if (!running) onCalibrate(System.currentTimeMillis()) },
    )
}

/** Thirty seconds, matching HeadTracking.CALIBRATION_NANOS. */
private const val CALIBRATION_MS = 30_000L

/** 32 bit float through the audio chain, at the cost of the low power offload path. */
@Composable
fun ColumnScope.HighPrecisionAudioFrag() {
    val (enabled, onEnabledChange) = rememberPreference(HighPrecisionAudioKey, defaultValue = false)

    ExplainedSwitchPreference(
        title = stringResource(R.string.high_precision_audio),
        description = stringResource(R.string.high_precision_audio_description),
        explanation = stringResource(R.string.high_precision_audio_explain),
        checked = enabled,
        onCheckedChange = onEnabledChange,
    )
}

/** Whether the unwatched end of the queue re-plans itself. */
@Composable
fun ColumnScope.AdaptiveQueueFrag() {
    val (mode, onModeChange) = rememberEnumPreference(
        key = AdaptiveQueueModeKey,
        defaultValue = AdaptiveQueueMode.AUTOPLAY_ONLY
    )

    EnumListPreference(
        title = { Text(stringResource(R.string.adaptive_queue)) },
        icon = { Icon(Icons.Rounded.AutoAwesome, null) },
        selectedValue = mode,
        onValueSelected = onModeChange,
        valueText = {
            when (it) {
                AdaptiveQueueMode.OFF -> stringResource(R.string.adaptive_queue_off)
                AdaptiveQueueMode.AUTOPLAY_ONLY -> stringResource(R.string.adaptive_queue_autoplay)
                AdaptiveQueueMode.ALWAYS -> stringResource(R.string.adaptive_queue_always)
            }
        }
    )

    InfoLabel(stringResource(R.string.adaptive_queue_description))
}

@Composable
fun ColumnScope.PlaybackAuthFrag() {
    val (authMode, onAuthModeChange) = rememberEnumPreference(
        key = PlaybackAuthModeKey,
        defaultValue = PlaybackAuthMode.WHEN_REFUSED
    )

    EnumListPreference(
        title = { Text(stringResource(R.string.playback_auth_mode)) },
        icon = { Icon(Icons.Rounded.AccountCircle, null) },
        selectedValue = authMode,
        onValueSelected = onAuthModeChange,
        valueText = {
            when (it) {
                PlaybackAuthMode.NEVER -> stringResource(R.string.playback_auth_never)
                PlaybackAuthMode.WHEN_REFUSED -> stringResource(R.string.playback_auth_when_refused)
                PlaybackAuthMode.ALWAYS -> stringResource(R.string.playback_auth_always)
            }
        }
    )

    InfoLabel(stringResource(R.string.playback_auth_mode_description))
}

@Composable
fun ColumnScope.AudioQualityFrag() {
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
                AudioQuality.MAX -> stringResource(R.string.audio_quality_max)
                AudioQuality.AUTO -> stringResource(R.string.audio_quality_auto)
                AudioQuality.HIGH -> stringResource(R.string.audio_quality_high)
                AudioQuality.LOW -> stringResource(R.string.audio_quality_low)
            }
        }
    )

    // What the control above actually produced. Bitrate and sample rate have always been recorded
    // for every track and have always been on display, but only in the details dialog three taps
    // into a menu and written as "48000 Hz", which is a developer's answer to a listener's
    // question. Under the tier that decides it, you can change the setting and watch the number
    // change, which is the only way to tell that any of this is doing anything.
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current
    val mediaMetadata by (playerConnection?.mediaMetadata ?: remember { MutableStateFlow(null) })
        .collectAsState()
    val currentId = mediaMetadata?.id
    val format by remember(currentId) {
        if (currentId == null) flowOf(null) else database.format(currentId)
    }.collectAsState(initial = null)

    val resolved = format?.let {
        listOfNotNull(
            readableCodec(it.codecs),
            it.bitrate.takeIf { rate -> rate > 0 }?.let { rate -> "${rate / 1000} kbps" },
            readableSampleRate(it.sampleRate),
        ).joinToString(", ").ifBlank { null }
    }

    // Value stacked under the label rather than set against it on the right. A trailing slot was
    // the first arrangement and it read badly: the note wraps to four lines beside a value that is
    // the actual answer, so the eye lands on the caveat first and the number gets squeezed into
    // the margin.
    val lossless = stringResource(R.string.audio_quality_no_lossless)
    val quality = resolved ?: stringResource(R.string.audio_quality_now_nothing)

    PreferenceEntry(
        title = { Text(stringResource(R.string.audio_quality_now)) },
        description = if (audioQuality == AudioQuality.MAX) "$quality\n$lossless" else quality,
        icon = { Icon(Icons.Rounded.Speed, null) },
        onClick = null,
    )
}

/** The codec string YouTube returns, as a name somebody would recognise. */
private fun readableCodec(codecs: String?): String? = when {
    codecs.isNullOrBlank() -> null
    codecs.startsWith("opus") -> "Opus"
    codecs.startsWith("mp4a") -> "AAC"
    codecs.startsWith("vorbis") -> "Vorbis"
    codecs.startsWith("flac") -> "FLAC"
    codecs.startsWith("ec-3") -> "E-AC-3"
    codecs.startsWith("ac-3") -> "AC-3"
    else -> codecs.substringBefore('.').uppercase()
}

/** 48000 reads as 48 kHz, 44100 as 44.1 kHz. Nobody thinks in hertz about a song. */
private fun readableSampleRate(sampleRate: Int?): String? {
    if (sampleRate == null || sampleRate <= 0) return null
    val kilohertz = sampleRate / 1000.0
    return if (kilohertz % 1.0 == 0.0) "${kilohertz.toInt()} kHz"
    else "%.1f kHz".format(kilohertz)
}

@Composable
fun ColumnScope.AudioEffectsFrag() {
    val (skipSilence, onSkipSilenceChange) = rememberPreference(key = SkipSilenceKey, defaultValue = false)

    val (audioNormalization, onAudioNormalizationChange) = rememberPreference(
        key = AudioNormalizationKey,
        defaultValue = true
    )

    SwitchPreference(
        title = { Text(stringResource(R.string.audio_normalization)) },
        description = stringResource(R.string.audio_normalization_description),
        icon = { Icon(Icons.AutoMirrored.Rounded.VolumeUp, null) },
        checked = audioNormalization,
        onCheckedChange = onAudioNormalizationChange
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.skip_silence)) },
        description = stringResource(R.string.skip_silence_description),
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
fun ColumnScope.PlaybackBehaviourFrag() {
    val keepAlive by rememberPreference(key = KeepAliveKey, defaultValue = false)
    val (skipOnErrorKey, onSkipOnErrorChange) = rememberPreference(key = SkipOnErrorKey, defaultValue = false)
    val (stopMusicOnTaskClear, onStopMusicOnTaskClearChange) = rememberPreference(
        key = StopMusicOnTaskClearKey,
        defaultValue = true
    )

    val (shareAudioFocus, onShareAudioFocusChange) = rememberPreference(
        key = ShareAudioFocusKey,
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

    var showSleepTimerFadeDur by remember {
        mutableStateOf(false)
    }

    SwitchPreference(
        title = { Text(stringResource(R.string.auto_skip_next_on_error)) },
        description = stringResource(R.string.auto_skip_next_on_error_desc),
        icon = { Icon(Icons.Rounded.SkipNext, null) },
        checked = skipOnErrorKey,
        onCheckedChange = onSkipOnErrorChange
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.stop_music_on_task_clear)) },
        description = stringResource(R.string.stop_music_on_task_clear_description),
        icon = { Icon(Icons.Rounded.ClearAll, null) },
        isEnabled = !keepAlive,
        checked = stopMusicOnTaskClear,
        onCheckedChange = onStopMusicOnTaskClearChange,
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.share_audio_focus)) },
        description = stringResource(R.string.share_audio_focus_description),
        icon = { Icon(Icons.Rounded.Hearing, null) },
        checked = shareAudioFocus,
        onCheckedChange = onShareAudioFocusChange,
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

/**
 * What this device can do for spatial audio, read straight from the platform.
 *
 * Read-only for now. It exists so the answer to "would head-tracked spatial audio work on my
 * phone with my headphones" can be read off the settings screen rather than guessed at: the
 * spatialiser's level, whether it is on, whether a head tracker is present (the Sony XM5 exposes
 * the standard Android head tracker, so it should be), and whether plain stereo would be
 * spatialised or, as on most devices, only multichannel, which is what decides whether InterTune
 * has to upmix. Android 13 and later only; older devices see one line saying so.
 */
@Composable
fun ColumnScope.SpatialAudioFrag() {
    val context = LocalContext.current
    val summary = remember {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@remember null
        runCatching {
            val sensors = context.getSystemService(android.hardware.SensorManager::class.java)
            val tracker = sensors?.getDynamicSensorList(android.hardware.Sensor.TYPE_HEAD_TRACKER)?.firstOrNull()
            // Whether the phone has the machinery for external sensors at all. This is the line
            // most devices fail, and it fails silently: without it the headphones stream
            // orientation into the kernel and nothing ever reads it.
            val canDiscover = sensors?.isDynamicSensorDiscoverySupported == true

            val head = when {
                tracker != null -> context.getString(
                    R.string.spatial_audio_status_tracking,
                    tracker.name,
                    tracker.maxDelay.takeIf { it > 0 }?.let { 1_000_000 / it } ?: 25,
                )
                !canDiscover -> context.getString(R.string.spatial_audio_status_no_discovery)
                else -> context.getString(R.string.spatial_audio_status_no_tracker)
            }

            // The platform's own spatialiser, which is a separate thing entirely and reports
            // nothing about whether InterTune can render. Worth showing because people looking at
            // this screen are usually trying to work out why the phone's own one does nothing.
            val sp = context.getSystemService(android.media.AudioManager::class.java).spatializer
            val platform = when {
                sp.immersiveAudioLevel == android.media.Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE ->
                    context.getString(R.string.spatial_audio_status_platform_none)
                !sp.isEnabled -> context.getString(R.string.spatial_audio_status_platform_off)
                else -> context.getString(R.string.spatial_audio_status_platform_on)
            }
            "$head $platform"
        }.getOrElse { context.getString(R.string.spatial_audio_status_unreadable, it.message ?: "") }
    }

    ExplainedPreference(
        title = stringResource(R.string.spatial_audio_status),
        explanation = stringResource(R.string.spatial_audio_status_explain),
        description = summary ?: stringResource(R.string.spatial_audio_needs_13),
    )
}
