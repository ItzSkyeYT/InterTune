<!-- wiki title: Local media -->
# Local media

InterTune plays music files that are already on your phone or SD card, not just YouTube Music. It does not find them on its own. You tell it which folders your music is in, it looks through those folders, and it adds what it finds to your library. The first-run setup asks you for those folders, and you can change them later.

## Turning local media on and off

**Settings > Local media > Enable local media**. This is on when you install the app. Turning it off hides your local files and the Folders tab, and the scanner settings vanish from that screen. It does not touch the files themselves, and the app says so before it does anything: "Are you sure you want to disable local media features? Your library contents will not be affected."

## The Folders tab

This is the screen for your local files. Out of the box the app has four tabs, Home, Songs, Folders and Library, so Folders is already there. If you do not want it, open **Settings > Interface > Arrange tabs**, tap **Folders** so it stops being highlighted, then tap OK. The drag handle on the right of each row is for reordering, not for removing.

Three controls sit in the row at the top of the Folders screen:

- A magnifying glass, which searches inside the folder you are currently in.
- **Local scanner**, which opens the Local media settings screen described below.
- An icon on the right that switches the view between a flat list of every folder and a tree that keeps the folder structure exactly as it is on disk. It is the same switch as **Settings > Library and content > Advanced > Flatten subfolders** ("Disable to preserve the file structure as on disk"). Flat is the default.

## Finding the scanner settings

**Settings > Local media**. The same screen is also listed inside **Settings > Library and content**, and there is a **Local scanner** shortcut in the row of buttons at the top of the Home screen.

## Scan locations

Tap **Configure scan locations…**. If you have never set a folder, this dialog opens by itself.

Tap **Add new folder** and Android's own folder picker appears. Everything inside a folder you pick is included, subfolders and all.

The switch in the top right of the dialog flips it between two lists:

- **Scan paths**, the folders to look in.
- **Excluded scan paths**, the folders to skip.

You can exclude a folder that sits inside a folder you included. The other way round does not work, so you cannot include a folder that sits inside one you excluded.

A scan folder cannot be your download folder or one of your extra import folders, and it cannot be anything inside them either. Pick one of those and the folder turns red in the list and the OK button stops working. If you pick the download folder itself, the app also explains why: "Invalid selection. A scan folder cannot be a subdirectory of the download folder, or the download folder itself". Downloads are handled separately, see [Downloading Songs](https://github.com/ItzSkyeYT/InterTune/wiki/Downloading-Songs).

Two things are skipped on purpose, and they are the usual reason music goes missing:

- Files and folders whose name starts with a dot.
- Any folder holding a file called `.nomedia`. That empty file is the standard way of telling Android apps to leave a folder alone, and InterTune respects it.

Anything Android recognises as audio is picked up, apart from `.m3u` playlist files. A handful of types Android does not recognise are added on top: `.dsf`, `.dff`, `.xm`, `.mod`, `.tta`, `.ape` and `.wv`.

The app needs permission to read audio files on your device. Without it, the Scan button reads "The local media scanner requires storage permissions to function", and tapping it asks for the permission. The Folders tab shows a red bar instead: "Storage permissions is required for local media. Click to grant permission."

## Scanning manually

Under **Manual scanner**, tap **Scan**. Playback pauses while a scan runs, and the button turns into a Cancel button so you can stop it. Two checkboxes sit underneath.

**Rescan the entire library and reload all songs' metadata.** Leave it unticked and the scan is quick: new files get added, but the title, artist and album of songs already in your library are left alone. Tick it and every song is read again from scratch, which is slower but picks up tags you have edited since. The tick is not remembered, it clears again when you leave the screen.

**Try to link local files' artists with ones on YouTube Music.** Off by default, and best left that way. It searches YouTube Music for each of your artists, so how well it works depends on their search and on how your files are tagged. Artists with similar names get mixed up, and artists who are not on YouTube Music will not be found at all. It can take a long time and use a lot of battery on a big library. Unlike the rescan tick, this one is remembered. To undo it, untick it first, then run a scan with the rescan box ticked.

## Scan automatically

**Scan automatically** is on by default: "Periodically refresh local media songs and custom downloads folders when opening the app". It runs at most once every 11 hours, and only once you have finished the first-run setup.

It behaves like a scan with the rescan box left unticked, which the app spells out on screen: "For local media, automatic scanning does not reload the metadata of existing songs. Use the manual scanner with the rescan checkbox selected if you also wish to refresh metadata."

If you left the YouTube artist linking box ticked, the automatic scan runs that slow job too.

## Additional scanner settings

These decide how the scanner tells one song from another, so it does not add the same song twice.

**Configure scanner sensitivity.** Three choices: "Match title", "Match title and artists" (the default), and "Match title, artists, albums".

**Strict file names.** Off by default. The app describes it as: When enabled, file names will NOT be ignored. Ex. "Song.ogg" will be a different song from "Song.flac". Scanner sensitivity preference will still apply. It looks at the file name and its extension, not at the folder the file is in.

**Match file paths instead of metadata.** Off by default. This throws out all the matching above and goes purely by where the file sits on disk. Turning it on greys out the other two. It means the same song in two folders stays as two songs, but it also means moving files around will confuse the app.

**Metadata extractor.** This is the part that reads the tags out of your files. "Use integrated TagLib metadata extractor" is the default and is the safe choice. "(BETA) Use MediaStore system scanner and extractors" hands the job to Android, which is faster but gets some tags wrong. The third option, the FFmpeg one, needs the "full" build and is greyed out otherwise, and its own label warns: Please only use the "full" variant of the app. The "core" variant could cause issues with song tracking. Switching extractor does not re-read your existing songs, so follow it with a scan with the rescan box ticked.

### Common problems

- **Two different songs are showing up as one.** For example "Bad Habits" by Ed Sheeran and "Bad Habits" by Bring Me The Horizon and Ed Sheeran. This happens when the sensitivity is set to "Match title". Put it back to "Match title and artists", or go up to "Match title, artists, albums" if the two also share an artist.
- **You just added tags to files that had none, and now everything is duplicated.** Scan once on "Match title" so the app connects the new files to the old entries, then switch to "Match title and artists" and scan again. Doing it in that order keeps your play counts and playlists.
- **You keep two copies of a song in different folders and only one shows up.** If the two files have different names, turn on "Strict file names". If the names are the same as well, you need "Match file paths instead of metadata".
- **You restored a backup onto a fresh install and nothing gets scanned.** The app warns about this on **Settings > Backup and restore**: "For fresh installs of the app, custom download and local media scan paths may not work. To resolve this, remove and add them again." Remove your scan folders and add them back.

After changing any of these, scan again with the rescan box ticked, otherwise nothing is re-examined.

## Playing file types Android cannot handle

> [!WARNING]
>
> This is unfinished and experimental.

Android cannot play every audio format. ALAC is the usual example. The "full" build of InterTune ships FFmpeg decoders that can cover some of the gap. Choose one under **Settings > Player and audio > Advanced > Audio decoder**:

- "System decoders only", the default.
- "Prioritize system decoders", which falls back to FFmpeg when Android cannot cope.
- "Prioritize FFmpeg decoders".

Restart the app afterwards. The app says as much: "An app restart is required for changes to take effect".

This setting is not shown at all on the "core" build, because that build does not include the FFmpeg libraries. Not sure which one you have? **Settings > About** shows the version number followed by either "core" or "full".

If you want to build the app yourself, or with your own set of FFmpeg libraries, that work belongs to upstream OuterTune and lives in their [ffMetadataEx](https://github.com/OuterTune/ffMetadataEx) project, which InterTune pulls in as is.

---

Something not behaving as described here? Open an issue at [ItzSkyeYT/InterTune](https://github.com/ItzSkyeYT/InterTune/issues). This is a personal fork, so replies come on a hobby schedule.
