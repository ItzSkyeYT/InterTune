package com.dd3boh.outertune.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
// TODO: keep as is for downloaded songs?
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
    val extraComment: String? = null,
)
