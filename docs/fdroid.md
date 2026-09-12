# Getting InterTune into F-Droid

Issue [#14](https://github.com/ItzSkyeYT/InterTune/issues/14) asks for it. This is what is needed, what is already true, and the two routes in the order worth taking them.

## Where the repository already stands

Checked on 12 September 2026, against the published 0.10.7 apk and the tree it was built from.

- **Licence.** GPL-3.0, with the full text in `LICENSE` and an SPDX header on every file this fork wrote.
- **No proprietary dependencies.** Nothing from Google Play services, Firebase, billing or crash reporting, in the gradle files or in the apk.
- **No prebuilt binaries in the source.** `media/` is ignored and untracked; the only jar in git is the gradle wrapper's. The one native library, `libtaglib.so`, is built by the NDK from the C++ in the `taglib` submodule.
- **The build needs no secrets.** Last.fm credentials and the poll endpoints are read from `local.properties`, which is not in the repository, and every one of them is optional: the Last.fm setting hides itself and the poll checker does nothing. An F-Droid build is an InterTune with no scrobbling login and no questions.
- **Store metadata is in the repository** at `fastlane/metadata/android/en-US`: title, short and full descriptions, an icon, five phone screen shots, three tablet screen shots, and a changelog for the current version code.
- **Releases are tagged.** `v0.10.7` and the rest are real tags on the default branch, which is what a build recipe points at.
- **An F-Droid install already updates through F-Droid.** `utils/InstallSource.kt` recognises the F-Droid clients and Droidify and Neo Store; the in-app updater then stands aside and points at the F-Droid page instead of offering an apk that would refuse to install over a differently signed one.

The signing certificate of the GitHub builds, for anyone verifying an apk or pinning it:

```
SHA-256  69c6b5fbf1220a44206e8a3a098f41c78af7f94d2fa56eb3165ca40b9199a2a7
SHA-1    b2753483bb52715810360d1e08ce1ebbe22b1abc
CN=InterTune, OU=Development, O=skye.dev, C=FR
```

F-Droid's own repository signs with its own key, so an install from there cannot be updated over an install from GitHub, in either direction. That is worth a line in the release notes when it happens.

## Route one: IzzyOnDroid

The fast one, days rather than months. IzzyOnDroid takes the apk from the GitHub release rather than building it, so the submodules and the NDK are not their problem, and it reaches everyone who has that repository in their F-Droid client.

What it asks for is already true: an OSI licence, fastlane metadata in the repository, an apk attached to a GitHub release, a version code that only ever goes up, and no trackers. Open an issue at <https://gitlab.com/IzzyOnDroid/repo/-/issues> using their inclusion request template, with the repository url. Expect them to run a scan and ask about anything it flags.

The one thing to say up front: the app talks to YouTube Music, which is a non-free network service, and to a self-hosted analytics endpoint for the questions it sometimes asks, which is not configured in any build but the maintainer's own.

## Route two: f-droid.org

The slow one, and the one that reaches everybody. It needs a request for packaging, then a build recipe that their server can run.

1. Open an RFP at <https://gitlab.com/fdroid/rfp/-/issues/new> with the template. Name the licence, the repository, and that the app is a fork of OuterTune, itself a fork of InnerTune.
2. Offer the recipe in `docs/fdroid/dev.skye.intertune.yml`, which is a draft of `metadata/dev.skye.intertune.yml` in fdroiddata. It builds the `core` flavour, pulls submodules for taglib, and names the NDK the taglib module asks for.
3. Settle which GPL the project means. Every file header says `GPL-3.0`, which SPDX deprecated because it does not say whether a later version is allowed; F-Droid wants `GPL-3.0-only` or `GPL-3.0-or-later`. The recipe says `GPL-3.0-only` for now, and upstream OuterTune should decide it rather than this fork.
4. Expect two questions. The native library, which is answered by it being built from source in the submodule. And the anti-feature, which is `NonFreeNet` and should be declared rather than argued about.

Their builds are unsigned by us and signed by them, and they build from the tag, so nothing about the release process changes except that the recipe needs a new `Builds:` entry per version.

## What every release has to do from now on

- Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
- Write `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`. F-Droid shows exactly this file, so it is the changelog people read.
- Tag it `vX.Y.Z` and let the workflow build it.
- Add a `Builds:` entry to the recipe draft, with the version name and code in the output path.

## What an F-Droid build will not have

Both are settings that hide themselves rather than failing:

- **Last.fm scrobbling**, which needs an API key the repository does not carry.
- **The questions**, which need the poll endpoints the repository does not carry.

Everything else, including the local player, YouTube Music, downloads, lyrics, the recommendation engine and the home screen widget, is in the build from source.
