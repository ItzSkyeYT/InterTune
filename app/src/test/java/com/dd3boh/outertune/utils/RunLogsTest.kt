/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.ui.component.shownLogs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Report a problem offers the logs of earlier runs, so a fault from before an update can still be
 * sent (his ask of 13 and 20 Oct 2026): one file per run, named for when it began and the version
 * that wrote it, the newest first, a few kept, none in a backup.
 */
class RunLogsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun at(text: String): Date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).parse(text)!!

    @Test
    fun `a run's file says when it began and which version wrote it`() {
        val started = at("2026-10-19 15:12:45")
        val name = RunLogs.fileName(started, 94, "0.11.6-preview.20261019.1502")
        assertEquals("20261019-151245_94_0.11.6-preview.20261019.1502.log", name)
        val run = RunLogs.parse(File(name))!!
        assertEquals(started, run.started)
        assertEquals(94, run.versionCode)
        assertEquals("0.11.6-preview.20261019.1502", run.versionName)
        // logcat's set-aside half and anything else in the folder are not runs of their own.
        assertEquals(null, RunLogs.parse(File("$name.1")))
        assertEquals(null, RunLogs.parse(File("notes.txt")))
    }

    @Test
    fun `the runs are listed newest first, whatever order the folder gives`() {
        val dir = folder.newFolder("logs")
        listOf("2026-10-18 09:00:00" to "0.11.6", "2026-10-20 11:02:07" to "0.12", "2026-10-19 22:46:52" to "0.12")
            .forEach { (time, version) -> File(dir, RunLogs.fileName(at(time), 95, version)).writeText("x\n") }
        File(dir, "${RunLogs.fileName(at("2026-10-20 11:02:07"), 95, "0.12")}.1").writeText("older half\n")
        assertEquals(
            listOf(at("2026-10-20 11:02:07"), at("2026-10-19 22:46:52"), at("2026-10-18 09:00:00")),
            RunLogs.runsIn(dir).map { it.started },
        )
    }

    @Test
    fun `the oldest runs go first, and a run that wrote nothing goes before any of them`() {
        val runs = (1..6).map { day -> RunLogs.Run(File("r$day"), at("2026-10-0$day 12:00:00"), 95, "0.12") }
        val empty = setOf("r5")
        val gone = RunLogs.toForget(runs, keep = 3) { it.file.name in empty }.map { it.file.name }
        // r5 wrote nothing; of the rest, the three newest stay: r6, r4 and r3.
        assertEquals(setOf("r5", "r2", "r1"), gone.toSet())
        assertTrue(RunLogs.toForget(runs.take(2), keep = 3) { false }.isEmpty())
    }

    @Test
    fun `a run's lines come back oldest first, the half logcat set aside before the rest`() {
        val dir = folder.newFolder("logs")
        val file = File(dir, RunLogs.fileName(at("2026-10-20 11:02:07"), 95, "0.12"))
        File(file.path + ".1").writeText("10-20 11:02:08.000 I/App( 123): first\n")
        file.writeText("10-20 11:40:00.000 E/MusicService( 123): last\n")
        val lines = RunLogs.read(RunLogs.parse(file)!!)
        assertEquals(listOf("first", "last"), lines.map { ProblemLog.parse(it)!!.message })
        // A run whose file went missing reads as nothing, not as a failure.
        file.delete(); File(file.path + ".1").delete()
        assertTrue(RunLogs.read(RunLogs.parse(file)!!).isEmpty())
    }

    @Test
    fun `each line reaches the file at once, and at the size cap the first half is set aside`() {
        val dir = folder.newFolder("logs")
        val file = File(dir, RunLogs.fileName(at("2026-10-20 14:30:00"), 95, "0.12"))
        val writer = RunLogs.Writer(file)
        writer.line("10-20 14:30:00.000 I/App( 1): first")
        // Nothing waits in a buffer: a process killed now still leaves this line on the disk.
        assertEquals("10-20 14:30:00.000 I/App( 1): first\n", file.readText())
        val long = "10-20 14:30:01.000 W/Somebody( 1): " + "x".repeat(1000)
        repeat(RunLogs.ROTATE_KB + 10) { writer.line(long) }
        val aside = File(file.path + ".1")
        assertTrue(aside.exists())
        assertTrue(aside.length() > RunLogs.ROTATE_KB * 1024L)
        assertTrue(file.length() < RunLogs.ROTATE_KB * 1024L)
        assertEquals(1 + RunLogs.ROTATE_KB + 10, RunLogs.read(RunLogs.parse(file)!!).size)
    }

    @Test
    fun `a crash written into the run reads as the fault, with its trace under it`() {
        val trace = "java.lang.IllegalStateException: no player\n\tat com.dd3boh.outertune.playback.MusicService.onCreate(MusicService.kt:1)\n"
        val lines = listOf("10-20 14:30:00.000 I/MusicService( 7): Playing s7-B-JXmhOs") +
            RunLogs.crashLines(at("2026-10-20 14:31:00"), 7, "main", trace)
        val excerpt = ProblemLog.excerpt(lines)
        // As with the runtime's own lines: the exception is the fault, under the line that names the thread.
        val fault = excerpt.lines[excerpt.faultAt!!]
        assertEquals("java.lang.IllegalStateException: no player", fault.message)
        assertEquals("14:31:00.000", fault.time)
        assertEquals("FATAL EXCEPTION: main", excerpt.lines[excerpt.faultAt!! - 1].message)
        assertTrue(excerpt.lines.last().message.startsWith("\tat com.dd3boh"))
    }

    @Test
    fun `logcat keeps every line a report can show, and only warnings from the rest`() {
        val filters = RunLogs.filters()
        ProblemLog.TELLING.forEach { assertTrue(it, "$it:V" in filters) }
        assertEquals("*:W", filters.last())
        // A warning from anyone, and the app's own information lines, are what keep() lets through.
        assertTrue(ProblemLog.keep(ProblemLog.Line("11:02:08.000", 'W', "Somebody", "a warning")))
        assertFalse(ProblemLog.keep(ProblemLog.Line("11:02:08.000", 'I', "Somebody", "chatter")))
    }

    @Test
    fun `SABR's lines from information up are kept, its debug lines are not`() {
        // A song over SABR left only its failures in the file (21 Oct 2026).
        val filters = RunLogs.filters()
        assertTrue("SabrOver:I" in filters)
        assertTrue("SabrDataSource:I" in filters)
        assertEquals("*:W", filters.last())
        assertTrue(ProblemLog.keep(ProblemLog.Line("16:53:22.000", 'I', "SabrOver", "[song0000001] SABR response 0 at 89107 ms: http 200, 2 segments")))
        assertTrue(ProblemLog.keep(ProblemLog.Line("16:53:22.000", 'I', "SabrDataSource", "[song0000001] SABR read from byte 1700000 of 2600000")))
        assertFalse(ProblemLog.keep(ProblemLog.Line("16:53:22.000", 'D', "SabrOver", "SabrAudioStream: SABR UMP request #0: playerTime=89107ms")))
    }

    @Test
    fun `an earlier run's report says which run it is, and keeps saying so when cut to fit`() {
        val lines = (1..300).map { ProblemLog.Line("11:0${it % 10}:00.000", 'I', "MusicService", "line $it of a long run") }
        val report = ProblemLog.report(ProblemLog.Excerpt(lines, null), build = "0.11.6 (94, core debug)", device = "samsung SM-S938B", started = at("2026-10-19 15:12:45"))
        assertTrue(report.lines()[2], report.lines()[2] == "From the run that began 2026-10-19 15:12")
        val fitted = ProblemLog.fitted(report, 2000)
        assertEquals(report.lines().take(4), fitted.lines().take(4))
        assertTrue(fitted.length <= 2000)
        // This run's report reads as before.
        assertFalse("From the run" in ProblemLog.report(ProblemLog.Excerpt(lines.take(3), null), build = "0.12 (95, core debug)", device = "x"))
    }

    @Test
    fun `the list shows this run and the three before it, and always the one picked`() {
        assertEquals(listOf(0, 1), shownLogs(2, 0, showAll = false))
        assertEquals(listOf(0, 1, 2, 3), shownLogs(9, 0, showAll = false))
        assertEquals(listOf(0, 1, 2, 3, 7), shownLogs(9, 7, showAll = false))
        assertEquals((0 until 9).toList(), shownLogs(9, 0, showAll = true))
    }

    @Test
    fun `the logs stay on the phone, out of the cloud backup and out of a transfer`() {
        val kept = Regex("""<exclude\s+domain="file"\s+path="${RunLogs.FOLDER}/"\s*/>""")
        val rules = File("src/main/res/xml/data_extraction_rules.xml").readText()
        assertTrue("cloud backup", kept.containsMatchIn(rules.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")))
        assertTrue("transfer", kept.containsMatchIn(rules.substringAfter("<device-transfer>").substringBefore("</device-transfer>")))
        assertTrue("Android 11 and earlier", kept.containsMatchIn(File("src/main/res/xml/backup_rules.xml").readText()))
    }
}
