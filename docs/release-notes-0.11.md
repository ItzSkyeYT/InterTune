# InterTune 0.11

Quick picks learns how you listen. Everything below is a switch or a choice; nothing changes unless you turn it on, except the two tidy-ups marked "on by default", which each have an off.

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

## Home screen

- A **widget**: what is playing, with previous, play or pause and next, and a list under it. Tap a row to play it. It works with the app closed, and its play button brings back the queue you were on.
- Each widget is set up on its own, when you add it and again from the launcher afterwards: what it shows (what's playing, a list, or both), which list (Quick picks, Forgotten favourites, Keep listening, Recently played), how many rows, the background (the system's, dark, light, taken from the artwork, or none), how solid it is, which buttons, the text size, whether artwork, artists and the heading are drawn, and whether the corners are rounded.
- It changes shape with its size rather than scaling: one cell is the cover with a play button, four by two is the song and two rows, taller adds rows, narrow stacks the cover over the song, and given only what's playing and enough room the cover fills it.

## Playback

- A pause, a closed app and a return later is one listen, not a stop. The app also comes back where you paused even when Android closed it in the meantime.
- Audio quality has a **Highest available** tier, and the settings show what is actually playing: codec, bitrate and sample rate.
- Spatial audio: a status row under Player and audio says whether your headphones and phone support it (the effect itself is planned for 0.12).

## Interface

- Predictive back and reveal transitions (switch, on).
- An Offline tab: the songs that will play with no connection.
- Remove a song from its playlist without leaving the player.
- Resume playback when the app opens, as a setting.
- The Quick picks row no longer blanks on every refresh, and Home no longer shows two rows both called Quick picks.
- On the YouTube Music source the row is YouTube's own Quick picks shelf. A feed that also carries a shelf of hour-long mixes no longer puts those under the heading.
- A card you play from Quick picks stays where it is, playing, until you refresh. The row never changes while you are away in the player.
- Pull to refresh brings new songs to Quick picks, Forgotten favourites and Keep listening, not the same ones in a new order. On the YouTube Music source each pull reaches past the shelf into the feed's other song shelves and into what YouTube lists as related to them.
- The app says so when the song cache is turned off.
- Screen shots and the store listing were redone.

## Updates

- Installs from F-Droid update through F-Droid; the in-app updater steps aside for them.
- **If InterTune reaches F-Droid**, its copy will be signed with F-Droid's key rather than mine, so it cannot install over this one and this one cannot install over it. Moving either way means uninstalling first, which loses your library unless you back it up. Settings > Backup and restore does that in one tap, and the restore puts everything back, including your play history.

## Under the hood

- Two long-standing defects in the library Quick picks query were fixed: it counted the same seed twice, and Forgotten favourites could surface songs still being played.
- Versions of one song (live, slowed, remastered, official video) are recognised and never shown side by side.
