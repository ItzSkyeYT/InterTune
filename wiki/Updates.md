<!-- wiki title: Updates -->
# Updates

InterTune is not on the Play Store, so it cannot update itself the way a normal app does. What it can do is check GitHub every so often and tell you when a newer version has come out. You download and install it yourself.

It is off until you turn it on.

## Turning it on

The last page of first time setup shows a card titled "Tell you about updates?" with two buttons, "No thanks" and "Yes, check". Whichever you pick is remembered, and you are not asked again.

If you are updating from an older InterTune, or you restored a backup made before this existed, setup is already done but the app still has no answer on file for you. So it asks the same question once, in a pop up, next time you open it.

You can change your mind at any point. Go to Settings > Updates and use the switch called "Tell me about updates". Turning that switch on runs a check straight away, so you find out now rather than in six hours.

## What it does when it is on

Each time you open InterTune it asks GitHub about the newest InterTune release. It will not do this more than once every six hours, so opening the app ten times in an afternoon still only means one check.

It looks at InterTune's own releases at [github.com/ItzSkyeYT/InterTune](https://github.com/ItzSkyeYT/InterTune). It does not look at [OuterTune](https://github.com/OuterTune/OuterTune), the project InterTune was forked from. Releases marked as a draft or a pre-release are skipped, so half finished test builds are never offered to you.

If the check fails, because your phone is offline or GitHub is having a bad day, the app says nothing at all. It also does not count that as a check, so it just tries again next time you open the app.

## Why it asks first

The check is a request to GitHub's servers, and GitHub is not us. They can see your IP address and roughly what time you opened your music player. That is not a lot, but it is not nothing, and it is not something a music player needs to do in order to play music. So it is your call, and the answer is no unless you change it.

## It never installs anything

There is no automatic update and no "install now" button.

When there is a newer version, Settings > Updates gains a section called "Available" with a row reading "Version 0.10.3 is available", or whatever the new version happens to be. Tapping that row opens the release page in your browser, where you can read what changed and download the file yourself if you want it. The row says as much on screen: "Opens the release page so you can read what changed and download it yourself. InterTune never installs anything on its own."

This is deliberate. For one app to install another, Android makes you grant it the "install unknown apps" permission, which is a lot of trust to hand over for a small convenience.

### If the "Available" section is not there

It is worked out fresh each time you open the app, and it is not saved. Two things stop it showing up:

- The app checked less than six hours ago, so this time it skipped the check.
- The switch is off, so the automatic check does nothing at all.

Either way, tap "Check for updates" and the section will appear if there is anything to show.

## Skipping a version

In that same "Available" section there is a row called "Skip this version". Tap it, the section disappears, and that particular version is never mentioned again. It is per version, so when a later one comes out you will still hear about that one.

Worth knowing: after skipping, "Check for updates" will keep saying "No updates available" until something newer than the version you skipped is released. As far as the app is concerned, there is nothing left to tell you.

## Checking by hand

Settings > Updates has a row called "Check for updates". Tapping it checks straight away and ignores the six hour gap. While it is working the row greys out and the words "Checking for updates" appear underneath it. If there is nothing new, a short message pops up saying "No updates available".

Two things to be aware of:

- It works even when the switch is off. Tapping it does contact GitHub, whatever the switch says.
- A check that fails also shows "No updates available", because the screen cannot tell "GitHub says you are up to date" apart from "GitHub never answered". If you are not sure, check your connection and try again.

## Which version am I running?

Settings > Updates, at the bottom, under the heading "Application information". The row is called "Installed version" and shows the version name with a number in brackets after it, like 0.10.2 (79). The number in brackets is the build number, and that is the one the update check actually compares. Quote both if you are reporting a problem at [github.com/ItzSkyeYT/InterTune/issues](https://github.com/ItzSkyeYT/InterTune/issues).
