/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** How a rung names the song to the renderer. */
enum class RungLabels {
    /**
     * As DLNA has it: the renderer's own spelling of the type, its profile (DLNA.ORG_PN) where there
     * is one, OP, CI and the flags, in the track's metadata and in the contentFeatures header.
     */
    DLNA,

    /** As a plain web server would: the type as InterTune writes it, and nothing else. */
    PLAIN,
}

/** What a rung sends. */
sealed interface RungForm {
    val key: String

    /** The song as the app has it, of [mimeType]. */
    data class AsIs(val mimeType: String) : RungForm {
        override val key: String get() = "asis:${CastMime.bare(mimeType)}"
    }

    /** The song fetched again as AAC in MP4. */
    data object Aac : RungForm {
        override val key: String get() = "aac"
    }

    /** The song decoded on the phone into raw audio of [form]. */
    data class Raw(val form: PcmForm) : RungForm {
        override val key: String get() = form.key
    }
}

/** One way of sending a song to a renderer: a form and how it is labelled. */
data class CastRung(val form: RungForm, val labels: RungLabels) {
    /** Stable from one cast to the next, for [CastRungMemory]: `l16-44100-2:plain`. */
    val key: String get() = "${form.key}:${labels.name.lowercase()}"

    override fun toString() = key
}

/**
 * The ways a song can go to a DLNA renderer, best first, for a renderer that takes some and not
 * others, his word of 5 Nov 2026: "the casting thing should try to work on as many speakers as
 * possible, as well as adapt to the speaker".
 *
 * The forms, each only where the renderer's list allows it, or all where it lists nothing: the
 * song as it is (no conversion at all), AAC in MP4 (a second fetch, no decoding), raw audio as
 * audio/L16 at the rates the renderer lists, the song's own rate first so nothing is resampled,
 * and the same raw audio in a WAV file. Never L8. Each form goes first with DLNA's labels, which
 * renderers that list profiles want, and then plain, which renderers that reject a profile or a
 * flag they do not know want; real ones are found on both sides.
 *
 * Kept short: two L16 rates at most, and one WAV. A renderer is tried down the ladder only the
 * first time (CastRungMemory), so a long first walk is paid once per speaker.
 */
object CastLadder {
    /** L16 rates tried at most: the song's own, or the nearest, and one more. */
    const val MOST_RATES = 2

    /**
     * The rungs for a song of [mimeType] on a renderer that said [support], best first.
     * [canFetchAac] is false for a song on the phone; [canDecode] is Unreleased.CAST_PCM.
     * [sourceRate] and [sourceChannels] are what the song decodes to, as far as known (see
     * [estimatedRate]): they order the raw rates.
     */
    fun rungs(
        support: FormatSupport,
        mimeType: String,
        canFetchAac: Boolean,
        canDecode: Boolean,
        sourceRate: Int,
        sourceChannels: Int = 2,
    ): List<CastRung> = forms(support, mimeType, canFetchAac, canDecode, sourceRate, sourceChannels)
        .flatMap { listOf(CastRung(it, RungLabels.DLNA), CastRung(it, RungLabels.PLAIN)) }

    /** The forms of [rungs], in order, each once. */
    fun forms(
        support: FormatSupport,
        mimeType: String,
        canFetchAac: Boolean,
        canDecode: Boolean,
        sourceRate: Int,
        sourceChannels: Int = 2,
    ): List<RungForm> = buildList {
        val types = (support as? FormatSupport.Listed)?.mimeTypes
        val aacSong = CastFormats.plays(setOf(CastFormats.AAC_MIME), mimeType)
        if (types == null || CastFormats.plays(types, mimeType) || (aacSong && CastFormats.takesAac(types))) {
            add(RungForm.AsIs(CastMime.bare(mimeType)))
        }
        if (canFetchAac && !aacSong && (types == null || CastFormats.takesAac(types))) add(RungForm.Aac)
        if (canDecode) addAll(rawForms(support, sourceRate, sourceChannels).map(RungForm::Raw))
    }

    /**
     * The raw audio a renderer that said [support] takes, best first: L16 at up to [MOST_RATES]
     * rates (an L16 entry that names none is taken at both of the LPCM profile's, CD audio's
     * first), then WAV. A renderer that said nothing gets both, as if it listed L16 with no rate.
     */
    fun rawForms(support: FormatSupport, sourceRate: Int, sourceChannels: Int): List<PcmForm> {
        val target = when (support) {
            FormatSupport.Unknown -> PcmTarget(listOf(PcmOffer("audio/L16")), wav = "audio/wav")
            is FormatSupport.Listed -> PcmFormats.target(support) ?: return emptyList()
        }
        val l16 = if (target.l16.isEmpty()) {
            emptyList()
        } else {
            PcmFormats.l16Forms(target, sourceRate, sourceChannels, bareRates = PcmFormats.LPCM_RATES).take(MOST_RATES)
        }
        val wav = target.wav?.let { PcmFormats.pick(PcmTarget(emptyList(), it), sourceRate, sourceChannels) }
        return l16 + listOfNotNull(wav)
    }

    /**
     * The rate a song of [mimeType] most likely decodes at, before anything has been decoded: Opus,
     * which is what YouTube's WebM holds, always at 48 kHz; YouTube's AAC at 44.1. Only an order:
     * the walk reorders the raw rungs by the real rate before it tries the first (RemotePlayer).
     */
    fun estimatedRate(mimeType: String): Int = when (CastMime.bare(mimeType)) {
        "audio/webm", "video/webm", "audio/ogg", "audio/opus" -> 48_000
        else -> 44_100
    }

    /**
     * [rest], the rungs not tried yet, once the song has been decoded: the raw ones put in the order
     * of the rate it really decodes at ([decoded]: rate and channels), or taken out when it cannot
     * be decoded ([decoded] null), after the others as always, and the [remembered] rung still first.
     */
    fun afterDecoding(rest: List<CastRung>, support: FormatSupport, decoded: Pair<Int, Int>?, remembered: String?): List<CastRung> {
        val raw = if (decoded == null) {
            emptyList()
        } else {
            rawForms(support, decoded.first, decoded.second)
                .flatMap { listOf(CastRung(RungForm.Raw(it), RungLabels.DLNA), CastRung(RungForm.Raw(it), RungLabels.PLAIN)) }
        }
        return startingAt(rest.filter { it.form !is RungForm.Raw } + raw, remembered)
    }

    /** [rungs] with the one under [remembered] first, when it is among them; as they are otherwise. */
    fun startingAt(rungs: List<CastRung>, remembered: String?): List<CastRung> {
        val first = rungs.firstOrNull { it.key == remembered } ?: return rungs
        return listOf(first) + (rungs - first)
    }
}

/** What one try of a rung came to (RungJudge). */
sealed interface RungAnswer {
    /** The renderer plays it: it said so, and it read the song. */
    data object Plays : RungAnswer

    /**
     * Turned away before the song was read, or after a look at its start: a fault at
     * SetAVTransportURI or Play, an error with little read, a fetch dropped at once. The labels
     * may be what it disliked, so the same form goes again labelled the other way.
     */
    data class Refused(val why: String) : RungAnswer

    /** Never asked for the song in [ms] after it was told to play it. */
    data class NeverFetched(val ms: Long) : RungAnswer

    /**
     * Read the song, [bytes] of it, and failed or stopped: it took the labels and not what they
     * named, so the same form labelled the other way is not worth a try.
     */
    data class FailedOnContent(val why: String, val bytes: Long) : RungAnswer
}

/** What the cast server saw of one url: requests (HEAD included), bytes of body sent, connections still open. */
data class FetchStats(val requests: Int = 0, val bodyBytes: Long = 0, val open: Int = 0, val lastActivityAt: Long = 0)

/**
 * Whether one try of a rung plays, failed, or has to be waited for: from what the renderer said
 * (DlnaLink's status for that load, a fault it answered with) and what the cast server saw it
 * fetch, at [RungJudge.judge]'s every look.
 */
object RungJudge {
    /** How long a renderer told to play may take to ask for the song; slow speakers need several seconds. */
    const val FETCH_TIMEOUT_MS = 9_000L

    /** How long a renderer that has dropped every connection is given to come back before it is taken to have stopped. */
    const val STOP_GRACE_MS = 3_000L

    /** How long a renderer that read the song may take to say it plays. */
    const val PLAY_TIMEOUT_MS = 25_000L

    /** Bytes read that show a renderer took the labels and went into the song itself. */
    const val READ_ENOUGH = 64L * 1024

    /**
     * The answer for a try sent at [sentAt] and told to play at [playAskedAt] (-1 while it waits
     * paused), now at [now], all on one clock; null while it has to be waited for. [refusal] is
     * the fault load threw, [state] and [error] what the renderer last said of this load.
     */
    fun judge(
        refusal: String?,
        state: ReceiverState,
        error: String?,
        fetch: FetchStats,
        playAskedAt: Long,
        now: Long,
    ): RungAnswer? {
        if (refusal != null) return RungAnswer.Refused(refusal)
        val read = fetch.bodyBytes
        when (state) {
            ReceiverState.PLAYING, ReceiverState.ENDED -> if (read > 0) return RungAnswer.Plays
            ReceiverState.FAILED -> {
                val why = error ?: "It failed"
                return if (read >= READ_ENOUGH) RungAnswer.FailedOnContent(why, read) else RungAnswer.Refused(why)
            }
            else -> Unit
        }
        if (playAskedAt < 0) return null
        if (fetch.requests == 0) {
            return if (now - playAskedAt >= FETCH_TIMEOUT_MS) RungAnswer.NeverFetched(now - playAskedAt) else null
        }
        val quiet = fetch.open == 0 && now - fetch.lastActivityAt >= STOP_GRACE_MS
        if (quiet && read < READ_ENOUGH) return RungAnswer.Refused("It fetched ${read / 1024} KB and stopped")
        if (now - playAskedAt >= PLAY_TIMEOUT_MS) return RungAnswer.FailedOnContent("It read the song and never played it", read)
        return null
    }
}

/**
 * One song's way down the ladder: the rung being tried, and the next after an answer. A rung the
 * renderer read and failed on its content skips its own plainly labelled twin, which carries the
 * same bytes. Three in a row never fetched end the walk: the renderer most likely cannot reach
 * the phone at all ([cannotReach]), and every rung would wait out its time for nothing.
 */
class LadderWalk(rungs: List<CastRung>) {
    /** The rungs in the order they are tried. */
    var rungs: List<CastRung> = rungs
        private set
    private var index = 0
    private val answers = mutableListOf<Pair<CastRung, RungAnswer>>()

    /** The rung to try now, or null once none is left. */
    val current: CastRung? get() = rungs.getOrNull(index)

    /** Every rung tried so far, with its answer, in order. */
    val tried: List<Pair<CastRung, RungAnswer>> get() = answers

    /** Whether the walk ended because the renderer never fetched anything three times in a row. */
    var cannotReach = false
        private set

    /** Takes [answer] for [current] and moves on: the next rung to try, or null when the walk is over. */
    fun next(answer: RungAnswer): CastRung? {
        val rung = current ?: return null
        answers += rung to answer
        if (answer == RungAnswer.Plays) {
            index = rungs.size
            return null
        }
        if (answers.size >= NEVER_FETCHED_IN_A_ROW && answers.takeLast(NEVER_FETCHED_IN_A_ROW).all { it.second is RungAnswer.NeverFetched }) {
            cannotReach = true
            index = rungs.size
            return null
        }
        var next = index + 1
        while (next < rungs.size && skips(rung, answer, rungs[next])) next++
        index = next
        return current
    }

    /**
     * Puts [rest] in place of the rungs not tried yet, from [current] on, less any tried already:
     * the raw rungs again, ordered by the rate the song really decodes at once it is known.
     */
    fun replaceRest(rest: List<CastRung>) {
        rungs = rungs.take(index) + rest.filter { candidate -> answers.none { it.first == candidate } }
    }

    /** Whether [candidate] is not worth a try after [rung] answered [answer]. */
    private fun skips(rung: CastRung, answer: RungAnswer, candidate: CastRung): Boolean {
        // The same bytes, the other labels, after the renderer took the labels and failed on the bytes.
        if (answer is RungAnswer.FailedOnContent && candidate.form == rung.form) return true
        // Tried already: the remembered rung, moved to the front, comes again in its own place.
        return answers.any { it.first == candidate }
    }

    private companion object {
        const val NEVER_FETCHED_IN_A_ROW = 3
    }
}

/**
 * The rung that last played on each renderer, so the next song, and the next cast, starts there:
 * by its UDN, or, for one without, by its make and model. On the phone only (a file in
 * noBackupFilesDir: another phone on another network starts from the top). A remembered rung that
 * fails is forgotten. Thread safe; [file] null keeps it in memory, for the tests.
 */
class CastRungMemory(private val file: File?) {
    private val rungs = LinkedHashMap<String, String>()
    private var loaded = false

    @Synchronized
    fun rungFor(speaker: String): String? {
        load()
        return rungs[speaker]
    }

    @Synchronized
    fun remember(speaker: String, rung: String) {
        load()
        if (rungs[speaker] == rung) return
        rungs.remove(speaker)
        rungs[speaker] = rung
        while (rungs.size > MOST) rungs.remove(rungs.keys.first())
        save()
    }

    @Synchronized
    fun forget(speaker: String) {
        load()
        if (rungs.remove(speaker) != null) save()
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val text = runCatching { file?.takeIf { it.exists() }?.readText() }.getOrNull() ?: return
        runCatching {
            for ((speaker, rung) in Json.parseToJsonElement(text).jsonObject) {
                rung.jsonPrimitive.contentOrNull?.let { rungs[speaker] = it }
            }
        }
    }

    private fun save() {
        val target = file ?: return
        runCatching {
            target.writeText(JsonObject(rungs.mapValues { JsonPrimitive(it.value) }).toString())
        }
    }

    companion object {
        const val FILE = "cast-rungs.json"
        const val MOST = 50

        /**
         * Which renderer [device] is, for remembering: its UDN, or its make and model when its
         * description gave no UDN (DlnaLink then names it by its address, which changes); null
         * when it has neither.
         */
        fun speakerOf(device: CastDevice): String? = when {
            device.id.startsWith("uuid:", ignoreCase = true) -> "udn:${device.id.lowercase()}"
            !device.model.isNullOrBlank() -> "model:${device.model}"
            else -> null
        }
    }
}

/** The listener's words for a song no rung could play (the app's, in its language; English here for the tests). */
interface CastWords {
    /** Every rung the renderer allows was tried and none played. */
    fun refusedEveryForm(speaker: String): String

    /** The renderer never once asked the phone for the song. */
    fun neverAsked(speaker: String): String

    object English : CastWords {
        override fun refusedEveryForm(speaker: String) =
            "$speaker did not play this song in any form it takes. Play it on this phone, or cast to another speaker."

        override fun neverAsked(speaker: String) =
            "$speaker never asked this phone for the song. Check that both are on the same Wi-Fi, and that it lets devices reach each other."
    }
}
