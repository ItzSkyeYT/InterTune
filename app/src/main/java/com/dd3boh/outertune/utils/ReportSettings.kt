/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import androidx.datastore.preferences.core.Preferences
import com.dd3boh.outertune.constants.AudioNormalizationKey
import com.dd3boh.outertune.constants.AudioOffloadKey
import com.dd3boh.outertune.constants.AudioQuality
import com.dd3boh.outertune.constants.AudioQualityKey
import com.dd3boh.outertune.constants.BatteryModeKey
import com.dd3boh.outertune.constants.DownloadOnWifiOnlyKey
import com.dd3boh.outertune.constants.DownloadPathKey
import com.dd3boh.outertune.constants.EnableBetterLyricsKey
import com.dd3boh.outertune.constants.EnableKugouKey
import com.dd3boh.outertune.constants.EnableLrcLibKey
import com.dd3boh.outertune.constants.EnableYouTubeCaptionsKey
import com.dd3boh.outertune.constants.EnableYouTubeLyricsKey
import com.dd3boh.outertune.constants.EqualizerEnabledKey
import com.dd3boh.outertune.constants.FollowPhoneSaverKey
import com.dd3boh.outertune.constants.InnerTubeCookieKey
import com.dd3boh.outertune.constants.LikedAutoDownloadKey
import com.dd3boh.outertune.constants.LikedAutodownloadMode
import com.dd3boh.outertune.constants.LyricSourcePrefKey
import com.dd3boh.outertune.constants.LyricTrimKey
import com.dd3boh.outertune.constants.LyricsSourceOrderKey
import com.dd3boh.outertune.constants.MaxSongCacheSizeKey
import com.dd3boh.outertune.constants.ProxyEnabledKey
import com.dd3boh.outertune.constants.ProxyTypeKey
import com.dd3boh.outertune.constants.RecogniseAutoAddKey
import com.dd3boh.outertune.constants.RecogniseKeepAwakeKey
import com.dd3boh.outertune.constants.RecogniseKeepListeningKey
import com.dd3boh.outertune.constants.RecogniseListenSecondsKey
import com.dd3boh.outertune.constants.RecognisePauseOnSpeakerKey
import com.dd3boh.outertune.constants.SkipOnErrorKey
import com.dd3boh.outertune.constants.SkipSilenceKey
import com.dd3boh.outertune.constants.SpatialAudioKey
import com.dd3boh.outertune.constants.SpatialAudioMode
import com.dd3boh.outertune.constants.TransitionFadeKey
import com.dd3boh.outertune.playback.BatteryUse
import com.dd3boh.outertune.recognition.MicrophoneListener
import com.dd3boh.outertune.ui.screens.isAccountCookie

/**
 * The few settings that matter for the kind of problem being reported, for Report a problem. His
 * idea of 2 Nov 2026, with his guard: never the whole list, only the ones that bear on the area of
 * the problem, and above all those changed from their default, since that is where bugs hide. A
 * report with Skip silence on and the quality on High says more than a hundred lines of log about
 * a song that skips ahead.
 *
 * Only the values are shown, and only as words: "on", "high", "custom". Never a folder, an address,
 * a name, an account or a song. The proxy's address stays on the phone; whether one is in use does
 * not. The same list names the settings the trail may record as changed ([ReportTrail.setting]):
 * a setting that is not here is never written down, whatever it holds.
 *
 * Each default is the one the settings screens and the code that reads the setting use. The test
 * reads the source to hold them to it (ReportSettingsTest).
 */
object ReportSettings {
    /** What a problem is about: playback unless the log or the screens say otherwise. */
    enum class Area(val title: String) {
        PLAYBACK("playback"), DOWNLOADS("downloads"), LYRICS("lyrics"), RECOGNITION("recognition"), CASTING("casting")
    }

    /** One setting: how it reads when shown, and whether it is other than the default. */
    sealed class Spec(val label: String) {
        abstract val key: Preferences.Key<*>
        abstract fun shown(prefs: Preferences): String
        abstract fun changed(prefs: Preferences): Boolean
    }

    class Flag(override val key: Preferences.Key<Boolean>, label: String, val default: Boolean) : Spec(label) {
        override fun shown(prefs: Preferences) = if (prefs[key] ?: default) "on" else "off"
        override fun changed(prefs: Preferences) = (prefs[key] ?: default) != default
    }

    /** A setting that is one of an enum's names, shown in words: LIKED_ONLY is "liked only". */
    class Choice(override val key: Preferences.Key<String>, label: String, val default: String) : Spec(label) {
        override fun shown(prefs: Preferences) = (prefs[key] ?: default).lowercase().replace('_', ' ')
        override fun changed(prefs: Preferences) = (prefs[key] ?: default) != default
    }

    class Count(override val key: Preferences.Key<Int>, label: String, val default: Int, val unit: String) : Spec(label) {
        override fun shown(prefs: Preferences) = "${prefs[key] ?: default} $unit"
        override fun changed(prefs: Preferences) = (prefs[key] ?: default) != default
    }

    /** A setting that holds something personal (a folder, an address): only whether it was set. */
    class Custom(override val key: Preferences.Key<String>, label: String, val word: String) : Spec(label) {
        override fun shown(prefs: Preferences) = if (prefs[key].isNullOrEmpty()) "default" else word
        override fun changed(prefs: Preferences) = !prefs[key].isNullOrEmpty()
    }

    /** The proxy: off, or on and of which kind. The address never. */
    private object ProxySpec : Spec("proxy") {
        override val key: Preferences.Key<*> = ProxyEnabledKey
        override fun shown(prefs: Preferences) =
            if (prefs[ProxyEnabledKey] == true) "on, ${(prefs[ProxyTypeKey] ?: "HTTP").lowercase()}" else "off"

        override fun changed(prefs: Preferences) = prefs[ProxyEnabledKey] == true
    }

    private val audioQuality = Choice(AudioQualityKey, "audio quality", AudioQuality.AUTO.name)
    private val normalisation = Flag(AudioNormalizationKey, "normalisation", true)
    private val skipSilence = Flag(SkipSilenceKey, "skip silence", false)
    private val offload = Flag(AudioOffloadKey, "offload", false)
    private val equalizer = Flag(EqualizerEnabledKey, "equalizer", false)
    private val spatial = Choice(SpatialAudioKey, "spatial audio", SpatialAudioMode.OFF.name)
    private val fade = Flag(TransitionFadeKey, "transition fade", false)
    private val skipOnError = Flag(SkipOnErrorKey, "skip on error", false)
    private val battery = Choice(BatteryModeKey, "battery use", BatteryUse.DEFAULT.name)

    private val wifiOnly = Flag(DownloadOnWifiOnlyKey, "Wi-Fi only", false)
    private val likedDownload = Choice(LikedAutoDownloadKey, "download liked songs", LikedAutodownloadMode.OFF.name)
    private val downloadFolder = Custom(DownloadPathKey, "download folder", "custom")

    private val kuGou = Flag(EnableKugouKey, "KuGou", true)
    private val lrcLib = Flag(EnableLrcLibKey, "LrcLib", true)
    private val betterLyrics = Flag(EnableBetterLyricsKey, "Better Lyrics", true)
    private val youTubeLyrics = Flag(EnableYouTubeLyricsKey, "YouTube Music lyrics", true)
    private val youTubeCaptions = Flag(EnableYouTubeCaptionsKey, "YouTube captions", true)
    private val localFirst = Flag(LyricSourcePrefKey, "prefer saved lyrics", true)
    private val trim = Flag(LyricTrimKey, "trim lyrics", false)
    private val sourceOrder = Custom(LyricsSourceOrderKey, "source order", "custom")

    private val keepListening = Flag(RecogniseKeepListeningKey, "keep listening", false)
    private val pauseOnSpeaker = Flag(RecognisePauseOnSpeakerKey, "pause on speaker", true)
    private val listenSeconds = Count(RecogniseListenSecondsKey, "listen length", MicrophoneListener.DEFAULT_SECONDS, "s")
    private val autoAdd = Flag(RecogniseAutoAddKey, "add matches to the playlist", true)
    private val keepAwake = Flag(RecogniseKeepAwakeKey, "keep the screen awake", false)

    /** Each area's settings, in the order they are shown. */
    private val AREAS: Map<Area, List<Spec>> = mapOf(
        Area.PLAYBACK to listOf(audioQuality, normalisation, skipSilence, offload, equalizer, spatial, fade, skipOnError, ProxySpec, battery),
        Area.DOWNLOADS to listOf(audioQuality, wifiOnly, likedDownload, downloadFolder, ProxySpec, battery),
        Area.LYRICS to listOf(kuGou, lrcLib, betterLyrics, youTubeLyrics, youTubeCaptions, localFirst, trim, sourceOrder),
        Area.RECOGNITION to listOf(keepListening, pauseOnSpeaker, listenSeconds, autoAdd, keepAwake),
        Area.CASTING to listOf(audioQuality, ProxySpec, battery),
    )

    /** Every setting that can be shown, once: the ones the trail may name when they change. */
    val all: List<Spec> = AREAS.values.flatten().distinctBy { it.label }

    fun specs(area: Area): List<Spec> = AREAS.getValue(area)

    /** A setting shown, and whether it is other than its default. */
    class Item(val label: String, val value: String, val changed: Boolean) {
        override fun toString() = "$label $value${if (changed) " *" else ""}"
    }

    fun items(area: Area, prefs: Preferences): List<Item> =
        specs(area).map { Item(it.label, it.shown(prefs), it.changed(prefs)) }

    /** Whether the account is signed in, told the way setup and Settings tell it. The cookie itself is never read out. */
    fun signedIn(prefs: Preferences): Boolean = runCatching { isAccountCookie(prefs[InnerTubeCookieKey].orEmpty()) }.getOrDefault(false)

    /**
     * The song cache in words, as the rule every reader of the size asks gives it ([BatteryUse.songCacheMb]): a size
     * never stored is off, or 512 MB in Saver. Not a setting with a default of its own, since a new install has 256
     * and an old one none. The phone's own battery saver, which can turn Saver on, is in the report's "Phone now".
     */
    fun songCache(prefs: Preferences): String {
        val mode = BatteryUse.modeOf(prefs[BatteryModeKey], prefs[FollowPhoneSaverKey], phoneSaver = false)
        return when (val mb = BatteryUse.songCacheMb(prefs[MaxSongCacheSizeKey], mode)) {
            0 -> "off"
            -1 -> "no limit"
            else -> "$mb MB"
        }
    }

    /**
     * What the report says of the settings, as lines short enough to read on a phone without sliding the text:
     * those changed from their default first, one to a line and marked with a star, then the rest at theirs,
     * then what is not a setting but bears on the area ([extra] and whether the account is signed in).
     */
    fun lines(area: Area, prefs: Preferences, extra: List<String> = emptyList(), now: Boolean = false): List<String> {
        val items = items(area, prefs)
        val changed = items.filter { it.changed }
        val rest = items.filter { !it.changed }
        val facts = buildList {
            add(if (signedIn(prefs)) "signed in" else "signed out")
            if (area == Area.PLAYBACK || area == Area.DOWNLOADS) add("song cache ${songCache(prefs)}")
            addAll(extra)
        }
        // A report of a run before this one carries the settings as they are now, which may not be as they were.
        val title = (if (now) "Settings now (" else "Settings (") + area.title + ")"
        return buildList {
            if (changed.isEmpty()) add("$title: none changed")
            else {
                add("$title, * = not the default:")
                changed.forEach { add("* ${it.label} ${it.value}") }
            }
            if (rest.isNotEmpty()) addAll(wrapped("At default: ", rest.map { "${it.label} ${it.value}" }))
            addAll(wrapped("Also: ", facts))
        }
    }

    /** The widest a line of the settings is, in characters: about what a phone shows of the report without sliding it. */
    const val WIDTH = 44

    /** [items] after [lead], separated by commas, in lines of [WIDTH] at most where an item allows, the next ones indented. */
    fun wrapped(lead: String, items: List<String>): List<String> {
        val lines = mutableListOf<String>()
        var line = lead
        var first = true
        for (item in items) {
            val piece = if (first) item else ", $item"
            // The comma stays at the end of the line it follows.
            if (!first && (line + piece).length > WIDTH) {
                lines += "$line,"
                line = "  $item"
            } else line += piece
            first = false
        }
        lines += line
        return lines
    }

    /** What changed between two readings of the settings: the setting's name and its new value, for the trail. */
    fun changes(before: Preferences, after: Preferences): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (spec in all) {
            val was = spec.shown(before)
            val now = spec.shown(after)
            if (was != now) out += spec.label to now
        }
        return out
    }

    // ---- which area ----

    /** The area a log tag belongs to, or null when it says nothing of one (the app's own, a library's). */
    fun areaOfTag(tag: String): Area? = when {
        tag.startsWith("Lyric") || tag == "KeptLyrics" -> Area.LYRICS
        tag.startsWith("Recogni") || tag == "ShazamClient" || tag == "MicrophoneListener" -> Area.RECOGNITION
        tag.startsWith("Cast") || tag.startsWith("FCast") || tag.startsWith("Dlna") || tag.startsWith("Chromecast") ||
            tag == "RemotePlayer" || tag == "RoutedPlayer" || tag == "Ssdp" -> Area.CASTING
        tag.startsWith("Download") -> Area.DOWNLOADS
        tag in PLAYBACK_TAGS -> Area.PLAYBACK
        else -> null
    }

    private val PLAYBACK_TAGS = setOf(
        "MusicService", "YTPlayerUtils", "QueueBoard", "StandIns", "Throttle", "PlayerConnection", "MediaLibrarySessionCallback",
        "ExoPlayerImplInternal", "MediaCodecAudioRenderer", "MediaCodecRenderer", "LoadTask", "DefaultAudioSink",
        "SimpleCache", "CacheDataSink", "SabrOver", "SabrDataSource", "PaceGuard", "TransitionFade",
    )

    /** The area a screen's route belongs to, or null. */
    fun areaOfScreen(route: String): Area? = when {
        "recogni" in route -> Area.RECOGNITION
        "lyrics" in route -> Area.LYRICS
        "download" in route -> Area.DOWNLOADS
        "cast" in route -> Area.CASTING
        else -> null
    }

    /**
     * The area of a problem: that of the fault's tag when there is a fault, otherwise of the latest warning
     * or error whose tag belongs to one, otherwise of the screen the person was last on, otherwise playback.
     * [recentTags] are the tags of the log's warnings and errors, the latest last.
     */
    fun areaOf(faultTag: String?, recentTags: List<String>, lastScreen: String? = null): Area {
        faultTag?.let { areaOfTag(it) }?.let { return it }
        if (faultTag == null) {
            recentTags.asReversed().firstNotNullOfOrNull { areaOfTag(it) }?.let { return it }
            lastScreen?.let { areaOfScreen(it) }?.let { return it }
        }
        return Area.PLAYBACK
    }
}
