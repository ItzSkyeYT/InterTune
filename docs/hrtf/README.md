# Head-related impulse responses

`sh_hrir_order_1.wav` is the first-order spherical-harmonic HRIR set that the binaural renderer in `BinauralAudioProcessor` uses. Four channels in ACN order (W, Y, Z, X), 256 taps, 48 kHz, SN3D normalised, measured on a Neumann KU100 dummy head by the Audio Lab at the University of York and diffuse-field corrected. One hemisphere only, because the set is symmetric about the median plane and the other ear is the same filters with ACN 1 negated.

Taken from Google's Resonance Audio, `third_party/SADIE_hrtf_database/WAV/Subject_002/SH/sh_hrir_order_1.wav`, licensed Apache-2.0. The licence is kept verbatim beside this file as `LICENSE.SADIE`. Apache-2.0 is one-way compatible with GPL-3.0, so it may be combined into this app.

Nothing here is read at build time or shipped in the APK. `convert_sadie.py` turns the wav into `app/src/main/java/com/dd3boh/outertune/playback/SadieHrir.kt`, a table of numbers, which is what the app actually compiles:

```
python3 docs/hrtf/convert_sadie.py docs/hrtf/sh_hrir_order_1.wav \
    > app/src/main/java/com/dd3boh/outertune/playback/SadieHrir.kt
```

Generated rather than loaded as an asset because F-Droid's inclusion policy refuses prebuilt binaries, and a list of integers is source in a way a wav file is not. The conversion is deterministic, so regenerating and diffing proves the committed table matches the measurements.
