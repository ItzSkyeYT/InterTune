/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

/**
 * The developer page's view of [EngineParams]: the constants worth turning by hand, each with its
 * default and a way to apply a new value. Overrides live in one JSON object in the preferences,
 * `{"name": value, ...}`, read whenever a row is built, so a change shows on the next build.
 */
object EngineTuning {
    class Tunable(val name: String, val description: String, val default: Double, val apply: (EngineParams, Double) -> EngineParams)

    val entries: List<Tunable> = listOf(
        Tunable("minPositiveMs", "Milliseconds a play must last before it counts for anything", 30_000.0) { p, v -> p.copy(minPositiveMs = v.toLong()) },
        Tunable("rampFrom", "Fraction of the song where engagement starts climbing", 0.10) { p, v -> p.copy(rampFrom = v) },
        Tunable("rampTo", "Fraction of the song where engagement reaches one", 0.80) { p, v -> p.copy(rampTo = v) },
        Tunable("skipWeight", "How much a counted skip takes off engagement", 0.5) { p, v -> p.copy(skipWeight = v) },
        Tunable("intentSearch", "Intent of a searched-for play", 1.0) { p, v -> p.copy(intentSearch = v) },
        Tunable("intentChosen", "Intent of a play from a playlist, album, artist or radio start", 0.8) { p, v -> p.copy(intentChosen = v) },
        Tunable("intentRow", "Intent of a play from a Home row", 0.7) { p, v -> p.copy(intentRow = v) },
        Tunable("intentRadioBase", "Intent of the first autoplayed song down a radio", 0.6) { p, v -> p.copy(intentRadioBase = v) },
        Tunable("activationDecay", "ACT-R's recency decay exponent", 0.5) { p, v -> p.copy(activationDecay = v) },
        Tunable("activationPerSession", "Listens of one song per session that count toward activation", 3.0) { p, v -> p.copy(activationPerSession = v.toInt()) },
        Tunable("likePseudoListen", "What a usable like is worth as a listen", 0.6) { p, v -> p.copy(likePseudoListen = v) },
        Tunable("satiationStart", "Exposure at which satiation begins", 10.0) { p, v -> p.copy(satiationStart = v) },
        Tunable("satiationRamp", "Exposure over which satiation reaches full", 20.0) { p, v -> p.copy(satiationRamp = v) },
        Tunable("dormantAfterDays", "Days of quiet before a song counts as dormant", 45.0) { p, v -> p.copy(dormantAfterDays = v.toInt()) },
        Tunable("impressionScale", "Passed-over cards at which the penalty reaches about two thirds", 3.0) { p, v -> p.copy(impressionScale = v) },
        Tunable("exploreBase", "Explore share at adventurousness 0", 0.05) { p, v -> p.copy(exploreBase = v) },
        Tunable("exploreSpan", "Explore share added at adventurousness 100", 0.30) { p, v -> p.copy(exploreSpan = v) },
        Tunable("relatedShare", "Related lane's share of the rest of the row", 0.40) { p, v -> p.copy(relatedShare = v) },
        Tunable("againShare", "Again lane's share (Familiarity overrides this)", 0.25) { p, v -> p.copy(againShare = v) },
        Tunable("artistShare", "Artist lane's share", 0.20) { p, v -> p.copy(artistShare = v) },
        Tunable("rediscoverShare", "Rediscover lane's share", 0.15) { p, v -> p.copy(rediscoverShare = v) },
        Tunable("maxPerArtist", "Cards one artist may hold", 2.0) { p, v -> p.copy(maxPerArtist = v.toInt()) },
        Tunable("maxPerSeed", "Related cards one seed may refer", 3.0) { p, v -> p.copy(maxPerSeed = v.toInt()) },
        Tunable("temperature", "Sampling temperature inside a lane", 1.0) { p, v -> p.copy(temperature = v) },
        Tunable("seedsNow", "Seeds from the current session", 4.0) { p, v -> p.copy(seedsNow = v.toInt()) },
        Tunable("seedsToday", "Seeds from earlier today", 3.0) { p, v -> p.copy(seedsToday = v.toInt()) },
        Tunable("seedsStrong", "Seeds from the strongest songs", 4.0) { p, v -> p.copy(seedsStrong = v.toInt()) },
        Tunable("seedDamp", "Weight kept by a seed used in the last three hours, per build", 0.3) { p, v -> p.copy(seedDamp = v) },
        Tunable("engineFreshHours", "Hours a song heard well stays out of the engine row", 1.0) { p, v -> p.copy(engineFreshHours = v.toInt()) },
        Tunable("againWindowDays", "Days back the Again lane looks", 14.0) { p, v -> p.copy(againWindowDays = v.toInt()) },
    )

    /** `{"name":value,...}` to a map; anything unreadable is ignored. */
    fun parse(json: String?): Map<String, Double> {
        if (json.isNullOrBlank()) return emptyMap()
        return Regex("\"([A-Za-z]+)\"\\s*:\\s*(-?[0-9.]+)").findAll(json)
            .mapNotNull { m -> m.groupValues[2].toDoubleOrNull()?.let { m.groupValues[1] to it } }.toMap()
    }

    fun encode(overrides: Map<String, Double>): String =
        overrides.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }

    /** The defaults with the overrides applied, unknown names ignored. */
    fun params(overrides: Map<String, Double>, base: EngineParams = EngineParams.DEFAULT): EngineParams =
        entries.fold(base) { p, t -> overrides[t.name]?.let { t.apply(p, it) } ?: p }
}
