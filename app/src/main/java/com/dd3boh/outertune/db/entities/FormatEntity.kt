package com.dd3boh.outertune.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "format")
data class FormatEntity(
    @PrimaryKey val id: String,
    val itag: Int,
    val mimeType: String,
    val codecs: String,
    val bitrate: Int,
    val sampleRate: Int?,
    val bitsPerSample: Int? = null,
    val contentLength: Long, // file size
    val loudnessDb: Double? = null,
    @Deprecated("playbackTrackingUrl should be retrieved from a fresh player request")
    val playbackTrackingUrl: String? = null,
    /**
     * Which audio quality setting fetched this, by name, or null for anything cached before the
     * app started recording it.
     *
     * Kept so a cached copy can be compared against what is being asked for now. Comparing
     * bitrates instead would be the obvious thing and does not work: a song whose best available
     * stream is a poor one would look like it needed upgrading forever, and re-fetch on every
     * play.
     */
    val qualityTier: String? = null,
    val extraComment: String? = null,
)
