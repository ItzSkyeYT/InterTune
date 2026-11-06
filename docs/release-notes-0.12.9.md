# InterTune 0.12.9

_Radios that stopped after one song in Indonesian, Finnish and Danish, and songs YouTube no longer plays skipped instead of stopping the music._

## Fixed

- **Radios and mixes in Indonesian, Finnish and Danish** keep going after the first song (#83). YouTube writes song lengths as 3.34 there instead of 3:34, and InterTune left out every song whose length it could not read. Albums in those languages had the same gap.
- **A song YouTube no longer plays is skipped**, with a short note saying which, instead of the music stopping (#82). Before giving up on a song, InterTune also asks YouTube Music's web player, which still plays some songs the other ways call gone.
- Report a problem and the crash page keep what you typed until the report is sent.
