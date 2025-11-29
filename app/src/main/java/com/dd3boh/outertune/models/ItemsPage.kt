package com.dd3boh.outertune.models

import com.dd3boh.outertune.db.entities.LocalItem


data class ItemsPage(
    val items: List<LocalItem>,
    val continuation: String?,
)
