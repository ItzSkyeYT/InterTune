# Plan — `UpdateChecker.check()` hides a known update after a cold start

Fingerprint: `app/src/main/java/com/dd3boh/outertune/utils/UpdateChecker.kt` + `check` + `logic`

## Root cause

`app/src/main/java/com/dd3boh/outertune/utils/UpdateChecker.kt:76`

```kotlin
val last = store.get(LastUpdateCheckKey, 0L)
val now = System.currentTimeMillis()
if (!force && now - last < MIN_CHECK_INTERVAL_MS) {
    return@withContext _available.value
}
```

The rate limit is keyed on `LastUpdateCheckKey`, which is **persisted in DataStore**
(`UpdateChecker.kt:99`). The value it returns instead of making the request is
`_available.value`, a `MutableStateFlow<Update?>` held on the `@Singleton`
(`UpdateChecker.kt:52`), which is **process memory** and starts at `null` on every cold
start.

Nothing rehydrates `_available`. `check()` is the only writer, and the only other update
state that survives the process is `UpdateAvailableKey` / `LastVersionKey`
(`UpdateChecker.kt:117-120`) — which is what the *badge* reads
(`SearchScreen.kt:91`, `SettingsScreen.kt:79`), while the Updates screen reads the
in-memory flow (`UpdateSettings.kt:70`).

So the persisted half and the in-memory half disagree for up to `MIN_CHECK_INTERVAL_MS`
(6 hours) after every cold start.

## Failure scenario

Preconditions: the user has opted in to update checks (`UpdateCheckEnabledKey = true`).

1. 10:00 — app opens. `MainActivity` calls `updateChecker.check()` (force = false,
   `MainActivity.kt:333`). A newer release is found: `_available` is set,
   `LastUpdateCheckKey = 10:00`, `UpdateAvailableKey = true`, `LastVersionKey` written.
2. The user does not act on it. The app is closed and the process is killed.
3. 11:00 — app reopens. `check()` runs again with force = false. `now - last` is 1 h,
   under the 6 h interval, so it returns `_available.value` — `null` on this fresh
   process. No request is made and no state is populated.
4. The search bar and Settings still show the update badge, because those read the
   persisted `UpdateAvailableKey`. Settings → Updates shows **no** update card, because
   it reads `updateChecker.available`. The "Skip this version" control lives inside that
   card and is therefore unreachable.

Wrong outcome: the app tells the user an update exists in one place and denies it in
another, and the dismiss path cannot be reached, until either 6 hours pass or the user
finds the manual "Check for update" button (which passes `force = true`).

A second, related consequence: after the user actually installs the update, the first
open inside the interval also skips the request, so `clearAvailable()` never runs and the
stale badge survives until the interval elapses.

## The minimal change

Skip the request only when doing so would tell us nothing new — that is, when the answer
is still in memory, or when the persisted flag says there was no update to hold on to:

```kotlin
val knownAvailable = store.get(UpdateAvailableKey, false)
if (!force && now - last < MIN_CHECK_INTERVAL_MS &&
    (_available.value != null || !knownAvailable)
) {
    return@withContext _available.value
}
```

One file, one condition, no new preference key, no API change. `UpdateAvailableKey` is
already imported.

Rejected alternative: persist `versionCode` and `releaseUrl` and rehydrate `_available`
at construction. That is strictly more state to keep in sync and needs two new preference
keys, to fix a case a single extra condition already covers.

Rejected alternative: drop or shorten the rate limit. It exists because the GitHub API
allows 60 unauthenticated requests an hour per IP and everyone behind one carrier NAT
shares that budget (`UpdateChecker.kt:64-70`). It should stay.

## What could regress

- **Request volume.** In the common case (no update pending) `UpdateAvailableKey` is
  false, so the condition is unchanged and the rate limit is exactly as before. The extra
  request only happens while an undismissed update is outstanding, and only once per cold
  start: the first successful check repopulates `_available`, after which the first half
  of the disjunction short-circuits for the rest of the process.
- **Dismissed updates.** `dismiss()` sets `UpdateAvailableKey = false`
  (`UpdateChecker.kt:128-134`), so a dismissed version does not keep the door open.
- **Offline cold starts.** `check()` already returns early when there is no connectivity
  (`UpdateChecker.kt:74`), before this branch is reached.
- **A failed request.** `_available` stays null and `LastUpdateCheckKey` is deliberately
  not stamped (`UpdateChecker.kt:92-95`), so the next app open retries. Bounded by app
  opens, not by a timer.

## Device test plan — Galaxy S25 Ultra

1. Settings → Updates → turn "Check for updates" on. Confirm the update card appears
   (needs a release newer than the installed `versionCode`; otherwise fake it by
   installing an older build).
2. Do **not** dismiss it. Force stop InterTune from Android Settings → Apps, so the
   process really dies rather than being backgrounded.
3. Reopen within 6 hours. **Before the fix:** the badge is on the search bar but
   Settings → Updates shows no card. **After the fix:** the card is there and "Skip this
   version" works.
4. Tap "Skip this version". Force stop, reopen inside the interval, confirm no card and
   no badge, and confirm (via logcat, tag `UpdateChecker`) that no request was made — the
   rate limit must still hold for the dismissed case.
5. Turn update checks off. Force stop, reopen, confirm nothing is fetched at all.
6. With no update pending, force stop and reopen several times inside the interval and
   confirm from logcat that only the first open fetched — the rate limit is intact for
   the ordinary case.

## Verification status

`UNVERIFIED — not compiled.` This environment has no Android SDK (`ANDROID_HOME` and
`ANDROID_SDK_ROOT` are empty and no SDK exists at any of the usual paths), so
`:app:compileCoreDebugKotlin` cannot run. The `:app` module has no JVM unit-test source
set either, so no test was added. The change is a single boolean condition using a symbol
already imported in the file.
