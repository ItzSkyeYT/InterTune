/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale

/** One button under an announcement: what it says, and the web address it opens. */
data class AnnouncementAction(val label: String, val url: String)

/** An announcement's words in one language. */
data class AnnouncementText(
    /** One line, for the banner at the top of Home. */
    val banner: String,
    val title: String,
    val body: String?,
    /** At most [AnnouncementParser.MAX_ACTIONS]. The first is the main one. */
    val actions: List<AnnouncementAction>,
)

/**
 * Something to say rather than something to ask.
 *
 * Same document, same fetch, same version bounds and expiry as a poll, because an announcement
 * that outlives its release is worse than no announcement. There is nothing to answer, so what it
 * offers instead is a few buttons that open web pages, and pictures.
 */
data class Announcement(
    val id: String,
    /** The words as the document writes them, and what the view count records. */
    val text: AnnouncementText,
    /** The same words in other languages, keyed by lower case language tag: "fr", "pt-br". */
    val translations: Map<String, AnnouncementText>,
    /** The picture at the top. */
    val heroUrl: String?,
    /** More pictures, in a row under the words. */
    val gallery: List<String>,
) {
    /**
     * The words for somebody reading in [locales], most preferred first: language and region
     * together, then the language alone, then the document's own words.
     */
    fun textFor(locales: List<Locale>): AnnouncementText {
        if (translations.isEmpty()) return text
        for (locale in locales) {
            val language = AnnouncementParser.modernLanguage(locale.language.lowercase(Locale.ROOT))
            val region = locale.country.lowercase(Locale.ROOT)
            if (region.isNotEmpty()) translations["$language-$region"]?.let { return it }
            translations[language]?.let { return it }
        }
        return text
    }
}

/**
 * Reads the "announcements" list of the poll document.
 *
 * The document is written by hand, so this assumes it will be wrong in places. An entry that
 * cannot be read, is meant for another version, has not started or is over is left out, and never
 * takes the others with it. A field that is present but unreadable, such as a date nobody can
 * parse, drops its entry rather than being ignored: an announcement that shows too early or never
 * stops is worse than one that does not show until the typo is fixed.
 *
 * kotlinx.serialization's tree rather than org.json, so that the rules can be unit tested: this
 * module's tests stub the Android classes, org.json among them.
 */
object AnnouncementParser {
    /** Buttons beyond this are left out: a column of five buttons is a menu, not a notice. */
    const val MAX_ACTIONS = 3

    /** Pictures beyond this are left out, as each is a download on somebody's data plan. */
    const val MAX_PICTURES = 10

    /**
     * Below this a timestamp was written in seconds rather than milliseconds: as milliseconds it
     * would fall in the first days of 1973, and no announcement was ever meant for then.
     */
    private const val SECONDS_BEFORE = 100_000_000_000L

    fun parse(document: String, versionCode: Int, now: Long): List<Announcement> {
        val root = runCatching { Json.parseToJsonElement(document) }.getOrNull() as? JsonObject
            ?: return emptyList()
        val list = root["announcements"] as? JsonArray ?: return emptyList()
        return list.mapNotNull { entry ->
            runCatching { (entry as? JsonObject)?.let { read(it, versionCode, now) } }.getOrNull()
        }
    }

    private fun read(o: JsonObject, versionCode: Int, now: Long): Announcement? {
        // A number is taken as written, since "id": 3 is an easy thing to type.
        val id = (o["id"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.ifEmpty { null }
            ?: return null

        val min = o.number("minVersionCode", 0) ?: return null
        val max = o.number("maxVersionCode", Int.MAX_VALUE) ?: return null
        if (versionCode < min || versionCode > max) return null

        // Absent is no bound. Present and unreadable throws, which drops the entry.
        val starts = o.time("startsAt", endOfDay = false)
        if (starts != null && now < starts) return null
        val expires = o.time("expiresAt", endOfDay = true)
        // Zero has always meant "never", in documents written before dates could be words.
        if (expires != null && expires != 0L && now >= expires) return null

        val text = AnnouncementText(
            banner = o.text("banner") ?: return null,
            title = o.text("title") ?: return null,
            body = o.text("body"),
            actions = actions(o),
        )

        val translations = (o["translations"] as? JsonObject)?.entries?.mapNotNull { (tag, value) ->
            val key = languageKey(tag) ?: return@mapNotNull null
            val t = value as? JsonObject ?: return@mapNotNull null
            key to AnnouncementText(
                banner = t.text("banner") ?: text.banner,
                title = t.text("title") ?: text.title,
                body = t.text("body") ?: text.body,
                actions = translatedActions(t, text.actions),
            )
        }?.toMap().orEmpty()

        // "image" is the picture at the top. Without one, the first of "images" takes its place,
        // so a document can list its pictures in one place and still get a header.
        val listed = (o["images"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.string()?.let(::webUrl) }
        val hero = o.text("image")?.let(::webUrl) ?: listed.firstOrNull()
        val gallery = listed.filter { it != hero }.distinct().take(MAX_PICTURES - if (hero != null) 1 else 0)

        return Announcement(id, text, translations, hero, gallery)
    }

    /**
     * The buttons, from "actions", or from the single actionLabel and actionUrl every document
     * before it used. Each needs both a label and a web address: a button with no address does
     * nothing, and one with no label cannot be read.
     */
    private fun actions(o: JsonObject): List<AnnouncementAction> {
        val list = o["actions"] as? JsonArray
        if (list == null) {
            val label = o.text("actionLabel") ?: return emptyList()
            val url = o.text("actionUrl")?.let(::webUrl) ?: return emptyList()
            return listOf(AnnouncementAction(label, url))
        }
        return list.mapNotNull { item ->
            val a = item as? JsonObject ?: return@mapNotNull null
            val label = a.text("label") ?: return@mapNotNull null
            val url = a.text("url")?.let(::webUrl) ?: return@mapNotNull null
            AnnouncementAction(label, url)
        }.take(MAX_ACTIONS)
    }

    /**
     * A translation's buttons. A translated button may leave out its address, which is then taken
     * from the button in the same place in the document's own list: a translator changes words,
     * not links.
     */
    private fun translatedActions(t: JsonObject, base: List<AnnouncementAction>): List<AnnouncementAction> {
        val list = t["actions"] as? JsonArray ?: return base
        val translated = list.mapIndexedNotNull { i, item ->
            val a = item as? JsonObject ?: return@mapIndexedNotNull null
            val label = a.text("label") ?: return@mapIndexedNotNull null
            val url = a.text("url")?.let(::webUrl) ?: base.getOrNull(i)?.url ?: return@mapIndexedNotNull null
            AnnouncementAction(label, url)
        }.take(MAX_ACTIONS)
        return translated.ifEmpty { base }
    }

    /**
     * A web address, or null. Full addresses keep what follows the scheme as written, with the
     * scheme in lower case, since Android matches schemes exactly and autocorrect writes "Https".
     * A bare one such as discord.gg/abc opens as https. Nothing else becomes a button or a picture:
     * not intent:, not javascript:, not a file on the phone.
     */
    fun webUrl(raw: String): String? {
        val s = raw.trim()
        FULL.matchEntire(s)?.let { m ->
            val scheme = m.groupValues[1]
            return scheme.lowercase(Locale.ROOT) + s.substring(scheme.length)
        }
        if (BARE.matches(s)) return "https://$s"
        return null
    }

    private val FULL = Regex("""(?i:(https?))://[^\s/?#]+[^\s]*""")
    private val BARE = Regex("""[a-z0-9][a-z0-9-]*(\.[a-z0-9][a-z0-9-]*)*\.[a-z]{2,}(/\S*)?""")

    /**
     * Milliseconds since 1970 from a number or a date written out. A date alone is midnight UTC,
     * at the start of the day for a start and the end of it for an expiry, so "expiresAt":
     * "2026-10-31" still shows on the 31st. A time without a zone is UTC too. A number that is
     * plainly seconds is read as seconds.
     */
    internal fun parseTime(value: JsonPrimitive, endOfDay: Boolean): Long? {
        if (!value.isString) {
            val n = value.longOrNull ?: return null
            return if (n in 1 until SECONDS_BEFORE) n * 1000 else n
        }
        val s = value.content.trim()
        runCatching { return Instant.parse(s).toEpochMilli() }
        runCatching { return OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        runCatching { return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli() }
        runCatching {
            val day = LocalDate.parse(s)
            return (if (endOfDay) day.plusDays(1) else day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }
        return null
    }

    /** "pt_BR", "PT-br" and "pt-br" are one key; Android's old names for three languages too. */
    internal fun languageKey(tag: String): String? {
        val parts = tag.trim().replace('_', '-').lowercase(Locale.ROOT).split('-')
        val language = modernLanguage(parts[0])
        if (!Regex("[a-z]{2,3}").matches(language)) return null
        val region = parts.getOrNull(1)?.takeIf { Regex("[a-z0-9]{2,8}").matches(it) }
        return if (region != null) "$language-$region" else language
    }

    /** Android still reports Hebrew, Indonesian and Yiddish by their pre-1989 codes. */
    internal fun modernLanguage(code: String): String = when (code) {
        "iw" -> "he"
        "in" -> "id"
        "ji" -> "yi"
        else -> code
    }

    /** The trimmed string at [key], or null when it is missing, null, not a string or blank. */
    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.string()

    private fun JsonPrimitive.string(): String? =
        takeIf { it !is JsonNull && it.isString }?.content?.trim()?.ifEmpty { null }

    /** [default] when absent, null when present and not a whole number. */
    private fun JsonObject.number(key: String, default: Int): Int? {
        val value = this[key] ?: return default
        if (value is JsonNull) return default
        return (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    }

    /** Null when absent; throws when present and unreadable, which drops the entry. */
    private fun JsonObject.time(key: String, endOfDay: Boolean): Long? {
        val value: JsonElement = this[key] ?: return null
        if (value is JsonNull) return null
        val primitive = value as? JsonPrimitive ?: throw IllegalArgumentException("$key is not a date")
        return parseTime(primitive, endOfDay) ?: throw IllegalArgumentException("$key is not a date")
    }
}
