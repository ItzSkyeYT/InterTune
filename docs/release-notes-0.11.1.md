# InterTune 0.11.1

Fixes for 0.11. One thing changes for everyone: History lists a song after 2 seconds of listening, where it took 5.

## Playback

- **Music could stop, and the app would then not open.** At the change from one song to the next, counting the play that had just ended could lock the app's database. Every song after it stayed on loading, and the app would not open again until it was force stopped. This is fixed. A database that is slow to answer also no longer keeps a song from starting, and closing the app no longer waits for it without end.
- **Play after a failed connection.** A song that had failed while the connection was bad failed again at every Play, also once the connection was back, and only skipping to another song brought the music back. Play now asks YouTube for the song again.
- **One song that would not play could hold up the whole app.** On a phone set to English, a single song YouTube would not serve, one blocked in your country for instance, made Home say "YouTube has paused this connection" and held recommendations, downloads and sync for five minutes. The app was reading its first source's refusal, which that source gives for every song, as YouTube refusing the connection. A song that fails is now only a song that fails.
- **A pause straight after a tap.** A tapped song starts at once and its radio joins it a second or two later. A song paused in that time started playing again by itself when the radio arrived. A pause now stands.
- **A tap on the song the app came back to.** After the app had been closed, it opens on the song it stopped at, and marks it as the current one in every list. A tap on it there did nothing, and only the play button started it. The tap plays it now. In the same state the heart in the player did nothing either, nor did previous and next in the queue. They work.
- **Two songs tapped one after the other.** A tapped song plays at once and its radio joins it a moment later. When the first song's radio arrived after the second tap, the second song was cut off for the first, or the first played in its place. The song tapped last now keeps playing.
- A song tapped after one whose radio could not be fetched, with no connection for instance, started where the song before it had been left and not at 0:00. It starts from the beginning, in a queue of its own.
- The first song tapped after the app opens starts loading at once, where it waited until its radio had been fetched. When the radio could not be fetched, it did not start at all, a downloaded song included.
- Starting the same song's radio a second time left two queues of the same name in the list of queues, one of them a single song. There is one.
- InterTune asks YouTube for a song through one source after another until one serves it, and the first it asked has been refused on every song for weeks. It now starts with the source that served the last song.
- On a network with both IPv4 and IPv6, a song was asked for over one and its stream fetched over the other, which YouTube can refuse. The stream is now fetched over the one its address was issued for, with the other as the way out.

## History

- **History** lists every song you heard for 2 seconds or more, where it took 5. That goes for what you played before the update as well. Play counts, Most played, your YouTube history and Last.fm still follow **Minimum playback duration**.
- Signed in with a channel (a brand account), plays did not reach that channel's history in YouTube Music. InterTune now asks YouTube where to report a play as the account itself. There was no such account to try this with: if your history still stays empty, please say so.

## Backup

- After a backup was restored, the app closed and did not come back. It now reopens by itself.

## Privacy

- Network addresses are no longer written into the log, onto the player's error screen and what its button copies, into error messages or into crash reports. A failed connection named the address it went to and the phone's own, and the log held each song's whole stream address, which names your IP address. An address now reads IPv4 or IPv6, and a stream is logged by its host.

## Smaller things

- The cover on the lock screen, in the notification and in the system's media player was the small thumbnail a song is stored with, stretched. It is now fetched at the size it is shown. On a slow connection the small one shows at once, and the large one when it has arrived.
- The widget fetches its covers at the size they are shown as well. A Recently played that is still short is filled up from History, and the preview in the widget's settings no longer goes blank while you change a setting.
- The explanations behind an "i" are two or three sentences. Several had grown to a page, 311 words in one case; 54 are cut to what the thing does, in English and French. **Quick picks source**, under Settings > Library and content, is one row again.
- On **How it's doing**, the Listens row said "in your History" of the plays that count towards play counts, and marked short listens "not in History", which lists them. It says "counted as plays" now.
- **Media control buttons** is under Settings > Look and feel now, beside the player's other buttons. It was under Player and audio.
- **Stats** showed a blank page for a period with nothing played. It says so.
- In Arabic, Hebrew and Persian the player showed the end of a title written in Latin letters and hid its beginning, and the mini player cut off its first letter. A title now reads in the direction it is written in. An Arabic or Hebrew title in the English app does too.
- With the slim navigation bar the tabs had no name for a screen reader. They are named.
- The search pill begins with a magnifier until there is something to go back from. It showed a back arrow at all times.
- On a narrow phone, "Local scanner" is written out under its button on Home, where it was cut short.
- On a new install, the short tour of the app starts as soon as setup is done, where it waited for the next launch.
- On some new installs that tour had a stop for the chips over Quick picks after they had gone, its bubble in the middle of the screen. The stop is left out when the chips are not there.
- Previous, play or pause, and next in the mini player are named for screen readers.
- On a tablet, the back button of a local playlist and of four pages under Recommendations was drawn under the navigation rail and could not be reached. It keeps clear of the rail now.
- The two microphone permissions were each declared twice. Nothing the app asks for changes.
