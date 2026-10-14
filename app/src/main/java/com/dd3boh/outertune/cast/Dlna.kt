/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

/**
 * Times as UPnP AV writes them: `H+:MM:SS`, optionally with a fraction (`.F+`, or `.F0/F1`).
 * Renderers bend this: some drop the hours, some answer `NOT_IMPLEMENTED` or nothing at all.
 */
object DlnaTime {
    /** Milliseconds, or null for a time that is missing, not implemented, or unreadable. */
    fun parse(text: String?): Long? {
        val value = text?.trim()?.removePrefix("+")?.takeIf { it.isNotEmpty() } ?: return null
        if (value.equals("NOT_IMPLEMENTED", ignoreCase = true)) return null
        val parts = value.split(':')
        if (parts.size !in 1..3) return null
        val secondsPart = parts.last()
        val whole = secondsPart.substringBefore('.')
        val fraction = secondsPart.substringAfter('.', "")
        val seconds = whole.toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val minutes = if (parts.size >= 2) parts[parts.size - 2].toLongOrNull()?.takeIf { it >= 0 } ?: return null else 0L
        val hours = if (parts.size == 3) parts[0].toLongOrNull()?.takeIf { it >= 0 } ?: return null else 0L
        val fractionMs = when {
            fraction.isEmpty() -> 0L
            '/' in fraction -> {
                val (top, bottom) = fraction.split('/', limit = 2).map { it.toLongOrNull() }
                if (top == null || bottom == null || bottom <= 0 || top >= bottom) return null
                top * 1000 / bottom
            }
            fraction.all { it.isDigit() } -> (fraction + "00").substring(0, 3).toLong()
            else -> return null
        }
        return ((hours * 60 + minutes) * 60 + seconds) * 1000 + fractionMs
    }

    /**
     * `H:MM:SS`, or `H:MM:SS.mmm` with [millis]. Whole seconds go in a Seek target, since a
     * fraction there is the part of the format renderers most often refuse.
     */
    fun format(ms: Long, millis: Boolean = false): String {
        val total = ms.coerceAtLeast(0)
        val seconds = total / 1000
        val clock = "%d:%02d:%02d".format(java.util.Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60)
        return if (millis) clock + ".%03d".format(java.util.Locale.ROOT, total % 1000) else clock
    }
}

/**
 * A renderer's ConnectionManager GetProtocolInfo `Sink`: a comma-separated list of
 * `protocol:network:contentFormat:additionalInfo`, for example `http-get:*:audio/mp4:*`.
 */
object ProtocolInfo {
    data class Entry(val protocol: String, val network: String, val contentFormat: String, val additionalInfo: String)

    /** The entries, split on the commas between them; DLNA escapes a comma inside one as `\,`. */
    fun parse(sink: String): List<Entry> {
        val entries = ArrayList<Entry>()
        val current = StringBuilder()
        fun flush() {
            val text = current.toString().trim()
            current.clear()
            if (text.isEmpty()) return
            val fields = text.split(':', limit = 4)
            if (fields.size == 4) entries += Entry(fields[0].trim(), fields[1].trim(), fields[2].trim(), fields[3].trim())
        }
        var index = 0
        while (index < sink.length) {
            val char = sink[index]
            if (char == '\\' && index + 1 < sink.length) {
                current.append(sink[index + 1])
                index += 2
                continue
            }
            if (char == ',') flush() else current.append(char)
            index++
        }
        flush()
        return entries
    }

    /**
     * What the renderer plays over HTTP. A list with `*` as a type, or with nothing over HTTP, says
     * nothing, and is treated as saying nothing.
     */
    fun support(sink: String?): FormatSupport {
        val http = parse(sink ?: return FormatSupport.Unknown).filter { it.protocol.equals("http-get", ignoreCase = true) }
        val types = http.map { CastMime.bare(it.contentFormat) }
        if (types.isEmpty() || types.any { it == "*" || it == "*/*" || it == "audio/*" }) return FormatSupport.Unknown
        return FormatSupport.Listed(types.toSet())
    }
}

/** The DIDL-Lite metadata a renderer is given with a song, and the DLNA flags that go with it. */
object Didl {
    /**
     * A `res` protocolInfo for a song of [mimeType]. DLNA.ORG_OP=01 says byte ranges work, which
     * is true whenever the server knows the length; the flags match what the server sends in
     * contentFeatures.dlna.org.
     */
    fun protocolInfo(mimeType: String, byteSeek: Boolean): String =
        "http-get:*:${CastMime.bare(mimeType)}:DLNA.ORG_OP=${if (byteSeek) "01" else "00"};DLNA.ORG_CI=0;${CastHttp.DLNA_FLAGS}"

    /**
     * One track. Every value is escaped, so this is well-formed XML; Soap then escapes it again as
     * an argument. A missing title becomes the id, because a few renderers refuse a track without one.
     */
    fun track(item: CastItem, protocolInfo: String = protocolInfo(item.mimeType, item.length >= 0)): String = buildString {
        fun element(name: String, value: String?) {
            if (value.isNullOrBlank()) return
            append('<').append(name).append('>').append(UpnpXml.escape(value)).append("</").append(name).append('>')
        }
        append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\"")
        append(" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"")
        append(" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"")
        append(" xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\">")
        append("<item id=\"").append(UpnpXml.escape(item.mediaId)).append("\" parentID=\"-1\" restricted=\"1\">")
        element("dc:title", item.title?.takeIf { it.isNotBlank() } ?: item.mediaId)
        element("dc:creator", item.artist)
        element("upnp:artist", item.artist)
        element("upnp:album", item.album)
        element("upnp:albumArtURI", item.artworkUrl)
        append("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")
        append("<res protocolInfo=\"").append(UpnpXml.escape(protocolInfo)).append('"')
        if (item.length >= 0) append(" size=\"").append(item.length).append('"')
        if (item.durationMs > 0) append(" duration=\"").append(DlnaTime.format(item.durationMs, millis = true)).append('"')
        append('>').append(UpnpXml.escape(item.url)).append("</res>")
        append("</item></DIDL-Lite>")
    }
}
