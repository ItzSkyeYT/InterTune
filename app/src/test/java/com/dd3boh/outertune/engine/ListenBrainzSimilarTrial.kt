/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.db.RelatedSql
import com.dd3boh.outertune.db.SchemaDb
import com.dd3boh.outertune.db.entities.RelatedSongMap
import com.dd3boh.outertune.utils.LbLabs
import com.dd3boh.outertune.utils.LbSimilar
import com.dd3boh.outertune.utils.SongVersions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URLDecoder

/**
 * The 18 Nov 2026 trial's public seeds (experiments/recs-trial-1118: 120 of today's songs, 100 from the public Last.fm
 * 1K corpus) through the app's own way to ListenBrainz's similar songs (Unreleased.LB_SIMILAR): the names asked and
 * the answers read as ListenBrainzSimilar asks and reads them (LbSimilar.ask, LbLabs), its neighbours matched to the
 * songs on the phone by SimilarMatch, stored as source 2 and read back per seed by the engine's own query
 * (RelatedSql.ENGINE_EDGES), so a seed ListenBrainz has nothing for keeps YouTube's list.
 *
 * Gated on `LB_TRIAL=<the trial's folder>`, so the ordinary run never depends on it, and it sends nothing: every answer
 * is the trial's, from its cache. A question the app puts and the trial never asked counts as unanswered, and is
 * counted. The phone is one that has played these seeds: the seeds and every song YouTube listed beside them. With
 * `LB_TRIAL_OUT=<file>` it writes each seed's lists, YouTube's and the app's, for scoring against the listeners.
 */
class ListenBrainzSimilarTrial {
    private data class Seed(val uid: String, val set: String, val stratum: String, val title: String, val artist: String, val ytId: String?)
    private data class Song(val id: String, val title: String, val artist: String)

    private fun JsonObject.str(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull

    /** The trial's query string, as urllib wrote it. */
    private fun params(url: String): Map<String, String> = url.substringAfter('?', "").split('&').filter { '=' in it }
        .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    @Test
    fun `the app's way to ListenBrainz's similar songs over the trial's seeds`(): Unit = runBlocking {
        val dirPath = System.getenv("LB_TRIAL"); assumeTrue("set LB_TRIAL to run", !dirPath.isNullOrBlank())
        val dir = File(dirPath!!)
        val json = Json { ignoreUnknownKeys = true }
        val seeds = buildList {
            for (o in json.parseToJsonElement(File(dir, "seeds_modern.json").readText()).jsonArray.map { it.jsonObject })
                add(Seed("M:${o.str("yt")}", "M", o.str("stratum")!!, o.str("title")!!, o.str("artist")!!, o.str("yt")))
            for (o in json.parseToJsonElement(File(dir, "seeds_crowd.json").readText()).jsonArray.map { it.jsonObject })
                add(Seed("C:${o["item"]!!.jsonPrimitive.int}", "C", o.str("stratum")!!, o.str("title")!!, o.str("artist")!!, null))
        }
        val yt = json.parseToJsonElement(File(dir, "results_yt.json").readText()).jsonObject

        // ListenBrainz's answers, as the trial cached them: names to recordings, recordings to their neighbours.
        val lookups = HashMap<Pair<String, String>, String>()
        val near = HashMap<String, String>()
        for (f in File(dir, "cache").listFiles().orEmpty()) {
            val text = f.readText()
            if ("labs.api.listenbrainz.org" !in text) continue
            val o = json.parseToJsonElement(text).jsonObject
            val url = o.str("url") ?: continue
            if (!url.startsWith("https://labs.api.listenbrainz.org/") || o["status"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() != 200) continue
            val p = params(url)
            val data = o["data"].toString()
            when {
                "/acr-lookup/json" in url -> lookups[p.getValue("artist_credit_name") to p.getValue("recording_name")] = data
                "/similar-recordings/json" in url && p["algorithm"] == LbLabs.ALGORITHM -> near[p.getValue("recording_mbids")] = data
            }
        }

        // The phone: the seeds, and every song YouTube listed beside them, by YouTube's id where there is one.
        val songs = LinkedHashMap<String, Song>()
        val ytLists = HashMap<String, List<String>>()
        fun seedId(s: Seed): String {
            val row = yt[s.uid]?.jsonObject?.get("seed_row") as? JsonObject
            return s.ytId ?: row?.str("id") ?: s.uid
        }
        for (s in seeds) {
            val id = seedId(s)
            songs.getOrPut(id) { Song(id, s.title, s.artist) }
            val related = (yt[s.uid]?.jsonObject?.get("related") as? JsonArray).orEmpty().map { it.jsonObject }
                .mapNotNull { r -> r.str("id")?.let { Song(it, r.str("title").orEmpty(), r.str("artist").orEmpty()) } }
                // As MusicService keeps YouTube's list: each song once, never the seed or a version of it.
                .distinctBy { it.id }
                .filterNot { it.id == id || SongVersions.isVersionOf(it.title, s.title) }
            related.forEach { songs.getOrPut(it.id) { it } }
            ytLists[id] = related.map { it.id }
        }
        val index = SimilarMatch.Index(songs.values.map { SongRow(it.id, it.title, null, it.artist) })

        val db = SchemaDb.open()
        val insertSong = db.prepareStatement("INSERT OR IGNORE INTO song(id, title, duration, liked) VALUES (?, ?, 200, 0)")
        for (s in songs.values) { insertSong.setString(1, s.id); insertSong.setString(2, s.title); insertSong.addBatch() }
        insertSong.executeBatch()
        val insertYt = db.prepareStatement("INSERT INTO related_song_map(songId, relatedSongId, fetchedAt, source) VALUES (?, ?, 1, 0)")
        for ((seed, list) in ytLists) for (to in list) { insertYt.setString(1, seed); insertYt.setString(2, to); insertYt.addBatch() }
        insertYt.executeBatch()

        var named = 0; var knew = 0; var neighbours = 0; var found = 0; var withList = 0; var edges = 0
        var unasked = 0; var unaskedSeeds = 0; var requests = 0
        val perSeed = HashMap<String, JsonObject>()
        for (s in seeds) {
            val id = seedId(s)
            var mbid: String? = null
            var missed = 0
            val answer = LbSimilar.ask(
                s.title, s.artist,
                recordingOf = { a, t ->
                    requests++
                    val got = lookups[a to t]
                    if (got == null) missed++
                    got?.let { LbLabs.mbidOf(it) }?.also { mbid = it }
                },
                similarTo = { m ->
                    requests++
                    val got = near[m]
                    if (got == null) missed++
                    got?.let { LbLabs.neighboursOf(it) }.orEmpty()
                },
            )
            if (missed > 0) { unasked += missed; unaskedSeeds++ }
            if (mbid != null) named++
            if (answer.isNotEmpty()) knew++
            neighbours += answer.size
            found += answer.count { n -> SimilarMatch.key(n.title, n.artist)?.let { index[it].isNotEmpty() } == true }
            val ids = SimilarMatch.match(id, s.title, s.artist, answer, index)
            if (ids.isNotEmpty()) withList++
            edges += ids.size
            // Stored as ListenBrainzSimilar stores them.
            for (to in ids) db.createStatement().use {
                it.execute(RelatedSql.INSERT_LISTENBRAINZ_EDGE.replace(":songId", "'${id.replace("'", "''")}'")
                    .replace(":relatedSongId", "'${to.replace("'", "''")}'").replace(":fetchedAt", "2"))
            }
            perSeed[s.uid] = buildJsonObject {
                put("uid", s.uid); put("set", s.set); put("stratum", s.stratum); put("id", id)
                put("named", mbid != null); put("neighbours", answer.size); put("matched", ids.size); put("unasked", missed)
            }
        }

        // The engine's own query, per seed: ListenBrainz's list where it has one, YouTube's for the rest.
        fun lists(source: Int): Map<String, List<String>> = db.createStatement().use { st ->
            st.executeQuery(RelatedSql.ENGINE_EDGES.replace(":source", "$source") + " ORDER BY r.id").use { rs ->
                val out = LinkedHashMap<String, MutableList<String>>()
                while (rs.next()) out.getOrPut(rs.getString("songId")) { ArrayList() } += rs.getString("relatedSongId")
                out
            }
        }
        val app = lists(RelatedSongMap.SOURCE_LISTENBRAINZ)
        val youtube = lists(RelatedSongMap.SOURCE_YOUTUBE)
        db.close()

        val n = seeds.size
        println("    seeds $n (M ${seeds.count { it.set == "M" }}, C ${seeds.count { it.set == "C" }}); the phone: ${songs.size} songs")
        println("    requests the app would send: $requests, ${"%.1f".format(requests.toDouble() / n)} a seed; questions the trial never asked: $unasked, on $unaskedSeeds seeds")
        println("    named by ListenBrainz: $named of $n; with neighbours: $knew; neighbours $neighbours, on the phone $found (${100 * found / maxOf(1, neighbours)}%)")
        println("    seeds with ListenBrainz's list after matching: $withList of $n, $edges edges (${"%.1f".format(edges.toDouble() / maxOf(1, withList))} a seed); YouTube's list for the other ${n - withList}")
        for (set in listOf("M", "C")) for (stratum in seeds.filter { it.set == set }.map { it.stratum }.distinct()) {
            val these = seeds.filter { it.set == set && it.stratum == stratum }
            val used = these.count { (perSeed.getValue(it.uid)["matched"]!!.jsonPrimitive.int) > 0 }
            val nam = these.count { perSeed.getValue(it.uid)["named"]!!.jsonPrimitive.content == "true" }
            println("      $set $stratum: ${these.size} seeds, named $nam, ListenBrainz's list $used")
        }

        System.getenv("LB_TRIAL_OUT")?.takeIf { it.isNotBlank() }?.let { out ->
            fun songList(ids: List<String>) = buildJsonArray {
                for (i in ids) songs[i]?.let { s -> add(buildJsonObject { put("id", s.id); put("title", s.title); put("artist", s.artist) }) }
            }
            val all = buildJsonObject {
                for (s in seeds) {
                    val id = seedId(s)
                    put(s.uid, buildJsonObject {
                        perSeed.getValue(s.uid).forEach { (k, v) -> put(k, v) }
                        put("app", songList(app[id].orEmpty()))
                        put("yt", songList(youtube[id].orEmpty()))
                    })
                }
            }
            File(out).writeText(all.toString())
            println("    lists written to $out")
        }
    }
}
