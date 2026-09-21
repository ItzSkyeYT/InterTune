package com.dd3boh.outertune.constants

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

/**
 * Appearance
 */
val DynamicThemeKey = booleanPreferencesKey("dynamicTheme")
val HighContrastKey = booleanPreferencesKey("highContrast")
val PlayerBackgroundStyleKey = stringPreferencesKey("playerBackgroundStyle")
// playerGlass was the separate "vivid player background" switch, folded into
// PlayerLiquidGlassKey. The stored value is left alone rather than migrated: it is a boolean
// nobody reads now, and deleting user data to tidy a key is not worth it.
val PlayerGlassIntensityKey = floatPreferencesKey("playerGlassIntensity")
val PlayerLiquidGlassKey = booleanPreferencesKey("playerLiquidGlass")
val DarkModeKey = stringPreferencesKey("darkMode")
val PureBlackKey = booleanPreferencesKey("pureBlack")
val BackAnimationsKey = booleanPreferencesKey("backAnimations")
val ShowLikedAndDownloadedPlaylist = booleanPreferencesKey("showLikedAndDownloadedPlaylist")
val SwipeToQueueKey = booleanPreferencesKey("swipeToQueue")
val FlatSubfoldersKey = booleanPreferencesKey("flatSubfolders")
val TabletUiKey = booleanPreferencesKey("tabletUi")

val EnabledTabsKey = stringPreferencesKey("enabledTabs")
val EnabledFiltersKey = stringPreferencesKey("enabledFilters")
val DefaultOpenTabKey = stringPreferencesKey("defaultOpenTab")
val SlimNavBarKey = booleanPreferencesKey("slimNavBar")

/**
 * Content
 */
const val SYSTEM_DEFAULT = "SYSTEM_DEFAULT"
val YtmSyncKey = booleanPreferencesKey("ytmSync")
val YtmSyncContentKey = stringPreferencesKey("ytmSyncContent")
val YtmSyncModeKey = stringPreferencesKey("ytmSyncMode")
val YtmSyncConflictKey = stringPreferencesKey("ytmSyncConflict")
val LikedAutoDownloadKey = stringPreferencesKey("likedAutoDownloadKey")
val ContentLanguageKey = stringPreferencesKey("contentLanguage")
val ContentCountryKey = stringPreferencesKey("contentCountry")
val ProxyEnabledKey = booleanPreferencesKey("proxyEnabled")
val ProxyUrlKey = stringPreferencesKey("proxyUrl")
val ProxyTypeKey = stringPreferencesKey("proxyType")

// sync time tracks
// Stored name kept from when this only governed the Quick picks row, so that widening it to
// the whole home screen does not silently reset the choice of anyone already on "Your library".
val QuickPicksSourceKey = stringPreferencesKey("quickPicksSource")
val LastFullSyncKey = longPreferencesKey("lastFullSync")
val LastLikeSongSyncKey = longPreferencesKey("lastLikeSongSync")
val LastLibSongSyncKey = longPreferencesKey("lastLibSongSync")
val LastAlbumSyncKey = longPreferencesKey("lastAlbumSync")
val LastArtistSyncKey = longPreferencesKey("lastArtistSync")
val LastPlaylistSyncKey = longPreferencesKey("lastPlaylistSync")
val LastRecentActivitySyncKey = longPreferencesKey("lastRecentActivitySync")


/**
 * Player & audio
 */
val AudioDecoderKey = intPreferencesKey("audioDecoder")
val AudioQualityKey = stringPreferencesKey("audioQuality")
val AudioOffloadKey = booleanPreferencesKey("enableOffload")

/**
 * Whether InterTune gives up audio focus, so another app's sound can play at the same time.
 *
 * Off by default, and it has to stay that way. Audio focus is also what pauses the music for a
 * phone call, ducks it for a navigation prompt and stops it for an alarm. Turning this on trades
 * all of that away, which is why the description says so in as many words.
 */
val ShareAudioFocusKey = booleanPreferencesKey("shareAudioFocus")

/**
 * Last.fm.
 *
 * The session key does not expire, so it is the whole of the login: present means connected,
 * absent means not. There is no password here and never will be; the browser flow is what
 * produces this value. [LastFmUsernameKey] exists only so the settings screen can say whose
 * account it is without a round trip.
 */
val LastFmSessionKey = stringPreferencesKey("lastFmSession")
val LastFmUsernameKey = stringPreferencesKey("lastFmUsername")

/** Scrobbling can be paused without disconnecting the account. */
val LastFmScrobbleKey = booleanPreferencesKey("lastFmScrobble")
val AudioGaplessOffloadKey = booleanPreferencesKey("enableGaplessOffload")

val MaxQueuesKey = intPreferencesKey("maxQueues")
val PersistentQueueKey = booleanPreferencesKey("persistentQueue")

/**
 * Start playing the restored queue as soon as the app opens, rather than waiting to be told.
 *
 * Off by default and it must stay that way. This is sound without anybody pressing anything, and
 * upstream #1132 asks for it from a car head unit, which is exactly where taking audio focus off a
 * navigation prompt would be worst.
 */
val ResumePlaybackOnLaunchKey = booleanPreferencesKey("resumePlaybackOnLaunch")

val SeekIncrementKey = stringPreferencesKey("seekIncrement")
val SkipSilenceKey = booleanPreferencesKey("skipSilence")
val SkipOnErrorKey = booleanPreferencesKey("skipOnError")
val AudioNormalizationKey = booleanPreferencesKey("audioNormalization")
val AutoLoadMoreKey = booleanPreferencesKey("autoLoadMore")
val KeepAliveKey = booleanPreferencesKey("keepAlive")
val StopMusicOnTaskClearKey = booleanPreferencesKey("stopMusicOnTaskClear")

val PlayerVolumeKey = floatPreferencesKey("playerVolume")
val RepeatModeKey = intPreferencesKey("repeatMode")
val LockQueueKey = booleanPreferencesKey("lockQueue")
val minPlaybackDurKey = intPreferencesKey("minPlaybackDur")
val SleepTimerFadeKey = booleanPreferencesKey("sleepTimerFade")
val SleepTimerFadeDurationKey = intPreferencesKey("sleepTimerFadeDuration")


/**
 * Lyrics
 */
val ShowLyricsKey = booleanPreferencesKey("showLyrics")
val LyricsTextPositionKey = stringPreferencesKey("lyricsTextPosition")
val MultilineLrcKey = booleanPreferencesKey("multilineLrc")
val LyricTrimKey = booleanPreferencesKey("lyricTrim")
val LyricSourcePrefKey = booleanPreferencesKey("preferLocalLyrics")
val LyricFontSizeKey = intPreferencesKey("lyricFontSize")
val LyricClickable = booleanPreferencesKey("lyricClickable")
val LyricKaraokeEnable = booleanPreferencesKey("lyricKaraokeEnable")
val LyricUpdateSpeed = stringPreferencesKey("lyricUpdateSpeed")


/**
 * Storage
 */
val DownloadExtraPathKey = stringPreferencesKey("dlExtraPath") // previously "downloadExtraPath"
val DownloadPathKey = stringPreferencesKey("dlPath") // previously "downloadPath"
val DownloadOnWifiOnlyKey = booleanPreferencesKey("downloadOnWifiOnly")
val MaxImageCacheSizeKey = intPreferencesKey("maxImageCacheSize")
val MaxSongCacheSizeKey = intPreferencesKey("maxSongCacheSize")

/**
 * Automatic backups.
 *
 * Off by default and opt in: a schedule that writes files to a folder is not something to switch on
 * for somebody. The folder is the tree uri the system picker handed back, kept as text because that
 * is what the persisted permission is keyed on. The last run and its result are here so the settings
 * screen can say what happened without sending the user to look in the folder.
 */
val AutoBackupEnabledKey = booleanPreferencesKey("autoBackupEnabled")
val AutoBackupFolderKey = stringPreferencesKey("autoBackupFolder")
val AutoBackupIntervalHoursKey = intPreferencesKey("autoBackupIntervalHours")
val AutoBackupKeepKey = intPreferencesKey("autoBackupKeep")
val AutoBackupLastRunKey = longPreferencesKey("autoBackupLastRun")
val AutoBackupLastResultKey = stringPreferencesKey("autoBackupLastResult")


/**
 * Privacy
 */
val PauseListenHistoryKey = booleanPreferencesKey("pauseListenHistory")
/** No song twice on Home, no version beside its original, nothing just played in Quick picks. */
val TidyHomeRowsKey = booleanPreferencesKey("tidyHomeRows")
/** Order the chosen source's Quick picks by the engine's score, whatever the source. */
val RankWithListeningKey = booleanPreferencesKey("rankWithListening")
/** Captions under engine cards saying why each is there. */
val ShowReasonsKey = booleanPreferencesKey("showReasons")
/** Adventurousness 0 to 100: how much of the engine row is new artists. */
val AdventurousnessKey = intPreferencesKey("adventurousness")
/** The engine row holds only songs new to the listener. */
val NewSongsOnlyKey = booleanPreferencesKey("newSongsOnly")
/** Familiarity 0 to 60: the share of the engine row given to songs heard well lately. */
val FamiliarityKey = intPreferencesKey("familiarity")
/** Build the engine row in the background while another source is showing, to compare a day later. */
val ShadowComparisonKey = booleanPreferencesKey("shadowComparison")
/** A skip before the middle of a liked or often-played song rests it from the engine row for a week. */
val RestSongsISkipKey = booleanPreferencesKey("restSongsISkip")
/** Rests filter the YouTube and library rows too, not only the engine's. */
val RestsEverywhereKey = booleanPreferencesKey("restsEverywhere")
/** The declared context chip above the engine row, kept until changed: 0 Auto, 1 Discover, 2 Favourites, 3 Focus, 4 Chill, 5 Party. */
val ContextChipKey = intPreferencesKey("contextChip")
/** Developer overrides of EngineParams, as one JSON object of name to value. */
val EngineOverridesKey = stringPreferencesKey("engineOverrides")
/** Off freezes the engine's weights and stops impressions; the listen log continues. */
val LearnFromListeningKey = booleanPreferencesKey("learnFromListening")
/** The local day (days since the epoch) and how much of the day's update budget is spent. */
val EngineBudgetDayKey = longPreferencesKey("engineBudgetDay")
val EngineBudgetSpentKey = floatPreferencesKey("engineBudgetSpent")
/** The day (in days since the epoch) and count of related-song refreshes spent, at most ten a day. */
val RelatedRefreshDayKey = longPreferencesKey("relatedRefreshDay")
val RelatedRefreshCountKey = intPreferencesKey("relatedRefreshCount")
val PauseRemoteListenHistoryKey = booleanPreferencesKey("pauseRemoteListenHistory")
val PauseSearchHistoryKey = booleanPreferencesKey("pauseSearchHistory")
val EnableKugouKey = booleanPreferencesKey("enableKugou")
val EnableLrcLibKey = booleanPreferencesKey("enableLrcLib")
val UseLoginForBrowse = booleanPreferencesKey("useLoginForBrowse")


/**
 * Local library
 */
val LocalLibraryEnableKey = booleanPreferencesKey("localLibraryEnable")


/**
 * Local media scanner
 */
val AutomaticScannerKey = booleanPreferencesKey("autoLocalScanner")
val ScannerSensitivityKey = stringPreferencesKey("scannerSensitivity")
val ScannerImplKey = stringPreferencesKey("scannerImpl")
val ScannerStrictFilePathsKey = booleanPreferencesKey("scannerStrictFilePaths")
val ScannerStrictExtKey = booleanPreferencesKey("scannerStrictExt")
val LookupYtmArtistsKey = booleanPreferencesKey("lookupYtmArtists")

val ScanPathsKey = stringPreferencesKey("inclScanPaths") // previously "scanPaths"
val ExcludedScanPathsKey = stringPreferencesKey("exclScanPaths") // previously "excludedScanPaths"
val LastLocalScanKey = longPreferencesKey("lastLocalScan")

/**
 * Experimental settings
 */
val DevSettingsKey = booleanPreferencesKey("devSettings")
val OobeStatusKey = intPreferencesKey("oobeStatus")
val SwipeToSkipKey = booleanPreferencesKey("swipeToSkip")

/**
 * Whether swiping the mini player downwards closes it and stops playback.
 *
 * Default true, which is the long-standing behaviour. Off leaves the mini player pinned: the drag
 * still follows your finger, but letting go springs it back and the music keeps going. The gesture
 * fires easily by accident when reaching for the mini player, and losing playback to a stray swipe
 * is a poor trade for a shortcut.
 *
 * Implemented by withholding the sheet's onDismiss callback rather than by blocking the drag.
 * BottomSheetState.performFling only dismisses when that callback is present and falls back to
 * collapse() when it is not, so there is no second copy of the gesture logic to keep in step.
 */
val SwipeToDismissPlayerKey = booleanPreferencesKey("swipeToDismissPlayer")

/**
 * Whether the transport controls sit inside one shared panel, or stand on their own.
 *
 * Only visible when liquid glass is on, since that panel is the glass slab. Default true keeps the
 * grouped look; off returns to free-standing buttons over the artwork.
 */
val GroupedPlayerControlsKey = booleanPreferencesKey("groupedPlayerControls")


/**
 * Non-settings UI preferences
 */
val SongSortTypeKey = stringPreferencesKey("songSortType")
val SongSortDescendingKey = booleanPreferencesKey("songSortDescending")
val FolderSortTypeKey = stringPreferencesKey("folderSortType")
val FolderSongSortTypeKey = stringPreferencesKey("folderSongSortType")
val FolderSongSortDescendingKey = booleanPreferencesKey("folderSongSortDescending")
val PlaylistSongSortTypeKey = stringPreferencesKey("playlistSongSortType")
val PlaylistSongSortDescendingKey = booleanPreferencesKey("playlistSongSortDescending")
val ArtistSortTypeKey = stringPreferencesKey("artistSortType")
val ArtistSortDescendingKey = booleanPreferencesKey("artistSortDescending")
val AlbumSortTypeKey = stringPreferencesKey("albumSortType")
val AlbumSortDescendingKey = booleanPreferencesKey("albumSortDescending")
val PlaylistSortTypeKey = stringPreferencesKey("playlistSortType")
val PlaylistSortDescendingKey = booleanPreferencesKey("playlistSortDescending")
val LibrarySortTypeKey = stringPreferencesKey("librarySortType")
val LibrarySortDescendingKey = booleanPreferencesKey("librarySortDescending")
val ArtistSongSortTypeKey = stringPreferencesKey("artistSongSortType")
val ArtistSongSortDescendingKey = booleanPreferencesKey("artistSongSortDescending")

val SongFilterKey = stringPreferencesKey("songFilter")
val ArtistFilterKey = stringPreferencesKey("artistFilter")
val ArtistViewTypeKey = stringPreferencesKey("artistViewType")
val AlbumFilterKey = stringPreferencesKey("albumFilter")
val PlaylistFilterKey = stringPreferencesKey("playlistFilter")
val AlbumViewTypeKey = stringPreferencesKey("albumViewType")
val PlaylistViewTypeKey = stringPreferencesKey("playlistViewType")
val LibraryFilterKey = stringPreferencesKey("libraryFilter")
val LibraryViewTypeKey = stringPreferencesKey("libraryViewType")

val PlaylistEditLockKey = booleanPreferencesKey("playlistEditLock")

val SearchSourceKey = stringPreferencesKey("searchSource")

val VisitorDataKey = stringPreferencesKey("visitorData")
val DataSyncIdKey = stringPreferencesKey("dataSyncId")
val InnerTubeCookieKey = stringPreferencesKey("innerTubeCookie")
val AccountNameKey = stringPreferencesKey("accountName")
val AccountEmailKey = stringPreferencesKey("accountEmail")
val AccountChannelHandleKey = stringPreferencesKey("accountChannelHandle")


/**
 * Misc
 */
val LastUpdateCheckKey = longPreferencesKey("lastUpdateCheck")
val LastVersionKey = stringPreferencesKey("lastVersion")
val UpdateAvailableKey = booleanPreferencesKey("updateAvailable")

/** The rest of the last found release, so a restart does not forget it. See UpdateChecker. */
val LastVersionCodeKey = intPreferencesKey("lastVersionCode")
val LastReleaseUrlKey = stringPreferencesKey("lastReleaseUrl")
val LastDownloadUrlKey = stringPreferencesKey("lastDownloadUrl")
val LastAssetSizeKey = longPreferencesKey("lastAssetSize")
val LastChangelogKey = stringPreferencesKey("lastChangelog")

/** Download and open the install prompt without asking first. Off by default. */
val AutoInstallUpdatesKey = booleanPreferencesKey("autoInstallUpdates")

/** Epoch millis before which the update prompt stays quiet, set by "remind me later". */
val UpdateSnoozeUntilKey = longPreferencesKey("updateSnoozeUntil")

/**
 * Whether to look for newer InterTune releases on GitHub.
 *
 * Off by default and opt in. A version check is a request to a third party that reveals roughly
 * when the app is used, which is not something to switch on for somebody.
 */
val UpdateCheckEnabledKey = booleanPreferencesKey("updateCheckEnabled")

/** versionCode the user dismissed. Anything newer than this is still worth raising. */
val DismissedUpdateCodeKey = intPreferencesKey("dismissedUpdateCode")

/**
 * Whether InterTune may ask the occasional question and send the answer.
 *
 * Off by default and opt in, read through rememberNullablePreference so that "never asked" stays
 * distinguishable from "said no". Nothing is fetched and nothing is sent until this is true, which
 * is the whole basis on which the feature can honestly be described to people.
 */
/**
 * How often, in hours, to look for updates and questions while the app is closed. 0 is off.
 *
 * Governs both because they are the same promise to the user: check quietly, tell me when there is
 * something. Two separate schedules would mean two background wakes for one answer.
 */
val BackgroundCheckHoursKey = intPreferencesKey("backgroundCheckHours")

val PollsEnabledKey = booleanPreferencesKey("pollsEnabled")

/** Poll ids already answered. Never asked again, on any device that shares this datastore. */
val AnsweredPollIdsKey = stringSetPreferencesKey("answeredPollIds")

/** Poll ids waved away with the banner's close button. Not answered, but not to be raised again. */
val DismissedPollIdsKey = stringSetPreferencesKey("dismissedPollIds")

/**
 * Announcements the user has closed.
 *
 * Separate from the poll set so that clearing one does not clear the other, and so an announcement
 * id can never collide with a poll id in the same document.
 */
val DismissedAnnouncementIdsKey = stringSetPreferencesKey("dismissedAnnouncementIds")

/** Whether the listen button starts a continuous listen rather than a single one. */
val RecogniseKeepListeningKey = booleanPreferencesKey("recogniseKeepListening")

/** Whether our own playback stops while listening, when it is coming out of the phone's speaker. */
val RecognisePauseOnSpeakerKey = booleanPreferencesKey("recognisePauseOnSpeaker")

/**
 * The build whose walkthrough has already been seen.
 *
 * Not the same as the last build that ran: this only moves when somebody reaches the end of a
 * walkthrough or skips one, so an update that crashes on launch does not silently mark its own
 * features as already explained.
 */
val WalkthroughSeenVersionKey = intPreferencesKey("walkthroughSeenVersion")

/** Epoch millis of the last poll fetch, for the rate limit floor. */
val LastPollFetchKey = longPreferencesKey("lastPollFetch")

/**
 * The last poll document, verbatim.
 *
 * Cached rather than parsed into separate keys because polls are a list and the interesting one
 * depends on what has since been answered. Re-parsing the raw json on restore keeps one source of
 * truth and means a restart inside the rate limit window still shows the right question.
 */
val CachedPollsJsonKey = stringPreferencesKey("cachedPollsJson")

/**
 * Consent to being counted once a day. Off until somebody says otherwise, like every other switch
 * that sends anything anywhere.
 */
/**
 * Whether playback may ask YouTube as the signed-in account. See [PlaybackAuthMode].
 *
 * Defaults to WHEN_REFUSED: costs nothing while everything works, and is the only thing that can
 * play an age gated song for somebody whose account is allowed it.
 */
val PlaybackAuthModeKey = stringPreferencesKey("playbackAuthMode")

/** Whether the unwatched end of the queue re-plans itself. See [AdaptiveQueueMode]. */
val AdaptiveQueueModeKey = stringPreferencesKey("adaptiveQueueMode")

/**
 * Whether the audio chain runs in 32 bit float rather than 16 bit integer.
 *
 * Matters because of the gain stage. Loudness normalisation multiplies every sample, and doing
 * that in 16 bit rounds the result back to 16 bit afterwards, which is avoidable quantisation on
 * every single track. GainAudioProcessor was written to handle float and never received any,
 * because the sink dropped the flag on the floor.
 *
 * Off by default. Float output cannot be offloaded, so on a device that would have offloaded this
 * costs battery, and the difference is small enough that it should be a choice rather than a
 * decision made for everybody.
 */
val HighPrecisionAudioKey = booleanPreferencesKey("highPrecisionAudio")

/**
 * What the audio chain does to the stereo it is given. See SpatialAudioMode.
 *
 * An effect, not fidelity, whichever way it is set. YouTube Music serves stereo only, so anything
 * beyond two channels is invented rather than recovered.
 */
val SpatialAudioKey = stringPreferencesKey("spatialAudio")

/**
 * Whether the soundstage stays put when the listener turns their head.
 *
 * Only means anything alongside SpatialAudioMode.HEADPHONES, and only on a phone that publishes a
 * head tracker at all, which most do not: the sensor is restricted to system uids and most vendors
 * never load the sub-HAL that would create one. Off by default and hidden where nothing is
 * available, rather than shown as a switch that does nothing.
 */
val HeadTrackingKey = booleanPreferencesKey("headTracking")

/**
 * How far apart the binaural renderer's two virtual loudspeakers stand, in degrees either side.
 *
 * Thirty is what a stereo mix is made for. Wider separates the instruments, because a first-order
 * field is blurry enough that two speakers sixty degrees apart land inside one blur, and the cost
 * is a hole in the middle. A tuning knob rather than a feature.
 */
val StageWidthKey = intPreferencesKey("stageWidth")

/** How hard the renderer guesses ahead of the head. See HeadTrackingResponse. */
val HeadTrackingResponseKey = stringPreferencesKey("headTrackingResponse")

/**
 * Written with the current time to ask for a drift calibration. See HeadTracking.
 *
 * A request rather than a value, because the work happens in the service and the button is in the
 * settings, and this is the channel those two already share.
 */
val HeadTrackingCalibrateKey = longPreferencesKey("headTrackingCalibrate")

/** Measured drift of the head tracker, in degrees per second. Zero until calibrated. */
val HeadTrackingDriftKey = floatPreferencesKey("headTrackingDrift")

/**
 * How far ahead of the head to aim, in milliseconds.
 *
 * Should be the real delay between rendering a sample and hearing it, which is mostly whichever
 * Bluetooth codec got negotiated and is not a number an app can find out: the platform reports no
 * latency modes for this route. So it is tuned by ear, once, against the hardware in use.
 */
val HeadTrackingLeadKey = intPreferencesKey("headTrackingLead")

/**
 * Whether tilting the head counts, not only turning it.
 *
 * Costs six more harmonics to convolve, which is a little over half again, and buys the weakest
 * cue a borrowed pair of ears can offer. Worth having as a choice.
 */
val HeadTracking3dKey = booleanPreferencesKey("headTracking3d")

/**
 * Whether walking away from the phone turns the music down.
 *
 * Signal strength is a usable distance measure here, which was worth checking rather than
 * assuming: near and far separate by about thirty decibels, and the body shadowing that makes
 * individual readings jump around is symmetric enough that a median deletes it. See
 * ProximityVolume.
 */
val ProximityVolumeKey = booleanPreferencesKey("proximityVolume")

/**
 * Whether to record how much the listener moves while each song plays.
 *
 * Changes nothing about what is chosen. It exists to find out whether activity says anything the
 * time of day does not already say, before anything is built on the assumption that it does.
 */
val ActivityLogKey = booleanPreferencesKey("activityLog")

val UsageCountEnabledKey = booleanPreferencesKey("usageCountEnabled")

/**
 * The random name this device is counted under this month, and the month it belongs to.
 *
 * Held rather than derived so that the device keeps no long lived secret either: when the month
 * turns the old value is overwritten, not hashed forward, so nothing on the device links this
 * month to the last one.
 */
val UsageCountIdKey = stringPreferencesKey("usageCountId")
val UsageCountPeriodKey = stringPreferencesKey("usageCountPeriod")

/** The last local day counted, which is what keeps this to once a day however often the app opens. */
val UsageCountLastDayKey = stringPreferencesKey("usageCountLastDay")

val LanguageCodeToName = mapOf(
    "af" to "Afrikaans",
    "az" to "Azərbaycan",
    "id" to "Bahasa Indonesia",
    "ms" to "Bahasa Malaysia",
    "ca" to "Català",
    "cs" to "Čeština",
    "da" to "Dansk",
    "de" to "Deutsch",
    "et" to "Eesti",
    "en-GB" to "English (UK)",
    "en" to "English (US)",
    "es" to "Español (España)",
    "es-419" to "Español (Latinoamérica)",
    "eu" to "Euskara",
    "fil" to "Filipino",
    "fr" to "Français",
    "fr-CA" to "Français (Canada)",
    "gl" to "Galego",
    "hr" to "Hrvatski",
    "zu" to "IsiZulu",
    "is" to "Íslenska",
    "it" to "Italiano",
    "sw" to "Kiswahili",
    "lt" to "Lietuvių",
    "hu" to "Magyar",
    "nl" to "Nederlands",
    "no" to "Norsk",
    "or" to "Odia",
    "uz" to "O‘zbe",
    "pl" to "Polski",
    "pt-PT" to "Português",
    "pt" to "Português (Brasil)",
    "ro" to "Română",
    "sq" to "Shqip",
    "sk" to "Slovenčina",
    "sl" to "Slovenščina",
    "fi" to "Suomi",
    "sv" to "Svenska",
    "bo" to "Tibetan བོད་སྐད།",
    "vi" to "Tiếng Việt",
    "tr" to "Türkçe",
    "bg" to "Български",
    "ky" to "Кыргызча",
    "kk" to "Қазақ Тілі",
    "mk" to "Македонски",
    "mn" to "Монгол",
    "ru" to "Русский",
    "sr" to "Српски",
    "uk" to "Українська",
    "el" to "Ελληνικά",
    "hy" to "Հայերեն",
    "iw" to "עברית",
    "ur" to "اردو",
    "ar" to "العربية",
    "fa" to "فارسی",
    "ne" to "नेपाली",
    "mr" to "मराठी",
    "hi" to "हिन्दी",
    "bn" to "বাংলা",
    "pa" to "ਪੰਜਾਬੀ",
    "gu" to "ગુજરાતી",
    "ta" to "தமிழ்",
    "te" to "తెలుగు",
    "kn" to "ಕನ್ನಡ",
    "ml" to "മലയാളം",
    "si" to "සිංහල",
    "th" to "ภาษาไทย",
    "lo" to "ລາວ",
    "my" to "ဗမာ",
    "ka" to "ქართული",
    "am" to "አማርኛ",
    "km" to "ខ្មែរ",
    "zh-CN" to "中文 (简体)",
    "zh-TW" to "中文 (繁體)",
    "zh-HK" to "中文 (香港)",
    "ja" to "日本語",
    "ko" to "한국어",
)

val CountryCodeToName = mapOf(
    "DZ" to "Algeria",
    "AR" to "Argentina",
    "AU" to "Australia",
    "AT" to "Austria",
    "AZ" to "Azerbaijan",
    "BH" to "Bahrain",
    "BD" to "Bangladesh",
    "BY" to "Belarus",
    "BE" to "Belgium",
    "BO" to "Bolivia",
    "BA" to "Bosnia and Herzegovina",
    "BR" to "Brazil",
    "BG" to "Bulgaria",
    "KH" to "Cambodia",
    "CA" to "Canada",
    "CL" to "Chile",
    "HK" to "Hong Kong",
    "CO" to "Colombia",
    "CR" to "Costa Rica",
    "HR" to "Croatia",
    "CY" to "Cyprus",
    "CZ" to "Czech Republic",
    "DK" to "Denmark",
    "DO" to "Dominican Republic",
    "EC" to "Ecuador",
    "EG" to "Egypt",
    "SV" to "El Salvador",
    "EE" to "Estonia",
    "FI" to "Finland",
    "FR" to "France",
    "GE" to "Georgia",
    "DE" to "Germany",
    "GH" to "Ghana",
    "GR" to "Greece",
    "GT" to "Guatemala",
    "HN" to "Honduras",
    "HU" to "Hungary",
    "IS" to "Iceland",
    "IN" to "India",
    "ID" to "Indonesia",
    "IQ" to "Iraq",
    "IE" to "Ireland",
    "IL" to "Israel",
    "IT" to "Italy",
    "JM" to "Jamaica",
    "JP" to "Japan",
    "JO" to "Jordan",
    "KZ" to "Kazakhstan",
    "KE" to "Kenya",
    "KR" to "South Korea",
    "KW" to "Kuwait",
    "LA" to "Lao",
    "LV" to "Latvia",
    "LB" to "Lebanon",
    "LY" to "Libya",
    "LI" to "Liechtenstein",
    "LT" to "Lithuania",
    "LU" to "Luxembourg",
    "MK" to "Macedonia",
    "MY" to "Malaysia",
    "MT" to "Malta",
    "MX" to "Mexico",
    "ME" to "Montenegro",
    "MA" to "Morocco",
    "NP" to "Nepal",
    "NL" to "Netherlands",
    "NZ" to "New Zealand",
    "NI" to "Nicaragua",
    "NG" to "Nigeria",
    "NO" to "Norway",
    "OM" to "Oman",
    "PK" to "Pakistan",
    "PA" to "Panama",
    "PG" to "Papua New Guinea",
    "PY" to "Paraguay",
    "PE" to "Peru",
    "PH" to "Philippines",
    "PL" to "Poland",
    "PT" to "Portugal",
    "PR" to "Puerto Rico",
    "QA" to "Qatar",
    "RO" to "Romania",
    "RU" to "Russian Federation",
    "SA" to "Saudi Arabia",
    "SN" to "Senegal",
    "RS" to "Serbia",
    "SG" to "Singapore",
    "SK" to "Slovakia",
    "SI" to "Slovenia",
    "ZA" to "South Africa",
    "ES" to "Spain",
    "LK" to "Sri Lanka",
    "SE" to "Sweden",
    "CH" to "Switzerland",
    "TW" to "Taiwan",
    "TZ" to "Tanzania",
    "TH" to "Thailand",
    "TN" to "Tunisia",
    "TR" to "Turkey",
    "UG" to "Uganda",
    "UA" to "Ukraine",
    "AE" to "United Arab Emirates",
    "GB" to "United Kingdom",
    "US" to "United States",
    "UY" to "Uruguay",
    "VE" to "Venezuela (Bolivarian Republic)",
    "VN" to "Vietnam",
    "YE" to "Yemen",
    "ZW" to "Zimbabwe",
)
