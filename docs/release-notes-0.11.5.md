# InterTune 0.11.5

_Lighter on the battery, steadier on the music._

**Much less battery.** A song's card on Home read the database on every frame, even with the screen off: a whole processor core on a Pixel 5, now a quarter of one. The playing bars no longer redraw the screen under the open player.

**Songs that had gone play again.** YouTube sometimes gives a song a new id, and the old one answers "This video is not available". InterTune now finds the same recording and plays it. When it is not sure, it plays nothing and lists it under **Exclusions** as "No longer on YouTube".

**More ways to reach a song.** When YouTube refuses the usual source, InterTune tries two other ways, then YouTube Music's web player.

**Import from another app.** Bring your playlists from Spotify, Apple Music, Deezer, Tidal, Amazon Music, SoundCloud, Qobuz, or any CSV file. Pick the app and follow its three steps. Each song is looked up on YouTube Music, the unsure ones wait for you to check, and the playlists are made on this phone. It is under Settings > Backup and restore, and on setup's first page.

**A note if you sign in.** There is a small chance that YouTube restricts or closes an account used with InterTune. Setup and the Account page now say so, and whoever is already signed in is told once. The app works without an account.

## Also new

- **Setup in three pages** where it was six: what the app does, signing in or not, and a few questions, one at a time. Access to the phone's music is asked for only where it is needed.
- **A layout for a phone on its side.** Lists run two abreast and an album or playlist has its header beside its songs.
- **Pictures in the explanations.** Some "i" buttons show a small moving picture.
- **Android Auto**, under Look and feel: why InterTune is missing from your car, and how to bring it in.
- **The sign-in page** says who is signed in.
- **Report a problem**, in About: the last fault the app noted, to copy or send with a report.
- **What is new** after an update: a card on Home opens a page with a Show me for each.

## A test that should not have shipped

**Bluetooth signal log**, a test row under Quieter as you walk away, was in 0.11 and 0.11.1 by mistake. When tapped, it wrote the names of Bluetooth devices nearby to a file on the phone. Nothing was sent anywhere. The row is gone, and InterTune deletes those files when it starts.

## Fixed

- **Turning the phone no longer resets what you were doing.** An open dialog or the page you were on stays.
- **A song no longer ends by itself half a minute in.** About one song in three hundred did, always the same ones, with no error.
- A song whose saved copy was damaged plays on instead of stopping at the same place.
- With Keep the queue up to date on, going back no longer hides the songs already played, and reopening the app brings back the whole queue.
- Dialogs keep their buttons on screen on a phone on its side with large text.
- Turning local media back on brings back the Folders tab.
- A crash on some phones while the notification showed how much of the song was loaded.
- **Restore** and Song recognition's **Clear** ask before they wipe anything, and **Log out** also clears the sign-in page's cookies.
- A song YouTube no longer has, or does not serve where you are, says so in plain words, not an error code.
- The queue stays where you scrolled it, and a menu stays open when the song changes.
- In Arabic, Hebrew and Persian the playback buttons and the seekbar run left to right.

## Smaller things

- Complete in German, Spanish, Brazilian Portuguese, Indonesian, Hindi, Italian and Russian.
- Labels that were cut off get smaller to fit.
- Grey and black and white covers give the player grey controls.
- With Liquid glass on, the queue's bar is one floating glass pane, like the dock.
- The sleep timer is a sheet, which a drag closes.
- More buttons are named for screen readers.
