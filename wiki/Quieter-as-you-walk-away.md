<!-- wiki title: Quieter as you walk away -->
# Quieter as you walk away

Treats your phone as the thing the music is coming from. Walk to the other end of the house and it fades; come back and it returns.

Settings > Player and audio > Where the sound is > Quieter as you walk away.

It pairs with [spatial audio](Spatial-audio) and [head tracking](Head-tracking): set the phone down facing you, recentre, and the music genuinely behaves like a speaker in the room.

## How it knows

From how strong the Bluetooth signal between your phone and your headphones is. That sounds unreliable, and individual readings are: your own body between the two is worth as much signal as walking several metres, so simply turning round throws them about.

What saves it is that the interference is even-handed. The middle of a batch of readings barely moves while distance genuinely does. Measured on one pair of headphones, sitting beside the phone read about thirty decibels stronger than standing at the far end of a home, while something in the room that never moved varied by two.

So it takes ten seconds of readings at a time and uses the middle one, and the whole range moves the volume by about eight decibels. Deliberately gentle: music that lurched every time you turned round would be worse than not having this at all. It never mutes, and it only measures while something is playing.

## Check it works where you are

With the setting on, a **Bluetooth signal log** appears underneath it. That is a hundred second test that tells you whether distance can be measured in your home. It reads each step out loud, so you can put the phone down and follow the voice: sit still, turn on the spot, walk away, stay there, walk back.

The step that matters is turning on the spot. If turning moves the reading as much as walking away does, there is nothing to measure where you are and the feature will not behave.

## Permission

Turning it on asks for Bluetooth scanning permission. InterTune declares it as never used for location, and nothing here wants to know where you are: the only question is how strong a signal from headphones you have already connected happens to be. Nothing is sent anywhere.

## See also

- [Spatial audio](Spatial-audio)
- [Head tracking](Head-tracking)
