# InterTune 0.11.6

_Music that comes back by itself, and fixes for 0.11.5._

**Music back on Bluetooth.** Music that stopped because your headphones, car or speaker went away can start again when Bluetooth audio connects within 30 minutes. Music you paused yourself stays paused. Turn it on under Settings > Player and audio > **Resume when Bluetooth connects**.

**Name a song from Quick Settings.** A Quick Settings tile and a home screen button open song recognition, already listening. On Android 13 and later, the card on Home for what is new adds the tile in one tap.

**No more stutter when a song comes in slowly.** Now and then YouTube sends a song slower than it plays, even on a fast network. The player kept waiting on it: a second of silence, a pause, a few seconds of music, over and over until the song was skipped. InterTune now notices when a song comes in slower than it plays and asks for it again on a fresh connection, from where it was. On a network that really is slow it leaves things as they are.

**Local media's folders come first.** Under Settings > Local media, choosing the folders to scan leads the scanner, with the folders you chose named under it.

**About has icons.** Every row under Settings > About wears an icon, like the rest of Settings, and the address for anything else, intertune.dev@gmail.com, shows under its row.

**Clearer icons in Settings.** Audio quality, Fix song volumes and the scanner's sensitivity get icons of their own. The sound bars now only ever mean song recognition.

## Fixed

- **Scan** with no folder picked, under Settings > Local media, opens the folder list instead of closing the app.
- The card for what is new stays on Home until everything on it has been looked at, and **Done** at the end of a **Show me** leaves you in the setting it showed.
