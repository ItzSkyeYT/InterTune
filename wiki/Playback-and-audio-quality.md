<!-- wiki title: Playback and audio quality -->
# Playback and audio quality

Everything here is in Settings > Player and audio, unless it says otherwise.

## Audio quality

Settings > Player and audio > Audio quality. There are three choices.

- **Auto**. This is what you get if you never touch the setting. On Wi-Fi it takes the best version of the song. On mobile data it takes the smallest one, to save your data. (To be exact, it goes by whether your phone counts the connection as one that costs data, so a Wi-Fi network you have marked as metered counts as mobile data here.)
- **High**. Always the best version, including on mobile data.
- **Low**. Always the smallest version, including on Wi-Fi.

InterTune does not pick a bitrate of its own. It asks YouTube which versions of that song exist and takes the biggest or the smallest from that list, so what you actually get depends on the song. When two versions are close in size it leans towards the Opus one, a format that sounds better for the space it takes.

This only affects songs streamed from YouTube. Files already on your phone play exactly as they are.

The same setting decides the quality of a download, at the moment you download it. On Auto that means a download started on mobile data is a small one. A song downloaded while the setting was on Low stays Low. Delete it and download it again to change that.

## Audio normalization

Settings > Player and audio > Audio normalization. On by default. It turns loud songs down so nothing suddenly blares out after a quiet track. It can only turn things down and never up, so everything ends up quieter, sometimes quite a lot quieter. Turn your device volume up to get that back.

Songs stored on your phone are left alone, because the app has no loudness figure for them. YouTube supplies that figure for its own songs.

## Skip silence

Settings > Player and audio > Skip silence, just under Audio normalization. Off by default. When it is on, the player skips over silent stretches instead of playing them, which helps with live recordings and with tracks that have minutes of nothing at the end.

It works by listening for quiet, not by understanding the song, so quiet intros and fade outs can get shortened too. If songs start sounding clipped, turn it back off.

## Fix song volumes

Settings > Player and audio > Fix song volumes, just under Skip silence.

Normalization needs to know how loud a song is, and for some songs that number never arrived from YouTube. The app assumes those songs are loud and turns them down by a fixed amount, which is only a guess, so they can end up a bit louder or a bit quieter than the rest.

Tap **Fix song volumes** and the app looks the real numbers up. The line underneath tells you how many songs still need it, and the setting is greyed out when there is nothing to do. It needs an internet connection. It only looks at songs you have played or downloaded before, and it leaves files on your phone alone. Tap it again while it is running to stop it. If YouTube starts refusing the requests it stops on its own and tells you to try again later. The song playing right now is skipped on purpose and gets picked up the next time you run it.

## Audio offload

Offload is a different way of playing sound, where a dedicated chip in your phone does the work instead of the main processor. It can save battery. It also breaks playback on a lot of phones.

It is off, and it is hidden. Go to Settings > Experimental, scroll down to the Debug section, and turn on **Enable developer settings**. **Enable offload** then appears below it, along with **Gapless offload**, which stays greyed out until Enable offload is on. Turning either of them on or off only takes effect after you close the app and open it again.

Skip silence stops working while offload is actually in use, because the part of the player that removes the quiet bits gets bypassed. Audio normalization is not affected and keeps working either way.

Read [Audio offload](https://github.com/ItzSkyeYT/InterTune/wiki/Audio-offload) before you switch it on. That page lists what goes wrong and on which phones.

## Saving data

Two more settings worth knowing about, both in Settings > Storage.

**Download on Wi-Fi only**, under Downloaded songs, is off by default. Turn it on and downloads wait for Wi-Fi. Ask for one while on mobile data and a message tells you it has been queued, then it starts by itself once you are back on Wi-Fi. Turning the setting on halfway through a download is safe, anything in progress just pauses and carries on later.

**Max cache size**, under Song Cache, is set to Off. Change it to a size and the songs you stream are kept on the phone, so playing the same song again does not spend your data a second time. Once that space is full, the songs you have not played for the longest are dropped first. There is an Unlimited option if you would rather not set a limit. Changing this needs an app restart.
