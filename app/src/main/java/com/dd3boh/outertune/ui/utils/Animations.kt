/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.utils

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import coil3.request.ImageResult
import coil3.transition.CrossfadeTransition
import coil3.transition.Transition
import coil3.transition.TransitionTarget
import com.dd3boh.outertune.constants.AnimationsKey
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Whether the app animates, decided here and nowhere else: not while Android's own Remove
 * animations is on (its animator duration scale at 0), and not while the Animations switch of Look
 * and feel is off. Then screens and controls change at once and the backgrounds stand still. A
 * tester's wish in the 0.13 poll, for older and smaller phones; his yes of 4 Nov 2026.
 *
 * Compose takes the pace of an animation from the MotionDurationScale of the coroutines it runs in,
 * and the recomposer Compose makes for a window by itself gives it Android's setting alone. Each
 * window of the app is given [recomposer] instead, with [current] in that place: every
 * animate*AsState, Animatable, AnimatedVisibility, AnimatedContent, Crossfade and transition, the
 * navigation's and the player sheet's among them, and those of the dialogs, sheets and popups
 * opened from them, go at this pace, and at 0 Compose plays each one as a jump to its end. Flings
 * and scrolling titles keep a pace of their own, as Compose has them with Android's setting too
 * (DefaultScrollMotionDurationScale and FixedMotionDurationScale, foundation 1.10.3).
 *
 * What moves by a clock of its own asks [off]: Living blur, the pictures of the (i) explanations,
 * and Coil's fade of the covers ([CoverFade]).
 */
object Animations {
    private const val TAG = "Animations"

    /** The switch is on until it is turned off. */
    const val SWITCH_DEFAULT = true

    /**
     * The rule: the pace of the app's animations, from Android's own pace and the switch. 0 is
     * animations off. Android's slower and faster paces are kept while the switch is on.
     */
    fun pace(system: Float, switchOn: Boolean): Float = if (switchOn) system else 0f

    /**
     * The pace as Compose reads it, from the two as they were last read. Snapshot state, so that a
     * change reaches what waits on it: InfiniteTransition waits with snapshotFlow for the pace to
     * come back above 0, and a screen that asks [off] as it is composed is composed again.
     */
    class Pace : MotionDurationScale {
        /** Android's animator duration scale: 1 unless it was changed, 0 for its Remove animations. */
        var system by mutableFloatStateOf(1f)
            internal set

        /** The Animations switch of Look and feel. */
        var switchOn by mutableStateOf(Animations.SWITCH_DEFAULT)
            internal set

        override val scaleFactor: Float get() = Animations.pace(system, switchOn)
    }

    /** The app's pace, given to every window's composition. */
    val current = Pace()

    /** Animations off, in Android's settings or by the switch. */
    val off: Boolean get() = current.scaleFactor == 0f

    /** Off in Android's settings, which the switch cannot undo. */
    val offInSystem: Boolean get() = current.system == 0f

    @Volatile private var started = false

    /**
     * Reads Android's pace and the switch, and follows both from then on, once for the process. On
     * the main thread, before a window's first composition: [recomposer] sees to it. Safe to call
     * again.
     */
    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        val app = context.applicationContext
        val resolver = app.contentResolver
        current.system = systemPace(resolver)
        // Without the switch's flag there is no row, and the switch counts as on.
        if (Unreleased.ANIMATIONS_SWITCH) current.switchOn = app.dataStore[AnimationsKey] ?: SWITCH_DEFAULT
        // Android sends nothing when its setting changes, so the setting itself is watched, as
        // Compose's own window recomposer watches it.
        runCatching {
            resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        current.system = systemPace(resolver)
                    }
                },
            )
        }.onFailure { Log.w(TAG, "Android's animation setting cannot be followed", it) }
        if (Unreleased.ANIMATIONS_SWITCH) CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            app.dataStore.data
                .map { it[AnimationsKey] ?: SWITCH_DEFAULT }
                .distinctUntilChanged()
                .collect { current.switchOn = it }
        }
    }

    private fun systemPace(resolver: ContentResolver): Float =
        runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)

    /**
     * The recomposer for [activity]'s window, made as Compose makes its own: it follows the
     * activity's lifecycle and stops when the window goes. Only the pace differs, the app's in
     * place of Android's alone. Given to setContent as the parent of the window's composition.
     */
    fun recomposer(activity: ComponentActivity): Recomposer {
        start(activity)
        return activity.window.decorView.createLifecycleAwareWindowRecomposer(current, activity.lifecycle)
    }
}

/**
 * Covers fade in as they arrive, as Coil's crossfade has always had them, and with animations off
 * they are there at once. Coil times its fade by the clock on the wall and not by the pace a
 * composition is given (CrossfadePainter, Coil 3.3.0), so the rule is asked here, for each picture.
 */
class CoverFade : Transition.Factory {
    private val fade = CrossfadeTransition.Factory()

    override fun create(target: TransitionTarget, result: ImageResult): Transition =
        (if (Animations.off) Transition.Factory.NONE else fade).create(target, result)
}
