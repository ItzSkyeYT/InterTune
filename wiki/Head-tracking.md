<!-- wiki title: Head tracking -->
# Head tracking

With [spatial audio](Spatial-audio) set to Headphones, the music sits in front of you. Head tracking keeps it there when you turn: the band stays where it was instead of turning with you. That is most of what makes it sound like a room rather than like headphones.

Settings > Player and audio > Where the sound is > Head tracking. It only appears when spatial audio is set to Headphones.

## Why it is probably greyed out

Two things have to be true, and the second one usually is not.

**Your headphones need a head tracker.** Sony's WH-1000XM5 and XM6, the WF-1000XM5, AirPods Pro and several others have one. Most headphones do not.

**Your phone has to publish it.** This is where it breaks. Android reserves head trackers for the system, and separately, most manufacturers never switch on the part of Android that reads them from your headphones. When they do not, your headphones send their orientation to the phone and nothing listens. Google's Pixels generally work. Samsung's Galaxy S25 and earlier do not, and the S26 does, on the same version of Android.

You can check for yourself with a computer and USB debugging:

```
adb shell dumpsys sensorservice | grep -c dynamic_sensor_meta
```

`0` means your phone cannot see external sensors at all, so head tracking is not possible on it and no app can change that.

If your headphones definitely have head tracking and InterTune still says otherwise, please [open an issue](https://github.com/ItzSkyeYT/InterTune/issues/new?labels=head+tracking). Include your headphones, your phone, your Android version, and the output of that command.

## Calibrate first

Headphones have a gyroscope and nothing that knows which way is north, so the direction they report slowly slides, usually about a degree a second. Left alone, the music wanders off to one side within a minute or two.

Tap **Calibrate**, then sit comfortably, look straight ahead and keep still for thirty seconds. It measures how fast yours drifts and corrects for it from then on. Worth doing again if you change headphones.

The soundstage also recentres itself whenever you have held still for six seconds. That is why holding a deliberate turn eventually makes the music follow you, and it is the price of having nothing to hold the direction steady.

## Recentre

In the player, tap the three dots and choose **Recentre** to put the sound back in front of wherever you are facing. Useful after a knock or a reconnection.

## Tracking response

Sound takes time to reach you, almost all of it Bluetooth, so InterTune aims slightly ahead of where your head is.

- **Smooth** never guesses. It never overshoots and always feels slightly behind.
- **Balanced** takes about half the gap.
- **Quick** and **Instant** feel immediate, at the cost of swinging a little past when you stop turning.

If none of them feel right, **Tracking lead** underneath sets the number directly, in milliseconds. It should match the real delay to your ears, which depends on which Bluetooth codec your headphones negotiated and which the phone does not report. Play something, turn your head, and drag until the sound stays put.

## Follow tilting too

By default only turning moves the sound. Turn this on and looking up, down or sideways moves it as well. It costs a little more processing and restarts the current song when you switch it.

Up and down is the weakest part of the effect, for the same reason described in [spatial audio](Spatial-audio): height depends on the shape of your own ears.

## See also

- [Spatial audio](Spatial-audio)
- [Troubleshooting](Troubleshooting)
