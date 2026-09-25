# InterTune 0.11

Quick picks learns how you listen, and InterTune can name the song playing around you. Most of what follows is a switch or a choice and stays off until you turn it on. What changes for everyone: the two tidy-ups marked "on by default", which each have an off, the new seekbar, and the top bars.

## One thing to know

InterTune can count this install once a day: one short message with a random name that is replaced by an unrelated one every month, and whether you installed from F-Droid or elsewhere. It is off until you say yes, it asks once, and **Forget this install** removes it. Nothing about what you listen to is ever sent.

## Recommendations

- **Best recommendations (experimental)**, a new Quick picks source beside YouTube Music and Your library. It builds the row from how you actually listen: what you finish, search for and like, when you play it, what you come back to and what you pass over. Twenty cards from five lanes: related to what you play, songs you come back to, more from artists you finish, favourites gone quiet, and artists you have never played.
- **Try both**: a row that mixes Best recommendations with the other source, card for card, so you can compare them fairly.
- **Rank with your listening** (on by default): the YouTube Music and Your library rows ordered by what you tend to finish, search for and like. Off keeps the source's own order.
- **Tidy Home rows** (on by default): no song twice on Home, no live or remixed version beside its original, and nothing you have just heard in Quick picks.
- **Context chips** under the row: Auto, Discover, Favourites, Focus, Chill and Party. The three moods learn from what you play while they are on.
- **Why these?** on the Quick picks heading: the songs the row was built around (each can be turned down), the lanes, and the exclusions in force. A line under each card says what put it there.
- **Adventurousness** and **Familiarity** dials, **New songs only**, and an **Off** option for the row.
- Long-press a card for **Not this song**, **Less of this artist** or **Never this artist**. Exclusions touch the recommendation rows only, never search, your library, playlists or radio, and banning a song covers its other versions. **Rest songs I skip** (off by default) rests a song you skip early for a week.
- It learns from what you play and pass over, a little at a time, never more than a set amount per day. **How it's doing** under Settings > Recommendations shows cards played of cards seen per source, how often each row held what you played next, how well it predicts, and every weight beside where it started. **Learn from listening**, **Forget the last session**, **Forget today**, **Reset**, **Rebuild from history** and **Export** are all there.
- Your play history from earlier versions is converted once at first start, so recommendations begin from everything you have played.

- **Discover something new** (off by default): a second row under Quick picks, of songs you have never played at all, picked the way Best recommendations picks. A song you skipped after a few seconds counts as played. Pull to refresh for other songs.
- **Similar songs from Last.fm** (off by default): the similar songs in Best recommendations come from what Last.fm's listeners play alongside a song, instead of what YouTube lists beside it. Songs Last.fm does not know keep YouTube's list.

## Song recognition

- **What's playing?** Tap the waveform in the search bar. InterTune listens, Shazam names the song, and it is found on YouTube Music so you can play it or add it. No account needed.
- **Listen once** names one song. **Keep listening** carries on in the background, with the screen off, and lists everything it hears. Switching between the two does not stop it.
- From that list, **Create playlist** or **Add to playlist**. Every song it hears after that goes into the same playlist.
- The notification shows the song playing around you and how far into it the room is, and goes back to "Listening" as soon as the song changes.
- It knows which version is playing. A sped-up or slowed edit is measured by how fast it plays rather than guessed from a title.
- **Mashups and remixes.** In Keep listening it notices when a song is being cut up with others, or when Shazam keeps naming different versions of it, and looks for the mashup or remix on YouTube instead of adding the pieces. When two uploads are equally likely, it asks.
- On headphones your music keeps playing while it listens. Only the phone's own speaker pauses.
- A **history** of everything it has recognised.
- Your playlists have a listen button in their header, to fill one with what is playing around you.
- Settings for how long each listen lasts, whether matches are added by themselves, and whether the screen stays on.

## Home screen

- A **widget**: what is playing, with previous, play or pause and next, and a list under it. Tap a row to play it. It works with the app closed, and its play button brings back the queue you were on.
- Each widget is set up on its own, when you add it and again from the launcher afterwards: what it shows (what's playing, a list, or both), which list (Quick picks, Forgotten favourites, Keep listening, Recently played), how many rows, the background (the system's, dark, light, taken from the artwork, or none), how solid it is, which buttons, the text size, whether artwork, artists and the heading are drawn, and whether the corners are rounded.
- It changes shape with its size rather than scaling: one cell is the cover with a play button, four by two is the song and two rows, taller adds rows, narrow stacks the cover over the song, and given only what's playing and enough room the cover fills it.

## Player

- An M3E seekbar: a bar thumb with a gap either side, and a thicker track.
- A sleep timer button beside like and the menu.
- **Player buttons**: Classic, or Connected, which joins the controls into one frosted row. Classic by default.
- Swiping the artwork changes song again, including straight after the app opens.

## Playback

- A pause, a closed app and a return later is one listen, not a stop. The app also comes back where you paused even when Android closed it in the meantime.
- Audio quality has a **Highest available** tier, and the settings show what is actually playing: codec, bitrate and sample rate.

## Sound

- **Spatial audio**. Takes the music off the line between your ears and puts it on a stage in front of you. It renders the stereo mix through a pair of virtual speakers using measured ear responses, so it works on any headphones, with nothing to pair and nothing to buy.
- **Stage width**, from 15 to 90 degrees. Thirty is what stereo is mixed for. Wider pulls the instruments apart at the cost of a hole in the middle.
- **Head tracking**, on headphones that report their orientation. The music stays where it is when you turn your head, including when you look up or down, so a song sounds like it is coming from a fixed point in the room rather than from the headphones.
- **Tracking response** picks how tightly the sound follows: Smooth, Balanced, Quick or Instant. **Tracking lead** compensates for the delay between your headphones reporting a movement and the sound arriving. **Recentre** puts the stage back in front of you, and there is a one-off calibration that learns your headphones' delay while you listen.
- The status row under Player and audio says whether your headphones and phone actually support tracking, and links out if yours should but does not.
- **Quieter as you walk away**. Treats your phone as where the music is coming from, so the volume falls as you leave it behind and comes back as you return. Off by default.

## Interface

- Top bars float over the page, the way One UI 8 draws them: a round back button, the title in a pill beside it, and the screen's buttons in one pill at the other edge.
- With Liquid glass on, the search pill and the floating buttons are glass as well.
- A short walkthrough after an update, covering only what is new. A fresh install gets the whole tour. Skip is there from the first screen.
- **Favourite artists** beside Liked songs and Downloaded songs: a mix of the artists you have bookmarked, one song from each in turn, so one big catalogue cannot take it over.
- Announcements appear on the same banner as the occasional question, and there is a Discord server.
- If InterTune crashes, it offers to report it the next time it opens, with the error already filled in. Nothing is sent unless you choose to.
- Predictive back and reveal transitions (switch, on).
- **Keep the queue up to date**: drops songs further down the queue that stop suiting what you are playing, and reacts to whether you stay with the current song or skip past it. Most of the queue is hidden while it settles, so you see what is actually coming rather than a list that is about to change.
- Quick picks is balanced across categories now. One genre can no longer take over the row.
- An Offline tab: the songs that will play with no connection.
- Remove a song from its playlist without leaving the player.
- Resume playback when the app opens, as a setting.
- The Quick picks row no longer blanks on every refresh, and Home no longer shows two rows both called Quick picks.
- On the YouTube Music source the row is YouTube's own Quick picks shelf. A feed that also carries a shelf of hour-long mixes no longer puts those under the heading.
- A card you play from Quick picks stays where it is, playing, until you refresh. The row never changes while you are away in the player.
- Pull to refresh brings new songs to Quick picks, Forgotten favourites and Keep listening, not the same ones in a new order. On the YouTube Music source each pull reaches past the shelf into the feed's other song shelves and into what YouTube lists as related to them.
- The app says so when the song cache is turned off.
- Screen shots and the store listing were redone.

## Settings

- Spatial audio has its own category rather than sitting inside the audio card.
- Every "i" explanation was rewritten: shorter, plainer, and sized to fit without scrolling.
- A button to report an issue, on GitHub or Discord.
- Automatic backups run every 6 hours, day, week, month, 6 months or year, and the backup button shows progress while it works.
- The proxy fields carry working examples.
- **New songs only** is a chip on Quick picks instead of a setting buried in Recommendations.
- Recommendation engine data can be imported as well as exported.
- Back buttons on the screens that were missing one, including the Last.fm login.

## Battery

- A stopped player service stayed in memory and kept working, and each one left a network listener behind.
- Synced lyrics no longer follow the song with the screen off.
- The listening notification, the sleep timer notification, the rate limit banner and the mini player no longer wake the phone to redraw for nobody.
- The dynamic theme no longer loads each cover at full size just to pick one colour.

## Fixes

- Search could come back empty when YouTube added a section after the results.
- A song uploaded as "Artist - Track" could lose its name and keep the artist's.
- Replaying a shuffled playlist that had lost songs started the wrong track, then turned shuffle off.
- **Not this song** hid every other song with the same name, for good.
- Saving an album, then hearing a song from it, unsaved the album.
- An album with no songs kept its loading shimmer forever.
- Dragging songs with the add panel open moved the wrong rows.

## Updates

- Installs from F-Droid update through F-Droid; the in-app updater steps aside for them.
- **If InterTune reaches F-Droid**, its copy will be signed with F-Droid's key rather than mine, so it cannot install over this one and this one cannot install over it. Moving either way means uninstalling first, which loses your library unless you back it up. Settings > Backup and restore does that in one tap, and the restore puts everything back, including your play history.

## Under the hood

- Two long-standing defects in the library Quick picks query were fixed: it counted the same seed twice, and Forgotten favourites could surface songs still being played.
- Versions of one song (live, slowed, remastered, official video) are recognised and never shown side by side.
