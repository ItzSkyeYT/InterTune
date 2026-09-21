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
| Language code style | Android |
| Language filter | `^(?!en_CA$).+$` |

**Do not add a second component for `strings.xml`.** Those 243 strings are OuterTune's and are
translated on their Weblate. They arrive here through upstream merges. A component here would mean
a merge and a translation sync writing over each other, and whichever lost would keep losing.

**Language code style must be Android.** The existing folders use the legacy codes Android wants:
`values-in` not `values-id`, `values-iw` not `values-he`, and `values-b+sr+Latn` for Serbian Latin.
Any other setting starts writing a second folder beside each of those and splits the language in
two, half translated in each.

**The language filter excludes `en_CA`.** `values-en-rCA` is not a translation. It is English, and
it is what en-GB and en-AU devices read ahead of `values/`, maintained by hand alongside the source.
Left in, Weblate would offer it to translators as a language and eventually overwrite it.

## Push access

Weblate commits translations itself rather than opening pull requests. Give it push access by
adding its SSH key (shown in the component's settings) as a deploy key on the repository with
write access.

If that is more trust than wanted, set the component to push to a fork and open merge requests
instead. It costs a click per batch and keeps 48 locale files from changing unreviewed.

## After it is running

Add the engage link to the F-Droid recipe, which has no `Translation` field yet:

    Translation: https://hosted.weblate.org/engage/intertune/
