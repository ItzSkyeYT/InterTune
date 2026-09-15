Backups are the big one this time. Two freezes are gone as well, and Quick picks is four seconds faster and much better behaved.

## Backups

Pick a folder, pick daily or weekly, pick how many to keep. Library, playlists, settings and listening history, on a schedule, without thinking about it again.

It is careful with them. A backup that fails halfway deletes its own half-written file rather than leaving something the restore would accept, and nothing old is pruned until a new one has actually landed. If the folder goes missing it says so instead of retrying quietly.

Off until you turn it on.

## The queue as a playlist, playlists as files

One button on the queue sheet turns whatever you are listening to into a playlist, in order, named by you. Radio songs that were never in your library get added too, so nothing is lost.

One tap exports every playlist you own as M3U files into a folder you choose.

## The freeze on "Wait to reconnect"

A single failed request could stop playback for good. The app showed that message and waited for the network to come back, except the network had never gone: one name lookup had missed on a connection that was working fine, and nothing was ever going to release it. Force closing was the only way out.

The player made it worse by drawing a pause icon over a dead player, so the one button that would have fixed it looked like the button that would stop the music.

It retries now, then decides, and the button shows what it actually does.

## Let other apps play at the same time

If this worked sometimes and not others, it was not your phone. The setting was read once, when the app built its player, and never again, and that player survives pausing, backgrounding and swiping the app away. So the switch did nothing until the process happened to be killed.

It applies the moment you turn it on, mid song.

Android still mutes music for the length of a call whatever this is set to, and some phones stop background playback to save battery, so allow unrestricted battery use if the music keeps stopping.

## A back button on the mini player

It had play and next and no way to go back. Now it has one.

## Quick picks

Four seconds faster: it had been waiting behind nine requests fetching rows further down the screen that it does not need.

The rest is behaviour. No more blanking on every refresh, no more two rows with the same name, no more hour-long mixes under the Quick picks heading, no more a card disappearing while you are in the player, no more refreshing into the same twenty songs reordered, and no more one song four times because it is live, slowed, remastered and an official video.

## Smaller things

- Skipping is lighter. Every song change used to read the settings file four times, three of them on the thread that draws the screen.
- Artists are no longer sometimes called "431K plays" or "Aug 25, 2016". YouTube leaves that slot empty on some rows and the app took whatever was there. Names already saved that way are not repaired yet.
- Predictive back, an Offline tab, removing a song from a playlist without leaving the player, resuming playback on open, a Highest available audio tier that reports what is actually playing, and the app saying so when the song cache is off.
- F-Droid installs update through F-Droid.

## The listening log

InterTune now keeps a record of what you play: every stop, how far you got, how it ended, where you started it. It stays on your device, in the app's own database, and goes nowhere else.

Two things read it today, both Quick picks improvements above: the row skips what you just heard, and it is ordered by what you tend to finish rather than the order it arrived in.

**Settings > Privacy and history > Pause listen history** turns it off, **Clear listen history** deletes all of it, and it travels with your backups.

It is also groundwork. Something rather bigger has been waiting on this, and it wanted a history to start from rather than a blank page. Give it a few weeks of your listening and you will see why. 👀

---

Upgrading from 0.10.7 was tested against a real 39,000 song library: the migration runs clean, playlists and history survive, and the old play log is folded into the new one.
