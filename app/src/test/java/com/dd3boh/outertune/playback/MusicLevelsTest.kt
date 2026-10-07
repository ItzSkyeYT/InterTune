/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * The levels the player's living background moves to. The picture should pulse with the kick drum
 * and not with the singer, move as much for a quiet recording as for a loud one, and stay still in
 * silence.
 */
class MusicLevelsTest {

    private val rate = 48_000

    /** Every frame the analyser gave for [signal]. */
    private fun frames(signal: FloatArray, sampleRate: Int = rate): List<FloatArray> {
        val out = mutableListOf<FloatArray>()
        val analyser = LevelAnalyser(sampleRate) { _, levels -> out += levels.copyOf() }
        signal.forEach(analyser::sample)
        return out
    }

    private fun tone(hz: Double, seconds: Double, loud: Float = 0.5f, sampleRate: Int = rate) =
        FloatArray((seconds * sampleRate).toInt()) { loud * sin(2 * PI * hz * it / sampleRate).toFloat() }

    /** A kick drum of sorts: [hz] for 80 ms at every half second, or [every] so many seconds. */
    private fun kicks(seconds: Double, hz: Double = 55.0, loud: Float = 0.8f, every: Double = 0.5) =
        FloatArray((seconds * rate).toInt()) {
            val inBeat = it % (rate * every).toInt()
            if (inBeat < rate * 0.08) loud * sin(2 * PI * hz * inBeat / rate).toFloat() else 0f
        }

    private fun List<FloatArray>.settled() = drop(size / 2)
    private fun List<FloatArray>.mean(band: Int) = map { it[band] }.average().toFloat()

    /** How loud each range is against full scale, before anything is set against the song. */
    private fun raw(signal: FloatArray): FloatArray {
        val out = mutableListOf<FloatArray>()
        lateinit var analyser: LevelAnalyser
        analyser = LevelAnalyser(rate) { _, _ -> out += analyser.raw.copyOf() }
        signal.forEach(analyser::sample)
        val settled = out.settled()
        return FloatArray(MusicLevels.BANDS) { settled.mean(it) }
    }

    // The ranges are measured against themselves, so what leaks from one into an emptier one is
    // stretched up with it. These hold the leaks down.

    @Test
    fun `a bass note is bass`() {
        val (bass, mid, high) = raw(tone(60.0, 2.0)).toList()
        assertTrue("bass $bass of 0.35", bass > 0.25f)
        assertTrue("mid $mid against bass $bass", mid < bass * 0.25f)
        assertTrue("high $high against bass $bass", high < bass * 0.02f)
    }

    @Test
    fun `a note in the middle is the middle`() {
        val (bass, mid, high) = raw(tone(800.0, 2.0)).toList()
        assertTrue("mid $mid of 0.35", mid > 0.28f)
        assertTrue("bass $bass against mid $mid", bass < mid * 0.06f)
        assertTrue("high $high against mid $mid", high < mid * 0.2f)
    }

    @Test
    fun `a cymbal is the top`() {
        val (bass, mid, high) = raw(tone(9000.0, 2.0)).toList()
        assertTrue("high $high of 0.35", high > 0.2f)
        assertTrue("mid $mid against high $high", mid < high * 0.15f)
        assertTrue("bass $bass against high $high", bass < high * 0.01f)
    }

    @Test
    fun `a voice is not bass`() {
        // a sung note around 300 Hz
        val (bass, mid, _) = raw(tone(300.0, 2.0)).toList()
        assertTrue("bass $bass against mid $mid", bass < mid * 0.3f)
    }

    @Test
    fun `a range with next to nothing in it is not stretched to full`() {
        // kicks, and a hiss at the top a hundred times quieter, for long enough to forget an ordinary song
        val hiss = tone(9000.0, 30.0, loud = 0.008f)
        val mixed = kicks(30.0).mapIndexed { i, x -> x + hiss[i] }.toFloatArray()
        val high = frames(mixed).takeLast(200).map { it[MusicLevels.HIGH] }
        assertTrue("the top reads ${high.max()}", high.max() < 0.2f)
    }

    @Test
    fun `a kick drum stands out from the gap after it`() {
        val bass = frames(kicks(6.0)).settled().map { it[MusicLevels.BASS] }
        assertTrue("loudest ${bass.max()}", bass.max() > 0.85f)
        assertTrue("quietest ${bass.min()}", bass.min() < 0.05f)
        // most of the half second between two kicks is quiet
        assertTrue("share of quiet frames ${bass.count { it < 0.2f } / bass.size.toFloat()}", bass.count { it < 0.2f } > bass.size * 0.6)
    }

    @Test
    fun `a kick drum under a voice still stands out`() {
        val voice = tone(300.0, 6.0, loud = 0.4f)
        val mixed = kicks(6.0).mapIndexed { i, x -> (x + voice[i]) * 0.7f }.toFloatArray()
        val bass = frames(mixed).settled().map { it[MusicLevels.BASS] }.sorted()
        val quietHalf = bass.take(bass.size / 2).average()
        assertTrue("loudest ${bass.last()}", bass.last() > 0.85f)
        assertTrue("the gaps $quietHalf", quietHalf < 0.3)
    }

    @Test
    fun `silence is still`() {
        // the three ranges, nothing going on, and nothing building or dropping
        frames(FloatArray(rate * 2)).forEach { assertArrayEquals(FloatArray(MusicLevels.VALUES), it, 0f) }
        // and so is the hiss before a song: about -60 dB
        val hiss = FloatArray(rate * 2) { if (it % 2 == 0) 0.001f else -0.001f }
        frames(hiss).forEach { assertArrayEquals(FloatArray(MusicLevels.VALUES), it, 0f) }
    }

    @Test
    fun `a quiet recording moves as much as a loud one, after a while`() {
        val quiet = frames(kicks(40.0, loud = 0.03f)).map { it[MusicLevels.BASS] }
        val early = quiet.take(quiet.size / 8).max()
        val late = quiet.takeLast(quiet.size / 4).max()
        assertTrue("at first it is small against an ordinary song: $early", early < 0.4f)
        assertTrue("then it is measured against itself: $late", late > 0.85f)
    }

    @Test
    fun `the loudest moment stays the yardstick for a while`() {
        val loudThenSoft = tone(60.0, 1.0, loud = 0.9f) + tone(60.0, 1.0, loud = 0.3f)
        val bass = frames(loudThenSoft).map { it[MusicLevels.BASS] }
        val soft = bass.takeLast(bass.size / 4).average()
        assertTrue("a third as loud reads as about a third: $soft", soft > 0.25 && soft < 0.5)
    }

    // How much is going on. A quiet passage is measured against itself like any other, so by its
    // levels alone it moves the picture as much as the loudest part of the song does.

    private fun List<FloatArray>.presence() = map { it[MusicLevels.PRESENCE] }

    @Test
    fun `a passage as loud as the song gets is all there`() {
        val there = frames(kicks(6.0)).settled().presence()
        assertTrue("${there.min()}", there.min() > 0.9f)
        assertTrue(frames(tone(220.0, 3.0)).settled().presence().min() > 0.9f)
    }

    @Test
    fun `a quiet passage after a loud one is hardly there, though its own levels have come back up`() {
        val all = frames(tone(220.0, 12.0, loud = 0.5f) + tone(220.0, 12.0, loud = 0.08f))
        val quiet = all.takeLast(100)
        assertTrue("set against the last few seconds, its level is most of the way back: ${quiet.mean(MusicLevels.MID)}", quiet.mean(MusicLevels.MID) > 0.6f)
        assertTrue("but next to what the song was, not much is going on: ${quiet.presence().max()}", quiet.presence().max() < 0.2f)
        assertTrue("it is never nothing while there is sound", quiet.presence().min() >= MusicLevels.LEAST_PRESENCE)
    }

    @Test
    fun `the first loud note after a quiet passage is there at once`() {
        val all = frames(tone(220.0, 10.0, loud = 0.5f) + tone(220.0, 10.0, loud = 0.06f) + tone(220.0, 0.2, loud = 0.5f))
        assertTrue("the last quiet frame: ${all[all.size - 11][MusicLevels.PRESENCE]}", all[all.size - 11][MusicLevels.PRESENCE] < 0.2f)
        assertTrue("and the loud ones after it: ${all.takeLast(9).presence()}", all.takeLast(9).presence().all { it > 0.9f })
    }

    @Test
    fun `a loud passage lets go over a second or so, not at its last note`() {
        val all = frames(tone(220.0, 6.0, loud = 0.5f) + tone(220.0, 4.0, loud = 0.05f)).presence()
        val end = 6 * 50
        assertTrue("a fifth of a second after: ${all[end + 10]}", all[end + 10] > 0.8f)
        assertTrue("three seconds after: ${all[end + 150]}", all[end + 150] < 0.25f)
    }

    @Test
    fun `a recording that is quiet all the way through comes to count in full`() {
        val all = frames(tone(220.0, 150.0, loud = 0.03f)).presence()
        assertTrue("at first it is set against an ordinary song: ${all[50]}", all[50] < 0.5f)
        assertTrue("two minutes on it is its own measure: ${all.last()}", all.last() > 0.9f)
    }

    @Test
    fun `in silence nothing is going on, and how much is runs from hardly to fully`() {
        assertTrue(frames(FloatArray(rate)).presence().all { it == 0f })
        assertEquals(MusicLevels.LEAST_PRESENCE, MusicLevels.presence(0.05f, 1f), 0f)
        assertEquals(MusicLevels.LEAST_PRESENCE, MusicLevels.presence(MusicLevels.HARDLY, 1f), 0.001f)
        assertEquals(1f, MusicLevels.presence(MusicLevels.FULLY, 1f), 0.001f)
        assertEquals(1f, MusicLevels.presence(3f, 1f), 0f)
        assertEquals("the same share of a quieter song", MusicLevels.presence(0.4f, 1f), MusicLevels.presence(0.04f, 0.1f), 0.001f)
        val rising = (0..20).map { MusicLevels.presence(it / 20f, 1f) }
        assertEquals(rising.sorted(), rising)
        assertEquals("nothing to set it against", 0f, MusicLevels.presence(0.5f, 0f), 0f)
    }

    // The build-up and the drop. In the music this is made for the bass leaves for the build-up,
    // and the drop is the bass back at once with the whole thing at the loudest the song gets.

    /** The line in the middle that never stops: alone, it is an intro, a breakdown or a build-up. */
    private fun line(seconds: Double, by: Float = 1f) = tone(700.0, seconds, loud = 0.25f * by)

    /** A section at full tilt: the kick under that line. */
    private fun full(seconds: Double, by: Float = 1f) = mixed(kicks(seconds, loud = 0.8f * by), line(seconds, by))

    private fun mixed(a: FloatArray, b: FloatArray) = FloatArray(maxOf(a.size, b.size)) { a.getOrElse(it) { 0f } + b.getOrElse(it) { 0f } }

    private fun List<FloatArray>.tensions() = map { it[MusicLevels.TENSION] }
    private fun List<FloatArray>.dropping() = map { it[MusicLevels.DROP] }
    private fun List<FloatArray>.driven() = map { it[MusicLevels.DRIVE] }

    /** The frames at which a drop is found: where [MusicLevels.DROP] comes up from nothing. */
    private fun List<FloatArray>.drops() = indices.filter { this[it][MusicLevels.DROP] > 0f && (it == 0 || this[it - 1][MusicLevels.DROP] == 0f) }

    /** The frame that begins [seconds] in. */
    private fun frameAt(seconds: Double) = Math.round(seconds / MusicLevels.FRAME_SECONDS).toInt()

    @Test
    fun `a beat that never stops builds nothing and drops nothing`() {
        for (signal in listOf(kicks(20.0), full(20.0))) {
            val all = frames(signal)
            assertTrue("tension ${all.tensions().max()}", all.tensions().all { it == 0f })
            assertTrue("drops at ${all.drops()}", all.drops().isEmpty())
            assertTrue("drive ${all.driven().max()}", all.driven().all { it == 0f })
        }
    }

    @Test
    fun `an intro without bass that kicks in is a drop, found on its first kick, and what follows is driven`() {
        val all = frames(line(10.0) + full(10.0))
        val kick = frameAt(10.0)
        val tension = all.take(kick).tensions()
        assertEquals("the first seconds are not yet a build-up", 0f, tension[frameAt(1.5)], 0f)
        assertTrue("then it only grows", tension.zipWithNext().all { (a, b) -> b >= a })
        assertTrue("and is nearly all there by the end: ${tension.last()}", tension.last() > 0.95f)

        assertEquals("one drop: ${all.drops()}", 1, all.drops().size)
        val found = all.drops().single()
        assertTrue("found ${(found - kick + 1) * 20} ms after the kick began", found - kick in 0..5)
        assertTrue("ten seconds of waiting make it a strong one: ${all[found][MusicLevels.DROP]}", all[found][MusicLevels.DROP] > 0.9f)
        assertEquals("it is a moment and passes", 0f, all[found + 50][MusicLevels.DROP], 0f)
        assertEquals("the tension is spent on it", 0f, all[found][MusicLevels.TENSION], 0f)
        assertTrue("no drive before it", all.take(found).driven().all { it == 0f })
        assertTrue("and from it on a drive that holds: ${all.drop(found).driven().min()}", all.drop(found).driven().min() > 0.9f)
    }

    @Test
    fun `the bass leaves, tension grows, and the longer it grew the stronger the drop`() {
        val all = frames(full(8.0) + line(12.0) + full(8.0))
        val left = frameAt(8.0)
        val back = frameAt(20.0)
        assertTrue("none while the bass is there: ${all.take(left).tensions().max()}", all.take(left).tensions().all { it == 0f })
        val tension = all.subList(left, back).tensions()
        assertEquals("a second without bass is a gap between kicks, not a build-up", 0f, tension[frameAt(1.0)], 0f)
        assertTrue("it only grows", tension.zipWithNext().all { (a, b) -> b >= a })
        assertTrue("half way after six seconds: ${tension[frameAt(6.0)]}", tension[frameAt(6.0)] in 0.4f..0.7f)
        assertEquals("all the way by the end", 1f, tension.last(), 0.001f)

        val found = all.drops().single()
        assertTrue("found ${(found - back + 1) * 20} ms after the kick came back", found - back in 0..5)
        assertEquals("as strong as a drop gets", 1f, all[found][MusicLevels.DROP], 0.01f)
        assertTrue("driven from there on: ${all.drop(found).driven().min()}", all.drop(found).driven().min() > 0.9f)
        assertTrue("and no tension left", all.drop(found).tensions().all { it == 0f })

        val short = frames(full(8.0) + line(4.0) + full(8.0))
        val sooner = short.drops().single()
        assertTrue("frame $sooner", sooner - frameAt(12.0) in 0..5)
        assertTrue("four seconds without bass: a drop, a smaller one: ${short[sooner][MusicLevels.DROP]}",
            short[sooner][MusicLevels.DROP] in MusicLevels.LEAST_DROP..0.75f)
    }

    @Test
    fun `kicks a second apart are a slow song, not a build-up`() {
        val all = frames(mixed(kicks(30.0, every = 1.0), line(30.0)))
        assertTrue("tension ${all.tensions().max()}", all.tensions().all { it == 0f })
        assertTrue("drops at ${all.drops()}", all.drops().isEmpty())
        assertTrue(all.driven().all { it == 0f })
    }

    @Test
    fun `one bass note in the breakdown is not the drop`() {
        // a note a third as loud as the kick, three seconds into ten without it
        val note = FloatArray(3 * rate) + tone(55.0, 0.3, loud = 0.25f)
        val all = frames(full(8.0) + mixed(line(10.0), note) + full(6.0))
        val back = frameAt(18.0)
        val found = all.drops().single()
        assertTrue("the only drop is where the kick comes back, frame $back: $found", found - back in 0..5)
        assertTrue("nothing is driven before it", all.take(back).driven().all { it == 0f })
        assertTrue("and it counts for what built up after the note: ${all[found][MusicLevels.DROP]}", all[found][MusicLevels.DROP] > 0.6f)
    }

    @Test
    fun `a song that fades out builds nothing and drops nothing`() {
        val fading = full(15.0).let { whole -> FloatArray(whole.size) { whole[it] * (1f - it.toFloat() / whole.size) } }
        val all = frames(full(10.0) + fading + FloatArray(4 * rate))
        assertTrue("tension ${all.tensions().max()}", all.tensions().all { it == 0f })
        assertTrue("drops at ${all.drops()}", all.drops().isEmpty())
        assertTrue(all.driven().all { it == 0f })
    }

    @Test
    fun `silence builds nothing, and a beat of it before the drop spends nothing`() {
        val built = frames(full(8.0) + line(8.0)).last()[MusicLevels.TENSION]
        assertTrue("$built", built > 0.7f)
        val held = frames(full(8.0) + line(8.0) + FloatArray(rate / 2) + full(4.0))
        val back = frameAt(16.5)
        assertEquals("held through the beat of silence", built, held[back - 1][MusicLevels.TENSION], 0.01f)
        val found = held.drops().single()
        assertTrue("frame $found", found - back in 0..5)
        assertTrue("${held[found][MusicLevels.DROP]}", held[found][MusicLevels.DROP] > 0.8f)

        // silence long enough to be the end of something is the end of what was building
        val over = frames(full(8.0) + line(8.0) + FloatArray(5 * rate) + full(4.0))
        assertEquals(0f, over[frameAt(20.9)][MusicLevels.TENSION], 0f)
        assertTrue("drops at ${over.drops()}", over.drops().isEmpty())
    }

    @Test
    fun `the drive lets go when the section falls away`() {
        val drive = frames(line(6.0) + full(8.0) + line(8.0)).driven()
        val ends = frameAt(14.0)
        assertTrue("held to the last kick: ${drive[ends - 1]}", drive[ends - 1] > 0.6f)
        assertEquals("and a moment longer", drive[ends - 1], drive[ends + 15], 0f)
        assertEquals("four seconds without the bass and it has gone", 0f, drive[ends + 200], 0f)
        assertTrue("it goes down, not out", drive.subList(ends, ends + 200).zipWithNext().all { (a, b) -> b <= a && a - b < 0.05f })
    }

    @Test
    fun `a drive does not last for ever`() {
        val drive = frames(line(6.0) + full(100.0)).driven()
        val dropped = frameAt(6.0)
        val whole = drive[dropped + 5]
        assertTrue("$whole", whole > 0.6f)
        assertEquals("a minute after the drop it is all there", whole, drive[dropped + frameAt(59.0)], 0f)
        assertEquals("a quarter of a minute later half of it is", whole / 2, drive[dropped + frameAt(75.0)], 0.02f)
        assertEquals("and half a minute later none, though the beat goes on", 0f, drive[dropped + frameAt(92.0)], 0f)
        assertTrue("nothing drops for that", frames(line(6.0) + full(100.0)).drops().size == 1)
    }

    @Test
    fun `a murmur of bass now and then in the intro takes the tension but not the drop`() {
        // a quiet line, and under it every second and a bit a moment of something low. Each of
        // them is bass while it lasts, and none is the bass the song is about to have.
        val murmur = FloatArray(12 * rate) {
            val at = it % (rate * 1.3).toInt()
            if (at < rate * 0.04) 0.03f * sin(2 * PI * 55.0 * at / rate).toFloat() else 0f
        }
        val all = frames(mixed(tone(700.0, 12.0, loud = 0.03f), murmur) + full(6.0))
        val kick = frameAt(12.0)
        assertTrue("the murmur keeps the tension down: ${all.take(kick).tensions().max()}", all.take(kick).tensions().max() < 0.3f)
        assertEquals("and is no drop itself: ${all.drops()}", 1, all.drops().size)
        val found = all.drops().single()
        assertTrue("frame $found for $kick", found - kick in 0..5)
        assertTrue("the bass that comes towers over it, and the drop is a strong one: ${all[found][MusicLevels.DROP]}", all[found][MusicLevels.DROP] > 0.9f)
    }

    @Test
    fun `a fill just before the drop does not take the drop away`() {
        // half a second before the kick comes back, 40 ms of something low and soft
        val fill = FloatArray((7.5 * rate).toInt()) + tone(55.0, 0.04, loud = 0.12f)
        val all = frames(full(8.0) + mixed(line(8.0), fill) + full(6.0))
        val back = frameAt(16.0)
        val found = all.drops().single()
        assertTrue("frame $found for $back", found - back in 0..5)
        assertTrue("${all[found][MusicLevels.DROP]}", all[found][MusicLevels.DROP] > 0.8f)
    }

    @Test
    fun `a smaller drop inside a driven part is a burst and leaves the drive as it is`() {
        // twelve seconds of intro, the drop, and twenty seconds on a break of a bar
        val all = frames(line(12.0) + full(20.0) + line(2.5) + full(10.0))
        assertEquals("${all.drops()}", 2, all.drops().size)
        val (first, second) = all.drops()
        assertTrue("${all[first][MusicLevels.DROP]} and ${all[second][MusicLevels.DROP]}", all[second][MusicLevels.DROP] < all[first][MusicLevels.DROP] - 0.3f)
        assertEquals("driven as it was", all[first + 5][MusicLevels.DRIVE], all[second + 50][MusicLevels.DRIVE], 0.01f)
    }

    /** A bass note [note] seconds long every [every] seconds under the line: the low end of a slow song. */
    private fun slowBass(seconds: Double, every: Double, note: Double = 0.3, loud: Float = 0.6f, under: Float = 0f) = mixed(
        FloatArray((seconds * rate).toInt()) {
            val at = it % (rate * every).toInt()
            (if (at < rate * note) loud else under) * sin(2 * PI * 55.0 * at / rate).toFloat()
        },
        line(seconds),
    )

    @Test
    fun `a bass that comes once a bar in a slow song is not a drop at every note`() {
        // more than two seconds from the end of one note to the start of the next, for two minutes
        for (every in listOf(2.5, 4.0)) {
            val all = frames(slowBass(120.0, every))
            assertTrue("a note every $every s: drops at ${all.drops().map { it / 50 }}", all.drops().isEmpty())
            assertTrue("and nothing is driven", all.driven().all { it == 0f })
        }
    }

    @Test
    fun `nor is a big note now and then over a murmur of bass, after the first`() {
        // the first one is the bass arriving, which is a drop; the ones after it are how the song is
        val all = frames(mixed(slowBass(120.0, 8.0, under = 0.05f), FloatArray(0)).let { song -> FloatArray(song.size) { song[it] * 0.8f } })
        assertTrue("drops at ${all.drops().map { it / 50 }}", all.drops().size <= 1)
        val stray = frames(slowBass(120.0, 20.0))
        assertTrue("one low note every twenty seconds: drops at ${stray.drops().map { it / 50 }}", stray.drops().size <= 1)
    }

    @Test
    fun `bass that had stayed and comes back after a break is a drop every time`() {
        // three parts of the song with its kick, two breaks: a drop after each break, as before
        val all = frames(full(10.0) + line(6.0) + full(10.0) + line(6.0) + full(6.0))
        assertEquals("drops at ${all.drops().map { it / 50 }}", 2, all.drops().size)
        assertTrue(all.drops()[0] - frameAt(16.0) in 0..5 && all.drops()[1] - frameAt(32.0) in 0..5)
    }

    @Test
    fun `a seek starts over`() {
        val out = mutableListOf<FloatArray>()
        val analyser = LevelAnalyser(rate) { _, levels -> out += levels.copyOf() }
        (full(6.0) + line(8.0)).forEach(analyser::sample)
        assertTrue("well into a build-up: ${out.last()[MusicLevels.TENSION]}", out.last()[MusicLevels.TENSION] > 0.5f)
        out.clear()
        analyser.jump()
        full(6.0).forEach(analyser::sample)
        assertTrue("what was building is gone", out.tensions().all { it == 0f })
        assertTrue("and the kick it lands on is no drop: ${out.dropping().max()}", out.dropping().all { it == 0f })
        assertTrue(out.driven().all { it == 0f })

        // and out of a driven part, the drive does not come along
        (line(6.0) + full(4.0)).forEach(analyser::sample)
        assertTrue("${out.last()[MusicLevels.DRIVE]}", out.last()[MusicLevels.DRIVE] > 0.6f)
        out.clear()
        analyser.jump()
        line(2.0).forEach(analyser::sample)
        assertTrue(out.driven().all { it == 0f })
    }

    @Test
    fun `a quiet recording builds and drops as a loud one does`() {
        fun song(by: Float) = frames(line(6.0, by) + full(8.0, by) + line(12.0, by) + full(6.0, by))
        val loud = song(1f)
        val quiet = song(0.1f)
        assertEquals("the intro's drop and the one after the break: ${loud.drops()}", 2, loud.drops().size)
        assertEquals(loud.drops(), quiet.drops())
        for (value in listOf(MusicLevels.TENSION, MusicLevels.DROP, MusicLevels.DRIVE)) {
            for (i in loud.indices) assertEquals("value $value, frame $i", loud[i][value], quiet[i][value], 0.02f)
        }
    }

    // Nothing is measured while nobody looks, and what was remembered must not stay as it was.

    /** The frames of two seconds of quiet kicks, heard [idle] seconds after ten of loud ones. */
    private fun quietAfterLoud(idle: Double): List<FloatArray> {
        val out = mutableListOf<FloatArray>()
        val analyser = LevelAnalyser(rate) { _, levels -> out += levels.copyOf() }
        kicks(10.0, loud = 0.9f).forEach(analyser::sample)
        out.clear()
        analyser.aged(idle)
        kicks(2.0, loud = 0.05f).forEach(analyser::sample)
        return out
    }

    @Test
    fun `what is remembered ages by the time nothing was measured`() {
        val atOnce = quietAfterLoud(0.0)
        assertTrue("straight after the loud one it is small against it: ${atOnce.maxOf { it[MusicLevels.BASS] }}", atOnce.maxOf { it[MusicLevels.BASS] } < 0.15f)
        assertTrue("and, once the loud one has rung out, hardly there: ${atOnce.takeLast(10).presence().max()}", atOnce.takeLast(10).presence().max() < 0.2f)

        val hoursOn = quietAfterLoud(3 * 3600.0)
        assertTrue("hours later it is its own measure from its first kick: ${hoursOn.take(10).maxOf { it[MusicLevels.BASS] }}", hoursOn.take(10).maxOf { it[MusicLevels.BASS] } > 0.85f)
        assertTrue("and all there: ${hoursOn.take(10).presence().max()}", hoursOn.take(10).presence().max() > 0.9f)

        // eight seconds unmeasured count as eight seconds of silence would have
        val silent = mutableListOf<FloatArray>()
        LevelAnalyser(rate) { _, levels -> silent += levels.copyOf() }.let { (kicks(10.0, loud = 0.9f) + FloatArray(8 * rate) + kicks(2.0, loud = 0.05f)).forEach(it::sample) }
        assertEquals(silent.takeLast(100).maxOf { it[MusicLevels.BASS] }, quietAfterLoud(8.0).maxOf { it[MusicLevels.BASS] }, 0.02f)
    }

    @Test
    fun `whatever was building when the measuring stopped is not carried over the gap`() {
        val out = mutableListOf<FloatArray>()
        val analyser = LevelAnalyser(rate) { _, levels -> out += levels.copyOf() }
        (full(6.0) + line(8.0)).forEach(analyser::sample)
        val built = out.last()[MusicLevels.TENSION]
        out.clear()
        analyser.aged(2.0)
        line(0.5).forEach(analyser::sample)
        assertTrue("a pause of two seconds is not the end of a build-up: ${out.last()[MusicLevels.TENSION]} after $built", out.last()[MusicLevels.TENSION] >= built)
        out.clear()
        analyser.aged(60.0)
        full(3.0).forEach(analyser::sample)
        assertTrue("a minute is", out.tensions().all { it == 0f })
        assertTrue("${out.dropping().max()}", out.dropping().all { it == 0f })
    }

    @Test
    fun `an intro louder than the drums that come in under it still drops`() {
        // a melody alone and at full blast, then the kick under it at half the level, which is
        // less than the melody was, frame for frame
        val all = frames(tone(700.0, 8.0, loud = 0.9f) + mixed(kicks(8.0, loud = 0.5f), tone(700.0, 8.0, loud = 0.3f)))
        val kick = frameAt(8.0)
        val found = all.drops().single()
        assertTrue("frame $found for $kick", found - kick in 0..5)
        assertTrue("${all[found][MusicLevels.DROP]}", all[found][MusicLevels.DROP] > 0.8f)
    }

    @Test
    fun `a frame is 20 ms whatever the sample rate`() {
        for (sampleRate in listOf(44_100, 48_000, 96_000)) {
            val ends = mutableListOf<Long>()
            val analyser = LevelAnalyser(sampleRate) { end, _ -> ends += end }
            repeat(sampleRate) { analyser.sample(0f) }
            assertEquals("$sampleRate", 50, ends.size)
            assertEquals("$sampleRate", sampleRate / 50L, ends.first())
            assertEquals("$sampleRate", sampleRate.toLong(), ends.last())
        }
    }

    @Test
    fun `16 bit and float samples read the same, and both channels count`() {
        val mono = kicks(3.0)
        val want = frames(mono)

        val as16 = ByteBuffer.allocate(mono.size * 4).order(ByteOrder.nativeOrder())
        mono.forEach { x -> repeat(2) { as16.putShort((x * 32767).toInt().toShort()) } }
        as16.flip()
        val got16 = mutableListOf<FloatArray>()
        LevelAnalyser(rate) { _, levels -> got16 += levels.copyOf() }.pcm16(as16, channels = 2)

        val asFloat = ByteBuffer.allocate(mono.size * 8).order(ByteOrder.nativeOrder())
        mono.forEach { x -> repeat(2) { asFloat.putFloat(x) } }
        asFloat.flip()
        val gotFloat = mutableListOf<FloatArray>()
        LevelAnalyser(rate) { _, levels -> gotFloat += levels.copyOf() }.pcmFloat(asFloat, channels = 2)

        assertEquals(want.size, got16.size)
        assertEquals(want.size, gotFloat.size)
        want.indices.forEach {
            assertArrayEquals("frame $it, 16 bit", want[it], got16[it], 0.01f)
            assertArrayEquals("frame $it, float", want[it], gotFloat[it], 0.0001f)
        }
        assertEquals("the buffer is the player's, it must be left alone", 0, as16.position())
        assertEquals(mono.size * 4, as16.limit())
        assertEquals(0, asFloat.position())
    }

    @Test
    fun `only what lies between position and limit is read`() {
        val buffer = ByteBuffer.allocate(4000).order(ByteOrder.nativeOrder())
        repeat(2000) { buffer.putShort(20000) }
        buffer.position(1000).limit(1000 + 960 * 2)        // exactly one frame of one channel
        var count = 0
        LevelAnalyser(rate) { _, _ -> count++ }.pcm16(buffer, channels = 1)
        assertEquals(1, count)
    }

    @Test
    fun `a sample that is not a number spoils its own frame and nothing after`() {
        val signal = kicks(4.0)
        signal[rate + 123] = Float.NaN
        signal[rate * 2 + 7] = Float.POSITIVE_INFINITY
        val heard = frames(signal)
        assertTrue("every level is a number between 0 and 1", heard.all { f -> f.all { it in 0f..1f } })
        assertTrue("and the kicks after it still show: ${heard.takeLast(60).maxOf { it[MusicLevels.BASS] }}", heard.takeLast(60).maxOf { it[MusicLevels.BASS] } > 0.85f)
    }

    @Test
    fun `a jump drops the half measured frame`() {
        val ends = mutableListOf<Long>()
        val analyser = LevelAnalyser(rate) { end, _ -> ends += end }
        repeat(500) { analyser.sample(0.5f) }
        analyser.jump()
        repeat(960) { analyser.sample(0.5f) }
        assertEquals("one whole frame after the jump, not one ending 460 samples in", listOf(1460L), ends)
    }

    // The timeline

    private fun levels(x: Float) = floatArrayOf(x, x / 2, x / 4)

    @Test
    fun `a level is read by the time it is heard`() {
        val timeline = LevelTimeline()
        for (i in 0 until 100) timeline.add(1_000_000L + i * 20_000L, levels(i / 100f))
        val got = FloatArray(3)
        assertTrue(timeline.read(1_000_000L + 40 * 20_000L, got))
        assertArrayEquals(levels(0.40f), got, 0f)
        // between two frames it is the one already heard, not the one to come
        assertTrue(timeline.read(1_000_000L + 40 * 20_000L + 19_999L, got))
        assertArrayEquals(levels(0.40f), got, 0f)
    }

    @Test
    fun `how much is going on is kept with the levels, and three levels alone count as all there`() {
        val timeline = LevelTimeline()
        timeline.add(1_000_000, floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f))
        timeline.add(1_020_000, floatArrayOf(0.5f, 0.6f, 0.7f))
        val four = FloatArray(MusicLevels.PRESENCE + 1)
        assertTrue(timeline.read(1_005_000, four))
        assertEquals(listOf(0.1f, 0.2f, 0.3f, 0.4f), four.toList())
        assertTrue(timeline.read(1_025_000, four))
        assertEquals(listOf(0.5f, 0.6f, 0.7f, 1f), four.toList())
        val three = FloatArray(MusicLevels.BANDS)
        assertTrue("whoever asks for the three ranges alone gets those", timeline.read(1_005_000, three))
        assertEquals(listOf(0.1f, 0.2f, 0.3f), three.toList())
    }

    @Test
    fun `what the song is doing is kept with the levels, and levels that do not say are building nothing`() {
        val timeline = LevelTimeline(capacity = 3)
        timeline.add(1_000_000, floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f))
        timeline.add(1_020_000, floatArrayOf(0.5f, 0.6f, 0.7f, 0.8f))
        timeline.add(1_040_000, floatArrayOf(0.5f, 0.6f, 0.7f))
        val all = FloatArray(MusicLevels.VALUES)
        assertTrue(timeline.read(1_005_000, all))
        assertEquals(listOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f), all.toList())
        assertTrue(timeline.read(1_025_000, all))
        assertEquals("four values: no tension, no drop, no drive", listOf(0.5f, 0.6f, 0.7f, 0.8f, 0f, 0f, 0f), all.toList())
        assertTrue(timeline.read(1_045_000, all))
        assertEquals("three: all there, and the same", listOf(0.5f, 0.6f, 0.7f, 1f, 0f, 0f, 0f), all.toList())
        // a place in the timeline used again keeps nothing of what was in it
        timeline.add(1_060_000, floatArrayOf(0.9f, 0.9f, 0.9f))
        assertTrue(timeline.read(1_065_000, all))
        assertEquals(listOf(0.9f, 0.9f, 0.9f, 1f, 0f, 0f, 0f), all.toList())
        val four = FloatArray(MusicLevels.PRESENCE + 1)
        assertTrue("whoever asks for four values gets the four it always got", timeline.read(1_025_000, four))
        assertEquals(listOf(0.5f, 0.6f, 0.7f, 0.8f), four.toList())
    }

    @Test
    fun `nothing is known before the first level or long after the last`() {
        val timeline = LevelTimeline()
        val got = FloatArray(3)
        assertFalse("empty", timeline.read(0, got))
        for (i in 0 until 10) timeline.add(1_000_000L + i * 20_000L, levels(0.5f))
        assertFalse("before the first", timeline.read(999_999L, got))
        assertTrue("just after the last", timeline.read(1_180_000L + 100_000L, got))
        assertFalse("the player ran dry: the last level is not held for ever", timeline.read(1_180_000L + 300_000L, got))
    }

    @Test
    fun `only the last few seconds are kept`() {
        val timeline = LevelTimeline(capacity = 50)
        for (i in 0 until 500) timeline.add(i * 20_000L, levels(i / 500f))
        val got = FloatArray(3)
        assertTrue(timeline.read(499 * 20_000L, got))
        assertArrayEquals(levels(499 / 500f), got, 0f)
        assertTrue(timeline.read(450 * 20_000L, got))
        assertArrayEquals(levels(450 / 500f), got, 0f)
        assertFalse("long gone", timeline.read(449 * 20_000L, got))
    }

    @Test
    fun `after a seek the old levels are gone`() {
        val timeline = LevelTimeline()
        for (i in 0 until 10) timeline.add(60_000_000L + i * 20_000L, levels(0.9f))
        timeline.clear()
        val got = FloatArray(3)
        assertFalse(timeline.read(60_100_000L, got))
        timeline.add(5_000_000L, levels(0.1f))
        assertTrue(timeline.read(5_010_000L, got))
        assertArrayEquals(levels(0.1f), got, 0f)
    }
}
