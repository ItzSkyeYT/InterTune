# DJ-style transitions: research

For transitions people make themselves (Spotify's Mix, and what the same would take here), see [user-transitions.md](user-transitions.md).

Asked 25 Sep 2026: find out what Spotify's transitions and DJ actually do, whether InterTune can do any of it, and what each part would cost. Research only, no code changed. It starts from TODO section 9 and does not re-argue the three routes that section rules out.

## What Spotify does

Confirmed by Spotify:

- **Crossfade** (Premium): one track fades out while the next fades in over it, length on a slider [1]. Spotify gives no range; third-party guides say up to 12 seconds.
- **Automix** (Premium): beat-matched transitions, only on selected Spotify playlists, including on shuffle [1].
- **Mix** (19 Aug 2025, desktop from May 2026): users add transitions between tracks in their own playlists, with presets such as Fade and Rise, an Auto mode, curves for volume, EQ and effects, and a waveform with beat data for moving the transition point. Each track shows its BPM and key [2]. **Smart Reorder** (25 Feb 2026) reorders a mixed playlist by BPM and key [3].
- **DJ** (22 Feb 2023): editors write the commentary with OpenAI's generative tools, and one synthetic voice, built with Sonantic and modelled on Xavier Jernigan, reads it between songs [4][5].

Not published: how Automix or Auto picks the transition point, and whether it stretches tempo or shifts key. Explainers that list its inputs cite nothing. Two public hints: Spotify's old Web API audio analysis returned per track beats, bars, sections, tempo, key, `end_of_fade_in` and `start_of_fade_out` [6], so Spotify has had this kind of server-side analysis for years (closed to new apps since 27 Nov 2024 [7]). And a Spotify patent covers downbeat alignment, vocal activity detection and time stretching [8], but for mashups, not Automix. The safe reading: analysis precomputed on Spotify's servers, beat-matched overlaps, curated playlists only. Anything more is a guess.

## What others do

- **Apple Music AutoMix** (iOS 26): Apple says it mixes one song into the next with time stretching and beat matching [9]. It sits beside Crossfade under Song Transitions [10]. That is level (e) below.
- **YouTube Music**: no crossfade or transitions; people are still asking on Google's forum [11].
- **djay** finds intro and outro sections and adjusts tempo [12]; **Mixxx** lines up intro and outro markers, its analyser placing the intro start on the first sound [13]; **Poweramp** has a plain crossfade of set length on its own native engine [14].
- **media3**: crossfade has been an open request since Nov 2021 with no plans [15]. In 2018 the ExoPlayer team described the single-player hack (two audio renderers, a mixing processor, merged and clipped sources) and advised against it for its messy timeline [16], which agrees with section 9. **Auxio** refuses crossfade for the same reason [17].
- **Metrolist** (GPL-3.0, a sibling YouTube Music client) crossfades with a second ExoPlayer: at the trigger it builds it, copies the whole queue into it, hands it to the session with `MediaSession.setPlayer`, and ramps both volumes over 20 steps. Offload is forced off, and the fade is skipped when the next song has the same album title [18]. A long run of bugs followed: queue reset under shuffle, next song skipped, autoplay, Last.fm and the sleep timer broken, next song doubled, and stalls because the second player was built at the trigger instant [19]. The stall fix prepares it about 12 s early and falls back to a normal transition if it is not ready.
- **SimpMusic** (GPL-3.0) has a 3,122-line `CrossfadeExoPlayerAdapter`: its own playlist, one ExoPlayer per track, up to two precached, a forwarding player for the session, equal-power curves, and a "DJ" mode that sweeps a low-pass filter on the outgoing track and a high-pass on the incoming one [20]. Its "AutoMix" takes BPM and key from Tidal metadata, sets the overlap to a whole number of beats, and ramps the outgoing player's speed and pitch through `PlaybackParameters` in 2% steps, because finer steps make Sonic reset and click. It matches tempo but not beat phase.

## What InterTune already has

- `SleepTimer.kt`: a `fadeFactor` updated every 40 ms and multiplied into `player.volume` by the one volume combine in `MusicService` (around line 645). Everything at or below unity goes through `player.volume`, which offload cannot skip.
- `FormatEntity.loudnessDb` for nearly every song. `normalizeFactor` pulls anything louder than about -17 LUFS down to it and never boosts, so normalised songs already sit at similar integrated loudness.
- The chain in `createRenderersFactory`: `GainAudioProcessor`, `BinauralAudioProcessor`, `StereoUpmixAudioProcessor`, silence skipping, `SonicAudioProcessor`, in 16-bit (float output is off, since media3 1.8.0 skips the whole chain on float output; found 25 Sep). All skipped under offload; spatial modes already force offload off. `HeadTracking` drives a single `binauralProcessor`.
- Streams: `ResolvingDataSource` gets the URL from `YTPlayerUtils.playerResponseForPlayback` and reads 512 KB ranges (`CHUNK_LENGTH`) through the download and player caches. The player cache defaults to off (`MaxSongCacheSizeKey` 0).
- No custom `LoadControl`: media3 buffers 50 s ahead and starts loading the next item once the current one is fully loaded (`MediaPeriodQueue.shouldLoadNextMediaPeriod`).
- `fingerprint/`: a pure JVM float `Fft` (internal); its comment puts 1,500 FFTs of 2,048 at a few tens of ms. The Shazam peak picking is for hashing, not onsets, so what carries over is the FFT and the host test pattern (ffmpeg decode, as in `ReplayProbe`).
- Nothing decodes files on the device yet (no `MediaExtractor` or `MediaCodec`); the recognition TODO needs the same utility. Native modules already build (`taglib`, `ffMetadataEx`, NDK 29).
- Listens are classified from `onMediaItemTransition` reasons and a `PlaybackStatsListener` on the one player (AUTO is ENDED, SEEK is SKIPPED), and the recommendation engine learns from that split (`Signals.kt`).

## Feasibility on a phone

**Tempo and beats.** Spectral flux on 16 kHz mono with a 1,024 FFT and a 256 hop is about 62 frames a second, so 30 s is under 2,000 FFTs: tens of ms. Tempo by autocorrelation and beat phase by dynamic programming add almost nothing, and `MediaCodec` decodes far faster than real time. Speed is not the problem; accuracy is. The best 2019 tempo estimators get about 74% of songs within 4% of the right tempo, and about 93% only when half and double tempo count as right [21]. Plain DSP does worse, and downbeats, which phrase boundaries need, are harder again. The better tool there is a neural model: Beat This! has MIT code and weights and a small model of about 8 MB [22], run through ONNX Runtime or LiteRT. madmom's models are CC BY-NC-SA and cannot ship [23]. DSP libraries [24]: aubio and TarsosDSP (GPL-3.0), Rubber Band and qm-dsp (GPL-2.0-or-later), SoundTouch (LGPL-2.1), Signalsmith Stretch (MIT) and Bungee (MPL-2.0) are all usable; Essentia is AGPL-3.0 and not worth its terms.

**Where the analysis comes from.** What matters is in the future: the outgoing song's last 30 s and the incoming song's first 30 s. Decoding ahead is a second read of the stream: free with the player cache on, about 0.5 MB per song with it off, and more requests from an IP YouTube already rate-limits. Or learn on first play: a metering processor records each song's loudness and onset envelope as it plays, a small summary goes into the database, and transitions use it from the second play on. No data, almost no CPU, and it suits a replayed library. Under offload, and for unheard songs, it falls back to a plain crossfade.

**Memory and battery.** A second ExoPlayer is a few threads, a software decoder, a 250 ms AudioTrack buffer and about 1 MB of compressed audio, and decoding doubles only during the overlap. The real extra is spatial audio: each player needs its own binaural instance (about 120 million multiplies a second each at third order). The 22 Sep battery audit found DSP negligible next to residency, so this is not a blocker.

**YouTube streams.** Nothing stops us starting the next song early; a second player would load it exactly as ExoPlayer already does for gapless. The risk is latency: resolving a URL can take up to 20 s under the po token bound, so the second player must be prepared 20 to 30 s before the overlap, with a fallback to an ordinary gapless change. Opus (48 kHz) and AAC (44.1 kHz) can mix freely, since Android resamples each AudioTrack.

**Offload.** Level (a) works under offload because it only uses `player.volume`. From (b) up, offload should be forced off while the setting is on, as Metrolist does: offload skips the processor chain, and two offloaded tracks at once cannot be relied on. It is already off by default.

## Corrections to sections 9 and 10

- Section 9 says overlap needs two ExoPlayers behind a hand-written facade. Two players, yes, but Metrolist shows a second shape: swapping the session's player with `MediaSession.setPlayer` (present in media3-session 1.8.0). Here the swap is not cheap either: `PlayerConnection` captures `service.player` once as a `val`, 74 references in 21 files reach the player that way, and `QueueBoard` calls `player.player.setMediaItems` directly.
- The rest of section 9 holds against the source in `media/`. A caution for whoever checks next: that checkout reports 1.8.0 but is not identical to the 1.8.0 AAR the app builds against (Maven, not `media/`). `DefaultAudioSink.configure` differs on the float path, so anything load-bearing should be confirmed with `javap` on the AAR in the Gradle cache. What was checked there: the drain in `DefaultAudioSink.handleBuffer`, `RendererHolder.start()`, `CompositionPlayer`'s restriction and commands.
- An addition: the fade in (a) must be another factor in the existing volume combine, like `sleepTimer.fadeFactor`, not a second writer of `player.volume`.
- Section 10 says nothing is fetched ahead. For an automatic advance that is wrong: the next song starts loading once the current one is fully buffered, about 50 s before its end. It holds for skips, jumps and the first song, which is where `setPreloadConfiguration` would help.

## Levels

### (a) Fade between tracks, no overlap

What: fade out over the last N seconds, fade the next song in over its first N.

How: a small `TransitionFade` beside `SleepTimer` feeding a factor into the volume combine in `MusicService`, a switch and slider in `PlayerFrag.kt`, no fade when the next item has the same album. Section 9's notes apply (equal power, clamp to half the duration, guard `TIME_UNSET`, quantise to about 1/256).

Risks: small. Songs with silent tails fade silence. It is not what people mean by crossfade, so name it honestly.

Cost: 1 to 2 days with device testing.

### (b) True crossfade with overlap

What: the next song starts under the end of the current one; two players, equal-power ramps.

How: keep the main ExoPlayer and its playlist so gapless albums stay native, and add a second ExoPlayer from the same `createRenderersFactory` with its own processors, prepared 20 to 30 s ahead with only the next item. At the overlap, ramp both volumes, then either give the session the second player or hide both behind a `SimpleBasePlayer` or `ForwardingPlayer` facade. Also needed: keep the two from fighting over audio focus (build the second with `handleAudioFocus` off), share the audio session id for the system equaliser, feed `HeadTracking` to both binaural instances, move `SleepTimer`, the `Scrobbler` and listen accounting so the outgoing song still counts as ENDED, force offload off, and fall back to gapless when the second player is not ready.

Risks: this is the part of the app that must never break, and Metrolist's bug list is the forecast. A wrong ENDED or SKIPPED would quietly train the recommendation engine on false skips.

Cost: 10 to 15 days to a first release behind a default-off setting, then a tail of fixes over several releases.

### (c) Loudness-matched crossfade

What: both songs at the same perceived level during the overlap, and the overlap starting where the outgoing song actually winds down.

How: the first half is nearly free once (b) exists: each player gets its own song's `normalizeFactor`. The second half uses the loudness envelope (metering processor or decode ahead) to find each song's intro end and outro start, like Spotify's old `end_of_fade_in` and `start_of_fade_out`, and times the overlap to them, so a song ending in 20 s of silence or applause is left early. A filter sweep (low-pass out, high-pass in, as SimpMusic does) fits here too and makes a plain crossfade sound more like a mix.

Risks: `loudnessDb` is a whole-song figure and intros and outros are often much quieter, which is why the envelope matters.

Cost: half a day on top of (b) for per-player normalisation; 4 to 6 days for the envelope, storage and decode utility; 1 to 2 days for the filter sweep.

### (d) Beat-aligned blend, no stretching

What: start the incoming song so its first downbeat lands on a downbeat of the outgoing one, on a phrase boundary (8 or 16 bars before the outro ends), with no tempo change.

How: a pure Kotlin analysis module beside `fingerprint/` (onset strength, tempo, beat phase, downbeat guess, confidence), tested on the host against the backup library the way `ReplayProbe` is; results stored per song; a planner that aligns only when both analyses are confident and otherwise falls back to (c).

Risks: drift. Without stretching, two tempos 1% apart drift 80 ms over 16 beats at 120 BPM, an audible flam. It only works for nearly equal tempos (or half and double) or overlaps of a bar or two, which is rare outside tempo-uniform playlists, and a wrong tempo or downbeat sounds worse than a plain crossfade. Most of what it adds over (c) is the phrase-aligned start, not locked beats.

Cost: 8 to 12 days with DSP analysis, about 5 more with a neural downbeat model.

### (e) Tempo-matched mix (what Automix does)

What: stretch one song's tempo to the other's during the blend, pitch kept, beats locked, then back to normal speed.

How: stretch the outgoing song, as SimpMusic does: its clock stops mattering once it has gone, and the song that stays keeps an exact position for lyrics and seeking. Three tools: `setPlaybackParameters` (media3 knows the speed, but each change drains the chain, hence SimpMusic's 2% staircase); media3's `SpeedChangingAudioProcessor` (no drain, but the reported position stops matching what is heard); or a native stretcher such as Signalsmith Stretch or Rubber Band, the best quality and the most work. Sonic was written for speech, so it needs a listening test on music. Key shifting is not worth it.

Risks: everything in (d), plus artefacts people notice more than a fade, and tuning that can only be judged by listening to hundreds of pairs.

Cost: 10 to 15 days on top of (d). From nothing to something like Automix is 6 to 8 weeks, and only as good as the analysis.

### (f) A DJ voice between songs

What: a short spoken line over the start of the next song, music ducked.

How: Android's `TextToSpeech` with the system voice [25], ducking through the volume combine, lines from templates (title, artist, why it was picked). Written commentary needs a language model, which is section 11's open decision.

Risks: system voices sound like satnav, not radio; Spotify's rests on editors and a cloned presenter. Easy to make cheesy.

Cost: 2 to 3 days for template lines, 1 to 2 weeks with a model, plus section 11's decisions.

## Recommendation

Build (a) first: a day or two, safe under offload, and an honest answer to #1249 and #871. If people still want overlap after using it, do (b) with (c)'s per-player normalisation and filter sweep in the same change, prepared early, falling back to gapless, off by default, and budget for the bug tail Metrolist had. Do not build (e) or (f): they cost weeks, rest on analysis that gets the tempo wrong for about a quarter of songs, and Spotify's versions depend on server-side analysis and a cloned voice we do not have. Revisit (d) only after (b) has been stable for a release, and only if a replay test shows its choices beat a plain crossfade.

## Sources

1. Spotify, Transitions between tracks: https://support.spotify.com/us/article/tracks-transitions/
2. Spotify newsroom, Mix (19 Aug 2025): https://newsroom.spotify.com/2025-08-19/mix-your-favorite-playlists-seamlessly-by-adding-your-own-transitions/
3. Spotify newsroom, Smart Reorder (25 Feb 2026): https://newsroom.spotify.com/2026-02-25/smart-reorder-playlist-mixing/
4. Music Business Worldwide, DJ launch: https://www.musicbusinessworldwide.com/spotify-just-launched-a-personalized-dj-powered-by-generative-and-voice-ai/
5. Wikipedia, DJ X: https://en.wikipedia.org/wiki/DJ_X
6. Spotify Web API, audio analysis (deprecated): https://developer.spotify.com/documentation/web-api/reference/get-audio-analysis
7. Spotify developer blog (27 Nov 2024): https://developer.spotify.com/blog/2024-11-27-changes-to-the-web-api
8. Music Business Worldwide, mashup patent: https://www.musicbusinessworldwide.com/spotify-holds-a-patent-for-tech-that-can-generate-song-mashups/
9. Apple newsroom (June 2025): https://www.apple.com/newsroom/2025/06/apple-services-deliver-powerful-features-and-intelligent-updates-to-users-this-fall/
10. MacRumors, AutoMix: https://www.macrumors.com/how-to/ios-enable-automix-feature-apple-music/
11. YouTube Music community: https://support.google.com/youtubemusic/thread/411657175/feature-request-adding-crossfade-support-for-youtube-music?hl=en
12. Algoriddim, Automix: https://help.algoriddim.com/user-manual/djay-pro-windows/mixing-basics/automix
13. Mixxx 2.3, intro and outro cues: https://mixxx.org/news/2020-07-09-intro-outro-sections/
14. Poweramp crossfade settings: https://caninfotech.com/poweramp-music-player/poweramp-music-player-how-to-crossfade-between-two-tracks/
15. androidx/media #2: https://github.com/androidx/media/issues/2
16. google/ExoPlayer #3438: https://github.com/google/ExoPlayer/issues/3438
17. Auxio wiki: https://github.com/OxygenCobalt/Auxio/wiki/Why-Are-These-Features-Missing%3F
18. Metrolist `MusicService.kt` (`scheduleCrossfade`, `startCrossfade`, `performCrossfadeSwap`): https://github.com/MetrolistGroup/Metrolist/blob/main/app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt
19. Metrolist issues 3535, 3916, 3883, 3915, 2749, 3159 (https://github.com/MetrolistGroup/Metrolist/issues/3535 and so on) and PR https://github.com/MetrolistGroup/Metrolist/pull/4389
20. SimpMusic `CrossfadeExoPlayerAdapter.kt`: https://github.com/maxrave-dev/core/blob/multiplatform/media/media3/src/main/java/com/maxrave/media3/exoplayer/CrossfadeExoPlayerAdapter.kt
21. Foroughmand and Peeters, Deep-Rhythm, ISMIR 2019, tables 2 and 3, Combined row: https://archives.ismir.net/ismir2019/paper/000077.pdf
22. Beat This!: https://github.com/CPJKU/beat_this
23. madmom: https://github.com/CPJKU/madmom
24. https://github.com/aubio/aubio, https://github.com/JorenSix/TarsosDSP, https://github.com/breakfastquay/rubberband, https://github.com/Signalsmith-Audio/signalsmith-stretch, https://github.com/bungee-audio-stretch/bungee, https://github.com/MTG/essentia
25. Android `TextToSpeech`: https://developer.android.com/reference/android/speech/tts/TextToSpeech
