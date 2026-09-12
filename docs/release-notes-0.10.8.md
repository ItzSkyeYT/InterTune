# InterTune 0.10.8

A small release before 0.11: three things you asked for, the Quick picks row behaving itself, and the interface work that was ready. Nothing here changes unless you use it.

## Backups

- **Automatic backups.** Under Settings > Storage > Backup and restore, choose a folder and InterTune backs up your library, playlists, settings and listening history there every day or every week, keeping the last three, five or ten. Off until you turn it on. There is a Back up now button beside it, and a line saying when the last one ran.
- **Export playlists as M3U.** Every playlist in your library as a file you can open anywhere, one tap, into a folder you choose. The same format the per-playlist export already wrote.

## Playing

- **Save the queue as a playlist.** A button on the queue sheet turns whatever you are listening to into a playlist, songs in the order shown, named by you. Radio songs that were never in your library are added to it as part of the save.
- Skip silence was already there, under Player and audio, and stays there.

## Quick picks

- The row no longer blanks on every refresh, and Home no longer shows two rows both called Quick picks.
- On the YouTube Music source the row is YouTube's own Quick picks shelf. A feed that also carries a shelf of hour-long mixes no longer puts those under the heading.
- A card you play from Quick picks stays where it is, playing, until you refresh. The row never changes while you are away in the player.
- Pull to refresh brings new songs to Quick picks, Forgotten favourites and Keep listening, not the same ones in a new order.
- Two long-standing defects in the library Quick picks query were fixed: it counted the same seed twice, and Forgotten favourites could surface songs still being played.
- Versions of one song (live, slowed, remastered, official video) are recognised and never shown side by side.

## Interface

- Predictive back and reveal transitions (switch, on).
- An Offline tab: the songs that will play with no connection.
- Remove a song from its playlist without leaving the player.
- Resume playback when the app opens, as a setting.
- Audio quality has a Highest available tier, and the settings show what is actually playing: codec, bitrate and sample rate.
- The app says so when the song cache is turned off.
- The store listing and its screen shots were redone.

## Updates

- Installs from F-Droid update through F-Droid; the in-app updater steps aside for them.
- If InterTune reaches F-Droid, its copy will be signed with F-Droid's key rather than mine, so neither copy can install over the other. Moving either way means uninstalling first, which loses your library unless you back it up. That is what the automatic backups are for.

## Not in this release, on purpose

Best recommendations, Try both, the context chips and the home screen widget are built and on the branch, and they are 0.11. The listening log that feeds them starts in this release so that they have something to learn from when they arrive; Pause listening history under Settings > Recommendations is its off switch.
