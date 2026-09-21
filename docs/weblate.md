# Weblate

InterTune's own strings are translated on Weblate's libre tier. This is the setup, written down
because the component settings are where it goes wrong and none of them are guessable afterwards.

## Requesting hosting

Apply at <https://hosted.weblate.org/hosting/>. Free for libre projects, and InterTune qualifies:
GPL-3.0-only, public repository, no paywall. Upstream OuterTune is already hosted there, which is
worth mentioning in the request.

## The component

One component, and only one. Its job is `strings-ot.xml`.

| Setting | Value |
| --- | --- |
| Repository | `https://github.com/ItzSkyeYT/InterTune` |
| Branch | `visionos-fix` |
| File mask | `app/src/main/res/values-*/strings-ot.xml` |
| Monolingual base language file | `app/src/main/res/values/strings-ot.xml` |
| Template for new translations | `app/src/main/res/values/strings-ot.xml` |
| File format | Android String Resource |
| Language code style | Default based on the file format |
| Language filter | `^(?!en[-_]r?CA$)[^.]+$` |

**Do not add a second component for `strings.xml`.** Those 243 strings are OuterTune's and are
translated on their Weblate. They arrive here through upstream merges. A component here would mean
a merge and a translation sync writing over each other, and whichever lost would keep losing.

**Leave the language code style on the default.** The file format is already Android String
Resource, so the default resolves to Android naming and keeps the legacy codes the existing folders
use: `values-in` not `values-id`, `values-iw` not `values-he`, `values-b+sr+Latn` for Serbian Latin.
Forcing another style writes a second folder beside each of those and splits the language in two,
half translated in each.

**The language filter is matched against the directory suffix, not the language code.** The `*` in
the file mask captures `en-rCA`, so a filter written as `^(?!en_CA$)...` silently does nothing and
Weblate offers the file to translators as English (Canada). It has to spell the folder:
`^(?!en[-_]r?CA$)[^.]+$`. Changing the filter does not remove a language that is already there;
Operations, Repository maintenance, Rescan applies it.

**Never remove the language from Weblate's side to get rid of it.** Deleting a translation in
Weblate deletes the file from the repository, which for this one means losing the hand-maintained
English that en-GB and en-AU read.

**Why `en_CA` is excluded at all.** `values-en-rCA` is not a translation. It is English, and
it is what en-GB and en-AU devices read ahead of `values/`, maintained by hand alongside the source.
Left in, Weblate would offer it to translators as a language and eventually overwrite it.

## The second component: the store listing

`fastlane/metadata/android/` is what F-Droid shows on the app's page, and it was English only.

| Setting | Value |
| --- | --- |
| Source code repository | `weblate://intertune/strings` |
| File format | App store metadata files |
| File mask | `fastlane/metadata/android/*` |
| Monolingual base language file | `fastlane/metadata/android/en-US` |
| Edit base file | off |

The repository is the `weblate://` form on purpose: it shares the clone the Strings component
already has rather than checking the same repository out twice, and the two then move together.

"No file mask matches" on a fresh one is expected and says so itself. `en-US` is the base rather
than a translation, so until somebody starts a language there is nothing for the mask to match.

**Turn Edit base file off here too.** It defaults on, and on this component it means a translator
can rewrite the English store description that F-Droid puts on the app's page.

## Push access

Weblate commits translations itself rather than opening pull requests. Give it push access by
adding its SSH key (shown in the component's settings) as a deploy key on the repository with
write access.

If that is more trust than wanted, set the component to push to a fork and open merge requests
instead. It costs a click per batch and keeps 48 locale files from changing unreviewed.

## Pulling

A webhook on the GitHub repository posts to `https://hosted.weblate.org/hooks/github/` on push, so
Weblate pulls by itself. Without it the repository is only pulled when somebody presses Update, and
the Update button on the component page does not always take.

## After it is running

Add the engage link to the F-Droid recipe, which has no `Translation` field yet:

    Translation: https://hosted.weblate.org/engage/intertune/
