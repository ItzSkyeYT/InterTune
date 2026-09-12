# Getting InterTune into F-Droid

Issue [#14](https://github.com/ItzSkyeYT/InterTune/issues/14) asks for it. This is what is needed, what is already true, and the two routes in the order worth taking them.

## Where the repository already stands

Checked on 12 September 2026, against the published 0.10.7 apk and the tree it was built from.

- **Licence.** GPL-3.0, with the full text in `LICENSE` and an SPDX header on every file this fork wrote.
- **No proprietary dependencies.** Nothing from Google Play services, Firebase, billing or crash reporting, in the gradle files or in the apk.
- **No prebuilt binaries in the source.** `media/` is ignored and untracked; the only jar in git is the gradle wrapper's. The one native library, `libtaglib.so`, is built by the NDK from the C++ in the `taglib` submodule.
- **The build needs no secrets.** Last.fm credentials and the poll endpoints are read from `local.properties`, which is not in the repository, and every one of them is optional: the Last.fm setting hides itself and the poll checker does nothing. An F-Droid build is an InterTune with no scrobbling login and no questions.
- **The screen shots carry nobody's library.** They were taken on a second, clean install of the app, package `dev.skye.intertune` beside the maintainer's own `dev.skye.intertune.debug`, whose library is thirteen well-known albums added by hand. No playlists, no recommendations, no listening history belonging to anyone.
- **Store metadata is in the repository** at `fastlane/metadata/android/en-US`: title, short and full descriptions, an icon, six phone screen shots, five tablet screen shots, and a changelog for the current version code.
- **Releases are tagged.** `v0.10.7` and the rest are real tags on the default branch, which is what a build recipe points at.
- **An F-Droid install already updates through F-Droid.** `utils/InstallSource.kt` recognises the F-Droid clients and Droidify and Neo Store; the in-app updater then stands aside and points at the F-Droid page instead of offering an apk that would refuse to install over a differently signed one.

The signing certificate of the GitHub builds, for anyone verifying an apk or pinning it:

```
SHA-256  69c6b5fbf1220a44206e8a3a098f41c78af7f94d2fa56eb3165ca40b9199a2a7
SHA-1    b2753483bb52715810360d1e08ce1ebbe22b1abc
CN=InterTune, OU=Development, O=skye.dev, C=FR
```

F-Droid's own repository signs with its own key, so an install from there cannot be updated over an install from GitHub, in either direction. Moving either way means uninstalling first, which takes the library with it unless somebody backs it up. The 0.11 release notes say so and point at Settings > Backup and restore, and that line should be repeated whenever the F-Droid listing actually lands.

## IzzyOnDroid is not a route, and here is why

Their [app inclusion policy](https://izzyondroid.org/docs/general/AppInclusionPolicy/) rejects apps created fully or in part by generative AI, rejects what it calls vibe-coded apps outright, and says that a lack of transparency about it can move a project to rejected. Using a language model to research, debug or look something up is allowed; its output ending up in the code is not.

A large part of this fork's recent work was written that way, in the open, with the maintainer directing it. That is exactly what the policy excludes. There are two honest options and neither is a submission as things stand:

1. Do not apply. The f-droid.org route below has no such rule.
2. Apply and say so in the request. Expect it to be rejected, and treat the answer as theirs to give rather than something to word around.

What is not an option is applying and staying quiet about it. The policy asks for transparency by name, and a repository that finds out afterwards is entitled to feel misled.

Everything else IzzyOnDroid asks for is already true here: an OSI licence, sources on GitHub, fastlane metadata in the repository, a release with one signed apk under thirty megabytes, no trackers, no proprietary libraries, and an in-app updater that is opt-in and off until somebody says yes.

## The f-droid.org route

Their [inclusion policy](https://f-droid.org/docs/Inclusion_Policy/) says nothing about how the code was written. It asks that the app be free software, that it build from source on their infrastructure with a free toolchain, that it carry no proprietary tracking or advertising libraries, and that anything which downloads an executable be opt-in and explained. All four hold.

Checked against the published 0.10.7 apk, by reading its dex directly: no Firebase, no Crashlytics, no Play services, no AdMob, no Facebook, Flurry, Adjust, AppsFlyer, Sentry, ACRA or Matomo, no Play billing and no install referrer. Three native libraries, two of them AndroidX and the third `libtaglib.so`, built from the C++ in the taglib submodule.

1. Open a request for packaging at <https://gitlab.com/fdroid/rfp/-/issues/new> with the template. The text below is ready to paste.
2. Offer the recipe in `docs/fdroid/dev.skye.intertune.yml`, which is a draft of `metadata/dev.skye.intertune.yml` in fdroiddata. It builds the `core` flavour, pulls submodules for taglib, and names an NDK.
3. The licence identifier is settled: `GPL-3.0-only`. The headers inherited from upstream say `GPL-3.0`, which SPDX deprecated because it does not say whether a later version may be used, and a fork cannot grant a permission its upstream never gave. The README says so in as many words. If OuterTune or InnerTune ever declare or-later, this follows them and the recipe changes with it.
4. Expect two questions. The native library, answered by it being built from source in the submodule. And the anti-feature, which is `NonFreeNet` because half of what the app does is talk to YouTube Music; declare it rather than argue.

The NDK question is settled rather than left open. The taglib submodule pins `29.0.13113456`, which is new enough that the buildserver may not carry it, so the recipe names `28.2.13676358` (r28c) and rewrites the submodule's pin in a prebuild line. Tested on 12 September 2026: taglib built for all four ABIs, and the whole core release apk built with it, native library and all.

The `full` flavour's ffMetadataEx pins the same NDK. The recipe builds `core`, so it does not matter, but anybody switching the recipe to `full` has a second sed to write.

### The request, ready to paste

```
App name: InterTune
Package ID: dev.skye.intertune
Source: https://github.com/ItzSkyeYT/InterTune
Issue tracker: https://github.com/ItzSkyeYT/InterTune/issues
Releases: https://github.com/ItzSkyeYT/InterTune/releases (tagged vX.Y.Z, one signed apk each)
License: GPL-3.0-only
Categories: Multimedia
Anti-Features: NonFreeNet

InterTune is a local music player and YouTube Music client for Android. It is a
fork of OuterTune, which is a fork of InnerTune, and it credits both. It plays
local files (MP3, OGG, FLAC and more, read with its own tag extractor), plays
and downloads from YouTube Music, shows synced lyrics, and keeps its
recommendations on the device.

It builds with gradle from the tag, needs no keys or secrets, and has no
proprietary dependencies: no Play services, no Firebase, no analytics library.
The Last.fm login and the occasional in-app question both read their
credentials from local.properties, which is not in the repository, so a build
from source simply does not offer them.

The one native library, libtaglib.so, is built by the NDK from the C++ in the
taglib submodule.

The in-app update check is opt-in, off until the user says yes during setup,
and it disables itself entirely when the app was installed by an F-Droid
client, pointing at the F-Droid page instead.

A build recipe is drafted at
https://github.com/ItzSkyeYT/InterTune/blob/visionos-fix/docs/fdroid/dev.skye.intertune.yml
```

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
