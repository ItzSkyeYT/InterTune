# Announcements

Announcements live in the same gist as polls, in a list called `announcements`. The app shows the first one that applies to it and that this phone has not dealt with yet, as a banner at the top of Home. Tapping the banner opens it full screen. Opening it, pressing any of its buttons or closing the banner deals with it for good on that phone.

Announcements exist from 0.10.9.5 (versionCode 89). Older versions only read `polls` and ignore the rest of the document.

## Example

```json
{
  "polls": [],
  "announcements": [
    {
      "id": "discord-2026-09",
      "banner": "InterTune has a Discord server",
      "title": "Come say hi on Discord",
      "body": "Talk about the app, share what you're listening to, and see what's coming **before anyone else**.\n\n- Ask for help\n- Suggest features\n- Report bugs with screenshots",
      "image": "https://raw.githubusercontent.com/ItzSkyeYT/InterTune/visionos-fix/fastlane/metadata/android/en-US/images/tenInchScreenshots/01.jpg",
      "actions": [
        { "label": "Join the server", "url": "https://discord.gg/68jmqhMjXk" }
      ],
      "minVersionCode": 89,
      "expiresAt": "2026-12-31",
      "translations": {
        "fr": {
          "banner": "InterTune a un serveur Discord",
          "title": "Venez dire bonjour sur Discord",
          "actions": [{ "label": "Rejoindre le serveur" }]
        }
      }
    }
  ]
}
```

## Fields

Only `id`, `banner` and `title` are needed.

| Field | What it does |
|---|---|
| `id` | Any text. A phone that dealt with an id never shows it again, so a new id shows an announcement again to everyone. |
| `banner` | The line on Home. Cut after two lines, so keep it short. |
| `title` | The heading on the page. |
| `body` | The text. A blank line starts a new paragraph. A line starting `- ` is a list item, `# ` a heading, and `**words**` are bold. Web addresses become links, bare ones like `discord.gg/abc` too. |
| `image` | The picture at the top, kept at its own shape between very wide (2.4:1) and 4:5. A wide picture such as 1600x900 works best. |
| `images` | More pictures, in a row under the text that scrolls sideways. Without `image`, the first of these goes at the top. Ten pictures at most in all. Tapping any picture shows it whole. |
| `actions` | Up to three buttons, each `{ "label": ..., "url": ... }`. The first is the big one. Every button also closes the announcement. With no buttons there is a single "Got it". |
| `actionLabel`, `actionUrl` | The older single button. Still read, and ignored when `actions` is there. |
| `startsAt`, `expiresAt` | When to start and stop showing it. `"2026-10-01"` is a day in UTC, and `expiresAt` runs to the end of that day. `"2026-10-01T18:00:00+02:00"` is an exact time. Milliseconds still work, and so does a timestamp in seconds. |
| `minVersionCode`, `maxVersionCode` | Which builds show it. 0.10.9.5 is 89, 0.11 is 90. |
| `translations` | The same words in other languages, keyed by language (`"fr"`) or language and region (`"pt-BR"`). Each can have `banner`, `title`, `body` and `actions`; anything left out comes from the main text. A translated button can leave out its `url`, and takes the address of the button in the same place. The phone's own language picks one, and anyone else gets the main text. |

## Worth knowing

- A mistake leaves out that one announcement, never the others. A date or version the app cannot read hides it, rather than showing it early or for ever, so if one does not appear, check those first.
- Buttons and pictures take web addresses only. Use `https://`: phones refuse to load pictures over plain `http://`.
- Pictures are downloaded on the phone's connection when the announcement opens. Keep each one well under 1 MB.
- The app checks the gist at most every six hours, so a change takes up to that long to reach phones.
- Only phones with news or questions switched on fetch the document at all (Settings, Privacy and history, Questions and news). Announcements need News from InterTune on. Phones that had said yes to questions before news had its own switch have news on too.
- The first time a phone opens an announcement it sends its id, its title and the app version to Umami, as `poll_view` with type `announcement`. That is the view count on the dashboard. Nothing else is sent.
