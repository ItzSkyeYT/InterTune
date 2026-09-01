<div align="center">

<img src="assets/intertune.svg" width="128" height="128" alt="">

# InterTune

**An Android music player for YouTube Music and local files. A fork of OuterTune, pinned to
upstream 0.10.1, that plays every song to the end again and reworks the landscape player.**

<sub>InnerTune ∩ OuterTune</sub>

[![release](https://img.shields.io/github/v/release/ItzSkyeYT/InterTune?label=release&color=ed5564&labelColor=1b1f24)](https://github.com/ItzSkyeYT/InterTune/releases/latest)

[Download](#download) ·
[Why playback breaks](docs/403.md) ·
[Full changelog](docs/CHANGES.md) ·
[Wiki](https://github.com/ItzSkyeYT/InterTune/wiki)

</div>

## Download

**[Download the latest APK](https://github.com/ItzSkyeYT/InterTune/releases/latest)**

One signed APK per release. Requires Android 7.0 or later. Sideload it, or point
[Obtainium](https://github.com/ImranR98/Obtainium) at this repo.

> [!IMPORTANT]
>
> InterTune installs alongside OuterTune, so both can sit on the device at once: it ships as
> `dev.skye.intertune`, not upstream's `com.dd3boh.outertune`. That also means it cannot update
> an existing OuterTune install in place. Back up in OuterTune first
> (**Settings → Backup and restore**), then restore into InterTune from the same screen. Do not
> uninstall OuterTune until the restore has finished.

## What changed

- **Songs play all the way through.** Upstream 0.10.1 dies partway into every track with
  `Source error (2004): Response code: 403`
  ([#1284](https://github.com/OuterTune/OuterTune/issues/1284),
  [#1282](https://github.com/OuterTune/OuterTune/issues/1282)). [Why](docs/403.md)
- **A landscape player, and a tablet layout, built for the shape of the screen.** Bigger
  artwork and controls, no system bars, and a queue arrow that stops swallowing the transport
  buttons ([#1133](https://github.com/OuterTune/OuterTune/issues/1133)).
  [Notes](docs/CHANGES.md#landscape-and-tablets)
- **Volume levelling that levels** ([#116](https://github.com/OuterTune/OuterTune/issues/116)),
  plus a repair scan for songs whose loudness data went missing. It can still only turn songs
  down, never up. [Notes](docs/CHANGES.md#volume-and-loudness)
- **Artwork at the size it is drawn**, remote and local. YouTube covers were being fetched at
  120px and stretched ([#1247](https://github.com/OuterTune/OuterTune/issues/1247)).
  [Notes](docs/CHANGES.md#artwork)
- **Liquid glass**, off by default; the refraction half needs Android 13 or newer.
  [Asked for](https://github.com/OuterTune/OuterTune/issues/1282#issuecomment-5383885534) in the
  comments of a 403 report rather than in an issue of its own.
  [Notes](docs/CHANGES.md#liquid-glass)
- **An update check.** Upstream removed its own before 0.10.1
  ([#1046](https://github.com/OuterTune/OuterTune/issues/1046)); InterTune asks GitHub about
  new releases, if you let it. [Notes](docs/CHANGES.md#updates-and-onboarding)
- **Play starts on the first tap.** Coming back to the app after a while, the play button turned
  into a replay button and took up to three taps to make a sound; the first tap was what created
  the replay button. [Notes](docs/CHANGES.md#play-on-the-first-tap)
- **Liked songs can download themselves**, all at once or as you like them, on Wi-Fi only if you
  prefer. [Notes](docs/CHANGES.md#downloading-liked-songs)
- **The "not a bot" block is handled rather than displayed.** YouTube rate limits by connection,
  and the app used to answer with a stack trace and keep asking, which is the one thing that
  keeps it blocked ([#1103](https://github.com/OuterTune/OuterTune/issues/1103)). It now backs
  off and says what is happening in words. Still not fixable, only survivable.
  [Notes](docs/CHANGES.md#when-youtube-refuses-the-connection)
- **Everything is translated again.** 51 strings added since the fork began were English in all 29
  languages. [Corrections welcome](#translations).

Eight smaller repairs are listed in [docs/CHANGES.md](docs/CHANGES.md#smaller-fixes), along
with [what this does and does not answer upstream](docs/CHANGES.md#upstream-issues).

## Why it exists

Since August 2026 YouTube has required a GVS proof-of-origin token from the InnerTube clients
upstream streams with. A client that owes a token and sends none gets a cold-start allowance
of roughly 1 MB, then `403` for everything past it. **The allowance is on data, not time**,
which is why the cutoff moves with audio quality: about 31 seconds at 256 kbps, about 60 at
136. Upstream stopped active development in February 2026, and its
[playback-errors megathread](https://github.com/OuterTune/OuterTune/issues/735) was closed as
not planned that April, before this particular regression began. InterTune adds the `VISIONOS`
client, which is exempt, and places it ahead of `IOS`: three files, about fifty lines, no UI
touched. [The write-up, with the measurements](docs/403.md). Not every 403 is this one.
Age-restricted and account-only tracks
([#972](https://github.com/OuterTune/OuterTune/issues/972)) and the "not a bot" block
([#1103](https://github.com/OuterTune/OuterTune/issues/1103)) are explained there, not fixed.

InterTune is pinned to upstream `v0.10.1` and does not follow upstream forward; its own
version numbers carry on from there and do not correspond to upstream releases. 0.10.1's
portrait now-playing screen is the reason this fork is based on 0.10.1 at all, so it is left
alone. I use this daily and keep it public because a few other people found it useful. Updates
come when they come, [issues](https://github.com/ItzSkyeYT/InterTune/issues) get read, and you
should [expect this to break again](docs/403.md#expect-this-to-break-again). If you want a
YouTube Music client that is actively maintained,
[Metrolist](https://github.com/MetrolistGroup/Metrolist) and
[ArchiveTune](https://github.com/koiverse/ArchiveTune) are.

## Translations

InterTune is translated into 29 languages, mostly not by native speakers, so some of it will read
oddly and some of it will simply be wrong. **If anything sounds off in your language, please
[say so](https://github.com/ItzSkyeYT/InterTune/issues/new?template=translation_report.yml).** A one
line report is enough, a suggested wording is a bonus, and you do not need to open a pull request.
Corrections are welcome and nobody minds being told.

Strings inherited from InnerTune and OuterTune belong upstream. The ones this fork added, updates,
loudness repair, liquid glass, the sleep timer fade and downloading liked songs, belong here.

## Building

```bash
git clone --recurse-submodules https://github.com/ItzSkyeYT/InterTune.git
cd InterTune
./gradlew assembleCoreDebug
```

Needs JDK 21 and an Android SDK carrying `platforms;android-36`, `ndk;29.0.13113456` and
`cmake;3.31.6`, with the licences accepted. `--recurse-submodules` matters: `taglib` pulls its
own submodules and the build fails without them. Flavours, signing and the rest are in
[docs/BUILDING.md](docs/BUILDING.md).

## Credits

[InnerTune](https://github.com/z-huang/InnerTune) by z-huang, forked into
[OuterTune](https://github.com/OuterTune/OuterTune), which is where the heavy lifting is.
[AsterTune](https://github.com/yuuichi-s/AsterTune) reached the same `VISIONOS` conclusion
independently. Several other fixes here were rewritten from it against 0.10.1 rather than
cherry-picked. The m3u import bugs were reported by
[cchery2512](https://github.com/cchery2512). Liquid glass was
[rii2609](https://github.com/rii2609)'s idea, and he caught the intensity slider running
backwards. It is built on [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)
(`io.github.kyant0:backdrop`, Apache-2.0).

## Licence

GPL-3.0. See [LICENSE](LICENSE).

Copyright © 2024 z-huang/InnerTune<br>
Copyright © 2025 OuterTune Project<br>
Copyright © 2026 ItzSkyeYT
