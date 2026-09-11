/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import java.util.Locale

/**
 * A built row as text, one card per line, so a build survives the process: Home can show the last
 * row the moment it opens instead of building for seconds, a shadow build can be judged a day
 * later against what was played, and a pool pick can be graded against the features it had.
 * Fields are tab-separated: id, lane, p, sampled, seed id, reasons (comma-separated), features
 * (comma-separated, four decimals).
 */
object RowBuildCodec {
    fun encode(cards: List<Card>): String = cards.joinToString("\n") { c ->
        listOf(
            c.songId, c.lane.name, String.format(Locale.ROOT, "%.4f", c.p), if (c.sampled) "1" else "0", c.seedId.orEmpty(),
            c.reasons.joinToString(","), c.features.joinToString(",") { String.format(Locale.ROOT, "%.4f", it) },
        ).joinToString("\t")
    }

    fun decode(text: String?): List<Card> {
        if (text.isNullOrBlank()) return emptyList()
        return text.lineSequence().mapNotNull { line ->
            val f = line.split("\t")
            if (f.size < 7) return@mapNotNull null
            val lane = runCatching { Lane.valueOf(f[1]) }.getOrNull() ?: return@mapNotNull null
            val features = Grading.parseFeatures(f[6]) ?: return@mapNotNull null
            Card(
                songId = f[0], lane = lane, z = 0.0, p = f[2].toDoubleOrNull() ?: 0.0, features = features,
                reasons = f[5].split(",").filter { it.isNotEmpty() }, seedId = f[4].ifEmpty { null }, sampled = f[3] == "1",
            )
        }.toList()
    }

    fun ids(text: String?): List<String> = decode(text).map { it.songId }
}
