# InterTune 0.12.11

_A new home for the daily count, and the fixes that were ready._

## The daily count and polls

If you chose to be counted, the one short message a day and your poll answers now go to InterTune's own counter, a small function on Cloudflare, instead of Umami, whose free plan ran out. What's sent is exactly the same: a random name that changes every month, where you installed from, and the app and Android versions; for polls, the question, your answer and the app version. As with Umami, the country is read from the connection, and the address itself is never kept.

## Fixed

- **The player in split screen, a pop-up window or on a small screen** no longer closes the app (#157, #158, #162, #163, #164).
- **Lyrics** no longer close the app when one song's lyrics follow another's within a moment (#146, #148, #168).
- **A wired headset's button** plays your saved queue when InterTune is closed, instead of starting a player that plays nothing (#153).
- **Local songs without a cover** show a music note instead of the InterTune logo, the first code sent in from outside: thanks, 3SSF (#161).
