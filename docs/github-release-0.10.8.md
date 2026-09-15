A small release before 0.11: three things you asked for, two freezes that had no business existing, and the Quick picks row finally behaving itself. Nothing here changes unless you use it.

## Your library can back itself up now

Under **Settings > Storage > Backup and restore**, choose a folder and pick every day or every week. InterTune writes your library, playlists, settings and listening history there and keeps as many as you say, on a slider from one to twenty. Off until you turn it on.

A backup that fails halfway deletes its own half-written file rather than leaving something the restore would accept, and nothing is pruned until a new one has been written successfully. If the folder goes away, the screen says so rather than retrying silently. There is a **Back up now** button beside it, and a line saying when the last one ran.

This matters more than it looks if InterTune ever reaches F-Droid: that copy would be signed with F-Droid's key rather than mine, so neither version can install over the other and moving between them means uninstalling first.

## The queue can become a playlist, and playlists can become files

A button on the queue sheet turns whatever you are listening to into a playlist, songs in the order shown, named by you. Radio songs that were never in your library are added as part of the save, so nothing goes missing.

And **Export playlists as M3U** writes every playlist in your library as a file you can open anywhere, one tap, into a folder you choose.

## Music sticking on "Wait to reconnect"

One failed request could hold a song for good. The app would show that message and then wait for the network to come back, except the network had never gone away: a single name lookup had missed on a connection that was working fine. Nothing was ever going to release it, so the music stayed dead until you force closed the app.

It now tries again on a timer and then makes a decision, rather than waiting for something that is not coming.

The player was also lying to you while that happened. It drew a **pause** icon over a stopped player, so the one tap that would have fixed it looked exactly like the tap that would have stopped your music. It shows play now, because that is what it does.

## "Let other apps play at the same time" actually applies

If you turned this on and it seemed to work sometimes and not others, that was not your phone being strange. The setting was read once when the app built its player and never again, and that player outlives pausing, backgrounding, and even swiping the app away. So flipping the switch did nothing at all until the process was killed, which happens at unpredictable times.

It now takes effect the moment you turn it on, mid song.

Two honest limits. Android mutes music for the length of a phone call whatever this is set to, and some phones stop background playback to save battery, so allow unrestricted battery use for InterTune if the music keeps stopping.

## Quick picks

The row arrives about **four seconds sooner**. It used to wait behind nine requests filling the "Similar to" rows further down the screen, which nothing about Quick picks needs; those now load alongside it instead of in front of it.

The rest of the row's bad habits are gone too:

- It no longer blanks on every refresh, and Home no longer shows two rows both called Quick picks.
- On the YouTube Music source it is YouTube's own Quick picks shelf, so a feed that also carries a shelf of hour-long mixes no longer puts those under the heading.
- A card you play stays where it is, playing, until you refresh. The row never changes while you are away in the player.
- Pull to refresh brings new songs, not the same ones in a new order.
- Two long-standing defects in the library query were fixed: it counted the same seed twice, and Forgotten favourites could surface songs still being played.
- Live, slowed, remastered and official-video versions of one song are recognised as one song and never shown side by side.

## Smaller things

- **A back button on the mini player.** It had play and next and nothing to go back with, so hearing something again meant opening the full player or reaching for the headset.
- **Skipping is lighter.** Every song change used to ask the settings file four questions, three of them on the thread that draws the screen, which is what made spamming next or previous feel heavy.
- Songs no longer arrive with a play count or an upload date where the artist should be. YouTube leaves that slot empty on some rows and the app took whatever was sitting there, which is how a library ends up with an artist called "431K plays". Names already saved that way are not repaired yet.
- Predictive back and reveal transitions, an Offline tab, removing a song from its playlist without leaving the player, resuming playback when the app opens, a Highest available audio tier that says what is actually playing, and the app admitting when the song cache is off.
- Installs from F-Droid update through F-Droid; the in-app updater steps aside for them.
- The store listing and its screen shots were redone.

## The listening log

InterTune starts keeping a record of what you play: every stop, how far through you got, how it ended and where you started it from. It is on your device, in the app's own database, and it goes nowhere else.

It is what the recommendations in 0.11 will learn from, and starting it now means they arrive to a history rather than to nothing.

Two things already read it, and they are the two Quick picks improvements above: the row skips what you have just heard, and it is ordered by what you tend to finish, search for and like rather than by the order it arrived in. Both are on, and this release has no switch for either. 0.11 adds one for each.

**Settings > Privacy and history > Pause listen history** turns the log off and **Clear listen history** deletes all of it. Both were already there and both cover the new log. With it paused, the two behaviours above have nothing to work from and the row is simply the order it arrived in. It rides along in a backup, so a backup taken now carries it.

## Not in this release, on purpose

Best recommendations, Try both, the context chips, the long-press card controls, the whole Recommendations settings screen and the home screen widget are built and on the branch, and they are 0.11. They are held behind one switch in the source rather than on a branch of their own, so 0.10.8 is this same tree with that switch off.

---

Upgrading from 0.10.7 was tested against a real 39,000 song library: the database migration runs clean, playlists and history survive, and the old play log is converted into the new one.
