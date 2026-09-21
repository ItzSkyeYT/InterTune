The headline is that music stops freezing. If you have been force stopping InterTune to get playback going again, that is fixed.

## Songs stopped playing until you force stopped the app

Playback would hang on a song, showing nothing, until the app was force stopped. Then it worked again for a while.

Before every song, InterTune was doing two pieces of work it did not need: extracting a signature from YouTube's player, and spinning up a hidden browser to fetch a "proof of origin" token. Neither result was used by the clients that actually fetch the audio. The token step waits for that hidden browser to answer, and it had no time limit, so if the page stalled the song simply never started. Worse, it holds a lock, so every song queued behind the stuck one. Nothing was logged, which is why it looked like the app had just given up.

Both requests are gone, and the wait now has a time limit for the day something needs them again. This also means noticeably less traffic to YouTube, which matters for the next item.

## YouTube blocking the connection

When YouTube temporarily refuses to serve music to your connection, the app now says so, in words, instead of going quiet. The back-off is also less trigger happy: it used to trip on every single song, because the first client InterTune asks is always refused and a second one succeeds a moment later. Only a genuine refusal counts now.

## Quick picks

**"Your library" now actually fills.** It builds the row from songs related to what you have played, and that related-songs lookup had silently stopped working: YouTube added a Comments tab to a response the app reads by position, so it was looking in the wrong place and finding nothing. On a fresh install the row could never fill, however much you listened.

**Quick picks is always at the top**, on both sources. The only difference between them is where the songs come from. When the source you picked has nothing yet, the row says so rather than vanishing.

## Plays from search were never counted

Tapping a song in search results and listening to the whole thing did not count as a play, did not appear in your history, and was never sent to YouTube Music. Search results carry no track length, and the app was using that missing length to work out whether you had listened to enough of it. Fixed.

Also: playing a song from your local library search no longer queues every other search result behind it.

## New: Last.fm

**Settings → Account and sync → Last.fm**

Connect a Last.fm account and InterTune will keep your listening history there, alongside anything else you listen with. You sign in on Last.fm's own page inside the app, so no password is ever typed into InterTune.

Scrobbling can be switched off without disconnecting.

## New: the occasional question

**Settings → Privacy and history → Questions**

Every so often InterTune can show a short question at the top of Home, about what to build or change next. Off by default, and nothing is fetched at all while it is off.

Answering is optional and anonymous. All that is sent is which question it was, what you picked, and the app version. No account, no device details, nothing about your library. You can turn it off at any time, and there is a button to clear what you have answered so past questions can come back.

## New: let other apps play at the same time

**Settings → Player and audio**

Normally, starting music here stops whatever else was playing, and something else starting stops InterTune. Turn this on and both can sound together, which is the point if you want music over a lecture or a video.

Off by default, and the cost is real: with it on, InterTune will no longer pause for a phone call, duck for a navigation prompt, or stop for an alarm.

## Sleep timer countdown

A running sleep timer now shows how long is left in a notification, silently, and on Android 16 in the status bar chip.

## 83 strings translated into 28 languages

Everything added over the last few releases was English only, so other languages showed English text in the middle of a sentence. All of it is now translated: the settings descriptions, the updater, the questions feature, and the Quick picks source setting.

These are machine translations rather than a native speaker's. Corrections are welcome and are one line each.

## Smaller fixes

- Lyrics no longer fail to load when LRCLIB returns a result with no duration.
- A failed related-songs lookup is no longer retried on every single play.
- Your YouTube playlists no longer vanish when YouTube sends an empty page part way through loading them.
- Settings preferences no longer read from disk on every redraw.
- Downloads appear as a Live Update on Android 16.
