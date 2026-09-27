# InterTune 0.10.9.6

A fix for songs that stop with "Source error (2004): Response code: 403", which some of you got on every song.

- **Songs play again.** Most songs need a visitor id from YouTube. InterTune fetches one when it starts, and when that failed (the "Failed to get visitorData." message when opening the app) or YouTube had stopped accepting the one it had, every song fell back to a source YouTube cuts off, and stopped with the 403. It now takes a new id from YouTube's own answers when it needs one, asks again, and keeps the one that works.
- **When YouTube does refuse a song**, the error says what YouTube said, such as "Sign in to confirm you're not a bot", instead of the 403.
- **Copied error reports** list what each source answered, so reports like these are quicker to sort out.

Nothing else changed.
