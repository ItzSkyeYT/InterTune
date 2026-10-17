/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.dialog

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AudioNormalizationKey
import com.dd3boh.outertune.constants.EqualizerEnabledKey
import com.dd3boh.outertune.constants.SkipSilenceKey
import com.dd3boh.outertune.constants.SpatialAudioKey
import com.dd3boh.outertune.constants.SpatialAudioMode
import com.dd3boh.outertune.db.entities.FormatEntity
import com.dd3boh.outertune.utils.rememberPreference
import kotlin.math.abs
import kotlin.math.log10

/**
 * Signal path: what happens to the song between YouTube and the ear, right now (his yes of 17 Oct
 * 2026, after reading how OpenTune shows it). Where it comes from and in what, every setting that
 * changes the sound, and where it plays. Green is untouched, amber is a setting of his changing the
 * sound, grey is where it plays. When something sounds off, the setting touching it is in plain view.
 */
object SignalPath {
    enum class Kind { UNTOUCHED, CHANGED, WHERE }

    /** One row: [detail] is a string resource formatted with [args]. */
    data class Stage(val kind: Kind, @StringRes val title: Int, @StringRes val detail: Int, val args: List<Any> = emptyList())

    /** What the rows are made from, read from the player and the settings by [SignalPathDialog]. */
    data class Inputs(
        val local: Boolean,
        val codec: String?,
        val bitrateKbps: Int?,
        /** The volume levelling applied to this song in dB, null when the setting is off. */
        val levellingDb: Float?,
        val equalizerOn: Boolean,
        val spatial: SpatialAudioMode,
        val speed: Float,
        val pitch: Float,
        val skipSilence: Boolean,
        /** Where it plays, by name. */
        val output: String?,
        val outputRateKhz: String?,
        val volumePercent: Int?,
    )

    fun stages(i: Inputs): List<Stage> = buildList {
        val source = if (i.local) R.string.signal_source_local else R.string.signal_source_stream
        add(
            when {
                i.codec != null && i.bitrateKbps != null && i.bitrateKbps > 0 ->
                    Stage(Kind.UNTOUCHED, source, R.string.signal_format, listOf(i.codec, i.bitrateKbps))
                i.codec != null -> Stage(Kind.UNTOUCHED, source, R.string.signal_text, listOf(i.codec))
                else -> Stage(Kind.UNTOUCHED, source, R.string.signal_unknown)
            }
        )
        add(
            when {
                i.levellingDb == null -> Stage(Kind.UNTOUCHED, R.string.audio_normalization, R.string.signal_off)
                abs(i.levellingDb) < 0.1f -> Stage(Kind.UNTOUCHED, R.string.audio_normalization, R.string.signal_not_needed)
                else -> Stage(Kind.CHANGED, R.string.audio_normalization, R.string.signal_turned_down, listOf("%.1f".format(abs(i.levellingDb))))
            }
        )
        add(
            if (i.equalizerOn) Stage(Kind.CHANGED, R.string.equalizer, R.string.signal_on)
            else Stage(Kind.UNTOUCHED, R.string.equalizer, R.string.signal_off)
        )
        add(
            when (i.spatial) {
                SpatialAudioMode.OFF -> Stage(Kind.UNTOUCHED, R.string.spatial_audio, R.string.signal_off)
                SpatialAudioMode.HEADPHONES -> Stage(Kind.CHANGED, R.string.spatial_audio, R.string.signal_headphones)
                SpatialAudioMode.SURROUND -> Stage(Kind.CHANGED, R.string.spatial_audio, R.string.signal_surround)
            }
        )
        add(
            if (abs(i.speed - 1f) < 0.005f && abs(i.pitch - 1f) < 0.005f) {
                Stage(Kind.UNTOUCHED, R.string.signal_speed_pitch, R.string.signal_normal)
            } else {
                Stage(Kind.CHANGED, R.string.signal_speed_pitch, R.string.signal_speed_value, listOf("%.2f".format(i.speed), "%.2f".format(i.pitch)))
            }
        )
        if (i.skipSilence) add(Stage(Kind.CHANGED, R.string.skip_silence, R.string.signal_skipping))
        if (i.output != null) {
            add(
                if (i.outputRateKhz != null) Stage(Kind.WHERE, R.string.signal_plays_on, R.string.signal_output_rate, listOf(i.output, i.outputRateKhz))
                else Stage(Kind.WHERE, R.string.signal_plays_on, R.string.signal_text, listOf(i.output))
            )
        }
        if (i.volumePercent != null) add(Stage(Kind.WHERE, R.string.signal_volume, R.string.signal_percent, listOf(i.volumePercent)))
    }

    /** "Opus", "AAC", "FLAC"... from what the format says of itself, or null. */
    fun codecName(mimeType: String?, codecs: String?): String? {
        val c = codecs.orEmpty().lowercase()
        val m = mimeType.orEmpty().lowercase()
        return when {
            "opus" in c || "opus" in m -> "Opus"
            c.startsWith("mp4a") || "aac" in m || m == "audio/mp4" -> "AAC"
            "flac" in c || "flac" in m -> "FLAC"
            "vorbis" in c -> "Vorbis"
            m == "audio/mpeg" || "mp3" in m -> "MP3"
            "alac" in c -> "ALAC"
            m.startsWith("audio/") -> m.removePrefix("audio/").uppercase()
            else -> null
        }
    }

    /** dB of a linear factor; 1 is 0 dB. */
    fun decibels(factor: Float): Float = if (factor <= 0f) -96f else 20f * log10(factor)
}

private val Untouched = Color(0xFF5EC982)
private val Changed = Color(0xFFF0B446)

@Composable
fun SignalPathDialog(currentFormat: FormatEntity?, local: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val levelling by playerConnection.service.loudnessFactor.collectAsState()
    val (normalizing) = rememberPreference(AudioNormalizationKey, true)
    val (equalizerOn) = rememberPreference(EqualizerEnabledKey, false)
    val (spatialName) = rememberPreference(SpatialAudioKey, SpatialAudioMode.OFF.name)
    val (skipSilence) = rememberPreference(SkipSilenceKey, false)
    val parameters = playerConnection.player.playbackParameters

    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val output = remember(audio) { audio?.let { playingOn(it) } }
    val outputName = when (output) {
        is Int -> stringResource(output)
        is String -> output
        else -> null
    }
    val inputs = SignalPath.Inputs(
        local = local,
        codec = SignalPath.codecName(currentFormat?.mimeType, currentFormat?.codecs),
        bitrateKbps = currentFormat?.bitrate?.let { it / 1000 },
        levellingDb = if (normalizing) SignalPath.decibels(levelling) else null,
        equalizerOn = equalizerOn,
        spatial = runCatching { SpatialAudioMode.valueOf(spatialName) }.getOrDefault(SpatialAudioMode.OFF),
        speed = parameters.speed,
        pitch = parameters.pitch,
        skipSilence = skipSilence,
        output = outputName,
        outputRateKhz = audio?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
            ?.let { if (it % 1000 == 0) "${it / 1000}" else "%.1f".format(it / 1000f) },
        volumePercent = audio?.let {
            val max = it.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max > 0) it.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max else null
        },
    )
    val stages = SignalPath.stages(inputs)

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Route, contentDescription = null) },
        title = { Text(stringResource(R.string.signal_path)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
        text = {
            Column(
                modifier = Modifier
                    .sizeIn(minWidth = 280.dp, maxWidth = 560.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.signal_path_sub),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                stages.forEach { stage ->
                    StageRow(
                        colour = colourOf(stage.kind),
                        title = stringResource(stage.title),
                        detail = stringResource(stage.detail, *stage.args.toTypedArray()),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Legend(Untouched, stringResource(R.string.signal_legend_untouched))
                Legend(Changed, stringResource(R.string.signal_legend_changed))
                Legend(MaterialTheme.colorScheme.outline, stringResource(R.string.signal_legend_where))
            }
        },
    )
}

@Composable
private fun colourOf(kind: SignalPath.Kind): Color = when (kind) {
    SignalPath.Kind.UNTOUCHED -> Untouched
    SignalPath.Kind.CHANGED -> Changed
    SignalPath.Kind.WHERE -> MaterialTheme.colorScheme.outline
}

@Composable
private fun StageRow(colour: Color, title: String, detail: String) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(vertical = 8.dp),
    ) {
        Box(
            Modifier
                .padding(top = 4.dp)
                .size(14.dp)
                .background(colour, CircleShape)
        )
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Legend(colour: Color, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(vertical = 2.dp),
    ) {
        Box(Modifier.size(10.dp).background(colour, CircleShape))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Where the music is going: from Android 13 the route media actually takes, before that the likeliest
 * of what is connected, headphones over the speaker. A name of its own when it has one (Bluetooth
 * buds), else a string resource.
 */
private fun playingOn(audio: AudioManager): Any? {
    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        audio.getAudioDevicesForAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        ).firstOrNull()
    } else {
        val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            ?: outputs.firstOrNull { it.type in WIRED }
            ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    } ?: return null
    return when (device.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> R.string.signal_phone_speaker
        in WIRED -> device.productName?.toString()?.takeIf { it.isNotBlank() && it != Build.MODEL } ?: R.string.signal_wired
        else -> device.productName?.toString()?.takeIf { it.isNotBlank() } ?: R.string.signal_other_output
    }
}

private val WIRED = setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_DEVICE,
)
