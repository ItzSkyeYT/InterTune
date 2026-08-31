<!-- wiki title: Player appearance -->
# Player appearance

These settings change how the now playing screen and the player controls look. They live in
**Settings > Appearance**, in the second box under the "Theme" heading. The same settings also show
up during first time setup, on the step called "Interface".

## Player background style

**Settings > Appearance > Player background style**

This sets what is drawn behind the cover art on the now playing screen.

- **Follow theme** puts nothing behind the player. You get the app's normal background colour.
- **Gradient** takes two colours out of the cover art and fades from one to the other down the
  screen, with the lighter one at the top.
- **Blur** shows the cover art itself, blurred and stretched to fill the screen.

**Blur** only appears in the list on Android 12 or newer, because the blur it uses does not work on
older versions. On Android 11 and older you get **Follow theme** and **Gradient** only.

The starting choice is **Blur** on Android 12 and newer, and **Gradient** below that.

Two things can change the picture without changing your setting:

- On a wide screen, **Blur** is drawn as **Gradient** instead. That catches tablets, and it also
  catches an ordinary phone turned sideways. The blurred image is made from a very small copy of the
  cover, 100 pixels across, and stretching that over a wide screen looks blocky. Your setting is left
  alone, so turning the phone upright gets the blur back.
- With Android's Battery saver switched on, the app stops reading colours out of the artwork, so
  **Gradient** stops following what is playing.

One more thing you may notice. When you open the lyrics, an extra dim layer goes over the background
so the words stay readable. That happens with **Gradient** and **Blur**, not with **Follow theme**.

## Liquid glass

**Settings > Appearance > Liquid glass**

Off unless you turn it on. It does two separate things.

The first is panels. The navigation bar at the bottom and the mini player become floating rounded
panels that you can partly see through, and whatever is behind them bends a little at the edges. The
row of play, skip and shuffle buttons on the now playing screen gets that same bending at the edges,
around a rounded bar, as long as **Group the player buttons** below is left on.

The second is colour on the now playing screen. Normally a flat dark (or light) wash is painted over
the background to keep text readable. Liquid glass fades that wash away and draws the artwork or the
gradient at about half strength over the app's own background colour instead. The result is less
flat and closer to the album's own colours.

Only the second half works on Android 12 and older. The bending needs a graphics feature that
arrived in Android 13. The text under the switch tells you which half you are getting:

- Android 13 or newer: "Floating glass panels for the navigation bar, mini player and transport
  controls, and richer artwork colours behind the now playing screen"
- Android 12 or older: "Richer artwork colours behind the now playing screen. The floating glass
  panels need Android 13 or newer"

"Transport controls" there means the row of play, skip and shuffle buttons.

Two more cases where you will see less than you expect:

- If **Player background style** is set to **Follow theme**, there is no artwork or gradient behind
  the player, so the colour half has nothing to change. The panels still work.
- On a large screen where the app puts a rail of tabs down the side instead of a bar along the
  bottom, the navigation bar and mini player panels are switched off. Nothing ever scrolls
  underneath them there, so there would be nothing to see through.

## Group the player buttons

**Settings > Appearance > Group the player buttons**

This one only shows up while **Liquid glass** is on, and it is on by default. On screen it says:
"Puts shuffle, skip, play and repeat together inside one glass bar. Turn it off to have the buttons
sit on their own over the artwork. Needs Liquid glass to be on."

Like the other panels, that bar needs Android 13 or newer before there is anything to see.

## Glass intensity

**Settings > Appearance > Glass intensity**

A slider from 0% to 100%, starting at 100%. It also only shows while **Liquid glass** is on, and it
controls both halves of it at once. On screen it says: "Higher is more see-through and more strongly
refracted; lower is closer to plain solid surfaces". Refracted is the bending at the edges of a
panel, like looking through the rim of a drinking glass.

So drag it up for more glass and down for less. At 0% the now playing background looks exactly as it
does with **Liquid glass** switched off, and the navigation bar and mini player panels go nearly
solid. The slider will not go fully clear even at 100%, on purpose: past a point, text behind a
panel starts colliding with the text on it.

If you are coming from v0.10.1-intertune.2, this slider used to work the other way round, and there
were two glass switches rather than one. It was flipped because people were dragging it down looking
for more glass and getting less.

---

Liquid glass, the intensity slider and the grouped buttons are InterTune's own. Upstream
[OuterTune](https://github.com/OuterTune/OuterTune) does not have them, so do not report problems
with them there. If something here is wrong or missing, open an issue at
[InterTune](https://github.com/ItzSkyeYT/InterTune/issues).
