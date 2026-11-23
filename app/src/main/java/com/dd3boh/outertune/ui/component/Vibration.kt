/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

// The vibrator's constants are numbers, copied in at build time: nothing here asks the vibrator anything (Haptics does).
@file:SuppressLint("InlinedApi")

package com.dd3boh.outertune.ui.component

import android.annotation.SuppressLint
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/**
 * The Vibration setting under Look and feel (Unreleased.HAPTICS_FEEL; his ask of 20 Nov 2026 on his S25U, "the haptics
 * aren't really noticeable... how custom can we make them?"). The shape is Essentials', his design reference, whose
 * Disabled, Enabled and Stronger are these three (experiments/features/design-language/essentials-study.md, part 4).
 */
enum class Vibration {
    /** None from the app at all. */
    OFF,

    /** Today's: the view's own feedback, which Android's Touch feedback setting turns off, as for every app. */
    LIGHT,

    /** The app's own, played through the vibrator as touch feedback, a feel per action ([Feel]). */
    STRONG;

    companion object {
        /** Strong for the trial: on his S25U Light is the gentlest Android has. */
        val DEFAULT = STRONG
    }
}

/** What an action feels like under Strong. */
enum class Feel {
    /** Skip, shuffle, repeat and every other tap: a crisp tick. */
    TICK,

    /** The volume landing on 100%, a swipe past its line: a click. */
    CLICK,

    /** Play and pause, a long press, the seek bar let go: a firm click. */
    FIRM,

    /** A like: two clicks. */
    DOUBLE,

    /** The seek bar's ticks while it is dragged: low and soft. */
    LOW_TICK,
}

/** What the vibrator is asked to play for one feel, worked out by [buzzFor]. */
sealed interface Buzz {
    /** One primitive of a composition, played [delay] ms after the one before it ends. */
    data class Step(val primitive: Int, val scale: Float, val delay: Int = 0)

    /** Nothing: the setting is Off. */
    data object None : Buzz

    /** Light's way: the haptic the app has always asked of the view. */
    data object Light : Buzz

    /** Primitives one after the other (VibrationEffect.startComposition, Android 11 and later). */
    data class Primitives(val steps: List<Step>) : Buzz

    /** One of Android's own effects (VibrationEffect.EFFECT_*), tuned by the phone's maker where it has them. */
    data class Predefined(val effect: Int) : Buzz

    /** Plain pulses of [length] ms, [gap] ms apart: the last resort, on a vibrator that offers nothing better. */
    data class Pulses(
        val count: Int,
        val length: Long,
        val gap: Long = 0,
        val amplitude: Int = VibrationEffect.DEFAULT_AMPLITUDE,
    ) : Buzz {
        /** The waveform's times: off for nothing, then on and off by turns. */
        fun timings(): LongArray = LongArray(count * 2) { i -> if (i == 0) 0L else if (i % 2 == 1) length else gap }

        fun amplitudes(): IntArray = IntArray(count * 2) { i -> if (i % 2 == 1) amplitude else 0 }
    }
}

/**
 * What the phone's vibrator can play, read once from it (Haptics).
 *
 * @param primitives the composition primitives it plays (VibrationEffect.Composition.PRIMITIVE_*)
 * @param effects its answer for each predefined effect asked about (Vibrator.VIBRATION_EFFECT_SUPPORT_*); one missing
 *   is one it did not say
 */
data class VibratorCaps(
    val sdk: Int,
    val hasVibrator: Boolean,
    val primitives: Set<Int>,
    val effects: Map<Int, Int>,
    val amplitudeControl: Boolean,
)

/** The predefined effects a feel can be played with, the best first. */
fun predefinedFor(feel: Feel): List<Int> = when (feel) {
    Feel.TICK, Feel.LOW_TICK -> listOf(VibrationEffect.EFFECT_TICK)
    Feel.CLICK -> listOf(VibrationEffect.EFFECT_CLICK)
    Feel.FIRM -> listOf(VibrationEffect.EFFECT_HEAVY_CLICK, VibrationEffect.EFFECT_CLICK)
    Feel.DOUBLE -> listOf(VibrationEffect.EFFECT_DOUBLE_CLICK)
}

/**
 * What [feel] plays under [vibration] on a phone that can do [caps].
 *
 * Off is nothing, Light today's. Strong is the brief's feel per action (experiments/features/haptics-feel/BRIEF.md),
 * played with the best the vibrator has: its primitives where it plays the ones needed, else its predefined effect
 * (unless it says it has none: one that cannot say gets it, and Android plays its own stand-in where the hardware has
 * none), else plain pulses of 20 ms. Below Android 11, where there are no primitives and no answer about effects, and
 * on a phone without a vibrator, Strong is Light's way.
 */
fun buzzFor(feel: Feel, vibration: Vibration, caps: VibratorCaps): Buzz {
    when (vibration) {
        Vibration.OFF -> return Buzz.None
        Vibration.LIGHT -> return Buzz.Light
        Vibration.STRONG -> Unit
    }
    if (caps.sdk < 30 || !caps.hasVibrator) return Buzz.Light
    composition(feel, caps)?.let { return it }
    predefinedFor(feel).firstOrNull { caps.effects[it] != Vibrator.VIBRATION_EFFECT_SUPPORT_NO }?.let { return Buzz.Predefined(it) }
    return when (feel) {
        Feel.DOUBLE -> Buzz.Pulses(count = 2, length = 20, gap = 60)
        // Essentials' subtle stand-in, where the vibrator can play softly; only shorter where it cannot.
        Feel.LOW_TICK -> if (caps.amplitudeControl) Buzz.Pulses(count = 1, length = 10, amplitude = 50) else Buzz.Pulses(count = 1, length = 10)
        else -> Buzz.Pulses(count = 1, length = 20)
    }
}

/** [feel] as primitives, where the phone plays the ones it needs. */
private fun composition(feel: Feel, caps: VibratorCaps): Buzz.Primitives? {
    val click = VibrationEffect.Composition.PRIMITIVE_CLICK
    val tick = VibrationEffect.Composition.PRIMITIVE_TICK
    val lowTick = VibrationEffect.Composition.PRIMITIVE_LOW_TICK
    val steps = when (feel) {
        Feel.TICK -> listOf(Buzz.Step(tick, 1f))
        Feel.CLICK, Feel.FIRM -> listOf(Buzz.Step(click, 1f))
        // Essentials' double: the second click the stronger, 60 ms after the first.
        Feel.DOUBLE -> listOf(Buzz.Step(click, 0.7f), Buzz.Step(click, 1f, delay = 60))
        // The low tick is Android 12's; before it, or on a phone without it, a softer tick.
        // Softer than at first, with twice the ticks (Ti-Die, 20 Nov 2026: "increase the frequency of dots but make them less intense").
        Feel.LOW_TICK -> if (caps.sdk >= 31 && lowTick in caps.primitives) listOf(Buzz.Step(lowTick, 0.35f)) else listOf(Buzz.Step(tick, 0.3f))
    }
    return if (steps.all { it.primitive in caps.primitives }) Buzz.Primitives(steps) else null
}

/**
 * A player button's feel under Strong, after Ti-Die (#testers, 21 Nov 2026: the like "does the haptics perfectly", copy
 * it to the buttons that do not open anything; the ones that do, and skip, "closer to how the pause button feels").
 * One that turns something on and off is a like: two clicks turning it on, the tap's tick turning it off ([turnsOff]).
 * One that opens something (a sheet, a menu, Android's share sheet) or skips (null) is play and pause's firm click.
 */
fun buttonFeel(turnsOff: Boolean?): Feel = when (turnsOff) {
    null -> Feel.FIRM
    true -> Feel.TICK
    false -> Feel.DOUBLE
}

/**
 * The feel of a haptic the app asks of Compose (FeltHaptics), as the player's buttons use them: Confirm is play and
 * pause, SegmentFrequentTick skip, shuffle and repeat under Light (Strong plays theirs as [buttonFeel] says), ContextClick
 * a tap.
 */
fun feelOf(type: HapticFeedbackType): Feel = when (type) {
    HapticFeedbackType.Confirm, HapticFeedbackType.LongPress -> Feel.FIRM
    HapticFeedbackType.SegmentTick, HapticFeedbackType.GestureEnd -> Feel.CLICK
    HapticFeedbackType.Reject -> Feel.DOUBLE
    HapticFeedbackType.TextHandleMove -> Feel.LOW_TICK
    else -> Feel.TICK
}
