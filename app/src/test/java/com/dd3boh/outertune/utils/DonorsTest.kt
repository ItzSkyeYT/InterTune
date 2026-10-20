/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** About > Support InterTune lists the names under README > Donors, read from the README itself. */
class DonorsTest {

    @Test
    fun `the README's own Donors section gives its names and nothing around them`() {
        val names = Donors.parse(File("../README.md").readText())
        assertTrue("README > Donors lists Dismorphism: $names", "Dismorphism" in names)
        assertTrue("the thanks and the call to action are not names: $names",
            names.none { it.contains("thank", ignoreCase = true) || it.contains("Want your name") })
    }

    @Test
    fun `a donor added as a new row shows up, in order, without the bold words around the table`() {
        val readme = """
            # InterTune
            ## Donors
            <p align="center">These people gave something back. <b>thank you!</b></p>
            <table>
              <tr>
                <td valign="middle"><img src="a.png" alt="A"></td>
                <td valign="middle"><b>Dismorphism</b><br><sub>The very first donation</sub></td>
              </tr>
              <tr>
                <td><b>Tom &amp; Jerry</b><br><sub>November 2026</sub></td>
              </tr>
            </table>
            <p align="center"><b>Want your name here?</b> Send me a message.</p>
            ## Special thanks
            <table><tr><td><b>Not a donor</b></td></tr></table>
        """.trimIndent()
        assertEquals(listOf("Dismorphism", "Tom & Jerry"), Donors.parse(readme))
    }

    @Test
    fun `no Donors section means no names`() {
        assertEquals(emptyList<String>(), Donors.parse("# InterTune\n## Special thanks\n<td><b>Someone</b></td>"))
    }
}
