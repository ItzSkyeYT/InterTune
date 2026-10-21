/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.edit

/**
 * The changes saved by hand, one per ordered pair of songs and shared by every Mix playlist where
 * the two meet. Auto is never stored: a pair with nothing here plays Auto, and saving Auto
 * unchanged deletes the pair's entry, so it follows Auto as Auto improves. "Plain change for these
 * two" is stored, as a spec with [TransitionSpec.plain].
 *
 * Blocking and safe from any thread; the file-backed one reads and writes its file, so call it off
 * the main thread. On the phone it is [JsonFileTransitionStore] on filesDir/mix/transitions.json,
 * kept out of the database so no migration is needed; tests use [InMemoryTransitionStore].
 */
interface TransitionStore {

    /** The saved change from [SongPair.from] into [SongPair.to], or null: Auto. */
    fun get(pair: SongPair): TransitionSpec?

    /** Saves [spec] under its own pair, in place of what was there. A spec that breaks a rule is refused. */
    fun put(spec: TransitionSpec)

    /** Forgets the pair's saved change; true when there was one. */
    fun delete(pair: SongPair): Boolean

    /** Every saved change this build can play, in no set order. */
    fun all(): List<TransitionSpec>

    /** Forgets every saved change (Settings, Mixing, Saved transitions, Clear), and says how many there were. */
    fun clear(): Int
}

/**
 * The listener's fixes to songs' beats (Fix the beat), one per song: a fix is kept for the song,
 * so Auto uses it too. Its own store, not the analysis cache, since a cache can be thrown away and
 * a fix cannot. On the phone [JsonFileBeatFixStore] on filesDir/mix/beat-fixes.json.
 */
interface BeatFixStore {

    fun get(songId: String): BeatFix?

    /** Keeps [fix] for the song; [BeatFix.NONE] forgets it, as Reset does. */
    fun put(songId: String, fix: BeatFix)

    fun delete(songId: String): Boolean

    fun all(): Map<String, BeatFix>
}

/** A [TransitionStore] in memory, for tests and previews: the same rules as the file, nothing kept. */
class InMemoryTransitionStore(initial: Collection<TransitionSpec> = emptyList()) : TransitionStore {

    private val specs = LinkedHashMap<SongPair, TransitionSpec>()

    init {
        initial.forEach(::put)
    }

    @Synchronized
    override fun get(pair: SongPair): TransitionSpec? = specs[pair]

    @Synchronized
    override fun put(spec: TransitionSpec) {
        val problems = spec.problems()
        require(problems.isEmpty()) { "not a spec to save: ${problems.joinToString("; ")}" }
        specs[spec.pair] = spec
    }

    @Synchronized
    override fun delete(pair: SongPair): Boolean = specs.remove(pair) != null

    @Synchronized
    override fun all(): List<TransitionSpec> = specs.values.toList()

    @Synchronized
    override fun clear(): Int = specs.size.also { specs.clear() }
}

/** A [BeatFixStore] in memory, for tests and previews. */
class InMemoryBeatFixStore(initial: Map<String, BeatFix> = emptyMap()) : BeatFixStore {

    private val fixes = LinkedHashMap<String, BeatFix>()

    init {
        initial.forEach { (song, fix) -> put(song, fix) }
    }

    @Synchronized
    override fun get(songId: String): BeatFix? = fixes[songId]

    @Synchronized
    override fun put(songId: String, fix: BeatFix) {
        require(fix.bpm == null || (fix.bpm.isFinite() && fix.bpm in 20.0..400.0)) { "a tempo of ${fix.bpm}" }
        if (fix.isNone) fixes.remove(songId) else fixes[songId] = fix
    }

    @Synchronized
    override fun delete(songId: String): Boolean = fixes.remove(songId) != null

    @Synchronized
    override fun all(): Map<String, BeatFix> = LinkedHashMap(fixes)
}
