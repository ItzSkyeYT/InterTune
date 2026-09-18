# Why the recipe looks like that

The recipe itself carries no comments, because it cannot. `fdroid rewritemeta` runs in
fdroiddata's CI, rewrites every metadata file into a canonical form, and fails the job if the
file on the branch differs from what it would have written. Comments do not survive that, so
the first CI run that actually executed failed on exactly this, with a diff of the comment
block being stripped. Everything that used to be explained in the file is here instead.

Keep the yml byte-identical to `fdroid rewritemeta` output. It is copied straight into
fdroiddata, so anything added here has to survive that pass.

## Field order

`ndk` goes after `scandelete`, not before. That was the only thing rewritemeta changed on an
otherwise canonical file, and it is not something you would guess.

## The NDK, and the sed that used to be here

The first submission carried a prebuild line that rewrote taglib's `ndkVersion` from
29.0.13113456 down to 28.2.13676358, on the assumption that the buildserver would not have
NDK 29. That assumption was wrong twice over.

It was wrong because the buildserver does not need to have anything. When fdroidserver meets
an NDK it lacks it downloads it from Google on the spot, which the build log shows it doing
for r28c: "Android NDK version '28.2.13676358' could not be found!" followed immediately by a
download. And 559 build entries in fdroiddata already name r29, with 40 of them on this exact
revision.

It was also wrong in effect. The sed ran, the log confirms it ran, and the build still died
with `[CXX1104] NDK from ndk.dir had version [28.2.13676358] which disagrees with
android.ndkVersion [29.0.13113456]`. Whatever the reason the edit did not reach the module
Gradle configured, the fix is not a better sed. It is to name the version the project already
declares and let fdroidserver fetch it.

So: `ndk: 29.0.13113456`, no prebuild rewriting, and one less thing that can drift when taglib
is next bumped. If taglib's pin changes, change this line to match and nothing else.

## cmake and scandelete

Both copied from OuterTune's own entry, which builds the same submodule on the same
infrastructure. cmake 3.31.6 is what taglib's CMakeLists demands and the buildserver does not
install by default. `scandelete: taglib` keeps the submodule's C++ and its own gradle wrapper
away from the source scanner.

## Reproducible builds

`Binaries` and `AllowedAPKSigningKeys` are in the first submission on purpose. fdroiddata's
inclusion template says an app first published signed with F-Droid's key cannot move to the
developer's own later, so there is no second chance at this. It decides whether someone
running the GitHub apk can ever move to the F-Droid one without uninstalling and losing their
library, which matters here because the updater already stands aside for F-Droid installs.

Both values were checked against the published 0.10.8 artifact rather than assumed: apksigner
reports the V2 signer certificate digest as 69c6b5fb..99a2a7, and aapt2 reports the package as
dev.skye.intertune.

If the comparison does not come out byte for byte, drop those two lines and take F-Droid's
signature. That costs a round trip. The other order costs the signature permanently.

## What CI actually checks

From the first real run, pipeline 2860470660: `fdroid lint`, `schema validation`,
`check source code`, `tools check scripts`, `checkupdates` and `git redirect` all pass.
`fdroid rewritemeta` and `fdroid build` are the two that bite. `check apk` only runs if the
build produced one.

Linting locally needs fdroidserver plus a directory holding `config.yml` and `metadata/`.
Three messages always appear there and can be ignored: NonFreeNet "not a valid AntiFeatures"
and the two Categories complaints. They come from the bare config lacking fdroiddata's own
`config/` directory, upstream's merged entry produces them identically, and the real
`fdroid lint` job passes.
