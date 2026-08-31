<!-- wiki title: Downloading Songs -->
# Downloading songs

InterTune can save songs to your phone so they still play when you have no connection. This page covers where those files end up, how to use a folder you can open yourself, and how to bring in files you got somewhere else.

To download a song, open its menu and tap **Download**. While it is working the menu says **Downloading**, and once it is done the same entry turns into **Remove download**.

The settings for all of this are in Settings > Storage, under the "Downloaded songs" heading. If you went through the setup screens the first time you opened the app, the "Song downloads" step is the same folder setting.

## The three kinds of downloads

1. **Internal downloads.** Every song you download inside InterTune is saved here. It is the app's own private storage, so a file manager cannot see it (only a rooted phone can). These are the downloads you get if you never touch any of the folder settings.
2. **External downloads.** A folder on your normal storage (internal storage or an SD card) that you pick under **Set external download folder…**. You can open it with any file manager. Downloading a song does not put it here on its own. This folder holds downloads you have moved out of internal storage, plus any files you copy in yourself. InterTune writes into this folder and deletes files from it, so give it an empty folder used for nothing else.
3. **Extra import folders.** Folders InterTune only reads songs from, set under **Configure extra import locations…** in the Advanced section. It never adds or changes files there, with one exception: if you tap **Remove download** on a song that lives in one of these folders, the file itself is deleted.

InterTune will not accept a download folder that clashes with your local media scan folders. If you get "Invalid selection", pick a different one.

The size of your internal downloads is shown straight away. Tap **Tap to calculate download size** to work out the other two, which can take a moment on a large library.

<img src="https://github.com/ItzSkyeYT/InterTune/blob/visionos-fix/assets/wiki/download_settings.png?raw=true" alt="Download settings" height="400">

(The picture was taken before the Wi-Fi setting below was added, so it is missing from the top of the card.)

## Download on Wi-Fi only

Settings > Storage > **Download on Wi-Fi only** holds downloads back until you are on a connection Android treats as unmetered. A Wi-Fi network you have marked as metered does not count, and an unmetered ethernet or hotspot connection does.

If you ask for a download while this is on and you are on mobile data, the song is queued instead of failing, and InterTune tells you "Queued. Will download when Wi-Fi is available". It starts on its own once you are back on Wi-Fi. Turning the switch on or off applies straight away, including to downloads that are already queued or running, so nothing is lost by changing your mind part way through.

## Clearing downloads

**Clear all downloads** only removes internal downloads. Files in the external download folder and in extra import folders are left alone, and you remove those yourself, either with **Remove download** in the song's menu or with a file manager. This is on purpose, so one tap cannot wipe a folder of files you collected by hand.

## Rescanning download folders

InterTune does not notice changes you make to the external download folder or the extra import folders with a file manager straight away. If you add, remove, rename or move files there, use Settings > Storage > Advanced > **Rescan download folders…** to bring the app's database back in line.

There is also an automatic pass. With Settings > Local media > **Scan automatically** switched on (it is on by default), InterTune refreshes the download folders when you open the app, though not more often than about once every 11 hours. The manual rescan is there for when you do not want to wait.

## Moving internal downloads to the external folder

First set an external download folder. Then, under Settings > Storage > Advanced, tap **Migrate downloads to external storage…**. This moves every internal download into that folder, and it cannot be undone. It can take a while on a big library, so leave the app alone until it finishes.

## Importing files from yt-dlp or anywhere else

You can hand InterTune files you downloaded elsewhere, as long as the song's YouTube id is in square brackets in the file name.

For example, `my song name blah blah [uwbf82ha].opus` is registered as a download for the song with the id `uwbf82ha`. InterTune reads the last pair of square brackets in the name.

To import:

1. Copy the files into your external download folder, or into a folder you have added under **Configure extra import locations…**. Subfolders are included, so you can keep them organised.
2. Go to Settings > Storage > Advanced and tap **Rescan download folders…**.

This system links files to songs InterTune already knows about. If what you actually want is to add a whole folder of music to your library, you want the local media scanner instead. See [Local Media](https://github.com/ItzSkyeYT/InterTune/wiki/Local-Media).

### If a file does not show up as downloaded

- **The song is not in the app's database yet.** Importing only attaches a file to a song InterTune has already seen, so a file on its own does nothing. Search for the song and add it to the queue, then rescan. For a lot of files at once it is quicker to put every song in a playlist, add the whole playlist to the queue, and then rescan.
- **The name is wrong.** The id has to be inside square brackets, and it has to be the right id for that song.
- **The file is in the wrong place.** It has to sit in the external download folder or in a folder you added as an extra import location. Nothing else is looked at.
- **The folder is not on your phone's own storage.** InterTune needs a folder on internal storage, an SD card or a USB drive. A folder handed over by another app, such as a cloud drive, does not work.
- **InterTune cannot play the file.** The file extension itself is not checked, but the app still has to be able to decode what is inside, so stick to normal audio files.
- **The song is one of your local media songs.** Local songs are already tracked by the local media scanner and are skipped here.

## Hiding the folder from other apps

Your external download folder can be picked up by other music apps on the phone, usually through Android's shared media index. Putting an empty file named `.nomedia` in the folder keeps it out of that index, which hides it from apps that do not deliberately look inside `.nomedia` folders. InterTune still finds its own songs there, so downloads keep working normally.

InterTune does not create that file for you. Make it yourself with a file manager.

---

Something on this page wrong or missing? Open an issue at [github.com/ItzSkyeYT/InterTune/issues](https://github.com/ItzSkyeYT/InterTune/issues).
