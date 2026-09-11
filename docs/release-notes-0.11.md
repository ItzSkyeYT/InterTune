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
- The app says so when the song cache is turned off.
- Screen shots and the store listing were redone.

## Updates

- Installs from F-Droid update through F-Droid; the in-app updater steps aside for them.

## Under the hood

- Two long-standing defects in the library Quick picks query were fixed: it counted the same seed twice, and Forgotten favourites could surface songs still being played.
- Versions of one song (live, slowed, remastered, official video) are recognised and never shown side by side.
