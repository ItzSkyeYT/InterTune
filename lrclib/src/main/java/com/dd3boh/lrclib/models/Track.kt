package com.dd3boh.lrclib.models

import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
data class Track(
    val id: Int,
    val trackName: String,
    val artistName: String,
    // LRCLIB can send null here. It did on 6 Sep 2026, and a non-null Double made the whole
    // lyrics fetch fail to parse rather than skip that one result.
    val duration: Double? = null,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
    val albumName: String? = null,
    // Marked by LRCLIB when a track has no words, in which case both lyrics fields are null.
    val instrumental: Boolean = false,
)

internal fun List<Track>.bestMatchingFor(duration: Int) =
    firstOrNull { it.duration != null && abs(it.duration.toInt() - duration) <= 2 }
