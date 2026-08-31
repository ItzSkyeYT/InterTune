<!-- wiki title: Volume and loudness -->
# Volume and loudness

Some songs blare out much louder than the ones before them, even though you have not touched your phone volume. This page explains why that happens, what InterTune does about it, and the button that repairs the songs it has no volume information for.

The on/off switch itself came from OuterTune, but the way InterTune levels songs, the repair button and the volume boost are all InterTune's own work. Upstream OuterTune does not behave this way, so if something here goes wrong, report it on [InterTune's issues](https://github.com/ItzSkyeYT/InterTune/issues) rather than to upstream.

## Why some songs are louder than others

Music is not all recorded at the same level. A modern pop single is usually made much louder than an album track from twenty years ago. Play one after the other and the difference can be big.

YouTube measures how loud each song is and publishes that figure. InterTune saves the figure for a song the first time it fetches that song, which is when you play it or download it, and then uses it to pull loud songs back down to a common level.

You can see the figure for a YouTube song that is playing. Open the player, tap the three dots button, tap **Details**, and look at the **Loudness** line. If it says "Unknown", that song has nothing stored, which is exactly what the repair button further down this page is for. Files stored on your own phone have no **Loudness** line at all.

## Audio normalization

**Settings > Player and audio > Audio normalization**. It is on by default.

With it on, InterTune turns loud songs down so that everything plays at roughly the same level. It can only turn songs down, never up, so quiet songs are left exactly as they are.

That has one side effect worth knowing about. Because the loud songs get pulled down and nothing gets pushed up, the app overall plays a bit quieter than it would with the setting off. You may want to turn your phone up a notch. That was chosen on purpose: turning your phone up is easy, and one track suddenly blasting in your ears is not something you can un-hear.

Music stored on your own phone is not touched. YouTube has no figure for a file sitting on your phone, and InterTune does not measure files itself yet, so those songs always play exactly as they are.

## What happens when the figure is missing

Some songs end up with nothing stored. Older versions let those play completely untouched, which meant one of them could come out as much as 16 dB louder than everything around it. (dB is the unit loudness is measured in. 16 dB is a big jump, roughly three times as loud to your ears.) That is the whole "why is this one song so loud" problem.

Now InterTune assumes an unknown song is one of the loud ones, and turns it down by about as much as a genuinely loud song would get. Usually that lands close to right. Sometimes it lands a little quiet. Being a little quiet is something you can fix with the volume rocker. Being far too loud is not.

It is a safety net, not a fix. The fix is to go and get the real figure, which is what the next section does.

## Fix song volumes

**Settings > Player and audio > Fix song volumes**.

The row ends by telling you how many songs are missing their figure, for example "355 songs still need it. Tap to start." (If none of them do, the row says so and does nothing when tapped.) Tap it and InterTune goes through those songs one at a time and asks YouTube for the real number. While it runs, the row reads "Checking song 12 of 355, fixed 9 so far. Tap to stop."

A few things to know:

- **It needs an internet connection.** Without one you get "Needs an internet connection to look up song volumes."
- **It only changes the volume figure.** Nothing else about a song is touched.
- **It skips the song you are listening to right now.** That song is being played quietly on the safe guess described above, so filling in its real figure halfway through would make it jump louder in your ears. It gets picked up the next time you run the scan.
- **It only looks at songs InterTune has already fetched**, which means songs you have played or downloaded at least once. A song you have never opened has nothing stored yet, and it gets its figure the moment you first play it. That is why the count is much smaller than your library.
- **Files on your phone are skipped**, for the reason given above.

### How long it takes

It waits about a third of a second between songs on purpose, so YouTube does not see a burst of requests coming from you. That makes a few hundred songs a job of a minute or two. A large library that has never been done will take longer. It starts with your downloads, then your liked songs, then the rest of your library, then everything else, so the songs you are most likely to hear get sorted first.

### Stopping is safe

Tap the row again to stop it. Every song it already fixed stays fixed, and the row says so: "Stopped. Fixed 40 songs, the rest are still to do. Tap to carry on." Tap once more to pick up where it left off. You can also leave the settings screen while it runs and come back later to see how it got on.

Two other messages you might see:

- "Fixed 40 songs. 3 have no volume information on YouTube and were left alone." Some songs genuinely have nothing published. Those are skipped and not asked about again until you restart the app.
- "Stopped after several failures in a row, which usually means YouTube wants a break. Fixed 40. Try again in a little while." It gives up after eight failures in a row rather than hammering away.

## The volume slider and the boost

Open the player, tap the three dots button, and there is a slider with a speaker icon at the top of the menu that appears. This is InterTune's own volume, separate from your phone's volume buttons. The two multiply together.

The slider goes up to four times normal volume, which is +12 dB. There is a small mark on the bar at the 100% point so you can find normal by feel, and the bar fills in a different colour past that mark, because past it the app is making the sound louder rather than just turning it down.

It exists because of the side effect described earlier. Levelling only ever turns things down, so with it on, playback sits below full volume. The boost is how you get that back for a quiet track, a quiet pair of headphones, or a noisy bus. Peaks are rounded off rather than left to distort harshly, but the app is still making a finished recording louder than it was made to be, so a big boost will not sound as good as the original. Use it when you need it, not as a default.

Two things to watch for:

- Anything above 100% is not remembered. Set it below 100% and it comes back that way next time. Set it to 250% and the next fresh start puts it back at 100%.
- If you turned on **Enable offload**, anything above 100% does nothing. In that mode the system handles playback and the app has no way to make it louder. Levelling still works normally. That setting lives in **Settings > Experimental**, and it only shows up after you switch on **Enable developer settings** on the same screen.

## Still sounds uneven?

- Run **Fix song volumes** and let it finish.
- Check that **Audio normalization** is actually on.
- If the loud song is a file on your phone, that is expected. Those are not levelled yet.
- If a song still stands out after all of that, open the player, tap the three dots, then **Details**, and note down the **Media id** and **Loudness** values. Those two lines are what makes a report on [InterTune's issues](https://github.com/ItzSkyeYT/InterTune/issues) useful.
