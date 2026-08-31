<!-- wiki title: Tablets and large screens -->
# Tablets and large screens

InterTune rearranges itself when there is more room on screen. There is nothing to set up on a tablet, it happens on its own. This page explains what changes, and what the one related setting does.

## What counts as a big screen

Android measures screens in a unit called dp. It ignores how many pixels the screen packs in, so a big blurry screen and a small sharp one do not get confused. InterTune checks two separate things.

- **Wide window.** The window is at least 600 dp across right now. Most phones pass this the moment you turn them sideways.
- **Tablet.** The *shorter* side of the screen is at least 600 dp, and the device is in landscape. Standing a tablet upright switches this off again, on purpose.

The tablet check is what gives you the two pane player. The wide window check only affects the background and some spacing.

## The player on a tablet

Turn a tablet sideways and open the now playing screen. It splits down the middle.

The left half is the player: cover art, song title, artist, the progress bar with the times either side of it, and the play and skip buttons.

The right half is the queue, under a heading that says **Songs**. It is the same up next list the slide up queue shows.

- The song playing right now is highlighted. Tap it to pause or resume, tap any other song to jump to it.
- Drag a song by the handle on its right to move it up or down.
- Swipe a song sideways, either direction, to take it out of the queue.
- The padlock button in the **Songs** header locks the list. While it is locked, dragging and swiping both stop working, which helps if you keep knocking songs out by accident. It is the same padlock as the one in the slide up queue, so locking in one place locks in both.
- Every song has its own three dot menu.

The side pane only shows what is up next. Your saved queues are not in it. To reach those, tap the small arrow at the bottom middle of the screen and the full queue slides up over the player. In landscape that sheet is split as well: the songs on the left, your saved queues under **Queues** on the right.

The strip that the arrow sits in is taller in portrait than it needs to be. On a tablet, and on a phone turned sideways, it shrinks to just the arrow so the artwork keeps that height.

## The player on a phone turned sideways

A phone in landscape is a wide window but it is not a tablet, so it gets a different split: cover art on the left, everything else on the right. The queue stays a sheet you pull up from the bottom. The title, the artist and the play and skip buttons also run a bit larger than in portrait, since the screen is usually further from your face.

## Landscape goes full screen

In landscape, opening the player hides the bar at the top of the screen and the navigation bar at the bottom, so the artwork gets the whole display. This happens on tablets and phones alike. Swipe in from an edge to bring the bars back for a moment. They come back for good once you collapse the player.

While the bars are hidden and music is actually playing, the screen is kept awake. Pause and it turns off as normal, so a paused player left in landscape overnight will not sit there burning battery. The lyrics view is the exception: it holds the screen awake whether or not anything is playing.

## The background on wide screens

**Settings > Appearance > Player background style** offers **Follow theme**, **Gradient** and **Blur**. **Blur** only appears in the list on Android 12 and newer.

On a wide screen, **Blur** is quietly swapped for **Gradient**. The blur is drawn from a tiny 100 pixel copy of the cover art stretched to fill the window. On a phone the blurring hides that. On a tablet there is far more window to stretch into, and the square cover gets squashed sideways to fit as well, so it comes out blocky instead of soft. The gradient is built from the colours in the cover itself, so there is nothing to pixelate.

Your choice is not overwritten. If you picked **Blur**, a phone in portrait still shows blur. A phone turned sideways is already a wide window, so it gets the gradient too.

Worth knowing, and this one is not just tablets: while battery saver is on, InterTune does not read the colours out of the cover art. The gradient then has nothing to draw with, so you get a plain background instead.

## Force tablet UI

**Settings > Experimental > Force tablet UI**, described on screen as "Enable a landscape interface that is optimized for larger screens regardless of the current screen size". It is off by default.

Turn it on and an ordinary phone will use the tablet layout, meaning the two pane player with the queue beside it.

Two things to know first.

1. **It only applies in landscape.** In portrait the switch does nothing at all, on any device. Turn the phone sideways to see any difference.
2. **A phone is not a tablet.** Each half gets about half a phone screen, so the artwork ends up small and long titles get cut short or scroll past. That is why this lives under Experimental.

The player does not always notice the change straight away. Rotate the device once, which rebuilds the screen, and the new layout takes over.

On a real tablet you do not need this setting. The app already treats it as a tablet, so leaving the switch off changes nothing.

## The tabs move to the side

Separate from everything above: once the window is really wide, roughly 840 dp and up, the row of tabs along the bottom of the app moves to a vertical strip down the side, with the app icon at the top. Tablets clear that width, and so do large phones turned sideways. This is automatic and has nothing to do with Force tablet UI.

---

If something on a tablet looks wrong or a layout does not fit, report it at [InterTune's issues](https://github.com/ItzSkyeYT/InterTune/issues). Please say which device you are on and whether Force tablet UI was switched on.
