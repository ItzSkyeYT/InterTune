/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.testerlock

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** What the door said of a code. */
enum class DoorAnswer {
    /** A tester's code: this build opens, and is not asked about again for a day. */
    OPEN,

    /**
     * The same yes, and the door has just given the code to this install: it held none before. Another
     * phone asking in the same minute may have been told yes too (the door's storage takes up to a
     * minute to agree with itself), so this install is looked at again soon, not in days.
     */
    CLAIMED,

    /** No tester has this code. */
    UNKNOWN,

    /** The code was a tester's, and the Tester role has been taken off them. */
    REVOKED,

    /** A tester's code, but another install holds it: it opens test builds on one device. */
    IN_USE,

    /**
     * A tester's code, but this is not the newest test build, and the newest has been out for more
     * than a day. The code is good: it is kept for the newest build to check with no typing.
     */
    OUTDATED,

    /**
     * No answer, or one that is not a yes or a 403 no: no network, a timeout, a 5xx, a 400 or a 405
     * from a door that is not ready for this, anything else. Never a lock: an outage of ours, or a
     * mistake of ours, lets a tester in, for seven days at most (TesterLockRules).
     */
    COULD_NOT_CHECK,
}

/**
 * The door the testers' codes are checked at: a Cloudflare Worker (door/worker.js), which also
 * hands out the tester builds.
 *
 * POST <door>/open with {"code", "build", "vc", "install"}: the code, the build's name, its version
 * number and the random number this install made for itself (TesterInstall), and nothing that names
 * the phone. No hardware id, no Android id, no account, and a User-Agent of its own rather than one
 * that says which phone and Android this is. The door keeps a code to the one install that first
 * used it, and notes "code X opened build Y" for the bot, which counts it as the tester's activity.
 */
class TesterDoor(
    private val door: String,
    private val client: OkHttpClient = CLIENT,
) {
    /** Blocks for up to [TIMEOUT_SECONDS]: never on the main thread. */
    fun ask(code: String, build: String, versionCode: Int, install: String): DoorAnswer {
        val request = try {
            Request.Builder()
                .url(door.trimEnd('/') + "/open")
                .header("User-Agent", USER_AGENT)
                .post(body(code, build, versionCode, install).toRequestBody(JSON))
                .build()
        } catch (e: IllegalArgumentException) {
            // A door that is not an address: a build made with a mistyped -Pintertune.testerDoor.
            return DoorAnswer.COULD_NOT_CHECK
        }
        return try {
            client.newCall(request).execute().use { answerOf(it.code, it.body?.string()) }
        } catch (e: Exception) {
            // IOException for the network and the timeout, and anything else on the way, which
            // must never become a lock: an outage of ours lets a tester in.
            DoorAnswer.COULD_NOT_CHECK
        }
    }

    companion object {
        const val TIMEOUT_SECONDS = 10L

        /** The same for every phone and every build. */
        const val USER_AGENT = "InterTune tester check"

        private val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * Its own client, with nothing of the app's: no interceptors, no cookies, no headers of the
         * app's other services. A redirect is not an answer either.
         */
        internal val CLIENT by lazy {
            OkHttpClient.Builder()
                .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }

        fun body(code: String, build: String, versionCode: Int, install: String): String = buildJsonObject {
            put("code", code)
            put("build", build)
            put("vc", versionCode)
            put("install", install)
        }.toString()

        /**
         * The answers of the contract, and only those: 200 {"ok":true}, with "claimed":true when the
         * door has just given the code to this install, and 403 {"ok":false,"why":"unknown"},
         * "revoked", "in_use" or "outdated", the only answers that lock. Anything else is a door that
         * could not check, a 400 {"ok":false,"why":"bad"} included: the app checks a code's shape
         * before it sends it, so the door finding it bad is a mistake of ours, never the tester's.
         */
        fun answerOf(status: Int, body: String?): DoorAnswer {
            val json = try {
                body?.let { Json.parseToJsonElement(it) as? JsonObject }
            } catch (e: Exception) {
                null
            } ?: return DoorAnswer.COULD_NOT_CHECK
            // A JSON true or false: booleanOrNull alone takes the string "true" too.
            val ok = (json["ok"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            val why = (json["why"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            // Only a JSON true is a claim: anything else is the plain yes, checked again in days.
            val claimed = (json["claimed"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            return when {
                status == 200 && ok == true -> if (claimed == true) DoorAnswer.CLAIMED else DoorAnswer.OPEN
                status == 403 && ok == false && why == "unknown" -> DoorAnswer.UNKNOWN
                status == 403 && ok == false && why == "revoked" -> DoorAnswer.REVOKED
                status == 403 && ok == false && why == "in_use" -> DoorAnswer.IN_USE
                status == 403 && ok == false && why == "outdated" -> DoorAnswer.OUTDATED
                else -> DoorAnswer.COULD_NOT_CHECK
            }
        }
    }
}
