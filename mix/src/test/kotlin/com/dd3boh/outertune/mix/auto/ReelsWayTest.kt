/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.auto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reels' way decides as the prototype's round 3 did on its 30 pairs (the party set p01 to p21,
 * r1 and h1, and the seven regression pairs reg1 to reg7): the fixtures in resources/reelsway are
 * the inputs decide.py read and the decisions it wrote, numbers and song ids only.
 */
class ReelsWayTest {

    @Test
    fun frozenDecisions() {
        val failed = ArrayList<String>()
        for ((n, a, b) in ReelsFixtures.pairs()) {
            val d = ReelsWay.decide(ReelsFixtures.song(a), ReelsFixtures.song(b))
            val diffs = jsonDiffs(ReelsFixtures.decision(n), d.toJson())
            println("$n $a -> $b: ${d.move.label}, A ${d.exitBar}, B ${d.entryBarB}" + (if (diffs.isEmpty()) ", as frozen" else ", ${diffs.size} differences"))
            if (diffs.isNotEmpty()) failed += "$n: " + diffs.take(8).joinToString("; ")
        }
        assertTrue(failed.joinToString("\n"), failed.isEmpty())
    }

    /** The late entry picked on 6 Nov 2026: with the rule on, h1 becomes h2 and nothing else moves. */
    @Test
    fun lateEntryWhenBuried() {
        val rules = ReelsRules(lateEntryWhenBuried = true)
        val failed = ArrayList<String>()
        var h1: ReelsDecision? = null
        for ((n, a, b) in ReelsFixtures.pairs()) {
            val d = ReelsWay.decide(ReelsFixtures.song(a), ReelsFixtures.song(b), rules)
            val expected = ReelsFixtures.decision(if (n == "h1") "h2" else n)
            val diffs = jsonDiffs(expected, d.toJson(), tolerance = if (n == "h1") H2_HAND_EDIT else emptyMap())
            d.heardDiffDb?.let { println("$n ${d.shape?.label} heard %+.2f dB%s".format(it, if (d.lateEntry) ", late entry" else "")) }
            if (diffs.isNotEmpty()) failed += "$n: " + diffs.take(8).joinToString("; ")
            if (n == "h1") h1 = d
        }
        assertTrue(failed.joinToString("\n"), failed.isEmpty())
        val d = h1!!
        assertTrue(d.lateEntry)
        assertEquals(-8.67, d.heardDiffDb!!, 0.01)
        assertEquals(3, d.leadOffsetBars)
        assertEquals(11, d.entryBarB)
        assertEquals(16, d.dropBar)
        assertEquals(false, d.echo)
    }

    /** B's lead-in and the echo are open choices: set by hand, they give h2 as well. */
    @Test
    fun leadInAndEchoByHand() {
        val d = ReelsWay.decide(ReelsFixtures.song("saxobeat"), ReelsFixtures.song("onemoretime"), ReelsRules(leadOffsetBars = 3, echo = false))
        val h2 = ReelsFixtures.decision("h2")
        for (key in listOf("move", "exitBar", "exitT", "boxStartBarA", "dropBar", "entryBarB", "entryT", "leadOffsetBars", "shape", "swap", "echo", "ratio")) {
            val diffs = jsonDiffs(h2[key] ?: JsonNull, d.toJson()[key] ?: JsonNull, key, H2_HAND_EDIT)
            assertTrue(diffs.joinToString(), diffs.isEmpty())
        }
        // Earlier than the decision's own entry is not a choice: B never enters before it.
        val frozen = ReelsWay.decide(ReelsFixtures.song("saxobeat"), ReelsFixtures.song("onemoretime"))
        val early = ReelsWay.decide(ReelsFixtures.song("saxobeat"), ReelsFixtures.song("onemoretime"), ReelsRules(leadOffsetBars = 0))
        assertEquals(frozen.entryBarB, early.entryBarB)
    }

    @Test
    fun pythonRounding() {
        assertEquals(2, Py.round(2.5))
        assertEquals(4, Py.round(3.5))
        assertEquals(-2, Py.round(-2.5))
        assertEquals(0.12, Py.round(0.125, 2), 0.0)
        assertEquals(2.67, Py.round(2.675, 2), 0.0)
        assertEquals(0.95, Py.round(0.95, 2), 0.0)
        assertEquals(2.5, Py.median(doubleArrayOf(4.0, 1.0, 3.0, 2.0)), 0.0)
        // np.percentile's own values: from the lower side under t = 0.5, from the upper side above it
        assertEquals(5.200000000000001, Py.percentile(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 10.0), 80.0), 0.0)
        assertEquals(7.6000000000000005, Py.percentile(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 10.0), 90.0), 0.0)
    }

    companion object {
        /**
         * h2 was edited by hand from h1, its entryT worked out as t0 + (4 * 11 + phase) * 60 / bpm:
         * 22.587591561721297, where B's bar 11 in the analysis, the time decide.py gives every entry,
         * is t0 + 46 * (60 / bpm) = 22.587591561721293. The port gives the bar's time.
         */
        val H2_HAND_EDIT = mapOf("entryT" to 1e-9)

        /**
         * Where [actual] differs from [expected], on every key [expected] has (but "n"); numbers
         * compared exactly, but for the keys in [tolerance].
         */
        fun jsonDiffs(expected: JsonElement, actual: JsonElement, path: String = "", tolerance: Map<String, Double> = emptyMap()): List<String> {
            val out = ArrayList<String>()
            fun walk(e: JsonElement, a: JsonElement, p: String) {
                when {
                    e is JsonObject -> {
                        if (a !is JsonObject) { out += "$p: expected an object, got $a"; return }
                        for ((k, v) in e) if (k != "n") walk(v, a[k] ?: JsonNull, if (p.isEmpty()) k else "$p.$k")
                    }
                    e is JsonArray -> {
                        if (a !is JsonArray || a.size != e.size) { out += "$p: expected $e, got $a"; return }
                        for (i in e.indices) walk(e[i], a[i], "$p[$i]")
                    }
                    e is JsonNull -> if (a !is JsonNull) out += "$p: expected null, got $a"
                    else -> {
                        val ep = e.jsonPrimitive
                        if (a !is JsonPrimitive || a is JsonNull) { out += "$p: expected $e, got $a"; return }
                        val same = when {
                            ep.isString -> a.isString && a.content == ep.content
                            ep.booleanOrNull != null -> a.booleanOrNull == ep.booleanOrNull
                            else -> {
                                val ev = ep.doubleOrNull
                                val av = a.doubleOrNull
                                val tol = tolerance[p.substringAfterLast('.')]
                                ev != null && av != null && (av == ev || (tol != null && kotlin.math.abs(av - ev) <= tol))
                            }
                        }
                        if (!same) out += "$p: expected ${ep.content}, got ${a.content}"
                    }
                }
            }
            walk(expected, actual, path)
            return out
        }
    }
}

/** The reels' way fixtures: the inputs decide.py read and the decisions it wrote. */
object ReelsFixtures {
    private val songs = HashMap<String, ReelsSong>()

    private fun text(path: String): String =
        ReelsFixtures::class.java.getResource("/reelsway/$path")?.readText() ?: error("no fixture $path")

    /** n, A's id and B's id of the 23 pairs. */
    fun pairs(): List<Triple<String, String, String>> = text("pairs.txt").lines().filter { it.isNotBlank() }.map {
        val (n, a, b) = it.trim().split(" ")
        Triple(n, a, b)
    }

    fun decision(n: String): JsonObject = Json.parseToJsonElement(text("decisions/$n.json")).jsonObject

    fun ids(): List<String> = pairs().flatMap { listOf(it.second, it.third) }.distinct().sorted()

    /** The song as decide.py read it. */
    fun song(id: String): ReelsSong = synchronized(songs) { songs.getOrPut(id) { parse(id) } }

    fun json(id: String): JsonObject = Json.parseToJsonElement(text("songs/$id.json")).jsonObject

    private fun parse(id: String): ReelsSong {
        val o = json(id)
        fun nums(k: String) = o[k]!!.jsonArray.map { it.jsonPrimitive.double }
        val t = nums("t")
        val level = nums("level")
        val low = nums("low150")
        val voice = o["voice"]!!.jsonArray.map { BarVoice.of(it.jsonPrimitive.content) }
        val pulse = nums("pulse")
        val lag = o["lagRelMs"]!!.jsonArray.map { it.jsonPrimitive.doubleOrNull }
        return ReelsSong(
            id = id,
            grid = ReelsGrid(o["bpm"]!!.jsonPrimitive.double, o["t0"]!!.jsonPrimitive.double, o["phase"]!!.jsonPrimitive.int),
            duration = o["duration"]!!.jsonPrimitive.double,
            bars = t.indices.map { ReelsBar(t[it], level[it], low[it], voice[it]) },
            lines = o["lines"]!!.jsonArray.map { it.jsonPrimitive.int },
            peaks = o["peaks"]!!.jsonArray.map { it.jsonPrimitive.int },
            pulse = PulseCheck(o["barAgreement"]?.jsonPrimitive?.doubleOrNull, pulse.indices.map { PulseBar(pulse[it], lag[it]) }),
            lufs = o["lufs"]?.jsonPrimitive?.doubleOrNull,
        )
    }
}
