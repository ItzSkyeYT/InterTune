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
| `minVersionCode`, `maxVersionCode` | Which builds show it. 0.10.9.5 is 89, 0.10.9.6 is 90, 0.11 is 91, 0.11.1 is 92, 0.11.5 is 93. |
| `minSdk`, `maxSdk` | Which Android versions show it, as API levels, both ends included. See "Some phones only" below. |
| `devices` | Which devices show it: a list of names. See "Some phones only" below. |
| `translations` | The same words in other languages, keyed by language (`"fr"`) or language and region (`"pt-BR"`). Each can have `banner`, `title`, `body` and `actions`; anything left out comes from the main text. A translated button can leave out its `url`, and takes the address of the button in the same place. The first language on the phone's list that has a translation, or that the main text is written in (see `language`), wins; a phone with neither gets the main text. |
| `language` | The language the main text itself is written in. Absent means English (`"en"`). A phone whose language list reaches this language before any translated one gets the main text, not a later translation further down the list. A translation keyed with this same language, alone or with a region, still wins at that language. Only from 0.11 (versionCode 91): 0.10.9.5 and 0.10.9.6 (89, 90) do not read this field, and simply take the first phone language that has a translation. |

## Some phones only

An announcement or a question can be kept to certain Android versions, certain devices, or both. These three fields work the same on the entries of `polls`.

```json
{
  "id": "samsung-glass-2026-10",
  "banner": "A question for Samsung phones on Android 16",
  "title": "How does Liquid glass run on yours?",
  "minVersionCode": 92,
  "minSdk": 36,
  "devices": ["samsung"]
}
```

- `minSdk` and `maxSdk` are Android API levels: 24 is Android 7.0, 26 is 8.0, 28 is 9, 29 is 10, 30 is 11, 31 is 12, 33 is 13, 34 is 14, 35 is 15, 36 is 16, 37 is 17. Leave one out for no limit on that side.
- `devices` is a list, and one entry matching is enough. An entry is compared, ignoring case, with the phone's manufacturer, its brand, its model, its code name, and manufacturer and model together, and has to be one of them whole: `"samsung"` is every Samsung, `"SM-S938B"` one model. A `*` stands for any run of characters, so `"SM-S93*"` is a family of models and `"pixel 10*"` every Pixel 10. `"pixel"` alone matches nothing, since no phone is called just that.
- With both, both have to hold.
- **Always give such an entry `minVersionCode` 92 or higher.** Versions up to 0.11 do not know these fields, ignore them, and would show the entry to everyone. 92, which is 0.11.1, is the first build that reads them.
- An audience the app cannot read (a number in quotes, a single name instead of a list) hides the entry, like a date it cannot read.

The phone decides by itself from the document everyone gets. Nothing about the phone is sent anywhere to be matched, and answers still carry only the app version.

## Questions: a follow-up after an answer

A choice of a question can carry `then`: a second question, asked right after the first in the same card, to whoever picked that choice. "Keep it, but polish the details", then "Which details?", often with boxes to tick. Each picked choice that has one asks it next, in the order of the choices, with Back to the question before, and then one Send for the whole answer.

```json
{
  "id": "look-2026-11",
  "banner": "Quick question about InterTune's look",
  "question": "Should InterTune get a new look?",
  "options": [
    { "id": "keep", "label": "Keep it as it is" },
    {
      "id": "polish",
      "label": "Keep it, but polish the details",
      "then": {
        "question": "Which details?",
        "body": "Tick all that apply.",
        "multiple": true,
        "options": [
          { "id": "spacing", "label": "Spacing and alignment" },
          { "id": "icons", "label": "Icons" },
          { "id": "other", "label": "Something else", "other": true, "placeholder": "What would you polish?" }
        ]
      }
    },
    { "id": "new", "label": "A whole new look" }
  ]
}
```

- `then` takes `question` (needed), `body`, `multiple` and `options`. Its choices are written like a question's own: `id`, `label`, and `other` and `placeholder` if wanted.
- One level only: a choice inside `then` has no `then` of its own.
- It is sent in the same answer as the first question. For the example, somebody who picked polish and ticked icons and spacing sends `answer_id` `polish,polish/icons,polish/spacing` (a follow-up's picks as `<choice id>/<follow-up choice id>`), with `answer` holding the first question's label only. Words typed for an Other go in `text` as before, one box to a line if there are two. So no choice `id` may hold `/` or `,`.
- A follow-up can be left with nothing picked, so it never costs the first answer. An Other picked in it needs its words.
- Only builds with follow-ups show them: debug and preview builds since 4 Nov 2026 (`Unreleased.POLL_FOLLOW_UPS`), and releases once it is turned on, 0.13 at the earliest. Every other build ignores `then` and sends the first answer alone, so one poll works for all of them. A `then` the app cannot read is left out, never the question.
- `tools/polls/gist.py check` refuses a choice id used twice in one question, an id holding `/` or `,`, a `then` with no question or no choices, and a `then` inside a `then`.

## Trying a question first

A debug or preview build reads a poll document from the phone itself while there is one: `files/poll-stand-in.json` in the app's own storage. Start from the live document (`tools/polls/gist.py fetch`, which keeps the announcements and the playback block as they are), add the question, then:

```
adb push polls.json /data/local/tmp/poll-stand-in.json
adb shell run-as dev.skye.intertune.debug cp /data/local/tmp/poll-stand-in.json files/
```

With Questions on, the question shows at the next check (Settings, Questions and news, or the next start). While the file is there nothing is fetched and nothing is sent: an answer goes to the log instead (`adb logcat -s PollChecker`), so a test never lands among the real answers. Let me be asked again shows an answered question again. `adb shell run-as dev.skye.intertune.debug rm files/poll-stand-in.json` ends it. Releases never read it.

## Worth knowing

- A mistake leaves out that one announcement, never the others. A date or version the app cannot read hides it, rather than showing it early or for ever, so if one does not appear, check those first.
- Buttons and pictures take web addresses only. Use `https://`: phones refuse to load pictures over plain `http://`.
- Pictures are downloaded on the phone's connection when the announcement opens. Keep each one well under 1 MB.
- The app checks the gist at most every six hours, so a change takes up to that long to reach phones.
- Only phones with news or questions switched on fetch the document at all (Settings, Privacy and history, Questions and news). Announcements need News from InterTune on. Phones that had said yes to questions before news had its own switch have news on too.
- The first time a phone opens an announcement it sends its id, its title and the app version to Umami, as `poll_view` with type `announcement`. That is the view count on the dashboard. Nothing else is sent.
