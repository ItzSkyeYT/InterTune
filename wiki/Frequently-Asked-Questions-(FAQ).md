<!-- wiki title: Frequently Asked Questions (FAQ) -->

# FAQ

### Q: Songs stop partway through with "Source error (2004): Response code: 403"

This is the bug InterTune exists to fix, so on InterTune it should not happen.

What is going on: YouTube serves audio to different kinds of "client". A client is just what sort of app YouTube thinks is asking, like a phone, a TV or a games console. In August 2026 YouTube started demanding an extra security token from the two clients OuterTune asks as. A client that owes that token and does not send one gets about 1 MB of audio, and then the server refuses everything after it. The limit is on how much data, not how much time, so the better your audio quality the sooner the song cuts out. It works out at somewhere between half a minute and a minute of music.

InterTune adds another client, an Apple Vision Pro headset. YouTube does not demand the token from that one, so it hands over the whole track. InterTune tries it before falling back to the iPhone client that gets cut off. Downloads go through the same code, so they are fixed too.

Upstream OuterTune still has this. They stopped supporting YouTube Music in February 2026 and marked the error Won't Fix. Their tracking issue is [OuterTune#735](https://github.com/OuterTune/OuterTune/issues/735), which is on upstream's own tracker, not this fork's.

If you are on InterTune and still see this, please report it at [InterTune issues](https://github.com/ItzSkyeYT/InterTune/issues) and say which song it was and what Settings > Player and audio > Audio quality is set to. InterTune is a one person hobby fork, so a reply may take a while.

### Q: I get "Sign in to confirm you're not a bot"

That check is satisfied by a visitor id, which is a token YouTube hands out to signed out visitors. InterTune asks for one the first time it needs it and then saves it. You do not need to be signed in for this to work.

If you see the bot message, that token is usually missing. You will normally have seen "Failed to get visitorData." pop up when the app opened. Check your connection and restart the app.

If it keeps happening, go to Settings > Experimental and tap the entry that starts with "Delete VisitorData". That throws the saved token away, so the app fetches a fresh one. The entry says on screen that it is not recommended for logged in users, so treat it as a last resort.

### Q: Age restricted songs, and songs only my account can play, still will not play

That part is not fixed, and it is a deliberate trade off.

The clients that will hand over a full track are ones InterTune can only use signed out. So every request for the actual audio goes out anonymously, even while you are signed in to the app. YouTube sees a signed out stranger asking, and anything it will only give to a signed in account is likely to fail.

Signing in is still worth doing. Only playback is affected. Your library, playlists, likes and recommendations all use your account as normal.

### Q: Why can't I modify my synced playlist when I am logged in?

InterTune has two modes for synced playlists, at Settings > Account and sync > Sync mode.

- "Read write (Also allow changes to remote content)" lets you change synced playlists. This is the default.
- "Read only (View remote content)" blocks all changes to synced playlists.

If you are already on read write and still cannot edit it, the playlist itself is probably not editable. Some YouTube Music playlists cannot be changed by you at all, for example the ones YouTube builds for you.

For more about the different kinds of playlist, see [Music library](https://github.com/ItzSkyeYT/InterTune/wiki/Music-library#playlist-types).

### Q: How do I scrobble music to LastFM, LibreFM, ListenBrainz or GNU FM?

InterTune does not do this itself. Use a separate scrobbler app that watches whatever is playing on your phone. [Pano Scrobbler](https://play.google.com/store/apps/details?id=com.arn.scrobble) is one option.

### Q: Why is InterTune not showing in Android Auto?

Android Auto hides apps that did not come from the Play Store until you turn on a hidden setting.

1. Open Android Auto's settings and tap the version number at the bottom several times. That turns on its developer settings.
2. Open the three dots menu at the top right and tap "Developer settings".
3. Turn on "Unknown sources".

### Q: Can I install InterTune on top of OuterTune?

No. InterTune is a separate app with its own name and its own signing key, so Android treats it as unrelated to OuterTune. It installs next to OuterTune rather than replacing it, and it cannot update an existing OuterTune install.

To bring your data across, make a backup in OuterTune at Settings > Backup and restore, then restore that file in InterTune at the same place. InterTune is built on OuterTune 0.10.1, so a backup made by a much newer OuterTune may be turned away with "Could not restore incompatible database".
