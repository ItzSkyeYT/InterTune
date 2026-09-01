<!--
This is a personal fork, so pull requests are not expected — but a small, tested, single-purpose one
gets read.

Worth knowing before you spend a weekend on it: InterTune is deliberately a minimal diff against
upstream v0.10.1. Rebases onto newer upstream, portrait redesigns and translations are out of scope,
as is extending glass onto song rows, grids, artwork, the lyrics body or the tablet nav rail — the
README says why. Anything inside what the fork already changed — the landscape player, liquid glass,
artwork loading, playback — is fair game.

Delete any section below that does not apply rather than leaving it empty.
-->

## What this changes, and why

<!-- Bullets or prose. Say what a user would notice, and what problem it solves. -->

<!-- One "Fixes #123" per line so GitHub closes each on merge. Delete the line if no issue applies. -->
Fixes #

## Before and after

<!--
UI changes only; delete this section otherwise. For anything animated, gesture-driven or inside the
landscape player, record a video rather than taking a screenshot — two defects in this fork's own
landscape work were invisible in a still.
-->

| Before | After |
| --- | --- |
|  |  |

## Built and tested

<!-- Building it and running it is the one thing genuinely required. Build setup is in the README:
https://github.com/ItzSkyeYT/InterTune#building -->

- Device and Android version:
- Variant built: <!-- coreDebug, coreRelease, fullDebug, ... -->
- What you did to convince yourself it works:

## Scope

<!-- Leave a box unticked and say why rather than ticking it dishonestly — an explained exception is
fine, a silent one is not. -->

- [ ] Stays on the `v0.10.1` base — no rebase onto newer upstream, no `lite` branch behaviour
- [ ] Leaves the portrait player alone, or is deliberately about portrait
- [ ] Any new user-facing strings are in `strings-ot.xml`
- [ ] No drive-by refactors riding along
- [ ] If ported from upstream or another fork, the original is credited above

---

Commits may be squashed or rebased on merge, and conflicts may be resolved for you. Say so here if
you would rather do that yourself.
