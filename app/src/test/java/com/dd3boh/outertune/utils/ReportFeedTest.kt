/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What feeds the trail: the app in front or not, from the activities started and stopped; and a hold on
 * every place that writes to it, read from the source (the callbacks cannot run here), so that a step
 * never carries a song, a search or a name, and a merge cannot quietly take a hook away.
 */
class ReportFeedTest {
    private var now = 1_000_000L

    private fun feed(): Pair<ReportFeed.Foreground, ReportTrail> {
        val trail = ReportTrail { now.also { now += 10_000 } }.also { it.enabled = true }
        return ReportFeed.Foreground(trail) to trail
    }

    private fun told(trail: ReportTrail) = trail.steps().map { it.text }

    @Test
    fun `the first activity opens the app, the last one stopping sends it to the background, a start after that brings it back`() {
        val (app, trail) = feed()
        app.started()
        app.stopped(changingConfigurations = false)
        app.started()
        assertEquals(listOf("app opened", "app sent to the background", "app back in front"), told(trail))
    }

    @Test
    fun `turning the phone is not leaving the app`() {
        val (app, trail) = feed()
        app.started()
        app.stopped(changingConfigurations = true)
        app.started()
        app.stopped(changingConfigurations = true)
        app.started()
        assertEquals(listOf("app opened"), told(trail))
        app.stopped(changingConfigurations = false)
        assertEquals(listOf("app opened", "app sent to the background"), told(trail))
    }

    @Test
    fun `a second screen of the app in front keeps it in front`() {
        val (app, trail) = feed()
        app.started()
        app.started()
        app.stopped(false)
        assertEquals(listOf("app opened"), told(trail))
        app.stopped(false)
        assertEquals(listOf("app opened", "app sent to the background"), told(trail))
    }

    // ---- the hooks, read from the source ----

    private val sources = File("src/main/java/com/dd3boh/outertune")
    private fun read(path: String) = File(sources, path).readText()

    /** Every call that writes a step, with what it is handed. */
    private val calls: List<Pair<String, String>> = sources.walkTopDown()
        .filter { it.extension == "kt" && it.name != "ReportTrail.kt" }
        .flatMap { file ->
            // The call's own parentheses, two levels of them inside: two calls on a line are two.
            Regex("""ReportTrail\.thisRun\.(\w+)\(((?:[^()\n]|\((?:[^()\n]|\([^()\n]*\))*\))*)\)""").findAll(file.readText()).map { it.groupValues[1] to it.groupValues[2] }
        }
        .toList()

    @Test
    fun `the trail is written from the places that were meant to, with what was meant`() {
        val methods = calls.map { it.first }.toSet()
        assertEquals(
            setOf("screen", "network", "networkChangedTo", "playbackError", "gaveUp", "waited", "resumed", "paused", "songStarted", "seeked", "downloadFailed"),
            methods,
        )
        assertTrue("ReportFeed.start(this)" in read("App.kt"))
        assertTrue("ReportTrail.thisRun.screen(destination.route)" in read("MainActivity.kt"))
        val service = read("playback/MusicService.kt")
        for (hook in listOf(
            "ReportTrail.thisRun.playbackError(error.errorCodeName, Recovery.status(error))",
            "ReportTrail.thisRun.songStarted(reason, chosen, transitionWentBack, fromError, isCasting)",
            "ReportTrail.thisRun.seeked(newPosition.positionMs)",
            "ReportTrail.thisRun.paused(StopReasons.paused(reason))",
        )) assertTrue("$hook is not in MusicService", hook in service)
        val network = read("utils/NetworkConnectivityObserver.kt")
        assertTrue("ReportTrail.thisRun.network(ReportTrail.Network.LOST)" in network && "ReportTrail.thisRun.network(ReportTrail.Network.BACK)" in network)
    }

    @Test
    fun `no call to the trail is handed a title, an id, a query, a name, an address or a cookie`() {
        val personal = Regex(
            """(?i)\b(?:title|mediaid|mediaitem|metadata|query|name|email|artist|album|playlist|url|uri|id|songid|videoid|cookie|account|visitor|path)\b"""
        )
        for ((method, handed) in calls) {
            // errorCodeName is the player's constant, not a name.
            val args = handed.replace("error.errorCodeName", "").replace("destination.route", "")
            assertFalse("$method($handed) hands over something personal", personal.containsMatchIn(args))
        }
    }

    @Test
    fun `the screens are named by the route's pattern, not by the entry's arguments`() {
        val main = read("MainActivity.kt")
        val listener = main.substringAfter("NavController.OnDestinationChangedListener {").substringBefore("navController.addOnDestinationChangedListener")
        assertTrue("destination.route" in listener)
        assertFalse("arguments" in listener)
        assertFalse("backStackEntry" in listener)
    }

    @Test
    fun `every route of the navigation graph reduces to words`() {
        val graph = read("ui/navigation/AppNavGraph.kt")
        val routes = Regex("""(?:route = |screen\()"([^"]+)"""").findAll(graph).map { it.groupValues[1] }.toList()
        assertTrue("${routes.size} routes", routes.size > 30)
        for (route in routes) {
            val name = ReportTrail.screenName(route)
            // "${Screens.Folders.route}/{path}" has a template in it and is read as the words around it.
            if ("$" in route) continue
            assertTrue("$route has no name", name != null)
            assertTrue("$route is $name", name!!.all { it.isLowerCase() || it == '_' || it == '/' })
        }
    }

    @Test
    fun `the crash file gets the extras after the trace, in a block of their own, and never throws for them`() {
        val crash = read("utils/CrashLog.kt")
        val trace = crash.indexOf("append(ErrorText.of(throwable))")
        val extras = crash.indexOf("extras()?.let { append(")
        assertTrue("the extras are not after the trace", trace in 1 until extras)
        // The heading ends with its empty line as it did, so the exception is still the first line after it.
        val head = crash.substringAfter("appendLine(\"Thread: \${thread.name}\")").substringBefore("append(ErrorText")
        assertTrue(head.trim().startsWith("appendLine()") && "extras()" !in head)
        assertTrue("runCatching" in crash.substringAfter("private fun extras()"))
        assertTrue("ReportFeed.latest" in crash)
    }

    @Test
    fun `the report page and the crash page add the extras only in a build that has them`() {
        val page = read("ui/component/ProblemReportDialog.kt")
        assertTrue("val plus = Unreleased.REPORT_PLUS" in page)
        assertTrue("if (plus) ReportExtras.forLog(" in page)
        assertTrue("Unreleased.REPORT_PLUS" in read("utils/CrashLog.kt"))
        assertTrue("Unreleased.REPORT_PLUS" in read("utils/ReportFeed.kt"))
    }
}
