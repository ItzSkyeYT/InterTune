/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import org.junit.Assert.assertEquals
import org.junit.Test
import java.sql.Connection

/**
 * The database of the last release, taken through every migration since, comes out as the schema
 * this build expects. 0.10.9 shipped schema 21; anybody updating to 0.11 goes through 22 and 23.
 *
 * Room checks a migrated database against the schema's identity hash when it opens it, and a
 * mismatch is a crash on launch, for everyone who updates. This runs Room's own generated
 * migrations on a copy of the old schema and compares every table, column, index and view with a
 * database created fresh at the current version.
 */
class MigrationFromReleasedTest {

    /** The schema of the last release. Moved on when a release ships a new one. */
    private val released = 21

    /** Just enough of a connection over JDBC for execSQL, which is all an auto migration uses. */
    private class Jdbc(private val db: Connection) : SQLiteConnection {
        override fun prepare(sql: String): SQLiteStatement = object : SQLiteStatement {
            override fun step(): Boolean { db.createStatement().use { it.execute(sql) }; return false }
            override fun close() {}
            override fun reset() {}
            override fun clearBindings() {}
            override fun bindBlob(index: Int, value: ByteArray) = unsupported()
            override fun bindDouble(index: Int, value: Double) = unsupported()
            override fun bindLong(index: Int, value: Long) = unsupported()
            override fun bindText(index: Int, value: String) = unsupported()
            override fun bindNull(index: Int) = unsupported()
            override fun getBlob(index: Int): ByteArray = unsupported()
            override fun getDouble(index: Int): Double = unsupported()
            override fun getLong(index: Int): Long = unsupported()
            override fun getText(index: Int): String = unsupported()
            override fun isNull(index: Int): Boolean = unsupported()
            override fun getColumnCount(): Int = unsupported()
            override fun getColumnName(index: Int): String = unsupported()
            override fun getColumnType(index: Int): Int = unsupported()
        }
        override fun inTransaction(): Boolean = !db.autoCommit
        override fun close() = db.close()
        private fun unsupported(): Nothing = throw UnsupportedOperationException("not needed by a migration")
    }

    private fun Connection.rows(sql: String): List<List<String?>> = createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            val n = rs.metaData.columnCount
            buildList { while (rs.next()) add((1..n).map { rs.getString(it) }) }
        }
    }

    /** Every table's columns and indices and every view, in a form two databases can be compared by. */
    private fun shape(db: Connection): Map<String, Any> {
        val objects = db.rows("SELECT type, name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name")
        return objects.associate { (type, name, sql) ->
            "$type $name" to when (type) {
                // name, type, notnull, default, pk. By name, since a migration adds a column at the
                // end, and with an explicit DEFAULT NULL as no default: Room reads both the same.
                "table" -> db.rows("PRAGMA table_info(`$name`)").map { c -> c.drop(1).toMutableList().also { if (it[3] == "NULL") it[3] = null } }.sortedBy { it[0] }
                "view" -> sql!!.substringAfter(" AS ").trim()
                else -> db.rows("PRAGMA index_info(`$name`)").map { it[2] } to (sql ?: "")
            }
        }
    }

    @Test
    fun `the last release's database migrates to exactly the current schema`() {
        val old = SchemaDb.open(version = released)
        val migrations = listOf(InternalDatabase_AutoMigration_21_22_Impl(), InternalDatabase_AutoMigration_22_23_Impl())
        assertEquals("a migration for every step from the release to now", MusicDatabase.MUSIC_DATABASE_VERSION - released, migrations.size)
        var at = released
        for (m in migrations) {
            assertEquals(at, m.startVersion)
            m.migrate(Jdbc(old))
            at = m.endVersion
        }
        val fresh = SchemaDb.open()
        val expected = shape(fresh)
        val migrated = shape(old)
        assertEquals(expected.keys, migrated.keys)
        for (key in expected.keys) assertEquals(key, expected[key], migrated[key])
    }
}
