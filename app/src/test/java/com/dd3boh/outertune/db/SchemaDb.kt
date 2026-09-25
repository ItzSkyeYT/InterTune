/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import com.dd3boh.outertune.db.MusicDatabase.Companion.MUSIC_DATABASE_VERSION
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * An in-memory SQLite database built from the exported Room schema, so a test runs against the
 * tables the app really has rather than a hand-written imitation that drifts the first time a
 * column changes.
 */
object SchemaDb {
    /** At [version], an older one to test the migrations from what people are running. */
    fun open(url: String = "jdbc:sqlite::memory:", version: Int = MUSIC_DATABASE_VERSION): Connection {
        val db = DriverManager.getConnection(url)
        val schemaFile = File("schemas/com.dd3boh.outertune.db.InternalDatabase/$version.json")
        val schema = Json.parseToJsonElement(schemaFile.readText()).jsonObject["database"]!!.jsonObject
        db.createStatement().use { st ->
            st.execute("PRAGMA foreign_keys = ON")
            for (entity in schema["entities"]!!.jsonArray.map { it.jsonObject }) {
                val table = entity["tableName"]!!.jsonPrimitive.content
                st.execute(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray?.forEach { index ->
                    st.execute(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            for (view in schema["views"]?.jsonArray.orEmpty().map { it.jsonObject }) {
                st.execute(view["createSql"]!!.jsonPrimitive.content.replace("\${VIEW_NAME}", view["viewName"]!!.jsonPrimitive.content))
            }
        }
        return db
    }
}
