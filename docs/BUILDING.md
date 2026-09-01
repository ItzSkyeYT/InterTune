# Building InterTune

Upstream `v0.10.1` does not build from a clean checkout today. Two of the blockers are already
fixed as commits here: a dead JitPack pin on NewPipeExtractor, and a licence check that rejected
GPL-3.0-or-later. The rest is toolchain you install yourself, listed below.

```bash
git clone --recurse-submodules https://github.com/ItzSkyeYT/InterTune.git
cd InterTune
./gradlew assembleCoreDebug
```

`--recurse-submodules` matters: `taglib` pulls its own submodules and the build fails without
them. If you have already cloned without it, `git submodule update --init --recursive`.

## What you need

**JDK 21.** `:app` and the Kotlin library modules declare `jvmToolchain(17)`, so Gradle can
provision 17 for them, but `:taglib` declares no toolchain and compiles at Java 21, meaning the
Gradle daemon's own JDK has to be 21 or newer. On JDK 17 the build fails in `:taglib` with
`invalid source release: 21`. CI builds on Temurin 21.

| Needed | Why |
|---|---|
| `platforms;android-36` | `compileSdk = 36` |
| `ndk;29.0.13113456` | native modules `ffMetadataEx` and `taglib` |
| `cmake;3.31.6` | taglib's build |
| SDK licences accepted | `sdkmanager --licenses` |

## Flavours and build types

Flavours are `core` (default) and `full`, which adds the FFmpeg audio decoders for ALAC, APE,
WavPack and DSD along with the FFmpeg metadata tag extractor. Build types are `release`, `debug`
and `userdebug`. Debug builds use the applicationId suffix `.debug`, so a debug and a release
build coexist on one device.

Published releases are `core` only, one universal APK each. The `full` variant needs extra
setup for `ffMetadataEx`; see
[its README](https://github.com/OuterTune/ffMetadataEx/blob/main/README.md#building).

`CONTRIBUTING.md` lists the update checker as a `full`-only feature. That was true upstream; in
InterTune `UpdateChecker` sits in the main source set and both flavours have it.

## Signing a release build

Release builds are signed from a gitignored `keystore.properties` at the repo root:

```properties
storeFile=/path/to/your.jks
keyAlias=youralias
storePassword=...
keyPassword=...
```

Without that file the release variant has no signing config and will not assemble.

InterTune installs alongside OuterTune because its applicationId is `dev.skye.intertune`, not
upstream's `com.dd3boh.outertune`. Its releases are signed with a different key as well, which is
why a build you sign yourself cannot update an InterTune release installed from GitHub, and vice
versa.

## Notes

On a memory-constrained machine, `--max-workers=2` avoids the OOM killer during the native
build.

Pull request and commit conventions are in [CONTRIBUTING.md](../CONTRIBUTING.md), which is still
upstream's document and describes upstream's process.
