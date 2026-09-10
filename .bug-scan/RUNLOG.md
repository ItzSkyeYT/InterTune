# Bug scan run log

Routine N2-A — proactive hunt for unreported bugs in this fork's own code.
Scope is our divergence from `OuterTune/OuterTune`, not upstream's code.

2026-09-01 — full sweep (run 1) of `visionos-fix` @ `de158cf`, surface computed as the tree
diff against `OuterTune/OuterTune@dev` (`12f61da`): 7 fork-only `*.kt` files plus 45
modified ones, read in risk order (playback, media session, coroutine managers, DAO,
scanners, then UI). 3 findings, all new; 7 candidates discarded, 4 of them because they
turned out to be unchanged upstream code. Issues #4, #5, #6. Draft PR #7 for the one
finding that cleared 80/100. Build capability: none — no Android SDK in this environment,
so the patch is UNVERIFIED.

2026-09-02 — diff scan of `visionos-fix` @ `da4843be`, surface `de158cf..da4843be` (8 commits,
22 changed `*.kt` files), read in risk order. 3 findings, all new; 6 candidates discarded, one of
them (`CoilBitmapLoader.fetch`) re-encountered from run 1's discard list and skipped without
re-reporting. Issues #9, #10, #11. **No code PR**: the highest scorer reached 75/100, below the
80 bar. With no Android SDK in this environment Verifiability is capped at 6/25, so a finding has
to be perfect on the other three dimensions to clear 80, and none was. Two of the three findings
(#9, #10) are the same sentinel confusion in the liked-songs catch up read in opposite
directions; both need more than a one-line change to fix correctly. Build capability: none.
