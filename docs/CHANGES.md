# What this fork changes, and why

Everything InterTune does that upstream `v0.10.1` does not, with the reasoning. The playback
fix has its own page: [Why OuterTune 0.10.1 stops playing after ~30 seconds](403.md).

0.10.1's portrait now-playing screen is the reason this fork is based on 0.10.1 at all, and it
is left alone. Liquid glass is the one thing below that restyles portrait chrome, and it is off
until you turn it on.

## Landscape and tablets

Separate from the playback fix, the two-pane landscape now-playing screen got some work. The
comparison shot on the [front page](../README.md) is the same track on the same device, taken
back to back: upstream `v0.10.1` built from source, then this fork. The system bars are gone,
the artwork is larger and sits against the outer edge instead of floating in the middle of its
half, the type and transport controls are bigger, and the queue arrow sits at the bottom edge
rather than in the gap beside the controls.

**The queue arrow no longer sits on top of the transport controls**, which is upstream
[#1133](https://github.com/OuterTune/OuterTune/issues/1133), reported there as "the player
buttons are covered by an invisible element". [`QueueSheet`][qs] pins its expand arrow to the
*top* of the collapsed sheet, and that sheet is `QueuePeekHeight` taller than the peek it
actually needs. In portrait, spending 96dp on that costs nothing. In landscape the whole player
is 384dp tall, so the extra 48dp reached up over the transport row. The sheet is transparent
but still takes the touches, which is why the buttons look uncovered and do nothing. Landscape
now collapses to exactly the peek.

Credit where it is due: upstream's maintainer guessed within two hours of the report that the
one-off `0.10.2-b1` build had already fixed it, and he was right. That build sets
`collapsedBound = dismissedBound` in its landscape player. It was never verified against
0.10.1, and it only exists in that one release, so this is that fix carried back. The issue was
closed in August 2026 once the cause was written up; upstream's `lite` branch has since dropped
the collapsed queue peek in landscape altogether.

**Fullscreen and keep-awake while the player is open.** The system bars hide, an edge swipe
brings them back transiently, and they are restored when the player collapses or the device
rotates. The screen is held awake while playing, and lyrics keep it awake independently, as
before. Both are gated on the player being *expanded*, so the mini player does not take over
the screen, and on playback being active, so a player left paused in landscape does not hold
the screen on all night.

**Layout, sized for arm's length rather than in-hand.** Gutter 32dp to 24dp, title 22sp to
25sp, artist 16sp to 19sp, artwork hugging the outer edge at ~86% of screen height, like and
more aligned to the artwork's top edge, transport icons 32dp to 42dp and the play button 72dp
to 84dp.

**Tablets get the queue beside the player** rather than under it, with the artwork centred and
enlarged, an album-coloured gradient background on big screens, and only the drag handle
reserved beneath the controls instead of a whole queue peek. Details are in the wiki:
[Tablets and large screens](https://github.com/ItzSkyeYT/InterTune/wiki/Tablets-and-large-screens).

Two defects in the first cut of this are worth recording, because neither was visible in a
screenshot and both are easy to reintroduce:

- `View.keepScreenOn` is a single boolean on a single View. `Thumbnail` owned it for lyrics and
  the new immersive effect owned it for landscape, so whichever disposed last silently cleared
  the other's request, breaking lyrics keep-awake **in portrait**. One owner now ORs both
  conditions.
- `WindowInsets.systemBars` shrinks when the bars hide, and that value reaches `collapsedBound`,
  which is a `remember()` key for the sheet state. Toggling the bars rebuilt the state mid-drag,
  cancelled the gesture and re-animated the sheet to its previous anchor, so the player sprang
  back open instead of collapsing. Both sheet bounds now read `systemBarsIgnoringVisibility`.

[qs]: ../app/src/main/java/com/dd3boh/outertune/ui/player/Queue.kt

## Artwork

Two separate resolution bugs, both of which the larger landscape artwork made obvious.

**Remote covers were fetched at the wrong size.** `getThumbnailModel(sizeX, sizeY)` took a size
and used it only for local files; for YouTube artwork it returned the raw url and ignored both
arguments. The player was drawing a 120px thumbnail and upscaling from there. The size now goes
into the url, and `Thumbnail` passes the size its own `BoxWithConstraints` measured, rounded up
to a fixed step so that two layouts measuring slightly differently still share one cache entry.

**Local covers all shared one image cache entry.** `ItemThumbnail` defaults its size to `-1`
when a caller does not pass one, and the Coil cache key is built from that size, so every size
of a given file shared the key `path;-1;-1`. Coil rejects an entry that does not match the size
being asked for rather than stretching it, so what this cost was decoding the same file again
for every size on screen, not a blurry picture. The size now falls back to the size the
thumbnail is actually drawn at.

This is **not** upstream [#798](https://github.com/OuterTune/OuterTune/issues/798), tempting as
the title is. That report is against 0.9.3.1, where grid and list thumbnails went through a
hard-coded 100x100 nearest-neighbour downscale while the album page and now playing screen did
not, which is exactly the described symptom. Upstream deleted that path before 0.10.1. Nothing
here reads a higher-resolution source for a local file than 0.10.1 already did.

Full-size covers then cost far more memory than the sampled ones they replaced and evicted
everything else, so artwork is now allocated outside the app's Java heap.

## Volume and loudness

Upstream's audio normalisation switch never worked on 0.10.1, because YouTube had renamed the
loudness field the code reads. Fixing that exposed the rest.

Levelling can only turn songs **down**, never up, so a song with no loudness figure is the
loudest possible outcome. Everything else had been pulled down by 6 to 10 dB, so an untouched
song stood out by as much as 16 dB, roughly three times as loud. Two things caused the gaps:
loudness has to be read from the client that actually served the audio rather than from the
metadata client, which now sends none; and downloading a song *erased* a figure the app had
already fetched, so one bulk download of liked songs was enough to break 305 songs at once.

Unknown loudness now assumes a loud song and attenuates accordingly, which drops the worst case
from about +16 dB to under +3 dB. This is a deliberately asymmetric trade: most songs whose
figure is genuinely unknown end up a few dB quiet, and being wrong quietly is fixable with the
volume rocker while being wrong loudly is a jump scare.

Local files are the exception and are left at full volume. They have no YouTube loudness and
never will, and the presumed-loud fallback would have attenuated every one of them by 13 dB
permanently with no way to undo it from inside the app. Levelling them properly needs ReplayGain
or R128 tags read at scan time, which does not exist yet.

**Settings → Player and audio → Fix song volumes** backfills the rest, asking YouTube for the
real figure and reporting progress on the row itself. It looks up only the songs that are
genuinely broken rather than the ones merely never played: on a real 32,473 song library that
is 355 lookups instead of nearly 29,000, and all 355 repaired in 2 minutes 46 seconds. Gain is
split by what survives audio offload, rounded rather than truncated, and applied through a gain
`AudioProcessor` that also lets the volume slider go past 100%.

The user-facing version of all of this is in the wiki:
[Volume and loudness](https://github.com/ItzSkyeYT/InterTune/wiki/Volume-and-loudness).

## Liquid glass

Optional. **Settings → Appearance → Liquid glass**, off by default.

The bottom bar becomes a floating frosted dock and the mini player a separate slab above it,
both refracting the album grid that scrolls underneath. The player's transport row gets the same
treatment, and the now playing background drops its flat grey wash so the artwork colours come
through. One intensity slider drives all of it: higher is more see-through and more strongly
refracted, lower is closer to plain solid surfaces.

The refraction needs Android 13 or newer, because it uses a runtime shader. The artwork-colour
half does not, so the switch still does something on older versions and the description says
which half you get.

Built on [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)
(`io.github.kyant0:backdrop`, Apache-2.0) rather than hand-rolled shaders. Refraction is
displacement by distance to a rounded-rect edge along that rect's outward normal, so the
backdrop bends at the rim and stays clean in the middle. Two hand-written shaders were tried
first and deleted: a beat-triggered ripple, and an edge-driven chromatic split that just traced
an orange outline around every bright shape in the artwork. Chromatic aberration is a garnish on
glass, not the substance of it.

Pinned to backdrop **1.0.6**, not 2.0.0. 2.0.0 declares `minCompileSdk=37`, and raising
`compileSdk` to 37 trips a Kotlin backend crash const-evaluating literal `.toULong()` calls in
`Lyrics.kt`.

Two things worth recording, because neither is visible in a screenshot:

- The nav bar and mini player read one shared backdrop published over the app content. Putting
  the player sheet *inside* that layer, which is the obvious way to capture its background,
  makes the layer contain one of its own readers: `RenderNode::prepareTreeImpl` recurses until
  the native stack overflows and the process dies on launch, with no Java exception and only a
  tombstone hundreds of frames deep in `libhwui`.
- Panel tints go in `.background(color, shape)` after `drawBackdrop`, never in its
  `onDrawSurface` callback. That callback runs on the node's own unclipped canvas, so a tint
  painted there is a square patch on a rounded panel.

Deliberately not glassed: song rows, grids, artwork and the lyrics body, because glass belongs
on floating chrome and not on content; and the tablet nav rail, because nothing scrolls behind
it, so it would be a full offscreen pass refracting a flat fill.

The glass background option is separate and older, and was
[asked for](https://github.com/OuterTune/OuterTune/issues/1282#issuecomment-5383885534) in the
comments of a 403 report rather than in an issue of its own. It reproduces the one-off
`pre_rel-0.10.1-b1-glass` build by dropping the flat overlay wash behind the player, halving the
gradient and putting the blurred artwork at half alpha, interpolated so 0% is stock 0.10.1
exactly and 100% is that build exactly.

## Smaller fixes

Eight of them. Each verified on a device rather than assumed.

**1. Bulk remove from playlist**, upstream
[#1172](https://github.com/OuterTune/OuterTune/issues/1172). Selection mode in a playlist had no
way to remove the selected songs; you opened each song's own menu one at a time. Removal reuses
the existing move-to-end-then-delete pattern, processing rows in descending position order
inside one transaction so each removal only renumbers rows already handled. Synced playlists
also fire the remote removal.

**2. m3u import**, upstream [#679](https://github.com/OuterTune/OuterTune/issues/679). Two bugs,
both reported by [cchery2512](https://github.com/cchery2512). The preview lists keyed rows by
`song.hashCode()`, so an m3u listing the same title twice crashed on duplicate keys. And YouTube
ids were never parsed at all: the code read the id with `substringBefore(',')`, which returns the
whole url for a YTM entry and is therefore never empty, so the branch that parsed the url was
unreachable and every entry fell through to fuzzy title matching. Ids from `watch?v=` and
`youtu.be/` now resolve, `&list=` and all.

**3. Wi-Fi only downloads.** A setting under Storage, off by default. Uses media3's download
requirements rather than refusing the request, so a download asked for on mobile data is queued
and starts on its own once an unmetered network appears. Unmetered rather than a literal Wi-Fi
check, so an unmetered ethernet or hotspot counts and a metered Wi-Fi network correctly does not.

**4. Sleep timer fade-out.** The timer used to cut playback dead. The volume now eases down over
a configurable window, on both the fixed-duration and end-of-song modes, and the fade is
released afterwards so the next play is not silent.

**5. Album pages showed "1 song"**, and albums whose track rows carry their videoId only in the
overlay play button opened empty. That second one matters more than it sounds: one malformed row
used to throw inside the enclosing `runCatching` and fail the entire album load, not just that
row.

**6. Playlists with no header.** Plain `PL` playlists shared as a `youtube.com/playlist?list=`
link threw an NPE, so the screen stayed blank forever and syncing them silently produced nothing.

**7. Search returned one section.** The response models only covered two shelf types, so every
`itemSectionRenderer` section was dropped. An unfiltered search now returns the top result plus
"Listen again", "Albums" and the rest, instead of a single card.

**8. A crash in album and playlist reads.** Both are two-pass `@Relation` queries that were not
transactional, so a sync committing between the passes could leave a parent whose key was missing
from the relation map, throwing `NoSuchElementException`.

Several of these were rewritten against 0.10.1 from
[AsterTune](https://github.com/yuuichi-s/AsterTune) rather than cherry-picked, where the bases
had diverged.

## Updates and onboarding

InterTune is on no store, so it checks GitHub for a newer release and tells you about one. It
asks whether you want that on the last page of first-time setup, and asks once in a dialogue if
you are coming from an older build or a restored backup. It is off until you answer, it never
installs anything itself, and it checks at most once every six hours.
See [Updates](https://github.com/ItzSkyeYT/InterTune/wiki/Updates) in the wiki.

## Play on the first tap

Reopening the app after it had been left alone, the play button showed a replay icon and took up to
three taps to produce sound. The first tap was what created the replay icon.

`QueueBoard` computed its current index as a property initialiser, which Kotlin runs before the
`init` block that fills the list the index points into. `MusicService` threads the previous board's
list instance back in, and on a cold start that list is empty, so the index came out as -1 while
nineteen queues then loaded behind it. `getCurrentQueue()` caught the resulting out-of-bounds,
repaired the index, and still returned null, so the first caller got nothing even though the queues
were there.

From there the sequence was mechanical. Tap one loaded an empty media list and called `prepare()` on
it, and `prepare()` on an empty timeline lands in `STATE_ENDED`, which is the replay icon. Tap two
loaded the real queue but `togglePlayPause` flipped `playWhenReady` to false, because its `prepare()`
guard required the player to be both paused and idle. Tap three finally played.

The index is now computed at the end of `init`, `getCurrentQueue()` returns the queue it just
repaired, and `togglePlayPause` mirrors media3's own `handlePlayButtonAction`: prepare when idle,
rewind when ended, then play. That last part is why the notification's play button always worked on
the first tap while the one inside the app did not.

## Downloading liked songs

**Settings > Storage > Automatically download your liked songs**, with Off, On and Wi-Fi only, plus
a **Download all liked songs now** button that catches up everything already liked. The button
reports progress on its own row and stops when tapped again, the same shape as the loudness repair.

The hook is on the like itself rather than a database observer. An observer reads better on paper,
but the download scanner clears every download timestamp on nearly every launch, so it would
periodically see the whole liked library as missing, and a queued song never leaves the query it
would watch. Upstream shipped this once and removed it because their version triggered from
composition, so merely opening a song menu queued a download.

Un-liking never deletes anything: one mis-tap on the heart in the media notification would otherwise
destroy offline music with no undo. A download removed by hand stays removed, and only the catch-up
button brings it back, which its description says.

Wi-Fi only gates whether anything is queued. It deliberately never writes media3's requirements,
which are service-wide and already owned by the **Download on Wi-Fi only** switch; two writers would
fight and would quietly make manual downloads Wi-Fi only too.

## When YouTube refuses the connection

YouTube rate limits by public IP. Everything from that connection then answers
`Sign in to confirm you're not a bot`, and the app used to show that over a Java stack trace and
carry on asking, which is the one behaviour that keeps a block alive.

Probing established what it is and is not. Four InnerTube clients, `VISIONOS`, `IOS`, `ANDROID_VR`
and `TVHTML5`, returned identical answers on the same videos, so it is not client specific. One of
six videos played across music, non-music, old and new, and the one that worked is the most
requested video on the platform and is almost certainly edge cached, so it is not content specific.
Rotating `visitorData` changed nothing. Moving to mobile data fixed it instantly.

There is no signal to detect this at the network layer: a blocked response is HTTP 200 with headers
byte-identical to a healthy one, and the whole of `playabilityStatus` is a status and an English
sentence. So detection matches that sentence, with two fallbacks that survive a rewording: HTTP 429,
and eight consecutive failures.

When it trips, work nobody asked for stands down rather than retrying, and the player says what has
happened, that it is temporary, that it is not the user's fault, and that mobile data usually works.
Downloads are also paced at 700ms per request, derived from the loudness scan's 350ms rather than
invented, which is invisible next to an audio transfer on a healthy connection and is the whole
difference on a refused one. Copying a full error report is a labelled button now instead of an
undocumented tap on the stack trace.

## Upstream issues

Most people who find this fork arrive from OuterTune's tracker, so here is what the work above
does and does not answer there. Upstream stopped active development in February 2026 and closed
a great deal as not planned, so an issue being open or closed says nothing about whether it
still bites.

### Fixed

- [#1284](https://github.com/OuterTune/OuterTune/issues/1284) and
  [#1282](https://github.com/OuterTune/OuterTune/issues/1282), `Source error (2004): Response
  code: 403` on 0.10.1. Downloads resolve through the same code path, so "cannot download any
  new song" is the same fault and the same fix. A reporter on #1282 installed it and confirmed.
- [#1133](https://github.com/OuterTune/OuterTune/issues/1133), landscape transport buttons
  covered by an invisible element.
- [#1247](https://github.com/OuterTune/OuterTune/issues/1247), blurry now playing artwork.
  Remote covers; artwork embedded in a local file is unchanged.
- [#1172](https://github.com/OuterTune/OuterTune/issues/1172), no way to remove several songs
  from a playlist at once.
- [#679](https://github.com/OuterTune/OuterTune/issues/679), m3u import matching the wrong
  track. Closed upstream as fixed, but that fix read the id with `substringBefore(',')`, which
  returns the whole url for a YTM entry and so is never empty, leaving the branch that parses
  the url unreachable. Still true on upstream's current branches.
- [#1168](https://github.com/OuterTune/OuterTune/issues/1168), tapping a bottom-bar tab from a
  sub-page taking two taps.
- [#1190](https://github.com/OuterTune/OuterTune/issues/1190), searching the word "null"
  crashing the app. `androidx.navigation` reserves that literal path segment as its null marker.
- [#1171](https://github.com/OuterTune/OuterTune/issues/1171), the heart in a song's menu not
  changing when tapped. The sheet held a frozen copy of the row, so every tap also re-wrote
  "liked" and un-liking from there was impossible.
- [#1046](https://github.com/OuterTune/OuterTune/issues/1046) and
  [#139](https://github.com/OuterTune/OuterTune/issues/139), the update check and automatic
  download of liked songs. Both shipped upstream and were deleted again before 0.10.1, leaving
  dead preference keys behind. Both are back, and both stay off until you turn them on.

### Partly

- [#735](https://github.com/OuterTune/OuterTune/issues/735), the playback megathread. It lists
  five failures and this fixes one, the `403`. Logged-in playback, "not a bot", songs stuck at
  0:00 and network timeouts are unchanged.
- [#116](https://github.com/OuterTune/OuterTune/issues/116), audio normalisation. The switch
  genuinely does nothing at all on 0.10.1, and it does something now. But levelling still only
  attenuates, so the rest of that thread, and
  [#101](https://github.com/OuterTune/OuterTune/issues/101) and
  [#76](https://github.com/OuterTune/OuterTune/issues/76) with it, is asking for quiet songs to
  be brought *up*, and that is neither solved nor planned.
- [#1062](https://github.com/OuterTune/OuterTune/issues/1062), auto-download. Liked songs, yes.
  Adding a song to a playlist, no.
- [#502](https://github.com/OuterTune/OuterTune/issues/502), a built-in update checker. It
  checks, and it shows the answer under **Settings → Updates**. Nothing prompts you outside
  that screen, and it never installs anything itself.
- [#1251](https://github.com/OuterTune/OuterTune/issues/1251), sleep timer with fade-out.
  0.10.1 already had the timer, in the player's overflow menu; the fade is what was missing.
  No preset buttons and no countdown in the notification.
- [#742](https://github.com/OuterTune/OuterTune/issues/742), keeping the screen on during
  playback. It falls out of the landscape player rather than being a setting: only while the
  full player is open, only in landscape, only while something is playing.
- [#205](https://github.com/OuterTune/OuterTune/issues/205) and
  [#419](https://github.com/OuterTune/OuterTune/issues/419), covers arriving at 120px. The now
  playing screen is fixed, and songs saved from now on are stored at 544px. Notification and
  lock screen artwork, album tiles, the remote browse and search grids, and rows already in
  your library, are not.
- [#959](https://github.com/OuterTune/OuterTune/issues/959), tapping a nav tab doing nothing.
  Upstream fixed the bottom bar before 0.10.1, with the `navigateUp()` that then caused #1168.
  The navigation rail, which tablets and landscape use, was left out; that half is fixed here.

### Explained, not fixed

Streams are resolved signed out here, exactly as they are in upstream 0.10.1.

- [#972](https://github.com/OuterTune/OuterTune/issues/972), age-restricted tracks and anything
  only your own account can play. Your login is never used to fetch a stream, so these still
  fail. See [what it does not fix](403.md#what-it-does-not-fix).
- [#1103](https://github.com/OuterTune/OuterTune/issues/1103), "Sign in to confirm you're not a
  bot". This is YouTube rate-limiting your public IP, not a client, account or song problem,
  and it cannot be fixed from inside the app. InterTune explains it in plain English on the
  player and stops background downloads and syncs hammering a connection that is already
  refusing them, but playback keeps failing until the block lifts on its own.
- [#830](https://github.com/OuterTune/OuterTune/issues/830), downloads at about 50 kbps. Audio
  quality on **Auto** deliberately takes the smallest stream whenever Android reports the
  connection as metered, and bakes that choice into the file. Set it to **High** and download
  the song again.

### Not fixed

Worth saying plainly, because these symptoms get searched for.
[#1145](https://github.com/OuterTune/OuterTune/issues/1145), the dead band at the bottom of the
portrait player on very small screens, because portrait is deliberately left alone.
[#753](https://github.com/OuterTune/OuterTune/issues/753), crash on rotate, and
[#1127](https://github.com/OuterTune/OuterTune/issues/1127), crash when the Bluetooth radio is
toggled: nothing here goes near either path.
[#1231](https://github.com/OuterTune/OuterTune/issues/1231), queue reordering scrambling local
songs; the reindexing is untouched, only the drag handles that had stopped appearing were fixed.
[#1153](https://github.com/OuterTune/OuterTune/issues/1153), local cover art in Android Auto.
[#770](https://github.com/OuterTune/OuterTune/issues/770), refetching a song at a higher quality.
And [#798](https://github.com/OuterTune/OuterTune/issues/798), pixelated local covers in the
album grid, for the reasons under [Artwork](#artwork).
