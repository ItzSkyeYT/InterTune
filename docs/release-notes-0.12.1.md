# InterTune 0.12.1

_Songs that start sooner and pick themselves up, and fixes for 0.12._

## Playback

- **Songs start sooner when the connection hangs.** InterTune asks YouTube for a song through one source after another. A request with no answer in a second and a half now goes out again on a new connection, a source that still does not answer is given up after 5 seconds instead of up to 30 (longer on a connection that is slow all along), and a connection idle for more than 5 seconds is not used again, as one the network had dropped would never answer.
- **A slow check no longer holds a song up.** Before a song plays, InterTune checks that YouTube will serve its stream, and a check that hung could keep a song silent for a minute. After 3 seconds with no answer, the song now plays anyway when it comes from a source whose streams play to the end.
- **Music that stops picks itself up.** A song that stops on a passing fault, such as a timeout, a stream that broke off, or 20 seconds without finding its stream, is tried again by itself a second later, then three seconds later, from where it stopped. A song that keeps failing, or whose stream YouTube refuses, is fetched another way. A network drop shorter than what is already loaded no longer stops the song at all.
- **A song that goes silent is not left hanging.** When a song should be playing and nothing has come in for 6 seconds, its connection is opened again, and a new connection gets 15 seconds to answer. If that does not help, the song is fetched another way, and then the app stops, or skips to the next song if set to, with a plain message instead of an error code. Every byte that comes in starts the count again.
- **The first song after opening the app starts sooner.** YouTube's player script, about 0.7 MB that the first song used to wait for, is fetched a few seconds after the app opens with a song in the queue, on Wi-Fi only. On mobile data the first song fetches it when it needs it, as before.
- **Music started offline plays when the network comes back.** The app thought it was online, gave up waiting after about 50 seconds and never resumed. It now waits, and plays as soon as the network is back.
- **The best source comes back sooner.** After YouTube refuses a source, that source now waits half an hour before it comes first again, then two hours, and six hours only from the third time, where every refusal meant six hours. A song YouTube calls gone no longer counts against a source.

## Fixed

- When YouTube turns a connection away, it says "This video is not available" of every song, and InterTune treated each one as gone. From the second song in a row that comes back that way, it now asks again as a new visitor, and from the third it says so plainly: YouTube is turning the connection away, which a VPN or a school or work network can do (#28).
- With the full player open over Library, Home, an album, an artist or a playlist, Back now closes the player. It used to act on the screen underneath, clearing a Library filter for example, and leave the player open.
- Looking for cast receivers no longer closes the app on a network the phone does not let InterTune use, as a per-app firewall or a VPN that blocks other ways out can do (#33).
- The cast screen lists a receiver you used before only once it answers, so one that is switched off no longer flashes up and disappears. While the screen is open, the list is checked again every ten seconds.
- The queue button at the top of Liked songs, Downloaded songs and the other automatic lists put every song at the end of the current queue without a word, so it seemed to do nothing. It now asks which queue, a new one or one of yours, and a short message says where the songs went. Your own playlists no longer have the button: their **Add to queue** is in their menu, which asks the same.
- Updating from inside the app now says what Android is doing, from the tap on **Install** until Android closes the app to put the new version in. The button looked dead while the update was handed over, and every extra tap started it over. You will see it on the update after this one.
- In French, a timeout says "Délai dépassé" instead of "Temps libre", which means free time.

## Other

- **Report a problem keeps earlier runs.** From this version, Settings > About > **Submit a bug report** keeps the log of each of the last fifteen times the app ran, the newest first, so a fault from an earlier run, or from before an update, can still be sent. The logs stay on the phone and out of backups, and nothing leaves the phone unless you send it.
- That log also says how long each step of finding a song took, so a slow start can be explained.
- If YouTube closes one of the sources, the sources InterTune asks and their order can now be changed for a few weeks without waiting for an update, through the document the questions and news come in. The second tries, the start before the check answers and the watch on silence can be switched off the same way. It reaches only phones with questions or news turned on, nothing new is fetched or sent, and it can never leave the app without a source that plays whole songs.
- F-Droid's description of the app says Android 7.0 or later, as the app does, no longer calls InterTune a beta, and names casting, the equalizer and lyrics in Latin letters.
- Brazilian Portuguese: the Living blur settings are translated.
