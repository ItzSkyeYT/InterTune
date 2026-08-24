package com.dd3boh.outertune.constants

import android.os.Build
import com.dd3boh.outertune.BuildConfig

/**
 * Feature flags
 */

const val ENABLE_FFMETADATAEX = BuildConfig.FLAVOR == "full"


/**
 * Extra configuration
 */

// maximum concurrent image resolution jobs
const val MAX_COIL_JOBS = 16

// maximum concurrent download jobs allowed
const val MAX_DL_JOBS = 5

// maximum concurrent scanner jobs allowed
const val MAX_LM_SCANNER_JOBS = 7 // 1 dispatcher + 6 workers

// maximum concurrent scanner jobs allowed
const val MAX_YTM_SYNC_JOBS = 3

// maximum concurrent scanner jobs allowed
const val MAX_YTM_CONTENT_JOBS = 16


/**
 * Constants
 */

/**
 * The minimum amount of time the automatic scanner in between successful auto scanner runs
 */
const val AUTO_SCAN_COOLDOWN = 39600000L // 11 hours

/**
 * The minimum amount of time the automatic scanner in between auto scanner runs, regardless of failure or success.
 * This value should always be less than AUTO_SCAN_COOLDOWN
 */
const val AUTO_SCAN_SOFT_COOLDOWN = 7200000L // 2 hours
const val LYRIC_FETCH_TIMEOUT = 60000L
const val SNACKBAR_VERY_SHORT = 2000L

/**
 * 5: pre 0.10.0-rc1
 * 6: 0.10.0-rc1 +
 */
const val OOBE_VERSION = 6

const val SCANNER_OWNER_DL = 32
const val SCANNER_OWNER_LM = 1
const val SCANNER_OWNER_M3U = 2

const val SYNC_CD = 60000 * 30

const val MAX_PLAYER_CONSECUTIVE_ERR = 3

/**
 * Misc weird constants
 */

val DEFAULT_PLAYER_BACKGROUND =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PlayerBackgroundStyle.BLUR else PlayerBackgroundStyle.GRADIENT

val scannerWhitelistExts = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    listOf("dsf", "dff", "xm", "mod", "tta", "ape", "wv")
} else {
    listOf("opus", "dsf", "dff", "xm", "mod", "tta", "ape", "wv")
}


/**
 * Debug
 */
// crash at first extractor scanner error. Currently not implemented
const val SCANNER_CRASH_AT_FIRST_ERROR = false

// true will not use multithreading for scanner
const val SYNC_SCANNER = false

// enable verbose debugging details for scanner
const val SCANNER_DEBUG = false

// enable verbose debugging details for extractor
const val EXTRACTOR_DEBUG = false

// enable printing of *ALL* data that extractor reads
const val DEBUG_SAVE_OUTPUT = false // ignored (will be false) when EXTRACTOR_DEBUG IS false

const val QUEUE_DEBUG = false

/**
 * Upper end of the in-app volume slider. 2.0 is +6 dB above unity.
 *
 * Anything past 1.0 is applied by [com.dd3boh.outertune.playback.GainAudioProcessor] on the PCM,
 * because ExoPlayer's own volume is clamped to [0,1]. The processor soft clips, so pushing the
 * slider to the top compresses peaks rather than tearing them, but it is still gain on already
 * mastered audio: past unity is a tool, not free loudness.
 */
/**
 * Top of the in-app volume slider, as linear gain. 4f is +12 dB.
 *
 * 2f was not enough to be useful. Normalisation pulls a median track down to about 0.345 and the
 * loudest in a real library to 0.153, so a 2x ceiling topped out at 0.69 net: still 3 dB BELOW
 * unity, which is why the amplifier felt like it was doing nothing. 4x reaches roughly +3 dB above
 * unity on a median track, which is an amplifier rather than a partial refund.
 *
 * Not raised further because the slider is linear, so unity already sits at a quarter of its
 * travel and fine control near normal listening levels gets worse the higher this goes. If more
 * boost is wanted the slider should become logarithmic first.
 */
const val MAX_PLAYER_VOLUME = 4f
