## Required

* [x] The app complies with the [inclusion criteria](https://f-droid.org/docs/Inclusion_Policy)
* [ ] The original app author has been notified (and does not oppose the inclusion)
* [x] All related [fdroiddata](https://gitlab.com/fdroid/fdroiddata/issues) and [RFP issues](https://gitlab.com/fdroid/rfp/issues) have been referenced in this merge request
* [ ] Builds with `fdroid build` and all pipelines pass
* [x] There is an issue tracker and contact info of the author so that we can report bugs and contact the author.

## Strongly Recommended

* [x] The upstream app source code repo contains the app metadata _(summary/description/images/changelog/etc)_ in a Fastlane or Triple-T folder structure
* [x] Releases are tagged and auto update is enabled

## Suggested

* [x] External repos are added as git submodules instead of srclibs
* [x] Enable [Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds)
* [ ] Multiple apks for native code

The universal apk is 11.2 MB with all four ABIs, so splitting it would save little and cost the sideload path a single file. Happy to split if you would rather.

---

InterTune is a local music player and YouTube Music client, a fork of OuterTune, which is already packaged here (com.dd3boh.outertune). Separate application id, name and icon: dev.skye.intertune.

Why a second one. OuterTune's packaged release is 0.10.1 from December 2025, which is where this fork started, and it has a defect that stops playback after about thirty seconds on affected songs. That is fixed here, along with two long-standing defects in its recommendation queries. Since then the fork has gone its own way: recommendations built on the device from what you actually play, a home screen widget, an offline tab, predictive back, a highest audio quality tier, and an updater that stands aside for F-Droid installs.

The recipe follows OuterTune's own entry, since it builds the same taglib submodule on the same infrastructure: cmake 3.31.6 installed in a prebuild line, and the submodule kept out of the scanner with scandelete. One thing is added. taglib pins NDK 29, which the buildserver may not carry, so the recipe rewrites that pin to r28c and names r28c. Built and tested that way on 12 September 2026: all four ABIs, and the whole core release apk.

Reproducible builds are requested from the start, since I gather an app first published with F-Droid's signature cannot move to the developer's own afterwards. The release apks are published at a stable path and signed with one key. Verified against the published 0.10.8 artifact rather than asserted: apksigner reports the V2 signer certificate digest as 69c6b5fbf1220a44206e8a3a098f41c78af7f94d2fa56eb3165ca40b9199a2a7, which is what AllowedAPKSigningKeys carries. If the comparison does not come out byte for byte, say so and I will drop Binaries and AllowedAPKSigningKeys and take F-Droid's signature instead.

The build needs no keys. Last.fm credentials and the endpoints for the occasional in-app question are read from local.properties, which is not in the repository, so a build from source simply does not offer either feature.

No proprietary dependencies. Reading the published apk's dex directly: no Play services, Firebase, Crashlytics, AdMob, Facebook, Flurry, Adjust, AppsFlyer, Sentry, ACRA, Matomo, billing or install referrer. Three native libraries, two from AndroidX and libtaglib.so built from the submodule's C++.

NonFreeNet is declared, because playing anything from YouTube Music depends on YouTube Music.

A request for packaging was filed as rfp#4387 and closed automatically by fdroid-bot six minutes later, labelled com.dd3boh.outertune. That is upstream's package, and the match is a false one: app/build.gradle.kts still carries upstream's namespace, inherited along with the package structure, while the applicationId two lines below it is dev.skye.intertune. aapt2 on the published apk reports package: name='dev.skye.intertune'. The two install side by side, and the signing keys differ, so there is no upgrade path between them either way. Raising it here rather than reopening there, since a merge request is what the docs ask for.

One thing worth your eye: OuterTune's entry here declares GPL-3.0-or-later and this declares GPL-3.0-only. The file headers in both projects say the deprecated GPL-3.0, which does not say whether a later version may be used, so this fork reads it the conservative way. Happy to match upstream if you would rather they agree.

I am the maintainer.

Closes rfp#4387

/label ~"New App"
