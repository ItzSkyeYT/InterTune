# The request for packaging, field by field

Where: <https://gitlab.com/fdroid/rfp/-/issues/new>, which loads their template.
Who posts it: the maintainer, from his own account. Nothing here has been sent.

The four checkboxes at the top: leave the first three ticked, leave the donation one
alone. The third one says the original app author has been notified and does not oppose
inclusion; the fork's author is the person posting, and the description says so.

**Title**

```
InterTune - Material 3 local music player and YouTube Music client
```

**Link to the source code**

```
https://github.com/ItzSkyeYT/InterTune
```

**Link to app in another app store**

```
None. Releases are published on GitHub: https://github.com/ItzSkyeYT/InterTune/releases
```

**License used**

```
GPL-3.0-only
```

**Category**

```
Multimedia
```

**Summary**

```
Material 3 local music player and YouTube Music client
```

**Description**

```
InterTune is a local music player and YouTube Music client for Android. It is a fork of
OuterTune, which is a fork of InnerTune, and it credits both.

OuterTune is already in F-Droid, at 0.10.1 from December 2025, so the first question is
fair: why a second one. That release is where this fork started, and it has a defect that
stops playback after about thirty seconds on affected songs. That is fixed here, along
with two long-standing defects in its recommendation queries. Since then the fork has gone
its own way: recommendations built on the device from what you actually play rather than
fetched, a home screen widget, an offline tab, predictive back, a highest audio quality
tier with a readout of what is really playing, and an F-Droid aware updater. It has its
own application id (dev.skye.intertune), its own name and icon, and it is maintained
weekly.

Features:
- YouTube Music client with downloads for offline playback, background playback, no ads,
  and account synchronisation
- Local audio file playback (MP3, OGG, FLAC and more), with its own tag extractor rather
  than MediaStore's, so tags delimited with a backslash are read properly
- Local files and YouTube Music songs in the same queue, and multiple queues
- An offline tab listing what is already cached on the device
- Synchronised lyrics, including word by word and karaoke (LRC, TTML)
- Audio normalisation, tempo and pitch adjustment
- Quick picks either from YouTube Music or built on the device from what you have played
- A resizable home screen widget
- Android Auto support

Android 8 and higher. It may work on 7.x, which is not officially supported.

InterTune is not affiliated with, sponsored by, or endorsed by Google or YouTube.

Notes for packaging:
- A build recipe is drafted at
  https://github.com/ItzSkyeYT/InterTune/blob/visionos-fix/docs/fdroid/dev.skye.intertune.yml
  It builds the core flavour, pulls submodules, and names NDK r28c.
- The taglib submodule pins NDK 29, which the recipe rewrites to r28c in a prebuild line.
  Built and tested that way on 12 September 2026: all four ABIs, and the whole release apk.
- The build needs no keys. Last.fm credentials and the endpoints for the occasional in-app
  question are read from local.properties, which is not in the repository, so a build from
  source simply does not offer either feature.
- No proprietary dependencies. Reading the published 0.10.7 apk's dex directly: no Play
  services, Firebase, Crashlytics, AdMob, Facebook, Flurry, Adjust, AppsFlyer, Sentry,
  ACRA, Matomo, billing or install referrer. Three native libraries: two from AndroidX and
  libtaglib.so built from the submodule's C++.
- Anti-feature: NonFreeNet, because half of what the app does is talk to YouTube Music.
- The in-app update check is opt-in, off until somebody says yes, and it disables itself
  and points at the F-Droid page when an F-Droid client installed the app.
```

---

Their tracker says plainly that an issue does not guarantee anybody packages the app, and
that a merge request to fdroiddata is the preferred route. The recipe is what that merge
request would carry, so the issue can be opened and the merge request offered with it.
