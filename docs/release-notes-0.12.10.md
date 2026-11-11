# InterTune 0.12.10

_A big round of fixes and no new features: every crash reported on 0.12.9, and the fixes from the next version that should not wait for it._

## Crashes fixed

- **A play press from a headset, a car, the widget or the notification** no longer gets the app closed by Android: when nothing can play, on Android 12 where less time is allowed, and when Android restarts the player after another fault (#98, #116, #129, #131, #132, #137).
- **Downloads waiting for Wi-Fi** no longer close the app after six hours on Android 15 and newer (#130).
- **A song listed twice** no longer closes the app: in the queue (#102, #103), in a playlist's quick search (#105, #136), in the remote history and on an artist's page.
- **Phones without a file or folder picker** say so instead of closing (#120, #121, #126), and a phone that refuses the network listener no longer stops the app opening (#123).
- **Shuffle on some playlist pages**, a full phone and a stray part in a sign-in cookie (#128).
- **The cover on the lock screen and in the notification** on phones that free it too early (#108), and **going to the background after a long walk through the app** (#107).

## Sound and playback

- **On mobile data, Auto quality takes a middle stream** instead of the smallest, and a song first played on mobile data is fetched again at full quality on Wi-Fi (#88).
- **With the battery saver on**, High and Highest audio quality stay as chosen on mobile data, only Auto drops to Low (#88), and the equalizer stays on (#91).
- **Music through USB headphones** no longer goes silent with the battery saver on and the screen off (#90).
- **The volume past 100%** turns the music up without distorting it (#93).
- **Behind a VPN**, the player says YouTube wants a sign-in to play from this network, with Log in and Proxy settings beside Retry (#124). A song YouTube keeps turning down is given up on after two tries instead of being tried for minutes (#138), and a stream that comes back as something other than audio says so.
- **After Android brings the app back** with Persistent queue off, the mini player no longer shows an old song whose buttons do nothing.
- **The queue**: its play, skip, shuffle and repeat buttons work at once after a restart (#127), its songs stop at the status bar, its total is right when some songs have no length, and a queue with every song taken out of it goes.
- **The seek bar** follows a drag that starts a little downward, and can be caught on its times. With repeat off, the repeat button is the repeat icon greyed out, as in the notification.
- **Android Auto**: a search typed or said finds songs on YouTube Music, not only in the library (#112).

## Downloads, files and playlists

- A download that fails leaves nothing behind, a liked song whose download you removed stays removed, a downloaded song is kept once instead of twice, and removing one song no longer takes the cover from the rest of its album.
- A downloaded song whose file was deleted outside the app leaves Downloads when you remove it, and songs moved into the download folder keep playing offline during the move.
- A download taken up partway at another quality starts again instead of joining two streams in one file.
- With a proxy set, covers and downloads go through it too.
- A scan folder that holds an extra import folder is refused by naming that folder (#122).
- Playlist edits do what they say, a playlist's page shows its downloads again, Add to playlist from an album's menu works before the album is saved, and Play in a playlist's menu works while the library is busy.
- **Lyrics as a video**: when a video cannot be made, the window says which step stopped, the picture is tried once more with the phone's software encoder, and the failure shows in Report a problem.

## Screens and words

- **Leaving Settings by a tab** leaves it for good (#135), and **going back with Back animations on** no longer shows the screen underneath (#134).
- **Artists**: a songs or videos page without a header opens instead of "Unknown error" (#119), the page no longer moves while a row of albums is scrolled sideways (#86), and artist pictures fill their circle.
- **The player**: its row of buttons is centred with the queue button on (#142), and the mini player's buttons can be seen on every cover (#104).
- **The widget** keeps the paused song when the app closes, plays a tapped song again after the music had stopped, and its "Nothing playing" fits.
- **Check for updates** offers a version you skipped again.
- **Report a problem** names what went wrong when a backup, an import, an export, a recognition or a cast fails, and keeps the line that names the fault when the log is long.
- Smaller things: lyrics fade out below the status bar, a setting opened from a link comes to rest above the mini player, the Songs tab says which list is empty, the folder search shows what was typed last, toasts fit on two lines, file sizes follow the app's language, and "Enable dynamic theme" says the colours follow the playing song's cover.
