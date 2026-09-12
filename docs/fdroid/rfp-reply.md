# The reply to the closed request, ready to post

The request was closed by `fdroid-bot` six minutes after it was opened. Its labels say why
without anybody having to guess: alongside `in-fdroiddata` it put `com.dd3boh.outertune`
on the issue. That is not this app's id. It is the `namespace` still declared in
`app/build.gradle.kts`, inherited from upstream along with the package structure, while the
`applicationId` two lines below it is `dev.skye.intertune`. The bot read the namespace,
found OuterTune already packaged, and closed the request as a duplicate.

So the reply is one correction and one offer. Post it as a comment on the issue; a closed
issue still takes comments, and a maintainer can reopen it.

```
The bot matched this to com.dd3boh.outertune, which is upstream's package and is indeed
already in F-Droid. That is not what this app installs as.

app/build.gradle.kts keeps upstream's namespace, because the fork kept the package
structure and renaming it would touch every generated R and BuildConfig reference, but the
applicationId is dev.skye.intertune:

    namespace = "com.dd3boh.outertune"
    applicationId = "dev.skye.intertune"

The published apk agrees: aapt2 on the 0.10.7 release reports
package: name='dev.skye.intertune'. The two install side by side, and the signing keys
differ, so there is no upgrade path between them either way.

Why a second one at all: OuterTune's packaged release is 0.10.1 from December 2025, which
is where this fork started, and it has a defect that stops playback after about thirty
seconds on affected songs. That is fixed here, and the fork has since gone its own way.
The full case is in the description above.

Happy to take this straight to a merge request against fdroiddata instead, which your
submission queue prefers anyway: the recipe is written, follows OuterTune's own entry for
cmake and the taglib submodule, and has been built and tested. Say which you would rather
have and I will do that.
```
