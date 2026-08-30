Same code as 0.10.1-8, renumbered to a cleaner version. If you are already on 0.10.1-8 there is nothing new here and no need to update.

Everything below is what landed in that build.

Song volumes are the big one: they should now be consistent across your whole library, and there is a new tool to repair songs that were missing volume information.

## Some songs played much louder than the rest

If a handful of songs, usually ones you downloaded or liked, blasted out while everything else sat at a sensible level, this is why.

Volume levelling can only turn songs **down**, never up. It needs to know how loud a song is, and that information comes from YouTube. A song missing it got no adjustment at all, which is the loudest possible outcome. Every other song had been pulled down by 6 to 10 dB, so the untouched ones stood out by as much as 16 dB. That is roughly three times as loud.

Two things caused it and both are fixed.

- **Downloading a song wiped its volume information.** Worse than failing to add it: if you had already played the song, downloading it *erased* the value that was already there. One bulk download of liked songs was enough to break 305 songs at once. Downloading can no longer overwrite a known value with nothing.
- **"I don't know" now means quiet, not loud.** When the volume is unknown the app assumes the song is a loud one and turns it down, rather than leaving it at full blast. Being wrong quietly is fixable with the volume rocker. Being wrong loudly is a jump scare. Worst case drops from about +16 dB to under +3 dB.

## New: Fix song volumes

**Settings → Player and audio → Fix song volumes**

Looks up the correct volume for songs that are missing it. It tells you how many need it, shows progress, and you can stop it at any time.

Some deliberate choices:

- **It only looks up songs that are actually broken.** Songs you have never played simply have no volume information yet, and they get it the first time you play them. Those are not broken and are left alone. On a real library that is the difference between a few hundred lookups and nearly 29,000.
- **It skips whatever is playing.** An unrepaired song is currently playing quiet, so filling in its real volume mid-song would make it jump up in your ears. Those are picked up next time.
- **It goes at a steady pace**, roughly a song every third of a second, so it does not look like abuse to YouTube. A few hundred songs takes about three minutes.
- **It stops itself** after several failures in a row, which usually means YouTube wants a break, rather than grinding through hundreds more.
- **It only ever writes the volume**, never any other detail about the song, and running it twice is harmless.

<details><summary>Testing notes</summary>

Run against a real 32,473 song library: 355 songs missing volume information, all 355 repaired, 0 unavailable, 0 failed, in 2 minutes 46 seconds.

Checked in the database afterwards rather than trusting the on-screen message: no other column on any row changed, the row counts were identical before and after, and the repaired values averaged 5.82 against 5.80 for the songs that were never broken, which is what shows the right value was read rather than something that merely looks like a number.

An adversarial review of this feature found two critical defects before release. The scan read the player from a background thread, which would have crashed on the first song and then reported itself as "stopped" rather than as a failure, so it would never have repaired anything. Separately, local music files, which never have YouTube volume information, would have been permanently turned down by 13 dB with no way to fix them. Both are fixed; local files are now left at full volume until there is a proper way to measure them.

</details>

## Album artwork

- **Sharper artwork.** The app asks for artwork at the size it is actually drawn. It was only doing this for one of Google's image servers, and almost all YouTube Music artwork comes from a different one, so nearly every cover was being stretched up from a 120 pixel thumbnail.
- **Artwork appears immediately.** A small version loads first and the full size one replaces it when it arrives. On a slow or patchy connection you see the cover straight away instead of an empty square, and it sharpens when the rest lands. Nothing measures your connection, so there is nothing to get wrong.
- **Fixed the slowdown that the sharper artwork caused.** Asking for full size covers made each one about twenty times larger in memory, which pushed everything else out and made lists reload constantly. Artwork no longer sits in the app's memory budget, and covers are requested in fixed size steps so rotating your phone reuses what it already downloaded.

## Landscape player

Dragging the player down in landscape made the album art vanish instantly and reappear smaller. The status bar was being brought back the moment your finger moved, which resized the whole screen mid-gesture. It now changes once, when the drag finishes.

## Smaller things

- **You can stop the mini player being swiped away.** Settings → Interface → "Swipe the mini player away". Turn it off and a downward swipe leaves it alone, so an accidental swipe cannot stop your music.
- **You can ungroup the player buttons.** Settings → Appearance → "Group the player buttons", if you would rather they were not in one bar.
- **Listening history works again.** Nothing you played was being reported to YouTube. The request the app used had started coming back empty, and because the result was optional it failed silently.
- **The volume slider goes past 100%**, up to 400%, with a marker showing where normal ends.
- **Tap the "no lyrics found" screen** to go back to the artwork, instead of hunting for the close button.
- **Plus and minus buttons removed** from the number settings, and the minimum playback duration setting now explains what it actually does.

## Install

`InterTune-0.10.2-core-release-79.apk`, signed with my own key, which is why Android treats it as a separate app from the official OuterTune. It installs alongside OuterTune rather than updating it. To move your library across, use **Settings → Backup and restore**.

There is still no in-app updater, so install this over your previous InterTune yourself.

One known gap worth mentioning: backups do not include the database's write-ahead log, so a backup taken while the app is mid-write can miss very recent changes. Close the app before taking one.
