/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The receivers seen or cast to before, the latest first, kept from one launch to the next so that
 * the picker lists them as it opens, the way a Chromecast's sender does, and takes off any that
 * does not answer.
 *
 * A receiver is found by answering the network's search, and some never answer: FCast Receiver
 * 2.1.5 on Android asks for no multicast lock (its manifest lacks CHANGE_WIFI_MULTICAST_STATE),
 * so its phone's Wi-Fi drops the questions and it is seen only in the seconds after it starts,
 * while it announces itself; some routers keep those questions from crossing at all (both seen
 * on 18 Oct 2026). A receiver already used is asked directly instead, at the address it had: a
 * connection opened and closed, which takes a few milliseconds when it is there and shows nothing
 * on its screen. It is listed once it has answered, and everything listed is asked again every
 * [RECHECK_MS] while the picker is open, so the list holds what is there now. Nothing is asked
 * while the picker is closed.
 */
object RecentReceivers {
    const val MOST = 10
    const val PROBE_MS = 800

    /** How often everything listed is asked again while the picker is open: as often as the SSDP search goes out. */
    const val RECHECK_MS = 10_000L

    /**
     * What the picker lists, in one order by name so a receiver keeps its place whether it was known
     * before or found now: what the search [found] unless it has since stopped answering ([silent]),
     * the receivers [added] by address, and the remembered ones the search has not found, but only
     * those that have [answered]. A remembered receiver that is off is never shown, not even for
     * the moment its check takes (his note of 20 Oct 2026: they flashed up and went).
     */
    fun listed(
        found: List<CastDevice>,
        added: List<CastDevice>,
        recent: List<CastDevice>,
        answered: Set<CastDevice>,
        silent: Set<CastDevice>,
    ): List<CastDevice> {
        val live = found.filterNot { it in silent }
        return (live + added + notFound(recent.filter { it in answered }, live + added))
            .distinctBy { it.protocol to it.id }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    /** [list] with [device] first and no other entry for the same receiver, at most [MOST]. */
    fun remember(list: List<CastDevice>, device: CastDevice): List<CastDevice> =
        (listOf(device) + list.filterNot { it.samePlace(device) }).take(MOST)

    /**
     * The same receiver: the same way in, at the same address and port. A Chromecast speaker group
     * has the address of one of its speakers and a port of its own.
     */
    fun CastDevice.samePlace(other: CastDevice) =
        protocol == other.protocol && host.equals(other.host, ignoreCase = true) && port == other.port

    /**
     * [list] with the receivers the search has just [found]: one already known, the same receiver
     * by its address or its id, takes the found one's details where it stands; a new one goes
     * first. At most [MOST].
     */
    fun seen(list: List<CastDevice>, found: Collection<CastDevice>): List<CastDevice> {
        var out = list
        for (device in found) {
            val at = out.indexOfFirst { it.samePlace(device) || (it.protocol == device.protocol && it.id == device.id) }
            out = if (at >= 0) out.toMutableList().also { it[at] = device } else listOf(device) + out
        }
        return out.distinctBy { it.protocol to it.id }.take(MOST)
    }

    /** Those of [recent] that the search has not found, so that each receiver is listed once. */
    fun notFound(recent: List<CastDevice>, found: Collection<CastDevice>): List<CastDevice> =
        recent.filterNot { r -> found.any { it.samePlace(r) || (it.protocol == r.protocol && it.id == r.id) } }

    fun encode(list: List<CastDevice>): String = buildJsonArray {
        for (d in list) add(buildJsonObject {
            put("protocol", d.protocol.name)
            put("id", d.id)
            put("name", d.name)
            put("host", d.host)
            put("port", d.port)
            d.location?.let { put("location", it) }
            d.model?.let { put("model", it) }
            if (d.audioOnly) put("audioOnly", true)
        })
    }.toString()

    /** What [encode] wrote. Anything it cannot read is left out rather than failing the picker. */
    fun decode(text: String?): List<CastDevice> {
        if (text.isNullOrBlank()) return emptyList()
        val array = runCatching { Json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            runCatching {
                val o = element.jsonObject
                fun text(key: String) = o[key]?.jsonPrimitive?.contentOrNull
                CastDevice(
                    protocol = CastProtocol.valueOf(text("protocol")!!),
                    id = text("id")!!,
                    name = text("name")!!,
                    host = text("host")!!,
                    port = o["port"]!!.jsonPrimitive.intOrNull!!,
                    location = text("location"),
                    model = text("model"),
                    audioOnly = o["audioOnly"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            }.getOrNull()
        }.take(MOST)
    }

    /** Whether anything takes a connection at [device]'s address. Nothing is sent. It blocks: never on the main thread. */
    fun answers(device: CastDevice, timeoutMs: Int = PROBE_MS): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(device.host, device.port), timeoutMs) }
        true
    }.getOrDefault(false)
}
