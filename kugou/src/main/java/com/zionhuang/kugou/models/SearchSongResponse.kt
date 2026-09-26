package com.zionhuang.kugou.models

import kotlinx.serialization.Serializable

@Serializable
data class SearchSongResponse(
    val status: Int,
    val errcode: Int,
    val error: String,
    val data: Data,
) {
    @Serializable
    data class Data(
        val info: List<Info>,
    ) {
        @Serializable
        data class Info(
            val duration: Int,
            val hash: String,
            // Names as KuGou lists them, several singers joined by "、". Read so a result can be
            // checked against the song asked for, not just its length.
            val songname: String = "",
            val singername: String = "",
        )
    }
}