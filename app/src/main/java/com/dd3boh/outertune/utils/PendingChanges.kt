/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * A change made in the app that the YouTube Music account is to have. With sync in Read and
 * write, the app is the source of truth for what the person changes in it (his word, 4 Nov 2026):
 * a change that could not be sent (offline, a sign-in that lapsed, YouTube throttling) is kept as
 * pending, sent at the next chance in the order it was made, and until then the sync leaves it
 * standing on the phone instead of putting the account's older state back. See [PendingStore]
 * and [Waiting]; PendingChanges sends them.
 *
 * Playlist ids are kept without YouTube's "VL", as the rest of the app keeps them.
 */
sealed interface Change {
    /** The song liked, or not. [outOfLibrary]: taken out of the library here, which takes its like with it. */
    data class Like(val videoId: String, val liked: Boolean, val outOfLibrary: Boolean = false) : Change

    /** A playlist that is not the account's own, saved to the library or taken out of it (the heart). */
    data class SavePlaylist(val browseId: String, val saved: Boolean) : Change

    /** An album saved to the library or taken out of it, through the album's playlist. */
    data class SaveAlbum(val albumId: String, val playlistId: String, val saved: Boolean) : Change

    /** An artist subscribed to or not. [channelId] is looked up when it is sent if it is not known. */
    data class Follow(val artistId: String, val channelId: String?, val followed: Boolean) : Change

    /** One of the account's playlists given a new name. */
    data class Rename(val browseId: String, val name: String) : Change

    /** One of the account's playlists deleted. */
    data class Delete(val browseId: String) : Change

    /**
     * A song put at the end of one of the account's playlists. [mapId] is the row it made here, which
     * is given YouTube's id for the entry (its setVideoId) once the add is answered. [duplicate]: the
     * playlist held the song already and Add anyway put it in again, so YouTube is told to keep both.
     */
    data class Add(val browseId: String, val mapId: Int, val videoId: String, val duplicate: Boolean) : Change

    /** The row [mapId] taken out. [setVideoId] names the entry on YouTube; when unknown it is found when sent. */
    data class Remove(val browseId: String, val mapId: Int, val videoId: String, val setVideoId: String?) : Change

    /** The row [mapId] put in front of the row [beforeMapId], as YouTube's ACTION_MOVE_VIDEO_BEFORE. */
    data class Move(
        val browseId: String,
        val mapId: Int,
        val videoId: String,
        val setVideoId: String?,
        val beforeMapId: Int,
        val beforeVideoId: String,
        val beforeSetVideoId: String?,
    ) : Change
}

/**
 * A [change] waiting to be sent: [seq] is its place in the line, [account] whose account it is for
 * ([LikedSync.accountKey] when it was made, never sent to another), [at] when it was made, and
 * [tries] the sends that failed for no reason the app could name (see [Sent.Failed]).
 */
data class Pending(val seq: Long, val account: String?, val at: Long, val change: Change, val tries: Int = 0)

/** What became of one send. */
sealed interface Sent {
    /** The account has it. [setVideoId]: what YouTube named the entry an add made. */
    data class Done(val setVideoId: String? = null) : Sent

    /** YouTube said no, and would say it again (a playlist that is gone, a song it already holds): forgotten. */
    data object Refused : Sent

    /** Not now: no connection, a sign-in that lapsed, YouTube throttling, Read only. Kept, nothing after it sent. */
    data object NotNow : Sent

    /** Failed for no clear reason: kept and counted, and given up after [PendingStore.MOST_TRIES]. */
    data object Failed : Sent
}

/** The rules for what goes in the line, kept apart from the file so a test can say what they do. */
object PendingRules {

    /** The playlist [change] is about, or null for a song's like, an album or an artist. */
    fun playlistOf(change: Change): String? = when (change) {
        is Change.SavePlaylist -> change.browseId
        is Change.Rename -> change.browseId
        is Change.Delete -> change.browseId
        is Change.Add -> change.browseId
        is Change.Remove -> change.browseId
        is Change.Move -> change.browseId
        else -> null
    }

    /**
     * [list] with [new] put in, at the end. A like, a save, a follow or a name says what the thing is
     * to be, so it replaces one still waiting for the same thing: only the last word is sent. A
     * removal of a row whose add has not gone out yet takes the add back instead, and neither is
     * sent: the account never had that row. A delete takes everything still waiting for the
     * playlist with it. [sending] is the change being sent at this moment, which nothing replaces.
     */
    fun put(list: List<Pending>, new: Pending, sending: Long?): List<Pending> {
        fun waiting(p: Pending) = p.account == new.account && p.seq != sending
        return when (val c = new.change) {
            is Change.Like -> list.filterNot { waiting(it) && (it.change as? Change.Like)?.videoId == c.videoId } + new
            is Change.SavePlaylist -> list.filterNot { waiting(it) && (it.change as? Change.SavePlaylist)?.browseId == c.browseId } + new
            is Change.SaveAlbum -> list.filterNot { waiting(it) && (it.change as? Change.SaveAlbum)?.albumId == c.albumId } + new
            is Change.Follow -> list.filterNot { waiting(it) && (it.change as? Change.Follow)?.artistId == c.artistId } + new
            is Change.Rename -> list.filterNot { waiting(it) && (it.change as? Change.Rename)?.browseId == c.browseId } + new
            is Change.Delete -> list.filterNot { waiting(it) && playlistOf(it.change) == c.browseId } + new
            is Change.Remove -> {
                val add = list.firstOrNull { p ->
                    waiting(p) && (p.change as? Change.Add)?.let { it.browseId == c.browseId && it.mapId == c.mapId } == true
                }
                if (add == null) list + new
                else list.filterNot { p ->
                    p.seq == add.seq || waiting(p) && (p.change as? Change.Move)?.let { it.browseId == c.browseId && it.mapId == c.mapId } == true
                }
            }
            is Change.Add, is Change.Move -> list + new
        }
    }

    /**
     * [list] once the add of row [mapId] to [browseId] is answered with [setVideoId]: a removal or a
     * move of that row still waiting can now name it. The answer was thrown away before (scan of
     * 4 Nov 2026, finding 6), so a song added from the phone and taken out again before the next sync
     * was never taken out of the account, and the sync put it back.
     */
    fun learned(list: List<Pending>, account: String?, browseId: String, mapId: Int, setVideoId: String): List<Pending> =
        list.map { p ->
            if (p.account != account) return@map p
            when (val c = p.change) {
                is Change.Remove ->
                    if (c.browseId == browseId && c.mapId == mapId && c.setVideoId == null) p.copy(change = c.copy(setVideoId = setVideoId)) else p
                is Change.Move -> {
                    var m = c
                    if (m.browseId == browseId && m.mapId == mapId && m.setVideoId == null) m = m.copy(setVideoId = setVideoId)
                    if (m.browseId == browseId && m.beforeMapId == mapId && m.beforeSetVideoId == null) m = m.copy(beforeSetVideoId = setVideoId)
                    if (m === c) p else p.copy(change = m)
                }
                else -> p
            }
        }

    /**
     * Which entry of a playlist as YouTube has it ([remote]: each song's id and its setVideoId, in
     * order) a row of [videoId] here stands for, when the row does not know: one of the same song
     * that no other row here holds ([held]). Null when there is none: the song never got there, or
     * every copy of it there is another row's.
     */
    fun entryFor(videoId: String, remote: List<Pair<String, String?>>, held: Set<String>): String? =
        remote.asSequence().filter { it.first == videoId }.mapNotNull { it.second }.firstOrNull { it !in held }

    /** The like a song taken out of the library here loses, to be sent: only a YouTube song that was liked. */
    fun outOfLibrary(songId: String, isLocal: Boolean, liked: Boolean): Change.Like? =
        if (!isLocal && liked) Change.Like(songId, liked = false, outOfLibrary = true) else null
}

/**
 * What the sync leaves alone, from the changes still waiting for the account it reads: each one
 * stands on the phone until it is sent, and then the account, which has it, is read as before.
 * Only these: whatever else the account changed by itself (a song liked on another device, a
 * playlist edited on the web) still comes in.
 */
class Waiting(pending: List<Pending>) {
    private val likes = HashMap<String, Change.Like>()
    private val playlists = HashMap<String, Boolean>()
    private val names = HashMap<String, String>()
    private val held = HashSet<String>()
    private val albums = HashMap<String, Boolean>()
    private val artists = HashMap<String, Boolean>()

    init {
        for (p in pending) when (val c = p.change) {
            is Change.Like -> likes[c.videoId] = c
            is Change.SavePlaylist -> playlists[c.browseId] = c.saved
            is Change.Delete -> playlists[c.browseId] = false
            is Change.Rename -> { names[c.browseId] = c.name; held += c.browseId }
            is Change.Add -> held += c.browseId
            is Change.Remove -> held += c.browseId
            is Change.Move -> held += c.browseId
            is Change.SaveAlbum -> albums[c.albumId] = c.saved
            is Change.Follow -> artists[c.artistId] = c.followed
        }
    }

    /** Whether the song is to be liked, as the last change made here says; null when none waits. */
    fun likeWanted(videoId: String): Boolean? = likes[videoId]?.liked

    /** Taken out of the library here, and its unlike not sent yet: the library sync leaves it out. */
    fun keptOutOfLibrary(videoId: String): Boolean = likes[videoId]?.let { !it.liked && it.outOfLibrary } == true

    /** A change to the playlist's songs or name waits: the sync leaves its songs as they are here. */
    fun holdsSongsOf(browseId: String): Boolean = browseId.removePrefix("VL") in held

    /** Whether the playlist is to be in the library (false too for one deleted here); null when nothing waits. */
    fun playlistWanted(browseId: String): Boolean? = playlists[browseId.removePrefix("VL")]

    /** The name given here that YouTube has not had yet. */
    fun nameFor(browseId: String): String? = names[browseId.removePrefix("VL")]

    fun albumWanted(albumId: String): Boolean? = albums[albumId]

    fun artistWanted(artistId: String): Boolean? = artists[artistId]

    val isEmpty: Boolean
        get() = likes.isEmpty() && playlists.isEmpty() && held.isEmpty() && albums.isEmpty() && artists.isEmpty()

    companion object {
        val NONE = Waiting(emptyList())
    }
}

/**
 * The changes waiting, in a file of their own in the app's files that are not backed up: a backup
 * restored elsewhere, or later, must never send an old change again. Written whole beside the old
 * file and renamed into place, as RecogniseLater is, so a phone that dies in the middle keeps the
 * list it had; by hand with kotlinx.serialization's tree, as this module does not apply the
 * serialization plugin. A file that cannot be written leaves the list in memory, where it is still
 * sent and still kept from the sync until the app ends.
 *
 * An outbox, as mail apps and sync engines keep one: the changes go out one at a time, oldest
 * first, each forgotten once the account has it, and the line stops at the first one that cannot
 * go now, so nothing overtakes a change it depends on (a removal its add, a move its row).
 */
class PendingStore(private val file: File) {

    private val lock = Mutex()
    private val sendLock = Mutex()

    private class State(val next: Long, val list: List<Pending>)

    /** Null until read from the file. Under [lock]. */
    private var state: State? = null

    /** The change being sent at this moment. Under [lock]. */
    private var sending: Long? = null

    /** The list as last kept, for [now]. */
    @Volatile
    private var latest: List<Pending>? = null

    /** Keeps [change] for [account]. */
    suspend fun put(account: String?, change: Change, now: Long = System.currentTimeMillis()) {
        update { s -> State(s.next + 1, PendingRules.put(s.list, Pending(s.next, account, now, change), sending)) }
    }

    /** Everything waiting, oldest first. */
    suspend fun all(): List<Pending> = withContext(Dispatchers.IO) { lock.withLock { load().list } }

    /** What waits for [account], oldest first. */
    suspend fun of(account: String?): List<Pending> = all().filter { it.account == account }

    /**
     * What waits for [account], as last kept, without waiting for a send: for a caller in a database
     * transaction, which must not wait on anything that could wait on the database.
     */
    fun now(account: String?): List<Pending> =
        (latest ?: runBlocking(Dispatchers.IO) { all() }).filter { it.account == account }

    /** Forgets everything waiting: a restore puts back a library these changes were not made to. */
    suspend fun forgetAll() {
        update { s -> State(s.next, emptyList()) }
    }

    /**
     * Sends what waits for [account] through [send], oldest first, until the line is empty
     * ([Flush.DONE]) or a change cannot go now ([Flush.LEFT]). One send at a time, whoever asks: a
     * second caller waits for the first and then sends whatever is still there.
     */
    suspend fun send(account: String?, send: suspend (Pending) -> Sent): Flush = sendLock.withLock { sendAll(account, send) }

    enum class Flush { DONE, LEFT }

    private suspend fun sendAll(account: String?, send: suspend (Pending) -> Sent): Flush {
        while (true) {
            val next = take(account) ?: return Flush.DONE
            val outcome = try {
                send(next)
            } catch (e: CancellationException) {
                withContext(Dispatchers.IO + NonCancellable) { lock.withLock { sending = null } }
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Sending a ${kindOf(next.change)} failed", e)
                Sent.Failed
            }
            if (settle(account, next, outcome)) return Flush.LEFT
        }
    }

    /** The oldest change for [account], marked as being sent, or null when none waits. */
    private suspend fun take(account: String?): Pending? = withContext(Dispatchers.IO) {
        lock.withLock { load().list.firstOrNull { it.account == account }?.also { sending = it.seq } }
    }

    /** Writes down what became of [sent]. True when it is still waiting, and the line stops there. */
    private suspend fun settle(account: String?, sent: Pending, outcome: Sent): Boolean = withContext(Dispatchers.IO + NonCancellable) {
        lock.withLock {
            sending = null
            val s = load()
            val rest = s.list.filterNot { it.seq == sent.seq }
            when (outcome) {
                is Sent.Done -> {
                    val c = sent.change
                    val list = if (c is Change.Add && outcome.setVideoId != null) {
                        PendingRules.learned(rest, account, c.browseId, c.mapId, outcome.setVideoId)
                    } else rest
                    keep(State(s.next, list))
                    false
                }
                Sent.Refused -> {
                    Log.w(TAG, "YouTube refused a ${kindOf(sent.change)} made here; the account keeps what it has")
                    keep(State(s.next, rest))
                    false
                }
                Sent.NotNow -> true
                Sent.Failed -> {
                    val tries = sent.tries + 1
                    if (tries >= MOST_TRIES) {
                        Log.w(TAG, "Gave up on a ${kindOf(sent.change)} after $tries tries")
                        keep(State(s.next, rest))
                        false
                    } else {
                        keep(State(s.next, s.list.map { if (it.seq == sent.seq) it.copy(tries = tries) else it }))
                        true
                    }
                }
            }
        }
    }

    private suspend fun update(how: (State) -> State) = withContext(Dispatchers.IO) {
        lock.withLock { keep(how(load())) }
    }

    /** The list as kept, read from the file the first time. Under [lock]. */
    private fun load(): State = state ?: decode(
        runCatching { file.takeIf { it.exists() }?.readText() }
            .onFailure { Log.w(TAG, "Could not read the changes waiting for the account", it) }
            .getOrNull()
    ).also {
        state = it
        latest = it.list
    }

    /** In memory at once, then in the file. Under [lock]. */
    private fun keep(new: State) {
        state = new
        latest = new.list
        runCatching { write(new) }.onFailure { Log.w(TAG, "Could not write the changes waiting for the account", it) }
    }

    private fun write(s: State) {
        if (s.list.isEmpty()) {
            file.delete()
            return
        }
        file.parentFile?.mkdirs()
        val beside = File(file.path + ".new")
        try {
            FileOutputStream(beside).use { out ->
                out.write(encode(s.next, s.list).toByteArray(Charsets.UTF_8))
                out.flush()
                runCatching { out.fd.sync() }
            }
            if (!beside.renameTo(file)) throw IOException("Could not put ${file.name} in place")
        } catch (e: Exception) {
            beside.delete()
            throw e
        }
    }

    companion object {
        private const val TAG = "PendingChanges"

        /** Sends that failed for no reason the app could name, before a change is given up. */
        const val MOST_TRIES = 5

        /** What a change is, for the log: never the song, the playlist or the account. */
        fun kindOf(change: Change): String = when (change) {
            is Change.Like -> if (change.liked) "like" else "unlike"
            is Change.SavePlaylist -> if (change.saved) "playlist save" else "playlist unsave"
            is Change.SaveAlbum -> if (change.saved) "album save" else "album unsave"
            is Change.Follow -> if (change.followed) "subscription" else "unsubscription"
            is Change.Rename -> "playlist rename"
            is Change.Delete -> "playlist delete"
            is Change.Add -> "playlist add"
            is Change.Remove -> "playlist removal"
            is Change.Move -> "playlist move"
        }

        internal fun encode(next: Long, list: List<Pending>): String = buildJsonObject {
            put("version", 1)
            put("next", next)
            put("changes", buildJsonArray { list.forEach { add(encode(it)) } })
        }.toString()

        private fun encode(p: Pending): JsonObject = buildJsonObject {
            put("seq", p.seq)
            p.account?.let { put("account", it) }
            put("at", p.at)
            if (p.tries > 0) put("tries", p.tries)
            when (val c = p.change) {
                is Change.Like -> {
                    put("kind", "like"); put("video", c.videoId); put("liked", c.liked)
                    if (c.outOfLibrary) put("outOfLibrary", true)
                }
                is Change.SavePlaylist -> { put("kind", "savePlaylist"); put("playlist", c.browseId); put("saved", c.saved) }
                is Change.SaveAlbum -> { put("kind", "saveAlbum"); put("album", c.albumId); put("playlist", c.playlistId); put("saved", c.saved) }
                is Change.Follow -> {
                    put("kind", "follow"); put("artist", c.artistId); c.channelId?.let { put("channel", it) }; put("followed", c.followed)
                }
                is Change.Rename -> { put("kind", "rename"); put("playlist", c.browseId); put("name", c.name) }
                is Change.Delete -> { put("kind", "delete"); put("playlist", c.browseId) }
                is Change.Add -> {
                    put("kind", "add"); put("playlist", c.browseId); put("map", c.mapId); put("video", c.videoId)
                    if (c.duplicate) put("duplicate", true)
                }
                is Change.Remove -> {
                    put("kind", "remove"); put("playlist", c.browseId); put("map", c.mapId); put("video", c.videoId)
                    c.setVideoId?.let { put("set", it) }
                }
                is Change.Move -> {
                    put("kind", "move"); put("playlist", c.browseId)
                    put("map", c.mapId); put("video", c.videoId); c.setVideoId?.let { put("set", it) }
                    put("beforeMap", c.beforeMapId); put("beforeVideo", c.beforeVideoId); c.beforeSetVideoId?.let { put("beforeSet", it) }
                }
            }
        }

        private class Decoded(val next: Long, val list: List<Pending>)

        /** The file's list; one change that cannot be read (from a newer version, say) is left out, not the rest. */
        private fun decode(text: String?): State {
            val d = runCatching {
                val root = Json.parseToJsonElement(text ?: return State(1, emptyList())).jsonObject
                val list = root["changes"]?.jsonArray.orEmpty().mapNotNull { runCatching { pending(it.jsonObject) }.getOrNull() }
                val next = root["next"]?.jsonPrimitive?.longOrNull ?: 1L
                Decoded(maxOf(next, (list.maxOfOrNull { it.seq } ?: 0L) + 1), list)
            }.onFailure { Log.w(TAG, "The changes waiting for the account could not be read", it) }
                .getOrNull() ?: Decoded(1, emptyList())
            return State(d.next, d.list)
        }

        internal fun decodeForTest(text: String?): List<Pending> = decode(text).list

        private fun pending(o: JsonObject): Pending? {
            fun s(key: String) = o[key]?.jsonPrimitive?.contentOrNull
            fun b(key: String) = o[key]?.jsonPrimitive?.booleanOrNull
            fun i(key: String) = o[key]?.jsonPrimitive?.intOrNull
            val change: Change = when (s("kind")) {
                "like" -> Change.Like(s("video") ?: return null, b("liked") ?: return null, b("outOfLibrary") == true)
                "savePlaylist" -> Change.SavePlaylist(s("playlist") ?: return null, b("saved") ?: return null)
                "saveAlbum" -> Change.SaveAlbum(s("album") ?: return null, s("playlist") ?: return null, b("saved") ?: return null)
                "follow" -> Change.Follow(s("artist") ?: return null, s("channel"), b("followed") ?: return null)
                "rename" -> Change.Rename(s("playlist") ?: return null, s("name") ?: return null)
                "delete" -> Change.Delete(s("playlist") ?: return null)
                "add" -> Change.Add(s("playlist") ?: return null, i("map") ?: return null, s("video") ?: return null, b("duplicate") == true)
                "remove" -> Change.Remove(s("playlist") ?: return null, i("map") ?: return null, s("video") ?: return null, s("set"))
                "move" -> Change.Move(
                    s("playlist") ?: return null,
                    i("map") ?: return null, s("video") ?: return null, s("set"),
                    i("beforeMap") ?: return null, s("beforeVideo") ?: return null, s("beforeSet"),
                )
                else -> return null
            }
            return Pending(
                seq = o["seq"]?.jsonPrimitive?.longOrNull ?: return null,
                account = s("account"),
                at = o["at"]?.jsonPrimitive?.longOrNull ?: 0L,
                change = change,
                tries = i("tries") ?: 0,
            )
        }
    }
}
