# InterTune 0.10.9

Fixes. Nothing new to learn, nothing moved, and the bigger work is waiting for 0.11.

## Liked songs

- **Likes stopped coming back unliked.** Syncing removed likes it had merely failed to fetch. If YouTube returned an empty or partial list of liked songs, InterTune took that at face value and unliked everything missing from it, quietly, on a normal sync. It now ignores an empty response entirely, and a song only loses its like after being absent for three days running, so one bad reply cannot undo anything.
- **Downloading your liked songs counts and cancels correctly.** The progress figure counted songs that had only started downloading, so it ran ahead of what was finished, and cancelling could take songs that were not part of the batch.

## Playing

- **A song that fails no longer strands the queue.** Pressing next after a playback error played nothing; it now moves on to the following song.
- **Age-gated songs say so.** Instead of playing a thirty second wrapper or failing without explanation, the app says the song needs an account that is allowed it.
- **Notification buttons draw on Samsung phones.** The like, shuffle and repeat icons were missing on One UI because of how they were named.
- **A request that never answers now fails** rather than holding the player open indefinitely.
- Error reports say which stream client was in use, which is the first thing needed to work out why a song would not play.

## Quick picks

- **Previews no longer appear.** A thirty second preview is never what anybody wanted, and it costs the slot twice: once when it plays, again when you go and find the real one.
- **One artist can no longer fill the row.** Quick picks holds at most two songs from the same artist, so a run of one sort of music does not take it over. Other rows are unaffected, since an album or a discography is meant to be one artist.
- **Radio no longer starts from songs you have not played.** It could build a station around a track that had only ever been shown to you.

## Interface

- **The refresh spinner stops when the row you pulled for is ready**, instead of turning for several more seconds while rows further down the screen fill in.
- **The keyboard stays up while you are still typing.** Searching hid it roughly a third of a second after you stopped, because new suggestions shifted the list and that was mistaken for you scrolling it.
- **The navigation bar shrinks when the window is too short for it**, so it stays usable in a pop-up or split-screen window.
- The button that explains a setting is now large enough to hit, and leaving a screen is a deliberate action rather than something a stray gesture does.

## Backups

- **More intervals**: every 6 hours, day, week, month, 6 months or year, instead of only daily or weekly.
- **Backing up says it is working.** It used to freeze the app for several seconds with no sign of anything happening, which is exactly when people press the button again. It now runs in the background with a spinner, and will not start a second backup on top of the first.

## Audio quality

- **Raising the quality setting re-fetches songs you already have.** A song cached at a lower setting stayed at that quality forever, because the cache was checked before the setting was. Downloads are left alone, since downloading a song is a request for it to work offline.

## Building

- The app builds without `local.properties`, produces an unsigned APK when no signing key is present, and no longer carries one machine's JDK path in a file everybody clones. None of this changes the app; it makes the F-Droid build possible.

---

0.11 is not a fixes release.
