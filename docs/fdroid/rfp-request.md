# The request for packaging, ready to post

Where: <https://gitlab.com/fdroid/rfp/-/issues/new>, using their Default template.
Who posts it: the maintainer, from his own account. Nothing below has been sent.

One thing to know before posting. Their tracker says plainly that an issue does not
guarantee anybody will package the app, and that a merge request to fdroiddata is the
preferred way in. The recipe in `dev.skye.intertune.yml` is what that merge request would
carry, so the issue can be opened first and the merge request offered in the same breath.

---

**Checklist**

- [x] I have read and followed the inclusion criteria.
- [x] The app is not in the repository or the issue tracker already.
- [x] It has not been requested before.
- [x] The source has its metadata in the fastlane folder structure.
- [x] I am the author of this app, so there is nobody else to notify.

**Application ID**

```
dev.skye.intertune
```

**Metadata**

```yaml
Categories:
  - Multimedia
License: GPL-3.0-only
AuthorName: Mel Boro
SourceCode: https://github.com/ItzSkyeYT/InterTune
IssueTracker: https://github.com/ItzSkyeYT/InterTune/issues
Changelog: https://github.com/ItzSkyeYT/InterTune/releases
AutoName: InterTune
AntiFeatures:
  - NonFreeNet
RepoType: git
Repo: https://github.com/ItzSkyeYT/InterTune.git
```

**Why do you want this app added to F-Droid**

I maintain it, and the people who use it deserve a way to install and update it that is
not a sideloaded apk from a GitHub release. It is a fork of OuterTune, itself a fork of
InnerTune, and it credits both; it is not a repackage but a fork with its own work in it,
including a playback fix for a defect that stops upstream after about thirty seconds on
some songs, and recommendations built on the device rather than fetched.

The app already behaves as though F-Droid were its home: the in-app update check is
opt-in, off until somebody says yes, and it turns itself off entirely and points at the
F-Droid page when it sees that an F-Droid client installed it.

**Summary**

```
Material 3 local music player and YouTube Music client
```

**Description**

```
InterTune is a fork of OuterTune, which is itself a fork of InnerTune. It is both a local
media player and a YouTube Music client.

Features:
- YouTube Music client with song downloading for offline playback, background playback,
  no ads, and account synchronisation
- Local audio file playback (MP3, OGG, FLAC and more)
- A custom tag extractor rather than MediaStore's, so tags delimited with a backslash are
  read properly
- Local files and YouTube Music songs in the same queue
- An offline tab listing what is already cached on the device
- Multiple queues
- Synchronised lyrics, including word by word and karaoke (LRC, TTML)
- Audio normalisation, tempo and pitch adjustment
- Quick picks either from YouTube Music or built on the device from what you have played
- Android Auto support

Notice:
- Android 8 and higher is supported. It may work on Android 7.x, which is not officially
  supported.
- InterTune is in a stable beta. It can be a main music player, but expect the occasional
  bug or unfinished feature. Please report anything you find.

InterTune is not affiliated with, sponsored by, or endorsed by Google or YouTube.
```

**Notes for whoever packages it**

- A build recipe is drafted at
  <https://github.com/ItzSkyeYT/InterTune/blob/visionos-fix/docs/fdroid/dev.skye.intertune.yml>.
  It builds the `core` flavour, pulls submodules, and names NDK r28c.
- The taglib submodule pins NDK 29, which the recipe rewrites in a prebuild line to r28c.
  Built and tested that way on 12 September 2026: all four ABIs, and the whole release apk.
- The build needs no keys. The Last.fm login and the occasional in-app question read their
  credentials from `local.properties`, which is not in the repository, so a build from
  source simply does not offer either of them.
- There are no proprietary dependencies. Reading the published 0.10.7 apk's dex directly:
  no Play services, Firebase, Crashlytics, AdMob, Facebook, Flurry, Adjust, AppsFlyer,
  Sentry, ACRA, Matomo, billing or install referrer. Three native libraries, two from
  AndroidX and `libtaglib.so` built from the submodule's C++.
- `NonFreeNet` is declared because half of what the app does is talk to YouTube Music.
