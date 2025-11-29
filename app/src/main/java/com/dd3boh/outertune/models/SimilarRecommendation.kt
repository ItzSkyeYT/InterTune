package com.dd3boh.outertune.models

import com.dd3boh.outertune.db.entities.LocalItem


data class SimilarRecommendation(
    val title: LocalItem,
    val items: List<LocalItem>,
)
