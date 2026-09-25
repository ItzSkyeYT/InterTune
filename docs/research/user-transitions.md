# Transitions you make yourself: research

Asked 25 Sep 2026: how Spotify does its DJ transitions and lets people make their own, and what the same would take in InterTune. Research only, no code changed. It builds on [dj-transitions.md](dj-transitions.md), which covers the automatic side and the levels (a) to (f); this one is about user control. Marked **[press]** where only press coverage says it, **[guess]** where it is inference.

## What Spotify's Mix does

Confirmed by Spotify [1][2][3][4]:

- **Where**: Premium, playlists you made yourself, collaborative ones included (collaborators can edit the mix). Launched in beta 19 Aug 2025 on mobile, rolled out to all eligible Premium users 22 Sep 2025, desktop from 19 May 2026. An algorithmic playlist has to be saved as your own copy first **[press]**.
- **How**: tap Mix in the playlist's toolbar. **Auto** blends every transition at once. Or open one transition, start from a preset (Fade, Rise), and adjust curves for **volume**, **EQ** (lows, mids, highs) and **effects** (low-pass and high-pass filters), placing it with the waveform and the beat data. Each track shows its **BPM** and **Camelot key**.
- **Who hears it**: other Premium listeners of the playlist hear the transitions; free users and Spotify Connect play it as a plain playlist. Turning Mix off keeps the transitions for when it goes back on. They cannot be edited offline.
- **Smart Reorder** (25 Feb 2026) reorders a playlist by BPM and key. Spotify says over 220 million hours of mixed playlists had been streamed by then.
- **In detail [press]** [5][6]: presets Auto, Fade, Rise, Blend, Wave, Melt and Slam; transition length in **bars** (2, 4 or 8), not seconds; three lanes (Volume, EQ, Filter), each with 7 to 9 presets and curves you can drag; the timing is set by dragging the two waveforms against each other.
- **Complaints [press]**: tempo matching (it stretches the tracks) cannot be switched off, which people ask for; clashing keys or tempos do not blend; you cannot see or save other people's mixes.

## How Spotify picks the points

Spotify has never published how Auto or Automix works. What is public:

- A 2017 ISMIR paper by Spotify's researchers treats playlist order as a graph walk and the crossfade as an optimisation, judged by professional curators [7].
- **US 10,803,118** (2020) and its continuation **US 11,461,389** (2022), "Transitions between media content items", mostly the paper's authors: candidate transition points on beats, the best pair picked by comparing features around them (beats and downbeats, timbre, pitch, loudness, vocals), and beat-aligned crossfades with time stretching when the tempos differ [8][9]. **US 10,101,960** (2018) chooses mix points for running so energy stays steady [10].
- The old Web API audio analysis returned exactly the data these need: beats, bars, sections, tempo, key, and where the fade in ends and the fade out starts [11]. Closed to new apps since 27 Nov 2024 [12].

**Worth knowing before copying Auto closely**: the two transition patents cover choosing points by comparing features at beat positions and stretching to align beats. Manual transitions, fixed curves and a plain crossfade are old and common; an automatic picker modelled on Spotify's method is the part to read the claims for first. Not legal advice, just the place to look.

## The DJ and Automix, briefly

- **DJ**: nothing official says how it moves between songs. A community idea titled "Enable seamless AutoMix transitions by default (not just in AI DJ)" suggests DJ uses Automix's blends **[guess]**. The voice sits at the boundaries. Since Oct 2025 it takes typed requests as well as spoken ones [13].
- **Automix**: Premium, only on some of Spotify's own playlists, on shuffle too. It can skip intros and outros, loop parts and add effects, and picks the points itself; the listener cannot change the overlap **[press]** [1][14]. It has never applied to user playlists. Mix is the answer to that.

## What it would take in InterTune

### Playing two songs at once

Media3 still has no crossfade (androidx/media #2, open since 2021) [15]. The fade branch avoids the problem: one player, no overlap, a fade out and a fade in through the single volume combine in MusicService, so it works under offload. Tried on the emulator (12 s fade), not on a phone yet.

For real overlap there are two routes:

- **Two players**, what Metrolist and SimpMusic do. It works elsewhere, but here the spatial audio processors are single service fields wired into the renderers, head tracking drives one of them, and PlayerConnection and QueueBoard assume one player. Two binaural renderers during every overlap double the DSP that already dominates the battery.
- **One player plus a "ghost tail"** (recommended by the research agent, and I agree it is the better bet): clip song A at its out point, then decode A's last seconds ahead of time into memory (about 4.6 MB for 12 s), render A's half of the style offline (fade, echo, filter sweep), and mix that buffer under B's first seconds in a new processor placed before the binaural one. One session, one queue, one spatial renderer, and gapless for every other pair. Unproven: an audio processor is not told where one song ends inside a gapless stream, so a sink wrapper has to pass that boundary on, and A and B may differ in sample rate. That needs a two-day spike before anything else.

Anything beyond a volume fade has to switch offload off (the processor chain does not run under offload, and in the 1.8.0 AAR not under float output either).

### Knowing the music

| Tool | Licence | Gives |
|---|---|---|
| aubio (C) | GPL-3.0 | onsets, tempo, beats |
| TarsosDSP (Java) | GPL-3.0 | onsets, beat tracking, stretching |
| libKeyFinder (Mixxx) | GPL-3.0 | key |
| Beat This! | MIT, ~8 MB model | beats and downbeats, the most accurate here |
| SoundTouch, Signalsmith Stretch | LGPL-2.1, MIT | tempo stretching |
| Essentia | AGPL-3.0 | the best extractors, but no Android build |

All of them can go into a GPL-3.0 app. YouTube gives nothing useful: only a whole-song loudness figure and, on the web client, the most-replayed heatmap, which could hint at a drop. Cheapest start: record waveform peaks and a loudness envelope while a song plays (Media3's TeeAudioProcessor with WaveformAudioBufferSink), store about 3 KB per song in a `song_analysis` table, and analyse only library and playlist songs, not the 46k rows in `song`.

### Styles

| Style | What it needs | Cost |
|---|---|---|
| Fade out, fade in | volume only | done on the branch |
| Crossfade | overlap, two gain ramps | overlap itself |
| Rise | high-pass swept down on B, A ducked | two filters, negligible |
| Echo out | a delay on A's last beats, then cut | rendered ahead, nothing live |
| Bass swap | B's bass held back until the midpoint | two filters, negligible |
| Beat-matched blend | beat grids, A stretched to B's tempo | a one-off burst per transition |

Overlaps are a few per cent of listening time, and every style costs well under 1% of what third-order spatial audio does. The real costs are a second spatial renderer (avoided by the ghost tail), offload off for people who turned it on, and the analysis passes (first play or while charging).

### Storing them

One row per transition, keyed by playlist and by the two songs' ids (not by playlist map rows, which sync can rebuild): style, out point on A, in point on B, length, curve, a small JSON of parameters, both songs' lengths when it was made (a stream whose length moves by more than 2 s gets flagged), and cascading deletes. A pair that stops being adjacent keeps its transition dormant and gets it back if the two meet again. On shuffle a pair applies only when the two actually play in a row; otherwise the global setting does. Transitions stay local, including on synced YouTube playlists, since YouTube has nowhere to put them, and they go into backups. The queue needs to know which playlist it came from (a `sourcePlaylistId`).

### The editor

In a local playlist, an "Edit transitions" mode puts a chip between each pair of rows. Tapping one opens a sheet with the end of A and the start of B as two waveforms, handles for the out point, in point and length, style chips, snapping to beats once there is analysis, and an 8-second preview rendered offline through its own AudioTrack with the main player paused. The editor must never reorder, since reorders sync to YouTube.

## Staged plan

0. **Try the fade branch on your phone and merge it.** About a day.
1. **Per-pair fade and trim** in local playlists: the transitions table, out and in points through Media3's clipping, per-pair fade lengths through the existing envelope, a sheet with sliders and no waveform. Works under offload, needs no analysis, and already covers "skip this long intro" and "fade that one out early". 4 to 6 days.
2. **Waveforms and suggested points**: peaks and loudness learned on play, a waveform editor, points suggested from the envelope, offline preview. 6 to 9 days.
3. **Real overlap**: the two-day spike, then the ghost tail mixer with crossfade, rise, echo and bass swap, falling back to stage 1 when it is not ready. Two to three weeks plus a bug tail; if the spike fails, two players instead.
4. **Auto transitions** for any queue from the envelope and loudness, off by default. 4 to 6 days.
5. **Beat-aware**: beat and key analysis, BPM and key on each row, snapping, a stretched blend. 3 to 5 weeks, and only if a listening test shows it beats a plain crossfade.

## Recommendation

Stages 0 and 1 are the honest 0.12 scope: user control that works today, on every song, with no analysis and no overlap. They give most of what people use Mix for (trimming dead intros and outros, fades placed by hand) without the part Spotify itself gets complaints about. Do the stage 3 spike before promising overlap to anyone, and treat stage 5 as research rather than a roadmap item.

## Sources

1. Spotify, Transitions between tracks: https://support.spotify.com/us/article/tracks-transitions/
2. Spotify newsroom, Mix (19 Aug 2025, desktop note May 2026): https://newsroom.spotify.com/2025-08-19/mix-your-favorite-playlists-seamlessly-by-adding-your-own-transitions/
3. Spotify, Mixed playlists: https://support.spotify.com/us/article/mixed-playlists/
4. Spotify newsroom, rollout (22 Sep 2025) and Smart Reorder (25 Feb 2026): https://newsroom.spotify.com/2025-09-22/mixed-playlists-debut-martin-garrix-pinkpantheress-sofi-tukker/ and https://newsroom.spotify.com/2026-02-25/smart-reorder-playlist-mixing/
5. TechRadar (21 Sep 2025): https://tech.yahoo.com/audio/articles/spotify-mix-could-ve-easily-113000411.html
6. Engadget (21 Jul 2026): https://www.engadget.com/2216279/how-to-use-spotify-mix-tool/
7. Bittner et al., Automatic Playlist Sequencing and Transitions, ISMIR 2017: https://research.atspotify.com/publications/automatic-playlist-sequencing-and-transitions
8. US 10,803,118 B2: https://patents.google.com/patent/US10803118B2/en
9. US 11,461,389 B2: https://patents.google.com/patent/US11461389B2/en
10. US 10,101,960 B2: https://patents.google.com/patent/US10101960B2/en
11. Spotify Web API, audio analysis: https://developer.spotify.com/documentation/web-api/reference/get-audio-analysis
12. Spotify for Developers, changes of 27 Nov 2024: https://developer.spotify.com/blog/2024-11-27-changes-to-the-web-api
13. Spotify newsroom, DJ requests (15 Oct 2025): https://newsroom.spotify.com/2025-10-15/dj-spanish-text-requests-update/
14. DJ.Studio on Spotify's transitions: https://dj.studio/blog/spotify-crossfade-transition
15. androidx/media #2, crossfade: https://github.com/androidx/media/issues/2
