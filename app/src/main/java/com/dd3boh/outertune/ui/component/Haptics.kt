/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.constants.VibrationKey
import com.dd3boh.outertune.extensions.toEnum
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get

/**
 * The app's haptics as the Vibration setting has them (Unreleased.HAPTICS_FEEL): nothing under Off, the app's own under
 * each of the five steps, from faint to Strong, Light among them, softer the lower ([buzzFor] says what, per [Feel]).
 *
 * Every haptic goes through [play]: the window's (FeltHaptics, which MainActivity hands down as LocalHapticFeedback),
 * and the ones that read the setting themselves, a tap's tick, the seek bar's and the volume's (MoreHaptics), which
 * also play in the menus and dialogs: their windows have Compose's own haptic feedback, not the main window's.
 *
 * Every step is played through the vibrator as touch feedback, the way Android's own touch haptics are, so the
 * phone's vibration switches and its touch strength still apply. They need android.permission.VIBRATE: without it, and
 * wherever the vibrator refuses, the view's own haptic stands in.
 */
object Haptics {
    private const val TAG = "Haptics"

    /** The app, given at start (App.onCreate); until then, and in a test, the setting reads as Light. */
    @Volatile
    private var app: Context? = null

    @Volatile
    private var strongVibrator: StrongVibrator? = null

    fun start(context: Context) {
        app = context.applicationContext
    }

    /** The setting in force. Light where the flag is off: a build without it plays today's haptics, unchanged. */
    fun vibration(): Vibration {
        if (!Unreleased.HAPTICS_FEEL) return Vibration.LIGHT
        val context = app ?: return Vibration.LIGHT
        // A look in the settings held in memory (SettingsSnapshot), cheap enough for every tick of a drag.
        return context.dataStore[VibrationKey].toEnum(Vibration.DEFAULT)
    }

    /**
     * Plays [feel] as [choice] says: nothing under Off, the app's own under each step, Light among them (softer than
     * Strong), and [light], the view's own haptic, wherever the app cannot play its own. Light where the flag is off is
     * the view's own, unchanged.
     */
    inline fun play(feel: Feel, choice: Vibration = vibration(), light: () -> Unit) {
        when (choice) {
            Vibration.OFF -> Unit
            Vibration.LIGHT -> if (!Unreleased.HAPTICS_FEEL || !own(feel, choice)) light()
            else -> if (!own(feel, choice)) light()
        }
    }

    /**
     * One buzz of [vibration] as it is picked in Settings: play and pause's firm click, or its Confirm under Light.
     * [platform] is Compose's own haptic feedback: the window's still holds the choice before.
     */
    fun sample(vibration: Vibration, platform: HapticFeedback) =
        play(Feel.FIRM, vibration) { platform.performHapticFeedback(HapticFeedbackType.Confirm) }

    /** Plays [feel] the app's own way at [vibration]'s strength; false where it cannot, and the view's own stands in. */
    fun own(feel: Feel, vibration: Vibration): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val vibrator = strongVibrator ?: app?.let { context ->
            synchronized(this) { strongVibrator ?: StrongVibrator.of(context).also { strongVibrator = it } }
        } ?: return false
        return try {
            vibrator.play(feel, vibration)
        } catch (e: Exception) {
            // A vibrator service that refuses (a SecurityException, a dead binder): the view's own haptic instead.
            Log.w(TAG, "$vibration could not play $feel", e)
            false
        }
    }
}

/** The phone's vibrator and what it can play, read once, the first time Strong plays. */
private class StrongVibrator(
    private val vibrator: Vibrator?,
    private val caps: VibratorCaps,
    private val allowed: Boolean,
) {
    @SuppressLint("MissingPermission") // Asked of the manifest's grant first ([allowed]); a release without it never plays.
    fun play(feel: Feel, vibration: Vibration): Boolean {
        if (!allowed || vibrator == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val effect = effectOf(buzzFor(feel, vibration, caps)) ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            // Before Android 13 the attributes are audio ones: Android files sonification under touch.
            vibrator.vibrate(effect, TOUCH_AUDIO)
        }
        return true
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun effectOf(buzz: Buzz): VibrationEffect? = when (buzz) {
        is Buzz.Primitives -> VibrationEffect.startComposition().apply {
            for (step in buzz.steps) addPrimitive(step.primitive, step.scale, step.delay)
        }.compose()
        is Buzz.Predefined -> VibrationEffect.createPredefined(buzz.effect)
        // Never repeated (-1): every buzz is one-shot, nothing can keep vibrating.
        is Buzz.Pulses -> VibrationEffect.createWaveform(buzz.timings(), buzz.amplitudes(), -1)
        Buzz.None, Buzz.Light -> null
    }

    companion object {
        private val TOUCH_AUDIO: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        fun of(context: Context): StrongVibrator {
            val allowed = context.checkSelfPermission(Manifest.permission.VIBRATE) == PackageManager.PERMISSION_GRANTED
            val vibrator = context.getSystemService(Vibrator::class.java)?.takeIf { it.hasVibrator() }
            val caps = if (vibrator != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) capsOf(vibrator)
            else VibratorCaps(Build.VERSION.SDK_INT, vibrator != null, emptySet(), emptyMap(), false)
            Log.i("Haptics", "Strong on this phone: $caps, the permission ${if (allowed) "granted" else "missing"}")
            return StrongVibrator(vibrator, caps, allowed)
        }

        /** What [vibrator] says it plays: the three primitives Strong uses and the four effects it falls back on. */
        @RequiresApi(Build.VERSION_CODES.R)
        @SuppressLint("InlinedApi") // The low tick is Android 12's, and only asked of it there.
        private fun capsOf(vibrator: Vibrator): VibratorCaps {
            val asked = buildList {
                add(VibrationEffect.Composition.PRIMITIVE_CLICK)
                add(VibrationEffect.Composition.PRIMITIVE_TICK)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
            }
            val primitives = asked.filter { vibrator.areAllPrimitivesSupported(it) }.toSet()
            val effects = Feel.entries.flatMap(::predefinedFor).distinct().associateWith { vibrator.areAllEffectsSupported(it) }
            return VibratorCaps(Build.VERSION.SDK_INT, true, primitives, effects, vibrator.hasAmplitudeControl())
        }
    }
}

/**
 * The window's haptic feedback with the Vibration setting in it: whatever a screen asks of LocalHapticFeedback (a long
 * press, a toggle, Compose's own) plays as the setting says, [platform] being today's.
 */
class FeltHaptics(val platform: HapticFeedback) : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        Haptics.play(feelOf(hapticFeedbackType)) { platform.performHapticFeedback(hapticFeedbackType) }
    }
}

/** The window's haptic feedback, the setting in it ([FeltHaptics]) where the flag is on, else Compose's own. */
@Composable
fun rememberFeltHaptics(): HapticFeedback {
    val platform = LocalHapticFeedback.current
    return remember(platform) { if (Unreleased.HAPTICS_FEEL) FeltHaptics(platform) else platform }
}
