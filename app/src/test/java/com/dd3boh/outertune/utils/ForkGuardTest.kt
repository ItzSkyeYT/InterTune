/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A fork leaves InterTune's services alone (FORKS.md, his rules of 27 Oct 2026): in a build that
 * is neither signed with the release key nor InterTune's own debug or preview build, no update
 * check on InterTune's releases, no form on its tracker, no community links, no share page, no
 * donors list. The keys were already held back the same way (BuiltInKeys).
 */
class ForkGuardTest {
    private fun read(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    @Test
    fun `only InterTune itself counts as InterTune, its own debug builds by their app id`() {
        val keys = read("utils/BuiltInKeys.kt")
        assertTrue(keys.contains("genuine = (BuildConfig.DEBUG && context.packageName.startsWith(OWN_PACKAGE)) ||"))
        assertTrue(keys.contains("private const val OWN_PACKAGE = \"dev.skye.intertune\""))
        assertTrue(keys.contains("val official: Boolean get() = genuine"))
    }

    @Test
    fun `each of InterTune's services asks first`() {
        val guarded = mapOf(
            "utils/UpdateChecker.kt" to "if (!BuiltInKeys.official) return@withContext null",
            "utils/Donors.kt" to "if (!BuiltInKeys.official) return@withContext emptyList()",
            "ui/component/ProblemReportDialog.kt" to "if (BuiltInKeys.official) TextButton(",
            "ui/component/CrashReportDialog.kt" to "if (BuiltInKeys.official) TextButton(",
            "ui/dialog/SupportDialog.kt" to "if (BuiltInKeys.official) TextButton(onClick = {",
            "utils/ReportRelay.kt" to "if (!BuiltInKeys.official) return@withContext Outcome.Failed(",
            "ui/menu/ShareLinkMenu.kt" to "if (Unreleased.SHARE_PAGE && BuiltInKeys.official) chosen else ShareLinkKind.YOUTUBE_MUSIC",
            "ui/screens/settings/fragments/PlayerFrag.kt" to "HEAD_TRACKING_ISSUE).takeIf { BuiltInKeys.official }",
        )
        for ((path, line) in guarded) assertTrue("$path is not guarded", read(path).contains(line))
        val about = read("ui/screens/settings/AboutScreen.kt")
        val community = about.substringAfter("if (BuiltInKeys.official) {").substringBefore("help_contact_email_inquiries")
        for (link in listOf("Links.DISCUSSIONS", "Links.DISCORD", "Links.TRANSLATE")) {
            assertTrue("$link is not inside the guard", link in community)
        }
    }
    @Test
    fun `a fork's build says it is not InterTune, in place of InterTune's name and logo`() {
        val about = read("ui/screens/settings/AboutScreen.kt")
        val header = about.substringAfter("if (BuiltInKeys.official) {").substringBefore("} else {")
        assertTrue("the logo and the name are not guarded", "R.drawable.launcher_monochrome" in header && "R.string.app_name" in header)
        assertTrue(about.contains("text = stringResource(R.string.unofficial_build)"))
        assertTrue("the GitHub and wiki buttons are not guarded", about.contains("if (BuiltInKeys.official) Row("))
        val support = about.substringAfter("// InterTune's support and address, not a fork's (FORKS.md).")
        assertTrue(support.substringBefore("Spacer(").let { "R.string.support_title" in it && "Links.EMAIL" in it })
        for (folder in listOf("values", "values-fr")) {
            assertTrue(File("src/main/res/$folder/strings-ot.xml").readText().contains("name=\"unofficial_build\""))
        }
    }

    @Test
    fun `what is new in InterTune is never told by a fork's build`() {
        val main = read("MainActivity.kt")
        assertTrue(Regex("""enabled = Unreleased\.WELCOME_BACK && BuiltInKeys\.official""").findAll(main).count() == 2)
    }
}
