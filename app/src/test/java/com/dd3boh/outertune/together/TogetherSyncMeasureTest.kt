package com.dd3boh.outertune.together

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Prints the numbers of the room's follow loop with the relays' jitter, before (OldPolicy) and after. See TogetherSyncSim. */
class TogetherSyncMeasureTest {
    private fun row(label: String, startOffset: Long, spikes: Boolean, seconds: Int, skip: Int, policy: () -> Policy): String {
        val results = (1..30L).map { TogetherSyncSim(it, startOffset, spikes, policy()).run(seconds, skip) }
        val sp = results.map { it.speedPerMin }
        val sk = results.map { it.seeksPerMin }
        val back = results.map { it.backInStepAtS }
        return "%-30s speed changes/min mean %5.1f max %5.1f | seeks/min mean %.2f max %.2f | real gap ms mean %.0f p95 %.0f | back within 100 ms (s) median %.1f worst %.1f%s"
            .format(label, sp.average(), sp.max(), sk.average(), sk.max(), results.map { it.meanGapMs }.average(), results.map { it.p95GapMs }.average(), back.sorted()[back.size / 2], back.max(),
                if (back.any { it < 0 }) " (${back.count { it < 0 }} of 30 never)" else "")
    }

    @Test
    fun measure() {
        val out = StringBuilder()
        for ((name, policy) in listOf<Pair<String, () -> Policy>>("OLD" to { OldPolicy() }, "NEW" to { NewPolicy() })) {
            out.appendLine("== $name")
            out.appendLine(row("in step (after 30 s)", 0, false, 630, 30, policy))
            out.appendLine(row("in step, late words (after 30 s)", 0, true, 630, 30, policy))
            out.appendLine(row("2 s behind (first 60 s)", -2_000, false, 60, 0, policy))
            out.appendLine(row("300 ms ahead (first 60 s)", 300, false, 60, 0, policy))
        }
        println(out)
        System.getenv("SIM_OUT")?.let { File(it).writeText(out.toString()) }
    }

    @Test
    fun `a guest in step through the relays corrects rarely and never jumps, a guest off comes back`() {
        val inStep = (1..30L).map { TogetherSyncSim(it, 0, true, NewPolicy()).run(630, 30) }
        assertTrue("speed changes a minute: ${inStep.map { it.speedPerMin }.max()}", inStep.all { it.speedPerMin <= 3.0 })
        assertTrue(inStep.map { it.speedPerMin }.average() <= 1.5)
        assertTrue("no jump in step", inStep.all { it.seeks == 0 })
        assertTrue("the real gap stays small", inStep.all { it.p95GapMs < 100 })
        val behind = (1..30L).map { TogetherSyncSim(it, -2_000, false, NewPolicy()).run(60) }
        assertTrue("2 s behind, back in step within 10 s: ${behind.map { it.backInStepAtS }}", behind.all { it.backInStepAtS in 0.0..10.0 })
        assertTrue("one jump, not more", behind.all { it.seeks == 1 })
        val ahead = (1..30L).map { TogetherSyncSim(it, 300, false, NewPolicy()).run(60) }
        assertTrue("300 ms ahead, back in step within 30 s: ${ahead.map { it.backInStepAtS }}", ahead.all { it.backInStepAtS in 0.0..30.0 })
        assertTrue("by nudging, not a jump", ahead.all { it.seeks == 0 })
    }
}
