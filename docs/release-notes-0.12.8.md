# InterTune 0.12.8

_Report a problem without a GitHub account, backups that keep your sign-ins out, and radios that start again for people signed out._

## Reporting a problem

- **Send a report to InterTune.** Report a problem and the crash page can send the report themselves, no GitHub account needed. Say what happened and, if you like, how to reach you (only the InterTune team sees it). You get a link to follow it. With a GitHub account you can still file it there yourself.
- **Share it as a file**: InterTune-report with the day and time, so a long report reaches Discord without Nitro.
- The crash page says when a newer version is out and offers Update, since it may already fix the crash.

## Backups

- **Backups leave your sign-ins out** unless you tick them, and you pick what goes in. Anyone with a backup file that holds your sign-ins could use those accounts.
- A backup holds the library as it stood at one moment, even while the app is writing to it.
- A restore puts the library in place in one step, so a crash during it can no longer leave you with an empty library.

## When YouTube does not load

- **Radios work again signed out.** If the sign-in page had ever been opened and left without signing in, every radio failed with an error, and with Use login for browsing on, search and Home too.
- When search, a radio, a mix or Home cannot load, the app says why: the YouTube sign-in expired, with Sign in again, or YouTube cannot be reached, with Retry and the proxy settings. The Account page says the same when it is empty.
- The player's error screen has a Retry button.
- An album YouTube stops sending stays in your library: its page shows the songs it had and says it did not load, with Retry.
- The proxy takes its address the way people type it, and says plainly when it cannot use one.

## Lyrics

- **Save lyrics cards and videos** on the phone: Save sits between Cancel and Share.
- Refetch lyrics keeps the lyrics you have until new ones arrive, and says so when none come.
- The lyrics follow the song again after you come back to the app: a song paused while you were away left them standing still until they were closed.

## Fixed

- A new message takes the place of the one on screen, instead of waiting until it has gone.
- Play next works before anything has played.
- On a full phone the app no longer closes when it cannot save a like, a play, the queue or a sync.
- After a restart the queue that was playing comes back, even when another queue was moved below it.
- Removing every song from the queue no longer closes the app.
- Downloads that start while the app is in the background no longer close it (#71).
- A like sent to YouTube, or a playlist fetched after it is saved, no longer closes the app when something in it fails.
- Looking up a song while the queue changes no longer turns shuffle off.
- Casting to a speaker that names AAC its own way plays the song instead of refusing it.
- A short swipe on a song says it plays next, instead of "inserted into queue".
- Importing an M3U file that cannot be read says so.
- Sync now says when a sync is already running, instead of "Nothing to sync".
- "Play X" from a voice assistant says when the search could not be made, instead of "No results found".
- A scan at launch that fails says only that, and linking local artists to YouTube says when it failed instead of "complete".
- Never this artist in the song menus says it only changes recommendations.
- A long message beside the three listening dots no longer pushes one of them up.

## Other

- Polish, Korean and French brought up to date by their translators. The Polish store listing spells InterTune right.
