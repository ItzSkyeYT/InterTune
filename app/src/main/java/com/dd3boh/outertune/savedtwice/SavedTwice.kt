/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.savedtwice

import com.dd3boh.outertune.lyrics.LyricsMatch
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Songs saved twice (issue #47): which saved copies in one list are the same song, and which of
 * them is kept unless somebody picks another.
 *
 * Two copies are one song when they are the very same song twice (a playlist can hold one twice),
 * or when they have the same title and the same artist, compared without case, accents and the
 * tags that only describe the upload ("(Official Video)", "[Official Audio]", "(Lyrics)",
 * "Remastered 2011", "- Remaster"), and run nearly as long as each other. A live, remixed,
 * acoustic, instrumental, sped up, slowed, covered or karaoke version is another recording and is
 * never grouped with the original, whatever it is called otherwise.
 *
 * Kept free of Android, the database and the network, so the rule is tested on its own
 * (SavedTwiceTest). It only ever sees the copies of one list: the liked songs, the songs in the
 * library, or one playlist, never every song the app has stored.
 */
object SavedTwice {

    /** What a copy is, for the default pick and for the first line of its row. */
    enum class Kind { SONG, MUSIC_VIDEO, FILE }

    /**
     * One saved copy, as the rule needs it.
     *
     * [key] tells the copies of one list apart: the song's id in Liked and in the library, the
     * playlist row's id in a playlist, where the same song can be twice. [duration] is in seconds,
     * 0 or less when not known. [savedAt] orders the copies by when they were saved, smaller first,
     * in whatever unit the list has (null when it does not say).
     */
    data class Copy(
        val key: String,
        val songId: String,
        val title: String,
        val artists: List<String>,
        val duration: Int,
        val album: String?,
        val kind: Kind,
        val savedAt: Long?,
    )

    /** The copies of one song, two at least, the one kept by default first. */
    data class Group(val copies: List<Copy>) {
        /** Tells a group apart from the others of its list, for the pick made in it. */
        val id: String get() = copies.first().key
        val keptByDefault: Copy get() = copies.first()
    }

    /**
     * Two copies of the same recording are this many seconds apart at most: YouTube rounds to
     * whole seconds, a file's tags do not, and a remaster can gain or lose a second of silence.
     */
    const val SAME_LENGTH_SEC = 4

    /**
     * A music video runs a little longer than its song, with an opening shot or a last scene: in
     * the mockup the song was 3:31 and its video 3:42. Its title still has to be the song's.
     */
    const val VIDEO_LENGTH_SEC = 15

    private val COMBINING = Regex("""\p{M}+""")
    private val APOSTROPHES = Regex("""['’‘`´]""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

    /**
     * Words that make another recording of a song. Compared as a set between two copies, so a song
     * whose own name has one ("Live Forever") still matches itself.
     */
    private val RECORDING = Regex(
        "\\b(?:live|remix(?:es|ed)?|rmx|acoustic|unplugged|instrumentals?|karaoke|covers?" +
            "|sped up|speed up|nightcore|slowed|daycore|reverb|8d|a ?cappella|acapella|demo)\\b"
    )

    private val ARTIST_SEPARATOR = Regex(
        """\s*(?:,|&|;|\bx\b|\bfeat\.?|\bft\.?|\bfeaturing\b|\bwith\b)\s*""",
        RegexOption.IGNORE_CASE
    )

    /** "Paramore - Topic" and "ParamoreVEVO" are Paramore's own channels on YouTube. */
    private val CHANNEL_SUFFIX = Regex("""(?:\s+-\s+topic|vevo)\s*$""", RegexOption.IGNORE_CASE)

    /** "Houdini - Single", "Houdini (Single)": how files and some stores name a single. */
    private val SINGLE_SUFFIX = Regex("""(?:\s+[-\u2013\u2014]\s+single|\s*[(\[]single[)\]])\s*$""", RegexOption.IGNORE_CASE)

    /** A YouTube video's own frame, which is what a music video's cover is. */
    private val VIDEO_FRAME = Regex("""^https?://i\d?\.ytimg\.com/(?:vi|vi_webp)/""")

    /** Lower case, no accents, no apostrophes, "&" said as "and", and every other mark a space. */
    fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(COMBINING, "")
            .lowercase(Locale.ROOT)
            .replace(APOSTROPHES, "")
            .replace("&", " and ")
            .replace(NON_WORD, " ")
            .trim()

    /** The song's name as compared: "Paramore - Misery Business (Official Video)" is "misery business". */
    fun name(title: String, artists: List<String>): String =
        fold(LyricsMatch.cleanTitle(LyricsMatch.dropArtistPrefix(title, artists + artists.map(::channelless))))

    private fun channelless(artist: String): String = artist.replace(CHANNEL_SUFFIX, "").trim()

    /** The recording words in a text, as a set: {"live"}, {"sped up"}, or none. */
    fun recordingWords(text: String?): Set<String> =
        if (text.isNullOrBlank()) emptySet()
        else RECORDING.findAll(fold(text)).map { it.value.replace(" ", "") }.map(::sameWord).toSet()

    private fun sameWord(word: String): String = when (word) {
        "remixes", "remixed", "rmx" -> "remix"
        "instrumentals" -> "instrumental"
        "covers" -> "cover"
        "speedup" -> "spedup"
        "acappella", "acapella" -> "acappella"
        else -> word
    }

    /** Every way the copy's artists can be named: each artist whole, and each name inside one. */
    fun artistNames(artists: List<String>): Set<String> =
        (artists + artists.flatMap { it.split(ARTIST_SEPARATOR) })
            .map(::foldArtist)
            .filter { it.isNotEmpty() }
            .toSet()

    private fun foldArtist(name: String): String = fold(channelless(name))

    /** What a stored song is: a file on the phone, an album's or a single's track, or a music video. */
    fun kindOf(isLocal: Boolean, album: String?, thumbnailUrl: String?): Kind = when {
        isLocal -> Kind.FILE
        !album.isNullOrBlank() -> Kind.SONG
        thumbnailUrl != null && VIDEO_FRAME.containsMatchIn(thumbnailUrl) -> Kind.MUSIC_VIDEO
        // No album and no video frame to say otherwise: a song whose album was not given.
        else -> Kind.SONG
    }

    /** Whether the copy's album is the song's single: an album named as the song is, or called a single. */
    fun isSingle(copy: Copy): Boolean {
        val album = copy.album?.takeIf { it.isNotBlank() } ?: return false
        if (SINGLE_SUFFIX.containsMatchIn(album)) return true
        return name(album, copy.artists) == name(copy.title, copy.artists)
    }

    /** Whether [a] and [b] are copies of one song. */
    fun sameSong(a: Copy, b: Copy): Boolean {
        if (a.songId == b.songId) return true
        val nameA = name(a.title, a.artists)
        if (nameA.isEmpty() || nameA != name(b.title, b.artists)) return false
        if (artistNames(a.artists).none { it in artistNames(b.artists) }) return false
        // Another recording of the song, said in the title or by the album it is on.
        if (recordingWords(a.title) != recordingWords(b.title)) return false
        if (recordingWords(a.album) != recordingWords(b.album)) return false
        // A length that is not known cannot say it is the same recording.
        if (a.duration <= 0 || b.duration <= 0) return false
        val video = a.kind == Kind.MUSIC_VIDEO || b.kind == Kind.MUSIC_VIDEO
        return abs(a.duration - b.duration) <= if (video) VIDEO_LENGTH_SEC else SAME_LENGTH_SEC
    }

    /**
     * The songs saved more than once among [copies], in the order their first copy comes, each
     * with the copy kept by default first and the others after it in the order of the same pick.
     * A copy is in one group at most: copies that match through a third are one song.
     */
    fun groups(copies: List<Copy>): List<Group> {
        if (copies.size < 2) return emptyList()
        // Only copies with the same name can match, apart from the same song twice, which has the
        // same name anyway: so each is compared with its own few, not with the whole list.
        val parent = IntArray(copies.size) { it }
        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            var j = i
            while (parent[j] != r) { val next = parent[j]; parent[j] = r; j = next }
            return r
        }
        val buckets = HashMap<String, MutableList<Int>>()
        copies.forEachIndexed { i, copy ->
            buckets.getOrPut(name(copy.title, copy.artists).ifEmpty { "\u0000" + copy.songId }) { mutableListOf() } += i
        }
        val bySongId = HashMap<String, Int>()
        copies.forEachIndexed { i, copy ->
            val first = bySongId.putIfAbsent(copy.songId, i)
            if (first != null) parent[root(i)] = root(first)
        }
        for (bucket in buckets.values) {
            if (bucket.size < 2) continue
            for (x in bucket.indices) for (y in x + 1 until bucket.size) {
                val i = bucket[x]
                val j = bucket[y]
                if (root(i) != root(j) && sameSong(copies[i], copies[j])) parent[root(j)] = root(i)
            }
        }
        val members = LinkedHashMap<Int, MutableList<Int>>()
        copies.indices.forEach { i -> members.getOrPut(root(i)) { mutableListOf() } += i }
        return members.values
            .filter { it.size > 1 }
            .sortedBy { it.min() }
            .map { indices -> Group(indices.sortedWith(keepOrder(copies)).map { copies[it] }) }
    }

    /**
     * The order a song's copies are kept in: the song before its music video, then the album's
     * track before the single's, then the one saved first. A file on the phone counts as a song.
     * The list's own order settles the rest, so the same list always gives the same pick.
     */
    private fun keepOrder(copies: List<Copy>): Comparator<Int> =
        compareBy<Int> { if (copies[it].kind == Kind.MUSIC_VIDEO) 1 else 0 }
            .thenBy { albumRank(copies[it]) }
            .thenBy { copies[it].savedAt ?: Long.MAX_VALUE }
            .thenBy { it }

    private fun albumRank(copy: Copy): Int = when {
        copy.album.isNullOrBlank() -> 2
        isSingle(copy) -> 1
        else -> 0
    }

    /** How many songs are saved more than once: the number on the button. */
    fun songCount(groups: List<Group>): Int = groups.size

    /** The copies that go when one of each song is kept, [picks] naming it by group (the default where it names none). */
    fun toTakeOut(groups: List<Group>, picks: Map<String, String> = emptyMap()): List<Copy> =
        groups.flatMap { group ->
            val kept = picks[group.id]?.takeIf { key -> group.copies.any { it.key == key } } ?: group.keptByDefault.key
            group.copies.filter { it.key != kept }
        }
}
