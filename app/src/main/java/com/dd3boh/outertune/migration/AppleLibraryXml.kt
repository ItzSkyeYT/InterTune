/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.migration

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

/**
 * Apple Music's File > Library > Export Library, and iTunes' before it: one XML property list with
 * every track in a "Tracks" dictionary keyed by track id, and "Playlists" that list only the ids.
 *
 * It becomes the whole library as one playlist, named after the file, followed by each playlist
 * the person made. The ones Apple makes for itself (Library, Music, Downloaded, the folders and
 * the smart lists it keeps hidden) are left out, and so are podcasts, films and audiobooks, which
 * YouTube Music would only match to the wrong thing.
 *
 * Read with SAX rather than into a tree, because a large library is tens of megabytes of XML and
 * this runs on a phone, and with every external entity refused: the file names Apple's DTD by URL,
 * and nothing here should ever fetch it.
 */
object AppleLibraryXml {

    fun looksLike(text: String): Boolean {
        val head = text.trimStart('﻿', ' ', '\t', '\r', '\n').take(1024)
        return head.startsWith("<?xml") && "plist" in head
    }

    fun parse(text: String, fallbackName: String): ImportParse {
        val handler = Handler()
        try {
            val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = false; isValidating = false }
            for (feature in listOf(
                "http://xml.org/sax/features/external-general-entities",
                "http://xml.org/sax/features/external-parameter-entities",
                "http://apache.org/xml/features/nonvalidating/load-external-dtd",
            )) {
                runCatching { factory.setFeature(feature, false) }
            }
            val reader = factory.newSAXParser().xmlReader
            reader.contentHandler = handler
            reader.setEntityResolver { _, _ -> InputSource(StringReader("")) }
            reader.parse(InputSource(StringReader(text)))
        } catch (e: Exception) {
            return ImportParse.Failed(ImportParse.Problem.NOT_TEXT, e.message)
        }

        val music = handler.tracks.filterValues { it.isMusic() }
        val library = handler.order.mapNotNull { music[it]?.toTrack() }
        if (library.isEmpty()) return ImportParse.Failed(ImportParse.Problem.NO_TRACKS)

        val playlists = buildList {
            add(ImportedPlaylist(fallbackName, library))
            for (playlist in handler.playlists) {
                if (!playlist.isOwn()) continue
                val tracks = playlist.items.mapNotNull { music[it]?.toTrack() }
                if (tracks.isNotEmpty()) add(ImportedPlaylist(playlist.fields["Name"] ?: fallbackName, tracks))
            }
        }
        val skipped = handler.tracks.size - music.size
        return ImportParse.Parsed(ExportFormat.APPLE_MUSIC_LIBRARY, playlists, skippedRows = skipped)
    }

    private fun Map<String, String>.isMusic(): Boolean {
        if (this["Podcast"] == "true" || this["Movie"] == "true" || this["TV Show"] == "true") return false
        val kind = this["Kind"].orEmpty().lowercase()
        if ("audiobook" in kind || "book" in kind) return false
        return !this["Name"].isNullOrBlank()
    }

    private fun Map<String, String>.toTrack() = ImportedTrack(
        title = getValue("Name").trim(),
        artists = ImportFile.splitArtists(this["Artist"], ExportFormat.APPLE_MUSIC_LIBRARY),
        album = this["Album"]?.trim()?.ifEmpty { null },
        durationSeconds = this["Total Time"]?.toLongOrNull()?.takeIf { it > 0 }?.let { ((it + 500) / 1000).toInt() },
    )

    private class Playlist(val fields: MutableMap<String, String> = HashMap(), val items: MutableList<String> = ArrayList()) {
        fun isOwn(): Boolean =
            fields["Master"] != "true" && fields["Distinguished Kind"] == null && fields["Folder"] != "true" &&
                    fields["Visible"] != "false"
    }

    /**
     * The shape is fixed, so position is enough to know what a value belongs to: depth 1 is the
     * top dictionary, whose keys name the sections; depth 3 is one track or one playlist; depth 5
     * is one entry of a playlist's items.
     */
    private class Handler : DefaultHandler() {
        val tracks = HashMap<String, Map<String, String>>()
        val order = ArrayList<String>()
        val playlists = ArrayList<Playlist>()

        private var depth = 0
        private var section: String? = null
        private var key: String? = null
        private val text = StringBuilder()
        private var track: MutableMap<String, String>? = null
        private var playlist: Playlist? = null
        private var inItems = false

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes?) {
            text.setLength(0)
            when (qName) {
                "dict" -> {
                    depth++
                    if (depth == 3 && section == "Tracks") track = HashMap()
                    if (depth == 3 && section == "Playlists") playlist = Playlist()
                }
                "array" -> {
                    depth++
                    if (depth == 4 && playlist != null && key == "Playlist Items") inItems = true
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (qName) {
                "dict" -> {
                    if (depth == 3) {
                        track?.let { t -> t["Track ID"]?.let { id -> tracks[id] = t; order += id } }
                        playlist?.let { playlists += it }
                        track = null
                        playlist = null
                    }
                    depth--
                }
                "array" -> {
                    if (depth == 4) inItems = false
                    depth--
                }
                "key" -> if (depth == 1) section = text.toString() else key = text.toString()
                "true", "false" -> value(qName)
                "string", "integer", "real", "date" -> value(text.toString())
            }
        }

        private fun value(value: String) {
            val name = key ?: return
            when {
                depth == 3 && track != null -> track!![name] = value
                depth == 3 && playlist != null -> playlist!!.fields[name] = value
                depth == 5 && inItems && name == "Track ID" -> playlist?.items?.add(value)
            }
        }
    }
}
