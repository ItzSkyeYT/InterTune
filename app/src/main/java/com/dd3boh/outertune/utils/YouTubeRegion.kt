/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import android.os.SystemClock
import android.telephony.TelephonyManager
import com.dd3boh.outertune.BuildConfig
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okio.ByteString.Companion.decodeBase64
import java.io.File
import java.util.Locale
import java.util.TimeZone

/**
 * Which country YouTube sees this connection in, for the few where YouTube Music has no free plan
 * (#68, 1 Nov 2026). In South Korea YouTube shows songs, albums and mixes only to Premium members,
 * so search found only videos and an album opened on an empty page. YouTube decides by the
 * connection alone: asked from France as Korea (gl=KR, or a visitor token that says KR) it answers
 * in full, and through a VPN outside Korea it answers in full in Korea too. So nothing the app
 * sends changes it, and what the app can do is say so.
 *
 * The country is the one YouTube writes into each visitor token it issues (field 6, then field 1,
 * of the token's protobuf). The token the app keeps says where it was first issued, and YouTube
 * hands the same country back for as long as it is used, so the live answer needs a fresh token:
 * one request of about 1.3 KB (sw.js_data), made only when something about the phone points to
 * one of these countries. Through the app's proxy it is the proxy's country, as it should be.
 */
object YouTubeRegion {
    /** Where YouTube Music has no free plan: South Korea, the only one (#68). */
    val NO_FREE_PLAN = setOf("KR")

    /** The time zones of [NO_FREE_PLAN]: a phone in Korea keeps its own even in English. */
    private val ZONES = setOf("Asia/Seoul", "ROK")

    /** Debug builds only: a file in filesDir holding a country code, see [pretendFrom]. */
    const val PRETEND_FILE = "pretend-youtube-country"

    /** A fresh look at most this often, so opening Search again and again costs nothing. */
    private const val LOOK_EVERY_MS = 2 * 60 * 1000L

    private val _seen = MutableStateFlow<String?>(null)

    /** The country YouTube saw this connection in at the last look, or null before any look. */
    val seen: StateFlow<String?> = _seen.asStateFlow()

    @Volatile
    private var pretend: String? = null

    @Volatile
    private var lastLook = 0L

    fun closed(country: String?): Boolean = country != null && country in NO_FREE_PLAN

    /**
     * The country inside a visitor token, or null for a token with none, a damaged one or none at
     * all. The app keeps tokens as YouTube sends them, with '=' sometimes written %3D.
     */
    fun countryOf(token: String?): String? {
        if (token.isNullOrBlank()) return null
        val bytes = token.replace("%3D", "=").replace("%3d", "=").decodeBase64()?.toByteArray() ?: return null
        val place = field(bytes, 6) ?: return null
        val code = field(place, 1) ?: return null
        if (code.size != 2) return null
        return String(code, Charsets.US_ASCII).takeIf { text -> text.all { it in 'A'..'Z' } }
    }

    /**
     * Whether anything about the phone points to a country in [NO_FREE_PLAN]: its time zone, the
     * country of its language settings, of its network or of its SIM, or of the token it keeps.
     * Only then is YouTube asked, so nobody else pays a request for it.
     */
    fun hinted(
        timeZone: String?,
        localeCountry: String?,
        networkCountry: String?,
        simCountry: String?,
        tokenCountry: String?,
    ): Boolean {
        if (timeZone in ZONES) return true
        return listOf(localeCountry, networkCountry, simCountry, tokenCountry)
            .any { it?.uppercase(Locale.ROOT) in NO_FREE_PLAN }
    }

    /**
     * Looks again where YouTube sees the connection, when the phone points to such a country and
     * the last look is more than two minutes old. A failed request changes nothing.
     */
    suspend fun refresh(context: Context) {
        pretend?.let { _seen.value = it; return }
        val now = SystemClock.elapsedRealtime()
        if (lastLook != 0L && now - lastLook < LOOK_EVERY_MS) return
        val phone = runCatching { context.getSystemService(TelephonyManager::class.java) }.getOrNull()
        val asked = hinted(
            timeZone = TimeZone.getDefault().id,
            localeCountry = Locale.getDefault().country,
            networkCountry = runCatching { phone?.networkCountryIso }.getOrNull(),
            simCountry = runCatching { phone?.simCountryIso }.getOrNull(),
            tokenCountry = countryOf(YouTube.visitorData),
        )
        if (!asked) return
        lastLook = now
        YouTube.visitorData().onSuccess { fresh ->
            countryOf(fresh)?.let { _seen.value = pretend ?: it }
        }
    }

    /**
     * Debug builds only: with the file [PRETEND_FILE] in filesDir, holding a country code such as
     * KR, YouTube counts as seeing the connection there, so the notes can be looked at from
     * anywhere. Read when the app starts. Nothing in a release.
     */
    fun pretendFrom(context: Context) {
        pretend = if (BuildConfig.DEBUG && File(context.filesDir, PRETEND_FILE).exists()) {
            runCatching { File(context.filesDir, PRETEND_FILE).readText().trim().uppercase(Locale.ROOT) }
                .getOrNull()?.takeIf { it.length == 2 }
        } else null
        pretend?.let { _seen.value = it }
    }

    /** The bytes of the first length-delimited [number] field of a protobuf message, or null. */
    private fun field(bytes: ByteArray, number: Int): ByteArray? {
        var i = 0
        while (i < bytes.size) {
            val (key, afterKey) = varint(bytes, i) ?: return null
            i = afterKey
            when ((key and 7L).toInt()) {
                0 -> i = (varint(bytes, i) ?: return null).second
                1 -> i += 8
                2 -> {
                    val (length, start) = varint(bytes, i) ?: return null
                    if (length < 0 || length > bytes.size - start) return null
                    val end = start + length.toInt()
                    if ((key ushr 3).toInt() == number) return bytes.copyOfRange(start, end)
                    i = end
                }
                5 -> i += 4
                else -> return null
            }
        }
        return null
    }

    private fun varint(bytes: ByteArray, from: Int): Pair<Long, Int>? {
        var value = 0L
        var shift = 0
        var i = from
        while (i < bytes.size && shift < 64) {
            val b = bytes[i].toInt() and 0xFF
            i++
            value = value or ((b and 0x7F).toLong() shl shift)
            if (b < 0x80) return value to i
            shift += 7
        }
        return null
    }
}
