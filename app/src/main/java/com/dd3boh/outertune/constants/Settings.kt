package com.dd3boh.outertune.constants

import android.content.Context
import com.dd3boh.outertune.R

/*
---------------------------
Appearance & interface
---------------------------
 */
enum class DarkMode {
    ON, OFF, AUTO
}

enum class PlayerBackgroundStyle {
    FOLLOW_THEME, GRADIENT, BLUR
}

/**
 * Where the Quick picks row gets its songs, and nothing else.
 *
 * YOUTUBE is the shelf YouTube Music puts at the top of its own home feed, picked for your account.
 * LIBRARY builds the row locally instead, out of songs related to what you have played, ranked by
 * how many of your recent plays point at each one.
 *
 * This governs one row. The rest of the home screen, YouTube's carousels and "Similar to" rows and
 * mood tiles, is unaffected either way.
 *
 * YOUTUBE is the default, and falls back to the library row on its own whenever YouTube sends no
 * such shelf, which is every signed-out session.
 */
/**
 * Whether playback may use the signed-in account, and when.
 *
 * Every client in the playback chain is anonymous: ANDROID_VR_NO_AUTH, VISIONOS and IOS all
 * declare loginSupported = false, so InnerTube never attaches the cookie even though /player asks
 * it to and the account is right there. That is fine almost always, and it is wrong in two cases.
 *
 * A song YouTube age gates will not play for anyone anonymous, including a listener whose own
 * account is perfectly entitled to it. And when YouTube starts refusing the address outright, an
 * anonymous request is the one thing it is refusing, so repeating it is both useless and the
 * reason the refusal lasts.
 *
 * This is not a way past the age gate. An authenticated request succeeds only if the account
 * behind it is allowed the song, which is the whole point: it asks on behalf of a real listener
 * rather than pretending to be a client the check does not apply to.
 */
/**
 * Whether the far end of the queue may change while it is not being looked at.
 *
 * Half of this listener's plays are six or more songs deep in an autoplay chain, and that band
 * skips hardest: a radio page is fetched once, when the queue runs low, and then played to the end
 * whatever happens in between. Twenty minutes later the listener has moved on and the queue has
 * not.
 */
enum class AdaptiveQueueMode {
    /** The queue is exactly what was put in it. */
    OFF,

    /** Only tails the app added by itself, which is where the skipping actually happens. */
    AUTOPLAY_ONLY,

    /** Any queue, including a playlist or album the listener queued on purpose. */
    ALWAYS,
}

/**
 * What, if anything, the audio chain does to the stereo it is given.
 *
 * Two different things wear the name "spatial audio" and only one of them works on any given
 * phone, so this is a choice rather than a switch. [HEADPHONES] is done here, in the app, and
 * therefore works everywhere; [SURROUND] hands the job to the phone and works only where the
 * phone is willing to do it, which on this one it is not.
 */
enum class SpatialAudioMode {
    /** The recording as it was mixed. */
    OFF,

    /**
     * Render the stereo as two loudspeakers in front of the listener, in the app.
     *
     * The mix comes off the line between the ears, where headphones otherwise pin it. Nothing
     * outside InterTune is involved, so nothing outside InterTune can decline.
     */
    HEADPHONES,

    /**
     * Invent a 5.1 signal and let the phone's own spatialiser render it.
     *
     * Works on hardware whose spatialiser is actually on, which is worth having for anyone whose
     * is. It is not on here: Samsung routes it through Dolby and keeps it switched off, so on
     * this phone the six channels are folded straight back down and nothing happens.
     */
    SURROUND,
}

enum class PlaybackAuthMode {
    /** Never send the account with playback. What the app did before this existed. */
    NEVER,

    /** Only once the anonymous clients have failed, or while YouTube is refusing us. */
    WHEN_REFUSED,

    /** Ask as the account from the start. */
    ALWAYS,
}

enum class QuickPicksSource {
    /** YouTube's own row for the account, or the library row when signed out. */
    YOUTUBE,
    /** The classic query: songs related to what has been played. */
    LIBRARY,
    /** InterTune's own engine: seeds, five lanes, and a score that learns. Experimental. */
    ENGINE,
    /** Try both: the engine's cards and the other source's, drawn alternately, so the ledger can say which get played. */
    COMPARE,
    /** No Quick picks row at all. */
    OFF,
    ;

    companion object {
        /** The sources a listener may choose in this release. See [Unreleased]. */
        fun offered(): List<QuickPicksSource> =
            if (Unreleased.ENGINE) entries else entries.filter { it != ENGINE && it != COMPARE }
    }
}

/**
 * The chosen source, or the default when what is stored is not on offer.
 *
 * A canary build can leave [QuickPicksSource.ENGINE] in the settings of a release that does not
 * offer it. Without this the row would quietly be the engine's with no way to see or change that.
 */
fun QuickPicksSource.orOffered(): QuickPicksSource =
    if (this in QuickPicksSource.offered()) this else QuickPicksSource.YOUTUBE

enum class LibraryViewType {
    LIST, GRID;

    fun toggle() = when (this) {
        LIST -> GRID
        GRID -> LIST
    }
}

enum class LyricsPosition {
    LEFT, CENTER, RIGHT
}

const val DEFAULT_ENABLED_TABS = "HSFM"
const val DEFAULT_ENABLED_FILTERS = "ARP"

/*
---------------------------
Sync
---------------------------
 */

enum class SyncMode {
    RO, RW, // USER_CHOICE
}

enum class SyncConflictResolution {
    ADD_ONLY, OVERWRITE_WITH_REMOTE, // OVERWRITE_WITH_LOCAL, USER_CHOICE
}

// when adding an enum:
// 1. add settings checkbox string and state
// 2. add to DEFAULT_SYNC_CONTENT
// 3. add to encode/decode
// 4. figure out if it's necessary to update existing user's keys
enum class SyncContent {
    ALBUMS,
    ARTISTS,
    LIKED_SONGS,
    PLAYLISTS,
    PRIVATE_SONGS,
    RECENT_ACTIVITY,
    NULL
}

/**
 * A: Albums
 * R: Artists
 * P: Playlists
 * L: Liked songs
 * S: Library (privately uploaded) songs
 * C: Recent activity
 * N: <Unused option>
 */
val syncPairs = listOf(
    SyncContent.ALBUMS to 'A',
    SyncContent.ARTISTS to 'R',
    SyncContent.PLAYLISTS to 'P',
    SyncContent.LIKED_SONGS to 'L',
    SyncContent.PRIVATE_SONGS to 'S',
    SyncContent.RECENT_ACTIVITY to 'C'
)

/**
 * Converts the enable sync items list (string) to SyncContent
 *
 * @param sync Encoded string
 */
fun decodeSyncString(sync: String): List<SyncContent> {
    val charToSyncMap = syncPairs.associate { (screen, char) -> char to screen }

    return sync.toCharArray().map { char -> charToSyncMap[char] ?: SyncContent.NULL }
}

/**
 * Converts the SyncContent filters list to string
 *
 * @param list Decoded SyncContent list
 */
fun encodeSyncString(list: List<SyncContent>): String {
    val charToSyncMap = syncPairs.associate { (sync, char) -> char to sync }

    return list.distinct().joinToString("") { sync ->
        charToSyncMap.entries.first { it.value == sync }.key.toString()
    }
}


/*
---------------------------
Local scanner
---------------------------
 */

enum class ScannerImpl {
    MEDIASTORE,
    TAGLIB,
    FFMPEG_EXT,
}

/**
 * Specify how strict the metadata scanner should be
 */
enum class ScannerMatchCriteria {
    LEVEL_1, // Title only
    LEVEL_2, // Title and artists
    LEVEL_3, // Title, artists, albums
}

enum class ScannerM3uMatchCriteria {
    LEVEL_1, // Title only
    LEVEL_2, // Title and artists
    LEVEL_0, // Do not compare, assume it is a match
    // TODO: Do albums for m3u if that even is a thing
}


/*
---------------------------
Player & audio
---------------------------
 */
enum class SeekIncrement(val millisec: Int, val second: Int) {
    OFF(0, 0), FIVE(5000, 5), TEN(10000, 10), FIFTEEN(15000, 15), TWENTY(20000, 20);

    companion object {
        fun getString(context: Context, seekIncrement: SeekIncrement) =
            when(seekIncrement) {
                OFF -> context.getString(androidx.compose.ui.R.string.state_off)
                else -> context.resources.getQuantityString(R.plurals.second, seekIncrement.second, seekIncrement.second)
            }

    }
}
enum class AudioQuality {
    /**
     * The largest stream on offer, whatever it costs.
     *
     * Not lossless, and deliberately not named as though it were. YouTube Music serves no lossless
     * audio at all: the ceiling is 256 kbps AAC on a premium account and about 160 kbps Opus
     * otherwise. This tier takes that ceiling rather than promising something the source does not
     * have.
     */
    MAX,
    AUTO, HIGH, LOW
}

/*
---------------------------
Library & Content
---------------------------
 */


enum class LikedAutodownloadMode {
    OFF, ON, WIFI_ONLY
}


/*
---------------------------
Misc preferences not bound
to settings category
---------------------------
 */
enum class SongSortType {
    CREATE_DATE, MODIFIED_DATE, RELEASE_DATE, NAME, ARTIST, PLAY_COUNT
}

enum class FolderSortType {
    NAME, // TODO: support CREATE_DATE, MODIFIED_DATE
}

enum class FolderSongSortType {
    CREATE_DATE, MODIFIED_DATE, RELEASE_DATE, NAME, ARTIST, PLAY_COUNT, TRACK_NUMBER
}

enum class PlaylistSongSortType {
    CUSTOM, NAME, ARTIST, ADDED_DATE, MODIFIED_DATE, RELEASE_DATE
}

enum class ArtistSortType {
    CREATE_DATE, NAME, SONG_COUNT
}

enum class ArtistSongSortType {
    CREATE_DATE, NAME
}

enum class AlbumSortType {
    CREATE_DATE, NAME, ARTIST, YEAR, SONG_COUNT, LENGTH
}

enum class PlaylistSortType {
    CREATE_DATE, NAME, SONG_COUNT
}

enum class LibrarySortType {
    CREATE_DATE, NAME
}

enum class SongFilter {
    LIBRARY, LIKED, DOWNLOADED
}

enum class ArtistFilter {
    LIBRARY, LIKED, DOWNLOADED
}

enum class AlbumFilter {
    LIBRARY, LIKED, DOWNLOADED
}

enum class PlaylistFilter {
    LIBRARY, DOWNLOADED
}

enum class SearchSource {
    LOCAL, ONLINE
}

enum class Speed {
    SLOW, MEDIUM, FAST;

    fun toLrcRefreshMillis(): Long =
        when (this) {
            SLOW -> 125
            MEDIUM -> 33
            FAST -> 16
        }
}
