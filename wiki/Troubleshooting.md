<!-- wiki title: Troubleshooting -->
# Troubleshooting

Common problems and what to check, in the order worth checking them. If a setting named here is not in your copy of the app, your version is probably older. `Settings > About` shows which version you have, and `Settings > Updates > Check for updates` tells you if there is a newer one. A lot of the YouTube playback errors people run into are fixed in newer builds, so that is worth doing first.

## No sound at all

Check the phone before you blame the app.

1. **Turn the volume up while a song is playing.** Android keeps separate volumes for media, calls and notifications. The volume keys usually only move the media one while something is actually playing, so pressing them on the home screen can leave media sitting at zero.
2. **Check where the sound is going.** If your phone is still connected to headphones, a speaker or a car, Android sends the music there and the phone itself stays silent. Turn Bluetooth off and try again.
3. **Check the app's own volume.** On the now playing screen, tap the three dots button. A volume slider sits at the top of the menu that opens, above the grid of buttons. It is separate from the phone's volume and it is saved between sessions, so if it was ever dragged to the far left it stays there.

If that has not done it:

- The same three dots menu has **Equalizer**, which hands you over to whatever equalizer your phone provides. A preamp or a band pulled all the way down in there silences InterTune without silencing anything else.
- If you turned on **Enable offload** in `Settings > Experimental`, turn it off. It is off by default and only appears after you turn on **Enable developer settings**, so this only applies if you went looking for it. On some phones it makes playback stop partway through a track, or go quiet when the track changes. If you want to keep it on, try turning off **Gapless offload** just underneath it first, which is the usual culprit on Pixels.

### Everything plays, but quietly

**Audio normalization** in `Settings > Player and audio` is on by default. It turns loud songs down so that nothing jumps out at you, and it can only turn songs down, never up. That means the whole library sits a little below what your phone can do. Turn your phone volume up, or turn the setting off.

If only *some* songs are much quieter than the rest, the app does not know how loud those songs really are, so it plays them cautiously low. `Settings > Player and audio > Fix song volumes` tells you how many songs are in that state and looks up the missing values for you. It needs an internet connection, and it is greyed out when there is nothing left to do. Local files are never adjusted, because there is nothing to look up.

## A song will not play

You will usually see one of these:

- "Error occurred. Playback stopped"
- "Error occurred. Playing next", if you turned skipping on
- "Too many errors. Playback stopped", when several songs fail one after another. The app gives up on purpose at that point rather than racing through the rest of your queue.
- "Wait to reconnect", which means the app thinks the network dropped and is waiting rather than giving up

Things to try:

- **Try a different song.** If nothing plays, the problem is not that song.
- **If it is a local file**, it may have been moved, renamed or deleted since the last scan. Check with a file manager, then rescan (see below).
- **Check your connection.** With no network the app waits and retries instead of skipping.
- **Some songs simply cannot be played.** Tracks that have been taken off YouTube Music, private videos, and some age restricted or region locked songs will fail every time. Logging in under `Settings > Account and sync > Login` can help with content that needs an account.
- **Sharing a link into InterTune** only works for songs that exist on YouTube Music. A plain YouTube video gives you "Failed to play song. Only content accessible on YouTube Music can be played".
- **Turn on `Settings > Player and audio > Auto skip to next song when error occurs`** so one bad track does not stop the queue.
- **Give the app a song cache.** `Settings > Storage > Song Cache > Max cache size`, which is off to begin with. Keeping recently played songs on the phone smooths over patchy connections and some YouTube errors.

If you specifically get "Sign in to confirm you're not a bot", there is a last resort in `Settings > Experimental`. Turn on **Enable developer settings**, then tap the row starting "Delete VisitorData". It may or may not help, and the row itself says it is not recommended if you are logged in.

## Downloads are not showing up

- **Check `Download on Wi-Fi only` in `Settings > Storage`.** If it is on and you are on mobile data, the download waits quietly instead of starting. You get "Queued. Will download when Wi-Fi is available" when you ask for it, and it starts on its own once you are back on Wi-Fi.
- **Look in the right place.** Go to the Songs tab of your library and pick the **Downloaded** filter.
- **Files you copied in by hand are not picked up straight away.** If you added, changed or removed files in your external download folder or an extra import folder with a file manager, run `Settings > Storage > Advanced > Rescan download folders…`. The automatic scan does eventually pick them up, but only when you open the app and at most about once every eleven hours. That row is greyed out until you have set an external download folder or an extra import folder.
- **Imported files must be named correctly.** InterTune reads the song id out of square brackets in the file name, for example `some song name [uwbf82ha].mka`. It takes whatever sits between the last `[` and the last `]`.
- **The song must already exist in the app.** Importing links a file to a song the app already knows about, so if the song has never been played or added to a playlist there is nothing to link it to. Search for the songs, add them to a playlist, play that playlist once, then rescan.
- **`Clear all downloads` only removes internal downloads.** Anything in your external download folder or an extra import folder has to go by hand, either from the song's menu or with a file manager. That is deliberate, so nobody wipes a whole folder by accident.

There is more detail on the three kinds of download folder in [Downloading songs](https://github.com/ItzSkyeYT/InterTune/wiki/Downloading-Songs).

## Local files are missing after a scan

- **Grant storage permission.** If your library shows "Storage permissions is required for local media. Click to grant permission.", tap it and allow access. Without it the scanner cannot see anything. Tapping the banner also hides it, so if it disappeared and you did not actually grant anything, close and reopen the app to get it back.
- **`Enable local media` must be on** in `Settings > Local media`.
- **Check the folders.** `Settings > Local media > Configure scan locations…`. Make sure the folder is in the include list and is not sitting inside an excluded one. Subfolders are always included.
- **A scan folder cannot be inside a download folder.** The app refuses it with "Invalid selection. A scan folder cannot be a subdirectory of the download folder, or the download folder itself".
- **Hidden folders are skipped.** Anything whose name starts with a dot is ignored, and any folder holding a `.nomedia` file is skipped along with everything inside it.
- **The file has to look like audio to Android.** A handful of extra extensions are accepted on top of that: `dsf`, `dff`, `xm`, `mod`, `tta`, `ape` and `wv`, plus `opus` on Android 9 and older. Playlist files such as `.m3u` are not songs and are skipped.
- **Changed the tags and still seeing the old ones?** An ordinary scan does not reload information for songs the app already has. Tick "Rescan the entire library and reload all songs' metadata" and scan again. On a big library this takes a while.
- **Two songs merged into one, or one song appearing twice?** That is the matching rules. `Settings > Local media > Additional scanner settings > Configure scanner sensitivity` decides what counts as the same song, and "Match title and artists" is the default. Turn on **Strict file names** if you keep the same song in more than one format. Then rescan with the rescan box ticked.
- **`Scan automatically` is not instant.** It runs when you open the app, and at most about once every eleven hours. If you just copied music over, run the scan yourself with the **Scan** button in `Settings > Local media`.

More on scanning is in [Local media](https://github.com/ItzSkyeYT/InterTune/wiki/Local-Media).

## The app looks wrong after an update

- **Close it fully and open it again.** Some settings only apply on a fresh start, and the app tells you so with "An app restart is required for changes to take effect".
- **Check the appearance settings.** They all live in `Settings > Appearance`: **Player background style**, **Liquid glass**, and, once Liquid glass is on, **Group the player buttons** and **Glass intensity**. Those last two stay hidden until Liquid glass is on, which catches people out.
- **Blurry, wrong sized or missing album art**: `Settings > Storage > Image Cache > Clear image cache`. It will be downloaded again.
- **Using Android's high contrast mode?** Turn off **Enable dynamic theme** in `Settings > Appearance`, which reveals **High contrast mode compatibility**, then turn that on.
- **Your library looks empty.** InterTune has its own app id, so Android treats it as a different app from OuterTune and installs it next to it rather than updating it. If you have just moved across, that is a fresh install, not lost data. Take a **Backup** in the old app under `Settings > Backup and restore`, then **Restore** it in the new one. The app closes and reopens itself when the restore finishes.

## Reporting a bug

Report it at **https://github.com/ItzSkyeYT/InterTune/issues**, or from inside the app at `Settings > About > Submit a bug report (Via GitHub)`, which opens the same page. For questions rather than bugs, `Settings > About > Help and support (Via GitHub)` opens the discussions page instead.

Please do not report InterTune bugs to OuterTune. Upstream did not build this app and cannot fix it. If you can reproduce exactly the same thing on plain OuterTune, then it is genuinely their bug and belongs on [their tracker](https://github.com/OuterTune/OuterTune/issues) instead.

A report is much easier to act on with these in it:

- **The version.** `Settings > About` shows it under the app name, like `0.10.2 (79) | core`. Include the last part. There is a core build and a full build, and the full one has extra audio decoders the core one does not. (`Settings > Updates > Installed version` shows the same version and build number without that last part.)
- **Your phone and Android version.** `Settings > About` has a **Device information** section that lists all of it, and an **Application information** section next to it. Both open when you tap them.
- **What you did, what you expected, and what actually happened.** Step by step is best, so someone else can follow it.
- **Whether the song is from YouTube Music or a local file**, and whether you are logged in.
- **If it is one particular song**, open its menu, tap **Details**, then tap the value under **Media id** to copy it and paste it into the report.
- **Any message the app showed you**, word for word. When playback fails, a second and more technical message starting with `plr:` also pops up. That one is the useful one, so copy it if you can.
- **A screenshot or a short screen recording** if the problem is something you can see.

This is a personal fork maintained by one person in their spare time. Issues may be answered slowly, or not at all.
