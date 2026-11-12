/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Report a problem's Send to InterTune: the report goes to intertune.app, which files it as an issue on
 * InterTune's GitHub with the InterTune bot as its author, and answers with the issue's number and link.
 * Issues are public, so the sender can follow theirs without an account (his idea and decisions of
 * 27 Oct 2026, after a report that could go neither to Discord, too long without Nitro, nor to GitHub's
 * form, too long a link for its sign-in page).
 *
 * A way to reach the sender, when given, travels beside the report and stays on the site: it is never
 * part of the issue. Only InterTune's own builds send (FORKS.md). The site's side is
 * tools/site/netlify/functions/report.mjs in the InterTune project; a report that looks like spam is
 * held there for a look, and a draft of the site files nothing and answers with issue 0.
 */
/**
 * The two ways a report goes from the app: [ANONYMOUS] through intertune.app ([ReportRelay]), no account
 * needed, or [GITHUB] through GitHub's own form, signed in with the person's account, where they get the
 * replies. Kept in ReportWayKey.
 */
enum class ReportWay { ANONYMOUS, GITHUB }

object ReportRelay {
    const val URL = "https://intertune.app/api/report"
    private const val MOST_TITLE = 120
    private const val MOST_CONTACT = 200
    private const val MOST_WHAT = 2000

    sealed interface Outcome {
        /** Filed as [number], to be followed at [url]. 0 only from a draft of the site, which files nothing. */
        data class Sent(val number: Int, val url: String) : Outcome

        /** Kept on the site for a look before it goes on GitHub. */
        data object Held : Outcome

        /** Not sent: no connection, or the site or GitHub said no. [why] is for the log only. */
        data class Failed(val why: String) : Outcome
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    /**
     * What the site is sent: the report exactly as the page showed it, what the person wrote about it
     * (it goes in the issue's "What happens"), and the contact beside it, which never goes in the issue.
     */
    fun body(build: String, device: String, report: String, title: String?, contact: String?, what: String? = null): String =
        buildJsonObject {
            put("build", build)
            put("device", device)
            put("report", report)
            title?.trim()?.takeIf { it.isNotEmpty() }?.let { put("title", it.take(MOST_TITLE)) }
            what?.trim()?.takeIf { it.isNotEmpty() }?.let { put("what", it.take(MOST_WHAT)) }
            contact?.trim()?.takeIf { it.isNotEmpty() }?.let { put("contact", it.take(MOST_CONTACT)) }
        }.toString()

    /** The site's answer, from its status and its JSON. */
    fun outcome(status: Int, answer: String?): Outcome {
        val json = runCatching { Json.parseToJsonElement(answer.orEmpty()).jsonObject }.getOrNull()
        fun text(key: String) = json?.get(key)?.jsonPrimitive?.contentOrNull
        return when {
            status == 202 && json?.get("held")?.jsonPrimitive?.booleanOrNull == true -> Outcome.Held
            status == 201 -> {
                val number = json?.get("number")?.jsonPrimitive?.intOrNull
                val url = text("url")
                if (number != null && url != null) Outcome.Sent(number, url) else Outcome.Failed("unreadable answer")
            }
            else -> Outcome.Failed(text("error") ?: "HTTP $status")
        }
    }

    /** The fault the page found, as an issue's title: its own words, without the time and the tag. */
    fun title(excerpt: ProblemLog.Excerpt?): String? =
        excerpt?.faultAt?.let { excerpt.lines.getOrNull(it) }?.let { "${it.tag.trim()}: ${it.message.trim()}" }?.take(MOST_TITLE)

    suspend fun send(build: String, device: String, report: String, title: String?, contact: String?, what: String? = null): Outcome =
        withContext(Dispatchers.IO) {
            if (!BuiltInKeys.official) return@withContext Outcome.Failed("not InterTune's own build")
            try {
                val request = Request.Builder()
                    .url(URL)
                    .post(body(build, device, report, title, contact, what).toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { outcome(it.code, it.body?.string()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Outcome.Failed(e.javaClass.simpleName)
            }
        }
}
