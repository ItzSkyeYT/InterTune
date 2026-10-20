/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * About > Support InterTune: donations go through a direct message on the Discord server, so the
 * note sends people there and no address of any kind is written into the app.
 */
class SupportTest {

    @Test
    fun `About has the row, the note opens the Discord server, and its words are in English and French`() {
        val about = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/AboutScreen.kt").readText()
        assertTrue(about.contains("R.string.support_title"))
        val dialog = File("src/main/java/com/dd3boh/outertune/ui/dialog/SupportDialog.kt").readText()
        assertTrue(dialog.contains("uriHandler.openUri(Links.DISCORD)"))
        for (path in listOf("src/main/res/values/strings-ot.xml", "src/main/res/values-fr/strings-ot.xml")) {
            val strings = File(path).readText()
            for (name in listOf("support_title", "support_text", "support_discord", "support_thanks")) {
                assertTrue("$name missing from $path", strings.contains("name=\"$name\""))
            }
        }
        assertFalse("no donor's name is written into the app", File("src/main/java/com/dd3boh/outertune/constants/Links.kt").readText().contains("SUPPORTERS"))
    }

    @Test
    fun `each one who gave has a line of their own under the thanks`() {
        val dialog = File("src/main/java/com/dd3boh/outertune/ui/dialog/SupportDialog.kt").readText()
        assertTrue(dialog.contains("donors.forEach"))
        assertTrue("the names come from the README", dialog.contains("Donors.refresh(context)"))
        assertFalse("the names are a list, not one line", dialog.contains("joinToString"))
        assertTrue("a long list scrolls inside the note", dialog.contains("verticalScroll"))
        for (path in listOf("src/main/res/values/strings-ot.xml", "src/main/res/values-fr/strings-ot.xml")) {
            val thanks = Regex("""name="support_thanks">([^<]*)<""").find(File(path).readText())!!.groupValues[1]
            assertFalse("$path: the names go below the thanks, not inside it", thanks.contains("%1"))
            assertTrue("$path: the thanks leads into the list", thanks.trimEnd().endsWith(":"))
        }
    }

    @Test
    fun `no wallet address is written into the app`() {
        val links = File("src/main/java/com/dd3boh/outertune/constants/Links.kt").readText()
        assertFalse(Regex("""\bbc1[a-z0-9]{20,}|\b[13][a-km-zA-HJ-NP-Z1-9]{25,34}\b""").containsMatchIn(links))
    }
}
