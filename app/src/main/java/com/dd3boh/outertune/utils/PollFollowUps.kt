/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.constants.Polls
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Follow-up questions in a poll (Unreleased.POLL_FOLLOW_UPS): an option of the document may carry
 * "then", a question of its own that comes right after the first one, in the same card, for whoever
 * picked that option. Everything goes in the one answer the poll sends, a follow-up's picks as
 * "<optionId>/<followUpOptionId>" beside the first question's ids (PollChecker.answerData).
 *
 * Read with kotlinx rather than org.json, as the audience of an entry is (AnnouncementParser), so the
 * reading runs in plain JVM tests. A "then" the app cannot read is dropped, and only it: the option
 * and the poll stay as they are, exactly as in a build that has never heard of follow-ups.
 */
internal object PollFollowUps {
    /** Between an option's id and its follow-up's option id in what is sent. */
    const val SEPARATOR = "/"

    /**
     * The follow-up of the option [ownerId], from its "then" given as text. Null when follow-ups are
     * off ([enabled]), when there is none, and when it cannot be read: no question, no option left
     * once the unreadable ones are skipped. One level only: a "then" inside it is never read.
     */
    fun read(ownerId: String, then: String?, enabled: Boolean): PollChecker.FollowUp? {
        if (!enabled || then == null || !usableId(ownerId)) return null
        return runCatching {
            val o = Json.parseToJsonElement(then) as? JsonObject ?: return null
            val question = o.text("question") ?: return null
            val seen = HashSet<String>()
            val options = (o["options"] as? JsonArray).orEmpty().mapNotNull { item ->
                val oo = item as? JsonObject ?: return@mapNotNull null
                // An id that holds the separator could not be told apart in what is sent, and a
                // second choice with the same id would be counted as the first.
                val id = oo.raw("id")?.takeIf(::usableId) ?: return@mapNotNull null
                val label = oo.text("label") ?: return@mapNotNull null
                if (!seen.add(id)) return@mapNotNull null
                PollChecker.Option(
                    id, label, oo.text("image"),
                    other = oo.flag("other"),
                    placeholder = oo.text("placeholder"),
                )
            }
            if (options.isEmpty()) null
            else PollChecker.FollowUp(question, o.text("body"), options, multiple = o.flag("multiple"))
        }.getOrNull()
    }

    /** The options picked that have a follow-up, in the order of the options: the questions after the first. */
    fun steps(poll: PollChecker.Poll, selected: List<String>): List<PollChecker.Option> =
        poll.options.filter { it.then != null && it.id in selected }

    /** What was picked in [owner]'s follow-up, by the follow-up's option ids. */
    fun picksIn(owner: PollChecker.Option, deeper: List<String>): List<String> {
        val prefix = owner.id + SEPARATOR
        return deeper.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
    }

    /** One tap on [option] in [owner]'s follow-up: one choice replaces the last, several toggle. */
    fun toggle(owner: PollChecker.Option, option: PollChecker.Option, deeper: List<String>): List<String> {
        val path = owner.id + SEPARATOR + option.id
        return when {
            owner.then?.multiple != true -> deeper.filterNot { it.startsWith(owner.id + SEPARATOR) } + path
            path in deeper -> deeper - path
            else -> deeper + path
        }
    }

    /**
     * Whether a follow-up can be left for the next question or sent. Nothing needs picking, since the
     * first answer is the one that counts and a follow-up must never cost it; an Other picked needs its
     * words, as in the first question.
     */
    fun canGoOn(followUp: PollChecker.FollowUp, picks: List<String>, words: String): Boolean =
        followUp.options.none { it.other && it.id in picks } || words.isNotBlank()

    /** Whether [path] is a follow-up pick of an option picked in [first]: "<optionId>/<followUpOptionId>". */
    fun isPick(poll: PollChecker.Poll, first: List<String>, path: String): Boolean {
        val ownerId = path.substringBefore(SEPARATOR, missingDelimiterValue = "")
        val id = path.substringAfter(SEPARATOR, missingDelimiterValue = "")
        if (ownerId.isEmpty() || id.isEmpty() || ownerId !in first) return false
        val then = poll.options.firstOrNull { it.id == ownerId }?.then ?: return false
        return then.options.any { it.id == id }
    }

    /**
     * Every id of the answer: the first question's picks, then the follow-ups' as
     * "<optionId>/<followUpOptionId>". A pick under an option that is no longer picked (somebody went
     * back and changed their mind) is left out, and so is anything that is not a choice of this poll.
     */
    fun answerIds(poll: PollChecker.Poll, selected: List<String>, deeper: List<String>): List<String> {
        val first = selected.filter { id -> poll.options.any { it.id == id } }.distinct()
        return first + deeper.filter { isPick(poll, first, it) }.distinct()
    }

    /**
     * The words written for each Other picked, as (box, words): the first question's (box null) first,
     * then each follow-up's (box: its option's id), in the order they are asked. Words in a box whose
     * Other is not picked stay on screen and are never sent.
     */
    private fun boxes(
        poll: PollChecker.Poll,
        selected: List<String>,
        otherText: String,
        deeper: List<String>,
        deeperText: Map<String, String>,
    ): List<Pair<String?, String>> {
        val out = mutableListOf<Pair<String?, String>>()
        if (poll.options.any { it.other && it.id in selected }) out += null to otherText.trim()
        for (owner in steps(poll, selected)) {
            val picks = picksIn(owner, deeper)
            if (owner.then!!.options.any { it.other && it.id in picks }) out += owner.id to deeperText[owner.id].orEmpty().trim()
        }
        return out.filter { it.second.isNotEmpty() }
    }

    /** The words sent with the answer, one box to a line, or null when nothing was written. */
    fun words(
        poll: PollChecker.Poll,
        selected: List<String>,
        otherText: String,
        deeper: List<String>,
        deeperText: Map<String, String>,
    ): String? = boxes(poll, selected, otherText, deeper, deeperText).joinToString("\n") { it.second }.ifEmpty { null }

    /**
     * How many characters the box [box] may hold: what one answer carries (Polls.MAX_TEXT_CHARS), less
     * what the other boxes sent with it hold. With one box, the usual case, that is all of it.
     */
    fun room(
        poll: PollChecker.Poll,
        selected: List<String>,
        otherText: String,
        deeper: List<String>,
        deeperText: Map<String, String>,
        box: String?,
    ): Int {
        val elsewhere = boxes(poll, selected, otherText, deeper, deeperText).filter { it.first != box }
        return (Polls.MAX_TEXT_CHARS - elsewhere.sumOf { it.second.length + 1 }).coerceAtLeast(0)
    }

    private fun usableId(id: String) = id.isNotBlank() && SEPARATOR !in id && "," !in id

    /** The value at [key] as written, numbers included, as org.json's optString reads it; null when absent or empty. */
    private fun JsonObject.raw(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.ifEmpty { null }

    private fun JsonObject.text(key: String): String? = raw(key)?.trim()?.ifEmpty { null }

    private fun JsonObject.flag(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull ?: false
}
