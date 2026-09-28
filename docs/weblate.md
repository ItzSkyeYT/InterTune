# Weblate

InterTune's own strings are translated on Weblate's libre tier, at <https://hosted.weblate.org/projects/intertune/>. Newcomers start at <https://hosted.weblate.org/engage/intertune/>, which is what the README and the app's About screen link to. This is the setup, written down because the component settings are where it goes wrong and none of them are guessable afterwards.

## Hosting

Libre hosting was requested on 21 Sep 2026 (ticket 2014781 with care@weblate.org) and is pending approval. Until it is approved the project runs on a trial, which ends on 5 Oct 2026; the billing page (<https://hosted.weblate.org/billing/>) shows both. InterTune qualifies: GPL-3.0-only, public repository, no paywall.

## The component

One component for `strings-ot.xml`.

| Setting | Value |
| --- | --- |
| Version control system | GitHub (via Weblate GitHub app) |
| Repository | `https://github.com/ItzSkyeYT/InterTune.git` |
| Branch | `visionos-fix` |
| Create merge requests | on |
| Merge pull requests automatically | off |
| File mask | `app/src/main/res/values-*/strings-ot.xml` |
| Monolingual base language file | `app/src/main/res/values/strings-ot.xml` |
| Template for new translations | `app/src/main/res/values/strings-ot.xml` |
| File format | Android String Resource |
| Language code style | Default based on the file format |
| Language filter | `^(?!en[-_]r?CA$)[^.]+$` |

**`strings.xml` has no component.** Those 243 strings came from OuterTune, and the reason given for leaving them out was that they arrive through upstream merges. InterTune stays on upstream 0.10.1 and merges nothing from upstream any more, so that reason is gone and translations made on OuterTune's Weblate never reach InterTune. Adding a component for them is open: it would put every string in one place, and 20 languages have only these.

**Leave the language code style on the default.** The file format is already Android String Resource, so the default resolves to Android naming and keeps the legacy codes the existing folders use: `values-in` not `values-id`, `values-iw` not `values-he`, `values-b+sr+Latn` for Serbian Latin. Forcing another style writes a second folder beside each of those and splits the language in two, half translated in each.

**The language filter is matched against the directory suffix, not the language code.** The `*` in the file mask captures `en-rCA`, so a filter written as `^(?!en_CA$)...` silently does nothing and Weblate offers the file to translators as English (Canada). It has to spell the folder: `^(?!en[-_]r?CA$)[^.]+$`. Changing the filter does not remove a language that is already there; Operations, Repository maintenance, Rescan applies it.

**Never remove the language from Weblate's side to get rid of it.** Deleting a translation in Weblate deletes the file from the repository, which for this one means losing the hand-maintained English that en-GB and en-AU read.

**Why `en_CA` is excluded at all.** `values-en-rCA` is not a translation. It is English, and it is what en-GB and en-AU devices read ahead of `values/`, maintained by hand alongside the source. Left in, Weblate would offer it to translators as a language and eventually overwrite it.

## The second component: the store listing

`fastlane/metadata/android/` is what F-Droid shows on the app's page, and it was English only.

| Setting | Value |
| --- | --- |
| Source code repository | `weblate://intertune/strings` |
| File format | App store metadata files |
| File mask | `fastlane/metadata/android/*` |
| Monolingual base language file | `fastlane/metadata/android/en-US` |
| Edit base file | off |

The repository is the `weblate://` form on purpose: it shares the Strings component's clone and its GitHub app connection, so the two move together and push in the same pull request.

"No file mask matches" on a fresh one is expected and says so itself. `en-US` is the base rather than a translation, so until somebody starts a language there is nothing for the mask to match.

**Turn Edit base file off here too.** It defaults on, and on this component it means a translator can rewrite the English store description that F-Droid puts on the app's page.

## How translations get here

The Hosted Weblate GitHub app is installed on ItzSkyeYT/InterTune only, with read access to metadata and read and write access to code, pull requests and workflows. It tells Weblate when the branch changes, and Weblate sends translations as a pull request into `visionos-fix` from a branch the app manages; the push URL and push branch fields are locked in this mode. Nothing lands on `visionos-fix` until the pull request is merged.

Weblate commits translations 24 hours after the last change and pushes on commit, so a pull request usually appears a day after somebody translates.

**Merging one.** Merge it on GitHub, then bring it into the local branch before the next push: `git fetch fork` and `git merge fork/visionos-fix`. Pushing without that is refused as a non-fast-forward, which is the right outcome; never force it.

## When it breaks

**Never rewrite the history of `visionos-fix` on GitHub.** Weblate keeps its own clone and rebases its commits onto the branch every time it changes. When the branch was rewritten in September 2026, its clone still held the old history, every update tried to replay about 3,250 old commits, conflicted in nearly every file, and the components locked themselves ("Could not merge the repository"). If it ever happens again: Operations, Repository maintenance, Reset and discard on the Strings component, which puts Weblate's clone back on the branch as GitHub has it. Check "pending units" is 0 first; anything pending that has not been pushed is lost by the reset.

Components lock themselves on repository errors (Lock on error), which keeps translators from working on top of a broken state. The reason is on the component's Diagnostics tab, and the alerts also arrive by email.

## Project settings

- **Translation instructions** tell translators to keep it short and plain, to leave InterTune, YouTube Music, Last.fm and Shazam as they are, to keep placeholders such as `%1$s`, to comment rather than guess, and where to report the old strings that are not on Weblate.
- **Use shared translation memory** is on, so common words arrive with suggestions from every project on Hosted Weblate.

## After it is running

Add the engage link to the F-Droid recipe, which has no `Translation` field yet:

    Translation: https://hosted.weblate.org/engage/intertune/
