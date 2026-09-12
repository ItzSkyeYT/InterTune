# The merge request, ready to post

Where: a merge request from your fdroiddata fork's `dev.skye.intertune` branch against
`fdroid/fdroiddata`. Their template asks for a description; this is it.

**Title**

```
New app: InterTune
```

**Description**

```
InterTune is a local music player and YouTube Music client, a fork of OuterTune, which is
already packaged here (com.dd3boh.outertune). Separate application id, name and icon:
dev.skye.intertune.

Why a second one. OuterTune's packaged release is 0.10.1 from December 2025, which is where
this fork started, and it has a defect that stops playback after about thirty seconds on
affected songs. That is fixed here, along with two long-standing defects in its
recommendation queries. Since then the fork has gone its own way: recommendations built on
the device from what you actually play, a home screen widget, an offline tab, predictive
back, a highest audio quality tier, and an updater that stands aside for F-Droid installs.

The recipe follows OuterTune's own entry, since it builds the same taglib submodule on the
same infrastructure: cmake 3.31.6 installed in a prebuild line, and the submodule kept out
of the scanner with scandelete. One thing is added. taglib pins NDK 29, which the buildserver
may not carry, so the recipe rewrites that pin to r28c and names r28c. Built and tested that
way on 12 September 2026: all four ABIs, and the whole core release apk.

The build needs no keys. Last.fm credentials and the endpoints for the occasional in-app
question are read from local.properties, which is not in the repository, so a build from
source simply does not offer either feature.

No proprietary dependencies. Reading the published 0.10.7 apk's dex directly: no Play
services, Firebase, Crashlytics, AdMob, Facebook, Flurry, Adjust, AppsFlyer, Sentry, ACRA,
Matomo, billing or install referrer. Three native libraries, two from AndroidX and
libtaglib.so built from the submodule's C++.

NonFreeNet is declared, because playing anything from YouTube Music depends on YouTube Music.

One thing worth your eye: OuterTune's entry here declares GPL-3.0-or-later, and this declares
GPL-3.0-only. The file headers in both projects say the deprecated GPL-3.0, which does not
say whether a later version may be used, so this fork reads it the conservative way. Happy to
match upstream if you would rather they agree.

Request for packaging: <link to the RFP issue>

I am the maintainer.
```
