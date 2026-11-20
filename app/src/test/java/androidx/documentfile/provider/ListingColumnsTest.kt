/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package androidx.documentfile.provider

import android.database.Cursor
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * The folder's listing reads each column by its name (TreeDocumentFileOt.columnOr): a provider that
 * answers with columns of its own, in its own order, gave each file its type for a name when they
 * were read by place, and then no file in the folder was any song's (dvd, 20 Nov 2026).
 */
class ListingColumnsTest {
    /** A cursor that has only [columns], in that order: all a listing asks of it is where each is. */
    private fun cursor(vararg columns: String): Cursor =
        Proxy.newProxyInstance(Cursor::class.java.classLoader, arrayOf(Cursor::class.java)) { _, method, args ->
            when (method.name) {
                "getColumnIndex" -> columns.indexOf(args!![0] as String)
                else -> throw UnsupportedOperationException(method.name)
            }
        } as Cursor

    @Test
    fun `columns in the order asked for are read where they are`() {
        val c = cursor("document_id", "_display_name", "mime_type")
        assertEquals(0, TreeDocumentFileOt.columnOr(c, "document_id", 0))
        assertEquals(1, TreeDocumentFileOt.columnOr(c, "_display_name", 1))
        assertEquals(2, TreeDocumentFileOt.columnOr(c, "mime_type", 2))
    }

    @Test
    fun `columns in a provider's own order are still read by their names`() {
        // DocumentsProvider's own order: the type before the name, and more besides.
        val c = cursor("document_id", "mime_type", "_display_name", "last_modified", "flags", "_size")
        assertEquals(2, TreeDocumentFileOt.columnOr(c, "_display_name", 1))
        assertEquals(1, TreeDocumentFileOt.columnOr(c, "mime_type", 2))
    }

    @Test
    fun `a cursor that does not name its columns is read by place, as before`() {
        val c = cursor()
        assertEquals(1, TreeDocumentFileOt.columnOr(c, "_display_name", 1))
    }
}
