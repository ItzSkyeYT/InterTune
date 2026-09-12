# InterTune 0.11: Quick picks that learn how you listen

Hey everyone. This one took a while, and it is the biggest change InterTune has had.

Until now the Quick picks row came from YouTube Music, or from a simple query over your library. Both had the same problem: neither knew anything about how you actually listen. 0.11 adds a third source, Best recommendations, built entirely on your phone from your own listening, and it learns.

**What it looks at.** Every time a song stops, the app notes how much of it you heard, how it ended (finished, skipped, replaced, stopped) and how it started (a search, a playlist, a card on Home, a radio carrying on by itself). A song you searched for and finished counts most, one finished from a row a little less, an early skip counts against it and a late one barely at all, and everything fades slowly, so last night matters a lot and last spring a little. A pause, a closed app and a return an hour later is one listen, not a stop and a loss. It also notices the small things: seeking back to hear a part again, turning it up, putting it on repeat, adding it to a playlist, downloading, sharing, opening the lyrics, taking a song out of the queue.

**How it picks.** From that memory it chooses up to sixteen seed songs, leaning on today and this hour but moving on after a couple of hours, and gathers what YouTube lists as related. Twenty cards come from five lanes: related to your seeds, songs you come back to, more from artists you finish, favourites gone quiet, and a few artists you have never played. Never two versions of a song, never more than two cards per artist, never what you just heard. Each card says why it is there ("Because you played Rosanna", "You finish a lot of Toto", "New to you"), and Why these? on the heading shows the songs the row was built around, each of which you can turn down.

**How it learns.** Every card it shows is remembered. A card you play is a win, graded by how much of it you heard. A card you scroll past for a day is a small loss. A few plain weights move a little from each, never far in a day, and never past their limits. Settings > Recommendations > How it's doing shows you the numbers as they come in: cards played per cards seen for each source, how often each row held what you played next, and every weight beside where it started. Nothing is hidden and nothing leaves your phone.

**You stay in charge.** Pick the source: YouTube Music, Your library, Best recommendations, Try both (a row that mixes the new one with the old, card for card, so you can judge for yourself), or Off. Turn the Adventurousness dial for more new artists and the Familiarity dial for more of what you love. Pick a context chip: Auto, Discover, Favourites, or Focus, Chill and Party, which learn from what you play while they are on. Long-press any card for Not this song, Less of this artist or Never this artist. Switch learning off, forget a session, reset the weights, or export the lot as JSON. Every new behaviour is a switch, and the only two that are on by default are tidy-ups: no song twice on Home and nothing you just heard, and your existing rows ordered by what you tend to finish. Both have an off.

**Honest about it.** It is marked experimental because it is. It starts from sensible guesses and gets better the more you use it, and the first week is it finding its feet. If a row looks odd, Why these? will tell you what it was thinking, and Try both will show you whether it is beating what you had.

**On the home screen.** You asked for this one in the poll, so it is here: a widget showing what is playing with its controls, and your Quick picks under it. Make it taller for more picks, narrower for just the song. Tapping a pick plays it. It works with the app closed, and pressing play brings back the queue you were on rather than starting something new.

**Also in 0.11.** A Highest available audio quality tier and a readout of what is actually playing. Predictive back and reveal transitions. The app comes back where you paused even if Android closed it. F-Droid installs now update through F-Droid. Your old play history is converted once at first start, so the new row begins from everything you have already played, not from zero.

Thank you to everyone who filled in the poll and opened issues. Tell me what the row gets right and what it gets wrong; that is exactly what it learns from.

Mel
