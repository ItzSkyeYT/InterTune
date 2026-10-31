# InterTune 0.12.6

_Fixes, Opus first, and InterTune in Polish._

## Playback

- **Songs play again on Low quality**, and on Auto over mobile data: they could take seconds to start, or not start at all.
- **Opus first.** High and Highest take the Opus stream when the AAC one is only a little larger: Opus sounds as good at about two thirds of the bits (#57).
- **Downloaded songs show their covers with no network**, in your lists, in the player and in the small player at the bottom. They are kept in the app's own storage, and songs downloaded before this update get theirs the next time the app is online.

## Fixed

- An artist's page no longer closes the app when YouTube lists the same song or album twice (#53).
- Adding songs to a playlist no longer closes the app; if a song could not be added, a note says how many (#62).
- Local albums sort by track number disc by disc, even songs scanned before 0.12.5, and an album's page and Play from a folder's menu follow that order too (#39).
- Folders in the Folders tab follow the sort you pick.
- Tapping a song YouTube sends no up next list for no longer shows "plr: null" (#28).
- With Keep radios up to date on, the queue no longer flashes other songs for a moment after you unlock the phone, and repeated unlocks stop piling up work in the background.
- Add to queue in a radio plays your song after your own picks instead of hours later, and the radio carries on.
- Add to queue's list of queues numbers them from the newest down, no longer running into zero and below.
- Saved albums keep YouTube Music's order when they are synced.
- A song What's playing finds on the phone opens its results in front of the player, even with the queue open.
- When What's playing hears a remix, it also finds the original song.
- Changing a search keeps the filter you picked.
- The slim navigation bar's icons sit in its middle (#54).
- The player's Share button looks centred.
- An update waiting to be installed and a song What's playing found no longer take each other's notification.
- Changing a widget's settings no longer closes InterTune's own screen.
- Report a problem's form opens for people signed out of GitHub.

## Other

- **Samsung's Auto Blocker.** When it stops an update, turn it off and come back to InterTune: the install starts again by itself, and the new version reminds you to turn Auto Blocker back on. This helps updates from 0.12.6 onward.
- **Open links in InterTune**, a switch in Settings > Library and content, lets another InterTune app, like InterTune Preview, open YouTube Music and intertune.app links instead.
- InterTune is fully in Polish, thanks to Jacek's review.
