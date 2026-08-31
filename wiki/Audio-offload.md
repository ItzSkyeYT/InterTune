<!-- wiki title: Audio offload -->
# Audio offload

Audio offload hands the job of decoding and playing your music to a small dedicated audio chip in the phone, instead of the main processor doing it. That chip is built for this one job, so it can use less battery. The catch is that everything InterTune normally does to the sound on its way out gets skipped, and plenty of phones handle offload badly.

Offload is off by default. Leave it off unless you have a reason to want it.

## Where the setting is

Go to Settings > Experimental, find the "Debug" heading, and turn on "Enable developer settings". A block of developer options appears below it. The first two are "Enable offload" and "Gapless offload".

Both are only read when the player starts up, so flipping one does nothing straight away. Close InterTune and swipe it out of your recent apps, then open it again. If that seems to make no difference, force stop InterTune from Android's app info screen, which definitely restarts the player.

Offload needs Android 10 or newer to work at all. Opus, which is the format InterTune prefers when streaming from YouTube Music, needs Android 11 or newer.

## What you lose by turning it on

InterTune adjusts the sound in a short chain of steps that run after a song has been decoded. Offload takes the decoding out of the app, so that chain is never built and none of it runs. In practice:

- **Volume above 100%.** The volume bar in the player menu can be dragged past the mark for normal volume, up to four times the normal level. Everything past the mark is the app making the audio itself louder. With offload on, the bar still moves but the sound stops getting louder at the mark. There is nowhere else for that boost to happen, because Android's own volume control cannot go above 100%.
- **"Skip silence"** (Settings > Player and audio > Skip silence) has no effect while offload is on.
- **Speed and pitch**, which live behind "Advanced" in the player menu. With offload on, InterTune asks the audio chip to make the change instead of doing it itself. Some chips can, some cannot. If yours cannot, the controls move and nothing happens.
- **Equalizers and other sound effects.** Offload and sound effects do not get along. The system equalizer (the "Equalizer" button in the player menu) and add-ons like Dirac may stop applying, or the phone may quietly refuse to offload at all. If an effect you rely on goes silent, offload is the first thing to suspect.

## What still works

"Audio normalization" (Settings > Player and audio > Audio normalization) behaves the same with offload on or off. Turning offload on will not make your quiet songs blare again.

The reason is that normalisation only ever turns loud songs down, never up, and turning down is done by the player's own volume control rather than by the chain that offload skips. The same goes for the volume bar up to the normal mark, and for the sleep timer's fade out (Settings > Player and audio > Fade out before stopping).

## Gapless offload

The second switch only becomes tappable once "Enable offload" is on. It also needs Android 13 or newer, and it needs the phone to report that it can play one track straight into the next while offloading.

Some phones do report that. Google's Pixel 9 series is the usual example. In practice it rarely works. Playback tends to break after one song, with the app stuck buffering or going silent when the next track has the same sample rate. If offload is misbehaving for you, turn "Gapless offload" off first and see whether that fixes it.

## Known problems

Whether offload works depends on your Android version, your phone's maker, and the audio chip inside it. If your phone does not support offload, or does not support the format the song is in, InterTune just plays it the normal way and the switch does nothing at all. If your phone half supports it, you get bugs.

The common ones, none of which happen on every phone:

- Playback carries on after you force close the app.
- Playback stops partway through a song for no reason.
- Resuming a song plays it from the start instead of from where you left off.
- On some MediaTek chips, sound stops after roughly 5 to 15 seconds.

These are limits of the phone, not things InterTune can patch around. If offload misbehaves, turn it back off.

## Reporting a problem

Offload bugs are almost always specific to one phone, so a report without details is not much use. If you open an issue at [InterTune's issue tracker](https://github.com/ItzSkyeYT/InterTune/issues), please say which phone and Android version you are on, whether "Gapless offload" was on, and whether the same song plays fine with offload switched off.
