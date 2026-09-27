# InterTune 0.11

Quick picks learns how you listen, InterTune can name the song playing around you, and there is a home screen widget. Most of what follows is a switch or a choice and stays off until you turn it on. What changes for everyone: whatever is marked "on by default", each with an off, the new seekbar and the top bars.

## One thing to know

InterTune can count this install once a day: one short message with a random name that is replaced by an unrelated one every month, and whether you installed from F-Droid or elsewhere. It is off until you say yes, it asks once, and **Forget this install** removes it. Nothing about what you listen to is ever sent.

After the update, a few quick questions come up once, for what needs your say-so and was never asked on this phone: this count, and whether recommendations may use Last.fm (below).

## Recommendations

- **Best recommendations (experimental)**, a new Quick picks source beside YouTube Music and Your library, and where a new install starts. Updating keeps the source you had. It builds the row from how you actually listen: what you finish, search for and like, when you play it, what you come back to and what you pass over. Twenty cards from five lanes: related to what you play, songs you come back to, more from artists you finish, favourites gone quiet, and artists you have never played.
- **Try both**: a row that mixes Best recommendations with the other source, card for card, so you can compare them fairly.
- **Rank with your listening** and **Tidy Home rows** are switches now. Since 0.10.8 Home has ordered the YouTube Music and Your library rows by what you finish, search for and like, and kept repeats and other versions of a song out of its rows; both stay on unless you turn them off.
- **Context chips** under the row: Auto, Discover, Favourites, Focus, Chill and Party. The three moods learn from what you play while they are on.
- **Why these?** on the Quick picks heading: the songs the row was built around (each can be turned down), the lanes, and the exclusions in force. A line under each card says what put it there.
- **Adventurousness** and **Familiarity** dials, **New songs only** (also a chip on the row), and an **Off** option for the row.
- Adventurousness puts one song by an artist you have never played in the row at its lowest and three at its highest, two by default, so new names never crowd out the rest.
- Long-press a card for **Not this song**, **Less of this artist** or **Never this artist**. Exclusions touch the recommendation rows only, never search, your library, playlists or radio, and banning a song covers its other versions. **Rest songs I skip** (off by default) rests a song you skip early for a week.
- It learns from what you play and pass over, a little at a time, never more than a set amount per day. **How it's doing** under Settings > Recommendations shows cards played of cards seen per source, how often each row held what you played next, how well it predicts, and every weight beside where it started. **Learn from listening**, **Forget the last session**, **Forget today**, **Reset**, **Rebuild from history** and **Export** are all there.

- **Discover something new** (on by default): a second row under Quick picks, of songs you have never played at all, picked the way Best recommendations picks. A song you skipped after a few seconds counts as played. Pull to refresh for other songs.
- **Similar songs from Last.fm and YouTube**: InterTune asks once whether Best recommendations may use Last.fm as well as YouTube. Say yes and it takes similar songs from both, what Last.fm's listeners play alongside a song and what YouTube lists beside it, and leans toward whichever you actually play, never more than four in five either way so it can swing back. Or pick one in Settings. With Last.fm, InterTune sends it the title and artist of each song you play, and once, of the songs you played in the last month and the ones you liked, and nothing else. Until you answer, nothing is sent.
- Quick picks never has more than two songs by one artist.

## Song recognition

- **What's playing?** Tap the waveform in the search bar. InterTune listens, Shazam names the song, and it is found on YouTube Music so you can play it or add it. No account needed.
- **Listen once** names one song, and gives up after about half a minute if it hears nothing it knows. **Keep listening** carries on in the background, with the screen off, and lists everything it hears. Switching between the two does not stop it.
- From that list, **Create playlist** or **Add to playlist**. Every song it hears after that goes into the same playlist.
- The notification shows the song playing around you and how far into it the room is, and goes back to "Listening" as soon as the song changes.
- When Shazam stops answering (no network, or too many requests), Keep listening waits longer between tries, says so, and carries on by itself once Shazam answers again.
- It knows which version is playing. A sped-up or slowed edit is measured by how fast it plays rather than guessed from a title.
- **Mashups and remixes.** In Keep listening it notices when a song is being cut up with others, or when Shazam keeps naming different versions of it, and looks for the mashup or remix on YouTube instead of adding the pieces. When two uploads are equally likely, it asks.
- On headphones your music keeps playing while it listens. Only the phone's own speaker pauses, whether you listen from the search bar or from a playlist.
- A **history** of everything it has recognised.
- Your playlists have a listen button in their header, to fill one with what is playing around you. If What's playing? is already listening, it says so and offers to switch, and closing it leaves that run going. The What's playing? screen does the same when a playlist is listening.
- Settings for how long each listen lasts, whether matches are added by themselves, and whether the screen stays on.

## Home screen

- A **widget**: what is playing, with previous, play or pause and next, and a list under it. Tap a row to play it. It works with the app closed, and its play button brings back the queue you were on.
- It takes the shape of its size rather than scaling: one cell is the cover with play over it, a single row is one slim line, four by two is the song with rows under it, square lets the cover fill it with the song over it, and wide and tall puts a big cover beside the song. Nothing is cut off at any size or text size.
- Each widget is set up on its own, when you add it and later from the launcher: what it shows (what's playing, a list, or both), which list (Quick picks, Forgotten favourites, Keep listening, Recently played), how many rows, the background (the system's, dark, light, taken from the artwork, or none), how solid it is, which buttons, the text size, whether artwork, artists and the heading are drawn, and whether the corners are rounded. The settings show the widget itself, changing as you choose.
- **Play liked songs** on the app icon: long-press it, or pin the shortcut to your home screen.

## Stats

- The Stats page now says something about your listening, above the most played lists: how much you listened and how that compares with the period before, the hours you listen at, and findings such as the song you played most in one day and how many of those plays were back to back, a song that came back after weeks away, the artist you always hear to the end, your longest session and nights past midnight. Each only shows when your listening backs it up. Nothing leaves the phone.

## Player

- An M3E seekbar: a bar thumb with a gap either side, and a thicker track.
- A sleep timer button beside like and the menu.
- **Player buttons**: Classic, or Connected, which joins the controls into one frosted row. Classic by default.
- Swiping the artwork changes song again, including straight after the app opens.
- In landscape the controls always have room for all five transport buttons, and nothing is cut off at the ends of the Connected row.
- In a small pop-up window the controls shrink together to fit, rather than the last ones being squashed.

## Playback

- **Fade between tracks** (off by default): each song fades out over its last few seconds and the next fades in, 2 to 12 seconds. The songs do not overlap, so it is not a crossfade, and songs from the same album play straight through.
- **Use my account to play**: when YouTube refuses to play a song without an account, playback can ask again as you, if you are signed in. Never, Only when a song is refused (the default), or Always.
- Raise the audio quality and a song cached at the lower one is fetched again the next time it starts. If that fails, the cached copy plays.
- **Keep the queue up to date** (on by default, for the songs InterTune added itself): drops songs further down the queue that stop suiting what you are playing, and reacts to whether you stay with the current song or skip past it. Most of the queue is hidden while it settles, so you see what is actually coming rather than a list that is about to change.

## Sound

- **Spatial audio**. Takes the music off the line between your ears and puts it on a stage in front of you. It renders the stereo mix through a pair of virtual speakers using measured ear responses, so it works on any headphones, with nothing to pair and nothing to buy.
- **Stage width**, from 15 to 90 degrees. Thirty is what stereo is mixed for. Wider pulls the instruments apart at the cost of a hole in the middle.
- **Head tracking**, on headphones that report their orientation. The music stays where it is when you turn your head, including when you look up or down, so a song sounds like it is coming from a fixed point in the room rather than from the headphones.
- **Tracking response** picks how tightly the sound follows: Smooth, Balanced, Quick or Instant. **Tracking lead** compensates for the delay between your headphones reporting a movement and the sound arriving. **Recentre** puts the stage back in front of you, and there is a one-off calibration that learns your headphones' delay while you listen.
- The status row under Player and audio says whether your headphones and phone actually support tracking, and links out if yours should but does not.
- **Quieter as you walk away** (Android 12 and later). Treats your phone as where the music is coming from, so the volume falls as you leave it behind and comes back as you return. It asks for the Nearby devices permission, to measure how far away your headphones are. Off by default.

## Interface

- Top bars float over the page, the way One UI 8 draws them: a round back button, the title in a pill beside it, and the screen's buttons in one pill at the other edge.
- With Liquid glass on, the search pill and the floating buttons are glass as well.
- The glass intensity slider shows a live sample as you drag it, and setup's back and forward buttons follow it too.
- **Signing in to YouTube Music** starts with your phone's own account picker, and Google's page opens with that address filled in, so it is usually a tap on your phone to confirm. The page shows Google's address with a lock, InterTune never sees your password, and Switch or Another account is right there.
- A short tour of the app, once after this update and on a fresh install. Skip is there from the first screen.
- **Favourite artists** beside Liked songs and Downloaded songs: a mix of the artists you have bookmarked, one song from each in turn, so one big catalogue cannot take it over.
- Questions and news are fetched fresh after an update rather than taken from the copy the old version saved, and the check-now row and "Let me be asked again" cover news as well as questions.
- If InterTune crashes, it offers to report it the next time it opens, with the error already filled in. Nothing is sent unless you choose to.
- Home opens on what it last showed rather than fetching a new row every time.

## Settings

- Spatial audio has its own category rather than sitting inside the audio card.
- Every "i" explanation was rewritten: shorter, plainer, and sized to fit without scrolling.
- **Backup and restore** is its own entry in Settings again, rather than the bottom of Storage and downloads.
- The proxy fields carry working examples.
- Recommendation engine data can be imported as well as exported.
- Back buttons on the screens that were missing one, including the Last.fm login.

## Battery

- A stopped player service stayed in memory and kept working, and each one left a network listener behind.
- Synced lyrics no longer follow the song with the screen off.
- The listening notification, the sleep timer notification, the rate limit banner and the mini player no longer wake the phone to redraw for nobody.
- The dynamic theme no longer loads each cover at full size just to pick one colour.

## Fixes

- Search could come back empty when YouTube added a section after the results.
- Songs listed under an artist's top search result showed their length where the artist should be, and podcast episodes showed their date.
- A song uploaded as "Artist - Track" could lose its name and keep the artist's.
- Replaying a shuffled playlist that had lost songs started the wrong track, then turned shuffle off.
- Saving an album, then hearing a song from it, unsaved the album.
- An album with no songs kept its loading shimmer forever.
- Playing one of your most played songs from Stats started the first one in the list instead.
- Tapping a search result inside a YouTube playlist or a folder played a different song, and in a folder sometimes nothing. Remote history always played the first song of the day.
- Searching the queue, then tapping or swiping a result, could play or remove a different song.
- **Select all** during a search selected the whole list, so Remove like or Remove from playlist reached songs the search was hiding.
- **Like all** and **Remove all likes** took the songs out of your library and made downloaded ones look not downloaded.
- **Play** and **Shuffle** in a YouTube playlist's menu did nothing unless the playlist itself was open.
- Long-pressing an album, artist or playlist in search results opened an empty menu.
- **Shuffle** on a local artist played three songs.
- In a shuffled queue, moving or removing a song and then adding one to the queue jumped to a different song.
- Lyrics without timings started every line after the first with a comma.
- Removing a song from a playlist while searching it could send a different song to the bottom.
- A drag in a playlist synced with YouTube Music could move a different song on YouTube.
- Tapping a song in an artist's song list could play a different one, and with no connection it waited before playing anything.
- Deleting a saved queue listed below the one playing made the queue above it current, so the song you were on was saved into the wrong queue. Saved queues can be dragged into a new order again.
- A search of the queue kept showing the old queue after Start radio from a result.
- Selecting songs while searching the queue drew the selection bar over the search field and its back arrow.
- With the sleep timer set to the end of the song, skipping to another song paused it straight away. The timer now moves to the song you picked.
- Leaving the Backup screen while a backup was being written said it had failed, though the file was complete.
- The Search shortcut on the launcher icon opened Home. It opens search now.
- Turning the phone during setup could open a second copy of it, whose last page had no way forward.
- A full rescan of your local files took every local song out of Liked and reset its date added. A scan folder that could not be read, such as an SD card that was not mounted, hid the whole local library.
- **Clear all downloads** deleted the audio but left every song marked as downloaded.
- Failed and queued downloads were listed under Downloaded.
- Downloads cut off by closing the app did not carry on at the next launch with automatic scanning off.
- A song whose download was removed from a download folder could not play again until the app restarted, and Remove download did nothing for those songs from most menus.
- Moving downloads to a folder left them unplayable offline until a later scan, and a full storage during the move could lose songs.
- Downloading a YouTube playlist from its menu was forgotten after a restart.
- Offline, a local file that had been moved or deleted waited for the network forever instead of being skipped.
- **View artist** on an album by Various Artists closed the app.
- A saved album could show and play only the one or two songs you had heard from it. Songs heard from an album you had not saved could land in a saved album with the same name by someone else.
- Liked songs sorted by play count listed every song the app had ever seen.
- Searching for a word such as "watch" or "playlist" did nothing, and searching the library missed synced playlists and saved albums.
- **Add to playlist** from a YouTube playlist's menu added nothing, a local artist's albums stopped at six, and the album menu queued an album out of order.
- **Read only** sync still changed your YouTube Music account, down to deleting playlists. It now changes nothing there.
- A sync that failed or came back partial, on a bad connection for example, could remove songs, albums, artists and playlists from your library with **Overwrite with remote** on.
- Signing in for the first time, or with another account, could take away likes you made while signed out. A like is now only taken back when YouTube had it and lost it.
- Creating a synced playlist with no connection closed the app.
- Last.fm scrobbles named every artist of a song as one, and songs started from search were never scrobbled. Nothing is sent as now playing while listening history is paused, and a Last.fm connection revoked on the website now shows as disconnected.
- **Sync now** often did nothing and said the sync was complete.
- Opening the app and leaving without pressing play reset the saved place in the song to the start, so the next resume, from headphones or the notification, began at 0:00.
- Pressing play on headphones with the app closed and nothing to resume closed the app about ten seconds later.
- In Android Auto, a list you started was not treated as the current queue: shuffle swapped back to the phone's old queue, radio songs could land in it, and the app could close. "Hey Google, play" a song or artist emptied the player; it now plays what you asked for from your library, and a request it cannot match leaves playback alone. A search for a name with a slash in it, such as AC/DC, played the wrong song.
- A song started by resuming could play briefly at full volume before normalisation and spatial audio took over.
- Dragging songs with the add panel open moved the wrong rows.
- The app could crash while saving the queue if the queue changed at the same moment, for example when starting a new playlist.
- **Download all liked songs** stayed stuck one short when a download failed. It now finishes, says how many failed, and stopping it keeps the songs that had already arrived.
- Deleting a synced playlist that someone else made came back with the next sync. It is now unsaved on YouTube as well. **Delete** in an online playlist's menu did nothing at all; it now shows only for a playlist in your library, and removes it.
- Saving a YouTube playlist kept only about its first hundred songs, and sync never refreshed saved playlists.
- **Remove download** on a playlist you cannot edit, such as a read-only synced one, emptied it.
- A download folder inside a folder InterTune scans is now refused, and so is a scan folder holding the download folder. Each download was also being scanned in as a local song, and tidying those up could delete the YouTube song with its likes and history.
- Start radio on a long album did nothing but show an error. Karaoke lyrics could close the app, and so could coming back to it while selecting songs in a YouTube playlist, or opening the Cached tab past 999 songs on Android 11 and older.
- A partly cached song could be finished from a different stream of it.
- One background task failing could stop the volume, normalisation and sleep timer from working until the app restarted.
- While signed out, playing a song no longer tells YouTube about it, and a like reaches YouTube once instead of twice.
- Adding to a playlist could put songs in the wrong place, lose them from YouTube if the dialog closed too soon, and Skip or Add anyway after the duplicates question sent nothing to YouTube.
- A failed search or artist page offers Retry. Folders with #, ?, % or ; in their name open. The open search bar no longer slides under the status bar. Tapping a Library tab again goes back to the top.
- In Android Auto, search results are songs to play rather than folders.
- After a resume, the shuffle button showed the wrong state for a shuffled queue.
- While a song played, the app told the whole system its position every three seconds, for nobody.
