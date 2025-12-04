package com.dd3boh.outertune.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.apache.commons.lang3.RandomStringUtils
import java.time.LocalDateTime

@Immutable
@Entity(tableName = "genre")
class GenreEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val bookmarkedAt: LocalDateTime? = null,
    val thumbnailUrl: String? = null,
) {

    companion object {
        fun generateGenreId() = "LG" + RandomStringUtils.insecure().next(8, true, false)
    }
}