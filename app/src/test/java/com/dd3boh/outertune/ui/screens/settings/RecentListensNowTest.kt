/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 26 Nov 2026 (the rooms walk): in How it's doing > Recent listens the song still playing came last
 * and was dated "Jan 1, 1:00 AM": an open listen's endedAt is 0, the list was ordered by it and the
 * date was made from it. Now it comes first, with its "in progress" and no date.
 */
class RecentListensNowTest {

    @Test
    fun `the listen still going comes first, with no date`() {
        val dao = File("src/main/java/com/dd3boh/outertune/db/daos/ListenDao.kt").readText()
        val query = dao.substringBefore("fun recentListenRows(").substringAfterLast("@Query(")
        assertTrue(query, "ORDER BY CASE WHEN listen.endedAt = 0 THEN 1 ELSE 0 END DESC, listen.endedAt DESC LIMIT :limit" in query)
        val screen = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/RecommendationsDoingSettings.kt").readText()
        assertTrue("val ended = if (row.endedAt <= 0L) null else {" in screen)
        assertTrue("(ended?.let { \" · \$it\" } ?: \"\")," in screen)
        assertFalse("never a date made from an end not there yet", "\" · \" + ended," in screen)
    }
}
