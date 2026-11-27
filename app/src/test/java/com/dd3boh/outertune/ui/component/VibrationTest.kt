/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.media3.common.Player
import com.dd3boh.outertune.ui.component.Buzz.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Vibration setting (Unreleased.HAPTICS_FEEL; his ask of 20 Nov 2026 on his S25U, "the haptics aren't really
 * noticeable... how custom can we make them?"): Off, Light (today's) or Strong (the app's own, a feel per action).
 * What Strong plays on a phone is a pure choice ([buzzFor]) and is tested as such; where the feels are wired is read
 * from the source, as MoreHapticsTest does.
 */
class VibrationTest {

    private val click = VibrationEffect.Composition.PRIMITIVE_CLICK
    private val tick = VibrationEffect.Composition.PRIMITIVE_TICK
    private val lowTick = VibrationEffect.Composition.PRIMITIVE_LOW_TICK
    private val yes = Vibrator.VIBRATION_EFFECT_SUPPORT_YES
    private val no = Vibrator.VIBRATION_EFFECT_SUPPORT_NO

    /** His S25U as dumpsys vibrator_manager read it on 20 Nov 2026: eight primitives, four effects, amplitude control. */
    private val s25u = VibratorCaps(
        sdk = 36,
        hasVibrator = true,
        primitives = setOf(
            click, tick, lowTick, VibrationEffect.Composition.PRIMITIVE_THUD, VibrationEffect.Composition.PRIMITIVE_SPIN,
            VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, VibrationEffect.Composition.PRIMITIVE_SLOW_RISE,
            VibrationEffect.Composition.PRIMITIVE_QUICK_FALL,
        ),
        effects = mapOf(
            VibrationEffect.EFFECT_CLICK to yes, VibrationEffect.EFFECT_DOUBLE_CLICK to yes,
            VibrationEffect.EFFECT_TICK to yes, VibrationEffect.EFFECT_HEAVY_CLICK to yes,
        ),
        amplitudeControl = true,
    )

    /** A phone with the predefined effects and no primitives: what many phones of Android 11 and 12 have. */
    private val effectsOnly = s25u.copy(primitives = emptySet())

    private fun strong(feel: Feel, caps: VibratorCaps = s25u) = buzzFor(feel, Vibration.STRONG, caps)

    private fun steps(vararg steps: Step) = Buzz.Primitives(steps.toList())

    // ---- the three choices ----

    @Test
    fun `Off plays nothing, and Light is the view's own only where the app cannot play its own`() {
        for (caps in listOf(s25u, effectsOnly, s25u.copy(sdk = 26), s25u.copy(hasVibrator = false))) {
            for (feel in Feel.entries) assertEquals(Buzz.None, buzzFor(feel, Vibration.OFF, caps))
        }
        for (caps in listOf(s25u.copy(sdk = 26), s25u.copy(sdk = 29), s25u.copy(hasVibrator = false))) {
            for (feel in Feel.entries) assertEquals("$feel on $caps", Buzz.Light, buzzFor(feel, Vibration.LIGHT, caps))
        }
    }

    @Test
    fun `Light is Strong at its step's strength, so the two differ on every phone`() {
        // His pick of 21 Nov 2026 on the preview line: dvd and Ti-Die felt Light, then the phone's own touch feedback, as
        // strong as Strong. Since the slider (26 Nov 2026) Light is its step 2, the app's own feel at 0.4 of Strong's.
        fun light(feel: Feel, caps: VibratorCaps = s25u) = buzzFor(feel, Vibration.LIGHT, caps)
        val share = Vibration.LIGHT.strength
        assertEquals(0.4f, share, 0f)
        // His S25U: the same primitives, each at the step's share of its scale.
        assertEquals(steps(Step(click, share)), light(Feel.FIRM))
        assertEquals(steps(Step(click, share)), light(Feel.CLICK))
        assertEquals(steps(Step(tick, share)), light(Feel.TICK))
        assertEquals(steps(Step(lowTick, 0.35f * share)), light(Feel.LOW_TICK))
        assertEquals(steps(Step(click, 0.7f * share), Step(click, share, delay = 60)), light(Feel.DOUBLE))
        for (feel in Feel.entries) {
            val strong = strong(feel) as Buzz.Primitives
            val soft = light(feel) as Buzz.Primitives
            assertEquals(feel.toString(), strong.steps.map { it.primitive }, soft.steps.map { it.primitive })
            assertTrue(feel.toString(), soft.steps.zip(strong.steps).all { (l, st) -> l.scale < st.scale })
        }
        // Effects only: a pulse at the step's amplitude where the vibrator plays softly, a gentler rung where it cannot.
        assertEquals(Buzz.Pulses(count = 1, length = 22, amplitude = 102), light(Feel.FIRM, effectsOnly))
        assertEquals(Buzz.Pulses(count = 2, length = 20, gap = 60, amplitude = 102), light(Feel.DOUBLE, effectsOnly))
        assertEquals(Buzz.Pulses(count = 1, length = 15), light(Feel.FIRM, effectsOnly.copy(amplitudeControl = false)))
        // Nothing but plain pulses: softer where the vibrator can, shorter where it cannot.
        val bare = s25u.copy(primitives = emptySet(), effects = Feel.entries.flatMap(::predefinedFor).associateWith { no })
        assertEquals(Buzz.Pulses(count = 1, length = 20, amplitude = 102), light(Feel.TICK, bare))
        assertEquals(Buzz.Pulses(count = 1, length = 5), light(Feel.TICK, bare.copy(amplitudeControl = false)))
        // Never Strong's own, nor the view's, on a phone where the app plays its own.
        for (caps in listOf(s25u, effectsOnly, bare, s25u.copy(amplitudeControl = false), effectsOnly.copy(amplitudeControl = false))) {
            for (feel in Feel.entries) {
                val buzz = light(feel, caps)
                assertTrue("$feel on $caps: $buzz", buzz != strong(feel, caps) && buzz != Buzz.Light)
            }
        }
    }

    @Test
    fun `Strong is the trial's default, his S25U finding Light too faint`() {
        assertEquals(Vibration.STRONG, Vibration.DEFAULT)
        // Off and five steps, in the slider's order.
        assertEquals(listOf(Vibration.OFF, Vibration.FAINT, Vibration.LIGHT, Vibration.MEDIUM, Vibration.FIRM, Vibration.STRONG), Vibration.entries)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), Vibration.entries.map { it.step })
    }

    // ---- Strong on his S25U: the primitives ----

    @Test
    fun `on his S25U Strong plays a primitive per action, at the brief's strengths`() {
        // Play and pause, a long press, the seek bar let go: a firm click.
        assertEquals(steps(Step(click, 1f)), strong(Feel.FIRM))
        // The volume landing on 100%: a click.
        assertEquals(steps(Step(click, 1f)), strong(Feel.CLICK))
        // Skip, shuffle, repeat, every tap: a tick.
        assertEquals(steps(Step(tick, 1f)), strong(Feel.TICK))
        // The seek bar's ticks while it is dragged: low ones.
        assertEquals(steps(Step(lowTick, 0.35f)), strong(Feel.LOW_TICK))
        // A like: two clicks 60 ms apart, the second the stronger, as Essentials' double has them.
        assertEquals(steps(Step(click, 0.7f), Step(click, 1f, delay = 60)), strong(Feel.DOUBLE))
        // The seek bar reaching its start or its end: the click at three quarters.
        assertEquals(steps(Step(click, 0.75f)), strong(Feel.EDGE))
        // A button turning something off, the pop: the click at half.
        assertEquals(steps(Step(click, 0.5f)), strong(Feel.POP))
    }

    @Test
    fun `Ti-Die's notes of 23 Nov 2026, the seek bar's ends a tad softer, the pop a little firmer, the hit untouched`() {
        // "Could maybe bring down the edge haptic amplitude a tad": the volume's click, softer.
        val edge = (strong(Feel.EDGE) as Buzz.Primitives).steps.single()
        val volume = (strong(Feel.CLICK) as Buzz.Primitives).steps.single()
        assertEquals(click, edge.primitive)
        assertTrue("$edge", edge.scale < volume.scale && edge.scale >= 0.7f)
        // "increase the pop's amplitude a little (without touching the hit's)": the tick already plays at its full
        // strength, so the pop is the next primitive up, a click, softer than either of the hit's two.
        assertEquals(steps(Step(tick, 1f)), strong(Feel.TICK))
        val pop = (strong(Feel.POP) as Buzz.Primitives).steps.single()
        assertEquals(click, pop.primitive)
        val hit = (strong(Feel.DOUBLE) as Buzz.Primitives).steps
        assertEquals(listOf(Step(click, 0.7f), Step(click, 1f, delay = 60)), hit)
        assertTrue("$pop under $hit", hit.all { pop.scale < it.scale })
    }

    @Test
    fun `on Android 11 there is no low tick, and the seek bar ticks with a softer tick`() {
        // PRIMITIVE_LOW_TICK is API 31: an Android 11 phone never says it plays one.
        val android11 = s25u.copy(sdk = 30, primitives = setOf(click, tick))
        assertEquals(steps(Step(tick, 0.3f)), strong(Feel.LOW_TICK, android11))
        assertEquals(steps(Step(tick, 1f)), strong(Feel.TICK, android11))
        assertEquals(steps(Step(click, 1f)), strong(Feel.FIRM, android11))
        assertEquals(steps(Step(click, 0.75f)), strong(Feel.EDGE, android11))
        assertEquals(steps(Step(click, 0.5f)), strong(Feel.POP, android11))
    }

    // ---- Strong without primitives: the predefined effects, then plain pulses ----

    @Test
    fun `without primitives Strong plays the phone's predefined effects`() {
        assertEquals(Buzz.Predefined(VibrationEffect.EFFECT_HEAVY_CLICK), strong(Feel.FIRM, effectsOnly))
        assertEquals(Buzz.Predefined(VibrationEffect.EFFECT_CLICK), strong(Feel.CLICK, effectsOnly))
        assertEquals(Buzz.Predefined(VibrationEffect.EFFECT_TICK), strong(Feel.TICK, effectsOnly))
        assertEquals(Buzz.Predefined(VibrationEffect.EFFECT_TICK), strong(Feel.LOW_TICK, effectsOnly))
        assertEquals(Buzz.Predefined(VibrationEffect.EFFECT_DOUBLE_CLICK), strong(Feel.DOUBLE, effectsOnly))
        // A predefined effect has no strength to turn: the ends keep the phone's click, and the pop its tick, since its
        // click would be the volume's whole one, far more than "a little".
        assertEquals(Buzz.Predefined(VibrationEffect.EFFECT_CLICK), strong(Feel.EDGE, effectsOnly))
        assertEquals(Buzz.Predefined(VibrationEffect.EFFECT_TICK), strong(Feel.POP, effectsOnly))
    }

    @Test
    fun `a phone that cannot say which effects it plays gets them all the same, Android's own stand-ins behind them`() {
        // Vibrator.VIBRATION_EFFECT_SUPPORT_UNKNOWN: the vibrator does not list its effects. createPredefined then
        // plays Android's fallback pattern where the hardware has none, so the effect is still the best guess.
        val unknown = effectsOnly.copy(effects = emptyMap())
        assertEquals(Buzz.Predefined(VibrationEffect.EFFECT_HEAVY_CLICK), strong(Feel.FIRM, unknown))
        assertEquals(Buzz.Predefined(VibrationEffect.EFFECT_DOUBLE_CLICK), strong(Feel.DOUBLE, unknown))
        val saysUnknown = unknown.copy(effects = mapOf(VibrationEffect.EFFECT_TICK to Vibrator.VIBRATION_EFFECT_SUPPORT_UNKNOWN))
        assertEquals(Buzz.Predefined(VibrationEffect.EFFECT_TICK), strong(Feel.TICK, saysUnknown))
    }

    @Test
    fun `a firm click the phone has no heavy click for is its plain click, and a double it has none for two pulses`() {
        // As the emulator's vibrator: CLICK and TICK, no DOUBLE_CLICK or HEAVY_CLICK.
        val clickAndTick = effectsOnly.copy(
            effects = mapOf(
                VibrationEffect.EFFECT_CLICK to yes, VibrationEffect.EFFECT_TICK to yes,
                VibrationEffect.EFFECT_DOUBLE_CLICK to no, VibrationEffect.EFFECT_HEAVY_CLICK to no,
            ),
        )
        assertEquals(Buzz.Predefined(VibrationEffect.EFFECT_CLICK), strong(Feel.FIRM, clickAndTick))
        assertEquals(Buzz.Pulses(count = 2, length = 20, gap = 60), strong(Feel.DOUBLE, clickAndTick))
    }

    @Test
    fun `a phone with none of it gets plain pulses of 20 ms, a soft short one for the seek bar`() {
        val none = effectsOnly.copy(effects = Feel.entries.flatMap { predefinedFor(it) }.associateWith { no })
        for (feel in listOf(Feel.FIRM, Feel.CLICK, Feel.TICK, Feel.EDGE, Feel.POP)) {
            assertEquals(feel.name, Buzz.Pulses(count = 1, length = 20), strong(feel, none))
        }
        assertEquals(Buzz.Pulses(count = 2, length = 20, gap = 60), strong(Feel.DOUBLE, none))
        // Soft where the vibrator can play softly, as Essentials' subtle stand-in (10 ms at 80); short where it cannot.
        assertEquals(Buzz.Pulses(count = 1, length = 10, amplitude = 50), strong(Feel.LOW_TICK, none))
        assertEquals(Buzz.Pulses(count = 1, length = 10), strong(Feel.LOW_TICK, none.copy(amplitudeControl = false)))
    }

    @Test
    fun `the pulses are a waveform that starts at once, on and off by turns`() {
        val double = Buzz.Pulses(count = 2, length = 20, gap = 60)
        assertEquals(listOf(0L, 20L, 60L, 20L), double.timings().toList())
        assertEquals(listOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE), double.amplitudes().toList())
        val soft = Buzz.Pulses(count = 1, length = 10, amplitude = 50)
        assertEquals(listOf(0L, 10L), soft.timings().toList())
        assertEquals(listOf(0, 50), soft.amplitudes().toList())
    }

    @Test
    fun `below Android 11 and without a vibrator Strong is Light's way`() {
        // Below API 30 there are no primitives and no answer about effects: the brief keeps today's haptics there.
        for (sdk in listOf(24, 26, 29)) {
            for (feel in Feel.entries) assertEquals("$feel on $sdk", Buzz.Light, strong(feel, s25u.copy(sdk = sdk)))
        }
        for (feel in Feel.entries) assertEquals(Buzz.Light, strong(feel, s25u.copy(hasVibrator = false)))
    }

    @Test
    fun `Strong never asks for a primitive the phone lacks, nor an effect it says it cannot play`() {
        val primitives = listOf(click, tick, lowTick)
        val answers = listOf(yes, no, null)
        val effects = listOf(
            VibrationEffect.EFFECT_CLICK, VibrationEffect.EFFECT_DOUBLE_CLICK, VibrationEffect.EFFECT_TICK, VibrationEffect.EFFECT_HEAVY_CLICK,
        )
        var phones = 0
        for (mask in 0 until (1 shl primitives.size)) {
            val has = primitives.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet()
            for (a in answers) for (b in answers) for (c in answers) for (d in answers) {
                val said = effects.zip(listOf(a, b, c, d)).filter { it.second != null }.associate { it.first to it.second!! }
                for (sdk in listOf(30, 31, 36)) {
                    // An Android 11 phone never lists the low tick, which came with Android 12.
                    val caps = VibratorCaps(sdk, true, if (sdk < 31) has - lowTick else has, said, amplitudeControl = mask % 2 == 0)
                    phones++
                    for (feel in Feel.entries) {
                        when (val buzz = strong(feel, caps)) {
                            is Buzz.Primitives -> assertTrue("$feel on $caps: $buzz", buzz.steps.all { it.primitive in caps.primitives })
                            is Buzz.Predefined -> assertTrue("$feel on $caps: $buzz", caps.effects[buzz.effect] != no)
                            is Buzz.Pulses -> assertTrue("$feel on $caps: $buzz", predefinedFor(feel).all { caps.effects[it] == no })
                            else -> error("$feel on $caps: Strong played $buzz on Android 11 or later with a vibrator")
                        }
                    }
                }
            }
        }
        assertEquals(8 * 81 * 3, phones)
    }

    // ---- what the app's other haptics feel like under Strong ----

    @Test
    fun `Compose's haptics each have a feel under Strong, the way the player's buttons use them`() {
        // Play and pause and the seek bar let go ask Confirm; a song row's long press LongPress.
        assertEquals(Feel.FIRM, feelOf(HapticFeedbackType.Confirm))
        assertEquals(Feel.FIRM, feelOf(HapticFeedbackType.LongPress))
        // Skip, shuffle and repeat ask SegmentFrequentTick; the taps ContextClick.
        for (type in listOf(
            HapticFeedbackType.SegmentFrequentTick, HapticFeedbackType.ContextClick, HapticFeedbackType.VirtualKey,
            HapticFeedbackType.KeyboardTap, HapticFeedbackType.ToggleOn, HapticFeedbackType.ToggleOff,
            HapticFeedbackType.GestureThresholdActivate,
        )) assertEquals(type.toString(), Feel.TICK, feelOf(type))
        // Landing somewhere: the lyrics' timing back on zero, a swipe past its line.
        assertEquals(Feel.CLICK, feelOf(HapticFeedbackType.SegmentTick))
        assertEquals(Feel.CLICK, feelOf(HapticFeedbackType.GestureEnd))
        // A row's swipe past its second line, a refusal: two clicks, as Android's own reject is a double buzz.
        assertEquals(Feel.DOUBLE, feelOf(HapticFeedbackType.Reject))
        // A handle dragged along: low ticks, as the seek bar's.
        assertEquals(Feel.LOW_TICK, feelOf(HapticFeedbackType.TextHandleMove))
    }

    // ---- where the feels are wired ----

    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** The source of [path] with its comments taken out. */
    private fun source(path: String) = File(sources, path).readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""(^|\s)//.*$""", RegexOption.MULTILINE), "$1")

    private val haptics get() = source("ui/component/Haptics.kt")

    @Test
    fun `on for everyone since 0_13, his pick of 20 Nov 2026, and without the flag every haptic is today's`() {
        assertTrue("val HAPTICS_FEEL = true" in source("constants/Unreleased.kt"))
        // The setting is Light where the flag is off, and Light without the flag runs today's call unchanged.
        val vibration = haptics.substringAfter("fun vibration(): Vibration {").substringBefore("\n    }")
        assertTrue(vibration, "if (!Unreleased.HAPTICS_FEEL) return Vibration.LIGHT" in vibration)
        assertTrue("context.dataStore[VibrationKey].toEnum(Vibration.DEFAULT)" in vibration)
        val play = haptics.substringAfter("inline fun play(").substringBefore("\n    }")
        assertTrue(play, "Vibration.OFF -> Unit" in play)
        assertTrue(play, "Vibration.LIGHT -> if (!Unreleased.HAPTICS_FEEL || !own(feel, choice)) light()" in play)
        assertTrue(play, "else -> if (!own(feel, choice)) light()" in play)
        // And the window keeps Compose's own haptic feedback where the flag is off.
        assertTrue("if (Unreleased.HAPTICS_FEEL) FeltHaptics(platform) else platform" in haptics)
    }

    @Test
    fun `the window's haptics carry the setting, so Compose's own haptics follow it there`() {
        val main = source("MainActivity.kt")
        assertTrue("val haptic = rememberFeltHaptics()" in main)
        assertTrue("LocalHapticFeedback provides haptic," in main)
        // Each type as the setting says, today's call kept for Light.
        assertTrue("Haptics.play(feelOf(hapticFeedbackType)) { platform.performHapticFeedback(hapticFeedbackType) }" in haptics)
        assertTrue("Haptics.start(this)" in source("App.kt"))
    }

    @Test
    fun `Strong vibrates as touch feedback, so the phone's own switches and strength still apply, and only with leave to`() {
        assertTrue("VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)" in haptics)
        // Android 11 and 12 take audio attributes, and Android turns sonification into touch.
        assertTrue(".setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)" in haptics)
        assertTrue("Manifest.permission.VIBRATE" in haptics)
        // Never the old vibrate(long) or a waveform that repeats: every buzz is one-shot.
        assertFalse(Regex("""vibrate\(\s*\d""").containsMatchIn(haptics))
        assertTrue("createWaveform(buzz.timings(), buzz.amplitudes(), -1)" in haptics)
    }

    @Test
    fun `a tap, the seek bar and the volume landing ask the setting themselves, today's haptic inside`() {
        // They also play inside the menus and dialogs, whose windows have Compose's own haptic feedback.
        val helper = source("ui/component/MoreHaptics.kt")
        assertTrue("if (Unreleased.HAPTICS_MORE) Haptics.play(strong) { performHapticFeedback(HapticFeedbackType.ContextClick) }" in helper)
        assertTrue("fun HapticFeedback.tapTick(strong: Feel = Feel.TICK)" in helper)
        val segment = helper.substringAfter("fun HapticFeedback.segmentTick(").substringBefore("\n}")
        // The seek bar's ticks low, the volume's landing a click, the seek bar's ends their own ([Feel.EDGE], Player).
        assertTrue(segment, "frequent: Boolean = false, strong: Feel = if (frequent) Feel.LOW_TICK else Feel.CLICK)" in segment)
        assertTrue(segment, "Haptics.play(strong) {" in segment)
        assertTrue(segment, "TickWay.CLOCK -> view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)" in segment)
    }

    @Test
    fun `a like plays two clicks and taking it back the pop, in the player and in a song's menu`() {
        val player = source("ui/player/Player.kt")
        // Classic's circle and the connected row's segment, in both places the row is drawn.
        assertEquals(3, Regex("""tap = if \((currentSong\?\.song\?\.liked == true|liked)\) Feel\.POP else Feel\.DOUBLE""").findAll(player).count())
        for (header in listOf("private fun PlayerCircleButton(", "private fun PlayerActionSegment(")) {
            val button = player.substringAfter(header).substringBefore("\n}")
            assertTrue("$header takes no feel", "tap: Feel = buttonFeel(turnsOff = on)" in button)
            assertTrue("$header does not tick with it", "haptic.tapTick(tap)" in button)
        }
        assertTrue("haptic.tapTick(if (song.song.liked) Feel.POP else Feel.DOUBLE)" in source("ui/menu/SongMenu.kt"))
        assertTrue("haptic.tapTick(if (librarySong?.song?.liked == true) Feel.POP else Feel.DOUBLE)" in source("ui/menu/YouTubeSongMenu.kt"))
        assertFalse("a like taken back still ticks somewhere", Regex("""Feel\.TICK else Feel\.DOUBLE""").containsMatchIn(player))
    }

    @Test
    fun `a button that turns something on feels like a like, one that opens something or skips like play and pause`() {
        // Ti-Die, #testers 21 Nov 2026: "the like button does the haptics perfectly ... copy paste that to the
        // shuffle/repeat buttons, and any other menu button that doesn't open a popup. those that do (share, queue,
        // cast) and the previous/next buttons, should have something closer to how the pause button feels".
        // Ti-Die, #testers 23 Nov 2026: the hit turning on stays, the pop turning off a little firmer ([Feel.POP]).
        assertEquals(Feel.DOUBLE, buttonFeel(turnsOff = false))
        assertEquals(Feel.POP, buttonFeel(turnsOff = true))
        assertEquals(Feel.FIRM, buttonFeel(turnsOff = null))
        // Repeat goes off, all, one, off: only the tap from off turns it on, so only that one is the hit. Into one, "loop
        // once", and back off are the pop (Ti-Die, the same night: "it's not turning on").
        assertEquals(Feel.DOUBLE, repeatFeel(Player.REPEAT_MODE_OFF))
        assertEquals(Feel.POP, repeatFeel(Player.REPEAT_MODE_ALL))
        assertEquals(Feel.POP, repeatFeel(Player.REPEAT_MODE_ONE))

        val player = source("ui/player/Player.kt")
        // The pinned buttons: lyrics turns something on and off (on = showLyrics, three places, for TalkBack's switch),
        // the queue, the timer, share, cast, the room and the menu open something and pass no on.
        assertEquals(3, Regex("""onClick = toggleLyrics,""").findAll(player).count())
        assertEquals(3, Regex("""\son = showLyrics,""").findAll(player).count())
        // Lyrics feels like the buttons that open something, as cast and the menu do: it opens and closes the lyrics
        // (Ti-Die, the same night), in all three places.
        assertEquals(3, Regex("""\son = showLyrics,\s+tap = Feel\.FIRM,""").findAll(player).count())
        // The controls: shuffle and repeat as a like, read before the tap changes them; skip both ways firm (Next through
        // the connection, PlayerConnection.skipToNext).
        val controls = player.substringAfter("val shuffleModeEnabled by playerConnection.shuffleModeEnabled.collectAsState()")
            .substringBefore("if (buttonsStyle == PlayerButtonsStyle.CONNECTED) quickActions()")
        assertTrue(controls, "val feel = buttonFeel(turnsOff = shuffleModeEnabled)\n" in controls)
        assertTrue(controls, "val feel = repeatFeel(repeatMode)\n" in controls)
        assertEquals(controls, 2, Regex("""haptic\.felt\(feel, HapticFeedbackType\.SegmentFrequentTick\)""").findAll(controls).count())
        assertEquals(controls, 2, Regex("""(seekToPrevious|skipToNext)\(\)\s+haptic\.felt\(Feel\.FIRM, HapticFeedbackType\.SegmentFrequentTick\)""").findAll(controls).count())
        // Under Light they ask what they always asked, through the window's haptics.
        assertFalse(controls, "haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)" in controls)
        // The queue's bar has the same four, felt the same.
        val bar = source("ui/player/Queue.kt")
        assertTrue("val feel = buttonFeel(turnsOff = shuffleModeEnabled)\n" in bar)
        assertTrue("val feel = repeatFeel(repeatMode)\n" in bar)
        assertEquals(2, Regex("""haptic\.felt\(feel, HapticFeedbackType\.SegmentFrequentTick\)""").findAll(bar).count())
        assertEquals(2, Regex("""(seekToPrevious|skipToNext)\(\)\s+haptic\.felt\(Feel\.FIRM, HapticFeedbackType\.SegmentFrequentTick\)""").findAll(bar).count())
        assertTrue("fun HapticFeedback.felt(strong: Feel, light: HapticFeedbackType) = Haptics.play(strong) { performHapticFeedback(light) }" in source("ui/component/MoreHaptics.kt"))
    }

    @Test
    fun `the row is under Look and feel's Behavior, after the swipes, and a pick plays one buzz of it`() {
        val look = source("ui/screens/settings/LookAndFeelSettings.kt")
        assertTrue("SwipeGesturesFrag()\n            if (Unreleased.HAPTICS_FEEL) VibrationFrag()" in look)
        val row = source("ui/screens/settings/fragments/InterfaceFrag.kt").substringAfter("fun ColumnScope.VibrationFrag() {").substringBefore("\n}")
        assertTrue(row, "rememberEnumPreference(VibrationKey, defaultValue = Vibration.DEFAULT)" in row)
        // A slider of six places, Off at one end and Strong at the other, labelled there only.
        assertTrue(row, "valueRange = 0f..Vibration.STRONG.step.toFloat()" in row)
        assertTrue(row, "steps = Vibration.STRONG.step - 1" in row)
        assertTrue(row, ".settingRow(R.string.vibration)" in row)
        // What's new's card for Vibration shows the row with its Show me (21 Nov 2026).
        assertTrue(row, "Modifier.tourTarget(Tour.SETTING_VIBRATION)" in row)
        assertTrue(row, "Haptics.sample(picked, platform)" in row)
        // Light's buzz is today's own, never the window's, which still holds the old choice as it is picked.
        assertTrue(row, "val platform = (haptic as? FeltHaptics)?.platform ?: haptic" in row)
    }

    @Test
    fun `its texts are short and open to translators since his pick, French beside them`() {
        val english = File("src/main/res/values/strings-ot.xml").readText()
        val french = File("src/main/res/values-fr/strings-ot.xml").readText()
        fun line(xml: String, name: String) = xml.lines().singleOrNull { "name=\"$name\"" in it } ?: error("$name is missing")
        for (name in listOf("vibration", "vibration_off", "vibration_step", "vibration_strong")) {
            for (xml in listOf(english, french)) {
                val entry = line(xml, name)
                assertFalse("$name is still held: $entry", "translatable=\"false\"" in entry)
                val text = entry.substringAfter(">").substringBefore("</string>")
                assertTrue("$name: $text", text.isNotBlank() && text.length <= 12 && '—' !in text)
            }
        }
        assertEquals("Strong", line(english, "vibration_strong").substringAfter(">").substringBefore("<"))
        assertEquals("Forte", line(french, "vibration_strong").substringAfter(">").substringBefore("<"))
    }

    @Test
    fun `a release asks to vibrate exactly when it has the setting`() {
        // As the camera before rooms shipped (RoomsCameraReleaseTest): the debug app, the Preview and the Tester app
        // ask for it, a release takes it out while the flag is held back, so 0.13 asks for nothing new.
        val main = File("src/main/AndroidManifest.xml").readText()
        assertTrue(Regex("""<uses-permission android:name="android\.permission\.VIBRATE"\s*/>""").containsMatchIn(main))
        val held = "val HAPTICS_FEEL = BuildConfig.PREVIEW_FEATURES" in source("constants/Unreleased.kt")
        val release = File("src/release/AndroidManifest.xml").readText()
        val stripped = Regex("""android\.permission\.VIBRATE"\s+tools:node="remove"""").containsMatchIn(release)
        assertTrue("the setting is held back but a release still asks to vibrate", !held || stripped)
        assertTrue("the setting ships but its manifest takes the vibrator away", held || !stripped)
    }

    // ---- the five steps (Ti-Die and Niko, #testers 24 and 26 Nov 2026) ----

    @Test
    fun `an old Light is still the app's own feel, softer, an old Strong the same feel, and the stored names are kept`() {
        // The setting is stored by name: what an update finds on a phone from before the slider.
        assertEquals(Vibration.LIGHT, Vibration.valueOf("LIGHT"))
        assertEquals(Vibration.STRONG, Vibration.valueOf("STRONG"))
        assertEquals(Vibration.OFF, Vibration.valueOf("OFF"))
        for (caps in listOf(s25u, effectsOnly, s25u.copy(amplitudeControl = false), s25u.copy(sdk = 30), s25u.copy(sdk = 26))) {
            for (feel in Feel.entries) {
                // The preview line's Light (his pick of 21 Nov 2026): the app's own, the view's only before Android 11.
                val light = buzzFor(feel, Vibration.LIGHT, caps)
                if (caps.sdk < 30) assertEquals(Buzz.Light, light) else assertTrue("$feel on $caps: $light", light != Buzz.Light && light != Buzz.None)
                // Step 5 is exactly Strong's, on every phone, amplitude control or not.
                assertEquals(strong(feel, caps), buzzFor(feel, Vibration.STRONG, caps))
            }
        }
        assertEquals(steps(Step(tick, 1f)), strong(Feel.TICK, s25u.copy(amplitudeControl = false)))
        assertEquals(Vibration.OFF, Vibration.ofStep(-3))
        assertEquals(Vibration.STRONG, Vibration.ofStep(9))
        assertEquals(Vibration.MEDIUM, Vibration.ofStep(3))
    }

    @Test
    fun `with amplitude control each step plays the same feel at more of its strength`() {
        val s = listOf(Vibration.FAINT, Vibration.LIGHT, Vibration.MEDIUM, Vibration.FIRM, Vibration.STRONG)
        for (feel in Feel.entries) {
            val scales = s.map { v ->
                val b = buzzFor(feel, v, s25u) as Buzz.Primitives
                b.steps.map { it.scale }
            }
            for ((a, b) in scales.zipWithNext()) {
                assertTrue("$feel: $scales", a.zip(b).all { (x, y) -> x < y })
            }
            // The primitives themselves stay Strong's, only their strength changes.
            val prims = s.map { v -> (buzzFor(feel, v, s25u) as Buzz.Primitives).steps.map { it.primitive } }
            assertTrue(prims.all { it == prims.last() })
        }
        assertEquals(steps(Step(tick, 0.25f)), buzzFor(Feel.TICK, Vibration.FAINT, s25u))
        assertEquals(steps(Step(click, 0.5f)), buzzFor(Feel.FIRM, Vibration.MEDIUM, s25u))
    }

    @Test
    fun `an effect with amplitude control but no primitives is a pulse at the step's amplitude`() {
        val a = listOf(Vibration.FAINT, Vibration.LIGHT, Vibration.MEDIUM, Vibration.FIRM).map {
            (buzzFor(Feel.TICK, it, effectsOnly) as Buzz.Pulses).amplitude
        }
        assertEquals(a.sorted(), a)
        assertTrue(a.all { it in 1..255 } && a.distinct().size == 4)
    }

    @Test
    fun `without amplitude control the steps are gentler effects, a rung down each, as Niko's phone needs`() {
        // Niko's Android 13 phone: effects, no amplitude control. Every step is a distinct feel for the common tick.
        val niko = s25u.copy(sdk = 33, primitives = setOf(click, tick), amplitudeControl = false)
        val rungs = listOf(Vibration.FAINT, Vibration.MEDIUM, Vibration.FIRM, Vibration.STRONG).map { buzzFor(Feel.TICK, it, niko) }
        assertEquals(Buzz.Pulses(count = 1, length = 5), rungs[0])
        assertEquals(Buzz.Pulses(count = 1, length = 10), rungs[1])
        assertEquals(Buzz.Pulses(count = 1, length = 15), rungs[2])
        assertEquals(steps(Step(tick, 1f)), rungs[3])
        // A firm click comes down through the click and the tick before it reaches the pulses.
        val firm = listOf(Vibration.MEDIUM, Vibration.FIRM, Vibration.STRONG).map { buzzFor(Feel.FIRM, it, niko.copy(primitives = emptySet())) }
        assertEquals(listOf(
            Buzz.Predefined(VibrationEffect.EFFECT_TICK),
            Buzz.Predefined(VibrationEffect.EFFECT_CLICK),
            Buzz.Predefined(VibrationEffect.EFFECT_HEAVY_CLICK),
        ), firm)
        // The like's two buzzes keep their gap and shorten.
        val like = listOf(Vibration.FAINT, Vibration.MEDIUM, Vibration.FIRM).map { buzzFor(Feel.DOUBLE, it, niko) as Buzz.Pulses }
        assertTrue(like.all { it.count == 2 && it.gap == 60L })
        assertEquals(like.map { it.length }.sorted(), like.map { it.length })
        // No step of a phone that says it cannot play an effect asks for it.
        val none = niko.copy(primitives = emptySet(), effects = Feel.entries.flatMap { predefinedFor(it) }.associateWith { no })
        for (v in Vibration.entries) for (feel in Feel.entries) {
            val b = buzzFor(feel, v, none)
            assertFalse("$v $feel $b", b is Buzz.Predefined)
        }
    }

    @Test
    fun `a step never asks for more than the phone has, nor a primitive it lacks`() {
        for (caps in listOf(s25u, effectsOnly, s25u.copy(amplitudeControl = false), s25u.copy(primitives = setOf(click, tick), sdk = 30))) {
            for (v in Vibration.entries) for (feel in Feel.entries) {
                when (val b = buzzFor(feel, v, caps)) {
                    is Buzz.Primitives -> assertTrue("$v $feel", b.steps.all { it.primitive in caps.primitives && it.scale in 0f..1f })
                    is Buzz.Pulses -> assertTrue("$v $feel $b", b.amplitude == VibrationEffect.DEFAULT_AMPLITUDE || b.amplitude in 1..255)
                    else -> Unit
                }
            }
        }
        assertEquals(Buzz.None, buzzFor(Feel.TICK, Vibration.OFF, s25u))
    }

    @Test
    fun `the slider's text is short, in both languages, and the steps are told`() {
        val english = File("src/main/res/values/strings-ot.xml").readText()
        val french = File("src/main/res/values-fr/strings-ot.xml").readText()
        assertTrue("%1\$d of 5" in english)
        assertTrue("%1\$d sur 5" in french)
        assertFalse("vibration_light" in english || "vibration_light" in french)
    }
}
