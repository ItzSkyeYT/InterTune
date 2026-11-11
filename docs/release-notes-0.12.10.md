# InterTune 0.12.10

_Crash fixes: six ways the app could close on some phones are gone._

## Fixed

- **Downloads waiting for Wi-Fi** no longer close the app after six hours on Android 15 and newer (#130).
- **Shuffle on some playlist pages** no longer closes the app, and neither do a full phone or a stray part in a sign-in cookie (#128).
- **Phones without a file or folder picker** say so instead of closing the app (#120, #121, #126), and a phone that refuses the network listener no longer stops the app opening (#123).
- **A play press from a headset, a car or the widget** with the app closed no longer gets the app closed by Android (#98, #129, #132).
- **Behind a VPN**, the player says YouTube wants a sign-in to play from this network, with Log in and Proxy settings beside Retry, instead of an unknown error (#124).
