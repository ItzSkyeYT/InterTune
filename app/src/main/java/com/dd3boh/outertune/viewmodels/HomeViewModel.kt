package com.dd3boh.outertune.viewmodels

import com.dd3boh.outertune.engine.PastSeeds
import com.dd3boh.outertune.constants.EngineOverridesKey
import com.dd3boh.outertune.engine.EngineTuning
import com.dd3boh.outertune.engine.ContextChip
import com.dd3boh.outertune.constants.ContextChipKey
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.engine.versionKey
import com.dd3boh.outertune.constants.RestsEverywhereKey
import kotlinx.coroutines.sync.withLock
import com.dd3boh.outertune.constants.ShadowComparisonKey
import com.dd3boh.outertune.engine.dayPartBucket
import com.dd3boh.outertune.engine.quotas
import com.dd3boh.outertune.engine.RowBuildCodec
import com.dd3boh.outertune.constants.FamiliarityKey
import com.dd3boh.outertune.constants.LearnFromListeningKey
import com.dd3boh.outertune.engine.EngineLearning
import com.dd3boh.outertune.constants.NewSongsOnlyKey
import com.dd3boh.outertune.db.entities.RecommendationExclusion
import com.dd3boh.outertune.engine.PlayedSong
import com.dd3boh.outertune.engine.Lane
import com.dd3boh.outertune.constants.RankWithListeningKey
import com.dd3boh.outertune.constants.AdventurousnessKey
import com.dd3boh.outertune.engine.Weights
import com.dd3boh.outertune.engine.EngineRow
import com.dd3boh.outertune.engine.EngineParams
import com.dd3boh.outertune.engine.EngineLoader
import com.dd3boh.outertune.engine.EngineInput
import com.dd3boh.outertune.engine.Card
import com.dd3boh.outertune.engine.BuiltRow
import kotlinx.coroutines.withContext
import com.dd3boh.outertune.constants.TidyHomeRowsKey
import com.dd3boh.outertune.engine.TidyPass
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.PlaylistFilter
import com.dd3boh.outertune.constants.PlaylistSortType
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Album
import com.dd3boh.outertune.db.entities.LocalItem
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.models.SimilarRecommendation
import com.dd3boh.outertune.constants.InnerTubeCookieKey
import com.dd3boh.outertune.extensions.toEnum
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.constants.QuickPicksSourceKey
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.db.entities.RowBuild
import com.dd3boh.outertune.db.entities.Impression
import com.dd3boh.outertune.constants.PauseListenHistoryKey
import com.dd3boh.outertune.constants.QuickPicksSource
import com.dd3boh.outertune.utils.SyncUtils
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.Throttle
import com.dd3boh.outertune.utils.SongVersions
import com.dd3boh.outertune.utils.QuickPicksShelf
import com.dd3boh.outertune.utils.RecentlyShown
import com.dd3boh.outertune.utils.reportException
import com.dd3boh.outertune.utils.syncCoroutine
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.models.YTItem
import com.zionhuang.innertube.pages.ExplorePage
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.pages.HomePage
import com.zionhuang.innertube.utils.completed
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The live log's session boundary, so "this session" here means what it means there. */
private const val SESSION_GAP_MS = 30L * 60 * 1000
/** How long the engine's input is kept before being read again. */
private const val ENGINE_INPUT_TTL_MS = 5L * 60 * 1000

/** One line under a card: a feature name and, for the seed and artist reasons, what it names. */
data class CardReason(val key: String, val arg: String?)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext val context: Context,
    val database: MusicDatabase,
    val syncUtils: SyncUtils
) : ViewModel() {

    // ---- Impressions: what Quick picks showed, so the engine can learn from what was passed over.
    // Every write goes through the serial transaction executor, so a card is never logged against a
    // build that has not been inserted yet, and the slot bookkeeping needs no locking. Every write
    // is also caught: a failure here is a lost data point, never a dead app. The first version
    // crashed on the YouTube row, whose songs exist only in the feed until they are inserted.
    // ---- Tidy Home rows. Every row is loaded into a pool and shown through one pass in screen
    // order (engine/Tidy.kt): nothing just played in Quick picks, no song twice, no version beside
    // its original. The pass runs again when Home comes back into view, never under the finger.
    private var quickPicksPool: List<Song> = emptyList()
    private var ytQuickPicksPool: List<SongItem>? = null

    /** What each row has shown lately, so that a refresh brings forward what it has not. */
    private val recentlyShown = RecentlyShown()

    /**
     * The tiers the YouTube row is drawn from, in order: the shelf's own songs, the songs of the
     * feed's other song shelves, and what YouTube lists as related to the shelf, fetched on a pull
     * and kept across loads. The shelf is the same for a whole session, so on its own it could only
     * ever be reordered; the other two are what a refresh has to show that is new.
     */
    private var ytShelfSongs: List<SongItem> = emptyList()
    private var ytShelfExtras: List<SongItem> = emptyList()
    private val ytRelated = ArrayDeque<SongItem>()
    private val ytRelatedSeeds = HashSet<String>()
    private val ytTier = HashMap<String, Int>()

    /** Set by a pull to refresh: this load also fetches songs related to the shelf. */
    @Volatile private var moreOnNextLoad = false
    private var forgottenPool: List<Song> = emptyList()
    private var keepListeningPool: List<LocalItem> = emptyList()
    private var similarPool: List<SimilarRecommendation>? = null
    private var homePagePool: HomePage? = null

    /** Home came back into view: grade and learn from what has happened since, then tidy the rows. */
    fun applyTidy() { viewModelScope.launch(Dispatchers.IO) { runCatching { learning.run() }.onFailure { Log.w("HomeViewModel", "The loop failed", it) }; tidyRows() } }

    // ---- Best recommendations: the engine's own row, and its score over the other sources.
    private var lastEngineRow: BuiltRow? = null
    private var lastEngineBuildAt = 0L
    private var lastEngineSession = -1L
    private var lastEngineBucket = -1
    private var engineInputCache: Pair<Long, EngineInput>? = null
    private val learning by lazy { EngineLearning(context, database) }
    /** The weights the last build or ranking used, for the build record. */
    private var weightsInUse: Weights = Weights.PRIORS
    /** Try both: which team each card of the drafted row came from (1 engine, 2 library, 3 YouTube). */
    private val compareTeams = HashMap<String, Int>()
    private var enginePoolForCompare: List<Song> = emptyList()

    /**
     * Try both: the engine's cards and the other source's, drawn alternately by version group,
     * so both are on screen under the same conditions and the ledger can say which get played.
     */
    private suspend fun draftCompareRow() {
        val other: List<Song>
        val otherTeam: Int
        val shelf = ytQuickPicksPool?.take(40)
        if (!shelf.isNullOrEmpty()) {
            val metas = shelf.map { it.toMediaMetadata() }
            runCatching { database.transactionNow { metas.forEach { if (!songExists(it.id)) insert(it) } } }
            val byId = database.songsByIds(metas.map { it.id }).first().associateBy { it.id }
            other = metas.mapNotNull { byId[it.id] }; otherTeam = 3
        } else {
            other = database.quickPicks().first().take(40).shuffled(); otherTeam = 2
        }
        val engine = enginePoolForCompare
        compareTeams.clear()
        val taken = HashSet<String>()
        val drafted = ArrayList<Song>()
        val a = engine.iterator(); val b = other.iterator()
        fun take(from: Iterator<Song>, team: Int): Boolean {
            while (from.hasNext()) {
                val song = from.next()
                val key = versionKey(song.song.title, song.artists.firstOrNull()?.name)
                if (!taken.add(key)) continue
                compareTeams[song.id] = team; drafted += song; return true
            }
            return false
        }
        var turn = 0
        while (drafted.size < 60) {
            val got = if (turn % 2 == 0) take(a, 1) else take(b, otherTeam)
            if (!got && !a.hasNext() && !b.hasNext()) break
            turn++
        }
        quickPicksPool = drafted
        ytQuickPicksPool = null
        ytQuickPicks.value = null
    }
    /** Why each card of the current engine row (and its pool) is there, for the captions. */
    val engineReasons = MutableStateFlow<Map<String, List<CardReason>>>(emptyMap())
    /** With the engine chosen: 0 its row is showing, 1 the library's row stands in, 2 YouTube's. */
    val engineFallback = MutableStateFlow(0)
    /** The current build's seeds as (id, title), its lane quotas, and the exclusions in force, for Why these? */
    val engineSeeds = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val engineQuotas = MutableStateFlow<Map<Lane, Int>>(emptyMap())
    val activeExclusions = database.activeExclusionCount(System.currentTimeMillis())
    private val rejectedSeeds = HashSet<String>()
    private var lastEngineNewOnly = false
    private var lastEngineFamiliarity = -1
    private var lastEngineChip = -1
    /** How many listens carry the current mood chip; below the threshold the row says it is still learning. */
    val engineChipTagged = MutableStateFlow(-1)

    /** Set by a pull to refresh: the next build reads the library afresh and shows other songs than the last row. */
    @Volatile private var varietyOnNextBuild = false

    /** Pull to refresh: a new row, not the same one again. */
    fun pullToRefresh() {
        varietyOnNextBuild = true
        moreOnNextLoad = true
        engineInputCache = null
        refresh(force = true)
    }

    /** The chip changed: the row is built again for it. */
    fun chipChanged() {
        lastEngineBuildAt = 0L
        refresh(force = true)
    }

    /** Not this one: the seed is left out and the row built again. */
    fun rejectSeed(songId: String) {
        rejectedSeeds += songId
        lastEngineBuildAt = 0L
        refresh(force = true)
    }

    /** Not this song, less of this artist, never this artist: written, applied to every row at once, and the engine row built again. */
    fun exclude(kind: Int, targetId: String, label: String, reason: Int) {
        val now = System.currentTimeMillis()
        val exclusion = RecommendationExclusion(
            kind = kind, targetId = targetId, label = label, reason = reason, createdAt = now,
            expiresAt = if (reason == ExclusionsViewModel.REASON_SNOOZE) now + ExclusionsViewModel.SNOOZE_MS else null,
        )
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { database.transactionNow { insertExclusion(exclusion) } }.onFailure { Log.w("HomeViewModel", "Could not write an exclusion", it) }
            lastEngineBuildAt = 0L
            tidyRows()
            refresh()
        }
    }

    fun excludeSong(song: Song, kind: Int, reason: Int) {
        if (kind == ExclusionsViewModel.KIND_SONG) exclude(kind, song.id, song.song.title, reason)
        else song.artists.firstOrNull()?.let { exclude(kind, it.id, it.name, reason) }
    }

    fun excludeYt(song: SongItem, kind: Int, reason: Int) {
        if (kind == ExclusionsViewModel.KIND_SONG) exclude(kind, song.id, song.title, reason)
        else song.artists.firstOrNull()?.let { a -> a.id?.let { exclude(kind, it, a.name, reason) } }
    }

    private val engineInputLock = kotlinx.coroutines.sync.Mutex()

    /** The engine's input, read at most every few minutes: the biggest read on Home is the song table. */
    private suspend fun engineInput(now: Long): EngineInput = withContext(Dispatchers.IO) {
        engineInputLock.withLock {
            engineInputCache?.takeIf { now - it.first < ENGINE_INPUT_TTL_MS }?.second
                ?: EngineLoader.load(database, now).also { engineInputCache = now to it }
        }
    }

    init {
        // Warmed up in the background as the app opens, so the first build or ranking does not wait for the read.
        viewModelScope.launch(Dispatchers.IO) { runCatching { engineInput(System.currentTimeMillis()) } }
    }

    private fun currentSessionOf(now: Long): Long = runCatching {
        database.openListens().firstOrNull()?.sessionId ?: database.lastListen()?.takeIf { now - it.endedAt <= SESSION_GAP_MS }?.sessionId
    }.getOrNull() ?: -1L

    private fun rowIsFresh(builtAt: Long, session: Long, bucket: Int, now: Long): Boolean =
        now - builtAt < 3 * 3_600_000L && session == currentSessionOf(now) && bucket == dayPartBucket(now, java.util.TimeZone.getDefault().getOffset(now) / 60_000)

    /** The last engine build from the database, when it is still fresh: shown at once, no seconds of building. */
    private suspend fun restoreEngineRow(now: Long): BuiltRow? = withContext(Dispatchers.IO) {
        val last = database.lastBuild(1) ?: return@withContext null
        if (!rowIsFresh(last.builtAt, last.sessionId, last.bucket, now)) return@withContext null
        if (last.contextChip != context.dataStore.get(ContextChipKey, 0)) return@withContext null
        val cards = RowBuildCodec.decode(last.cards)
        if (cards.size < EngineParams.DEFAULT.minCards) return@withContext null
        BuiltRow(cards, EngineLoader.parseSeeds(last.seeds), RowBuildCodec.decode(last.pool), quotas(20, last.dial / 100.0, false)).also {
            lastEngineRow = it; lastEngineBuildAt = last.builtAt; lastEngineSession = last.sessionId; lastEngineBucket = last.bucket
            lastEngineNewOnly = context.dataStore.get(NewSongsOnlyKey, false); lastEngineFamiliarity = context.dataStore.get(FamiliarityKey, 25); lastEngineChip = last.contextChip
        }
    }

    /**
     * With another source showing, the engine still builds its row in the background and keeps
     * it, unseen, so a day later How it's doing can say whether it would have held what was
     * played. Skipped while the last one is fresh, and on battery saver.
     */
    private fun shadowBuild() {
        if (!context.dataStore.get(ShadowComparisonKey, true)) return
        val power = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        if (power?.isPowerSaveMode == true) return
        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                val now = System.currentTimeMillis()
                val last = database.lastBuild(4)
                if (last != null && rowIsFresh(last.builtAt, last.sessionId, last.bucket, now)) return@launch
                val input = engineInput(now)
                val weights = runCatching { learning.weights() }.getOrDefault(Weights.PRIORS)
                val familiarity = context.dataStore.get(FamiliarityKey, 25)
                val row = EngineRow.build(input, weights = weights, p = EngineTuning.params(EngineTuning.parse(context.dataStore.get(EngineOverridesKey, ""))).withFamiliarity(familiarity / 100.0), dial = context.dataStore.get(AdventurousnessKey, 15) / 100.0)
                if (row.cards.isEmpty()) return@launch
                database.transactionNow {
                    insert(RowBuild(
                        builtAt = now, rowKey = 4, sessionId = currentSessionOf(now), bucket = input.bucket, dial = context.dataStore.get(AdventurousnessKey, 15),
                        seeds = EngineLoader.seedsJson(row.seeds), weights = weights.asMap().entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" },
                        pool = RowBuildCodec.encode(row.pool), cards = RowBuildCodec.encode(row.cards),
                    ))
                }
            }.onFailure { Log.w("HomeViewModel", "Shadow build failed", it) }
        }
    }

    private fun reasonOf(key: String, card: Card, input: EngineInput): CardReason = when (key) {
        "x_seed" -> CardReason(key, card.seedId?.let { input.songs[it]?.title })
        "x_art" -> CardReason(key, input.songs[card.songId]?.artistName)
        "x_ctx" -> CardReason(key, if (input.chip in ContextChip.MOODS) input.chip.toString() else null)
        else -> CardReason(key, null)
    }

    /**
     * The engine row, rebuilt when Home appears after a new session, in a new day part, three
     * hours after the last build, or on pull to refresh; otherwise the last build stands and only
     * the tidy pass moves it. Returns the row followed by its pool, as songs, or nothing when
     * there was not enough to build from.
     */
    private suspend fun buildEngineRow(force: Boolean): List<Song> = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        runCatching { learning.run(now) }.onFailure { Log.w("HomeViewModel", "The loop failed", it) }
        weightsInUse = runCatching { learning.weights() }.getOrDefault(Weights.PRIORS)
        if (lastEngineRow == null && !force) restoreEngineRow(now)
        val restored = lastEngineRow
        val session = currentSessionOf(now)
        val newOnlyNow = context.dataStore.get(NewSongsOnlyKey, false)
        val familiarityNow = context.dataStore.get(FamiliarityKey, 25)
        if (restored != null && !force && now - lastEngineBuildAt < 3 * 3_600_000L && session == lastEngineSession && newOnlyNow == lastEngineNewOnly && familiarityNow == lastEngineFamiliarity && context.dataStore.get(ContextChipKey, 0) == lastEngineChip &&
            lastEngineBucket == dayPartBucket(now, java.util.TimeZone.getDefault().getOffset(now) / 60_000) && engineInputCache == null) {
            // Fresh and restored from the database: shown as it was, without reading the library first.
            engineReasons.value = (restored.cards + restored.pool).associate { c -> c.songId to c.reasons.map { CardReason(it, null) } }
            engineSeeds.value = restored.seeds.map { it to it }; engineQuotas.value = restored.quotas
            val wanted = (restored.cards + restored.pool).map { it.songId }
            val byId = database.songsByIds(wanted).first().associateBy { it.id }
            engineSeeds.value = restored.seeds.map { id -> id to (byId[id]?.song?.title ?: id) }
            engineReasons.value = (restored.cards + restored.pool).associate { c -> c.songId to c.reasons.map { key -> when (key) { "x_seed" -> CardReason(key, c.seedId?.let { byId[it]?.song?.title }); "x_art" -> CardReason(key, byId[c.songId]?.artists?.firstOrNull()?.name); else -> CardReason(key, null) } } }
            return@withContext wanted.mapNotNull { byId[it] }
        }
        val input = engineInput(now)
        val standing = lastEngineRow
        // A refresh asks for something else: the last row's songs sit this build out and its
        // seeds are damped as if they had just been used, which they were.
        val variety = varietyOnNextBuild && standing != null
        varietyOnNextBuild = false
        val varied = if (variety) input.copy(
            pastSeeds = input.pastSeeds + PastSeeds(now, standing!!.seeds),
            banned = standing.cards.mapTo(HashSet()) { it.songId },
        ) else input
        val newOnly = context.dataStore.get(NewSongsOnlyKey, false)
        val familiarity = context.dataStore.get(FamiliarityKey, 25)
        val chip = context.dataStore.get(ContextChipKey, ContextChip.AUTO)
        engineChipTagged.value = if (chip in ContextChip.MOODS) input.listens.count { it.contextChip == chip } else -1
        val row = if (standing != null && !force && now - lastEngineBuildAt < 3 * 3_600_000L && session == lastEngineSession && input.bucket == lastEngineBucket && newOnly == lastEngineNewOnly && familiarity == lastEngineFamiliarity && chip == lastEngineChip) standing
        else EngineRow.build(varied.copy(notSeeds = rejectedSeeds.toSet(), chip = chip), weights = weightsInUse, p = EngineTuning.params(EngineTuning.parse(context.dataStore.get(EngineOverridesKey, ""))).withFamiliarity(familiarity / 100.0), dial = context.dataStore.get(AdventurousnessKey, 15) / 100.0, newOnly = newOnly).also {
            lastEngineRow = it; lastEngineBuildAt = now; lastEngineSession = session; lastEngineBucket = input.bucket; lastEngineNewOnly = newOnly; lastEngineFamiliarity = familiarity; lastEngineChip = chip
            Log.d("HomeViewModel", "engine row: ${it.cards.size} cards, ${it.pool.size} in the pool, ${it.seeds.size} seeds, from ${input.songs.size} songs, ${input.listens.size} listens, ${input.edges.size} edges in ${System.currentTimeMillis() - now} ms")
        }
        engineReasons.value = (row.cards + row.pool).associate { c -> c.songId to c.reasons.map { reasonOf(it, c, input) } }
        engineSeeds.value = row.seeds.map { it to (input.songs[it]?.title ?: it) }
        engineQuotas.value = row.quotas
        if (row.cards.size < EngineParams.DEFAULT.minCards) return@withContext emptyList()
        val wanted = (row.cards + row.pool).map { it.songId }
        val byId = database.songsByIds(wanted).first().associateBy { it.id }
        wanted.mapNotNull { byId[it] }
    }

    /**
     * Rank with your listening: the chosen source's pool in the engine's order. The shelf is only
     * ranked once it has been fetched afresh, never the stale one still on screen, or a pull to
     * refresh would reshuffle the old shelf and then replace it, which reads as a glitch.
     */
    private suspend fun rankPools(includeShelf: Boolean) {
        val src = quickPicksSource()
        // The engine's row is in the order it was built in and has its own way of varying.
        if ((src == QuickPicksSource.ENGINE || src == QuickPicksSource.COMPARE) && engineFallback.value == 0) return
        if (context.dataStore.get(RankWithListeningKey, true)) {
            val shelf = if (includeShelf) ytQuickPicksPool else null
            val ids = quickPicksPool.map { it.id } + shelf.orEmpty().map { it.id }
            val order = if (ids.isEmpty()) null else runCatching { withContext(Dispatchers.Default) {
                val input = engineInput(System.currentTimeMillis())
                runCatching { learning.run() }.onFailure { Log.w("HomeViewModel", "The loop failed", it) }
                weightsInUse = runCatching { learning.weights() }.getOrDefault(Weights.PRIORS)
                EngineRow.rankSampled(input, ids, weightsInUse).withIndex().associate { it.value to it.index }
            } }.onFailure { reportException(it) }.getOrNull()
            if (order != null) {
                quickPicksPool = quickPicksPool.sortedBy { order[it.id] ?: Int.MAX_VALUE }
                // Ranked inside each tier: the shelf's own songs first however they score, so the
                // row as it opens is YouTube's Quick picks and the other tiers are what a refresh
                // reaches for.
                if (includeShelf) ytQuickPicksPool = ytQuickPicksPool?.sortedWith(compareBy({ ytTier[it.id] ?: 9 }, { order[it.id] ?: Int.MAX_VALUE }))
            }
        }
        // Whatever the order so far, what the row has shown lately goes to the back, so that a
        // refresh is new songs and not the same ones again.
        quickPicksPool = recentlyShown.order("lib", quickPicksPool) { it.id }
        if (includeShelf) ytQuickPicksPool = ytQuickPicksPool?.let { pool -> recentlyShown.order("yt", pool) { it.id } }
    }

    private suspend fun tidyRows() = withContext(Dispatchers.IO) {
        val tidy = context.dataStore.get(TidyHomeRowsKey, true)
        if (!tidy) {
            quickPicks.value = quickPicksPool.take(20)
            ytQuickPicks.value = ytQuickPicksPool?.take(20)
            forgottenFavorites.value = forgottenPool.take(20)
            keepListening.value = keepListeningPool
            similarRecommendations.value = similarPool
            homePage.value = homePagePool
            return@withContext
        }
        val now = System.currentTimeMillis()
        val session = runCatching {
            database.openListens().firstOrNull()?.sessionId
                ?: database.lastListen()?.takeIf { now - it.endedAt <= SESSION_GAP_MS }?.sessionId
        }.getOrNull() ?: -1L
        val played = runCatching { database.justPlayed(now - 86_400_000L, session) }.getOrDefault(emptyList())
        // Manual bans and snoozes filter every recommendation row, whatever the source.
        val restsHere = quickPicksSource() == QuickPicksSource.ENGINE || context.dataStore.get(RestsEverywhereKey, false)
        val exclusions = runCatching { database.engineExclusions(now) }.getOrDefault(emptyList()).filter { it.reason != ExclusionsViewModel.REASON_REST || restsHere }
        val bannedSongs = exclusions.filter { it.kind == ExclusionsViewModel.KIND_SONG }.map { it.targetId }
            .takeIf { it.isNotEmpty() }?.let { ids -> runCatching { database.songsByIds(ids).first() }.getOrDefault(emptyList()) }
            ?.map { PlayedSong(it.id, it.song.title, it.artists.firstOrNull()?.name) }.orEmpty()
        val bannedArtists = exclusions.filter { it.kind == ExclusionsViewModel.KIND_ARTIST }.flatMap { listOf(it.targetId, it.label.trim().lowercase()) }.toSet()
        val pass = TidyPass(played, bannedSongs, bannedArtists)
        fun songs(items: List<Song>, fresh: Boolean = false) = pass.row(items, fresh, { it.song.id }, { it.song.title }, { it.artists.firstOrNull()?.name }, { it.artists.firstOrNull()?.id })
        fun local(items: List<LocalItem>) = pass.row(items, false, { (it as? Song)?.song?.id }, { (it as? Song)?.song?.title }, { (it as? Song)?.artists?.firstOrNull()?.name }, { (it as? Song)?.artists?.firstOrNull()?.id })
        fun yt(items: List<YTItem>, fresh: Boolean = false) = pass.row(items, fresh, { (it as? SongItem)?.id }, { (it as? SongItem)?.title }, { (it as? SongItem)?.artists?.firstOrNull()?.name }, { (it as? SongItem)?.artists?.firstOrNull()?.id })
        // Whichever Quick picks row is on screen goes first; the other is not shown and must not
        // claim songs from the rows below it.
        val ytShown = ytShelfWanted() && !ytQuickPicksPool.isNullOrEmpty()
        if (ytShown) {
            ytQuickPicks.value = ytQuickPicksPool?.let { yt(it, fresh = true).filterIsInstance<SongItem>().take(20) }
            Log.d("HomeViewModel", "showing the YouTube row: ${ytQuickPicks.value?.size} of a pool of ${ytQuickPicksPool?.size} after tidy, first ${ytQuickPicks.value?.firstOrNull()?.title}")
            quickPicks.value = quickPicksPool.take(20)
        } else {
            quickPicks.value = songs(quickPicksPool, fresh = true).take(20)
            ytQuickPicks.value = ytQuickPicksPool?.take(20)
        }
        forgottenFavorites.value = songs(forgottenPool).take(20)
        keepListening.value = local(keepListeningPool)
        similarRecommendations.value = similarPool?.map { it.copy(items = yt(it.items)) }?.filter { it.items.isNotEmpty() }
        homePage.value = homePagePool?.let { page -> page.copy(sections = page.sections.map { it.copy(items = yt(it.items)) }.filter { it.items.isNotEmpty() }) }
    }

    private var shownBuildIds: List<String> = emptyList()
    private var currentBuildId = 0L
    private var currentBuildSongs: List<String> = emptyList()
    private var currentTeam = 0
    private val loggedSlots = HashSet<Int>()
    private val impressionIds = HashMap<Int, Long>()

    /** A new set of songs is on screen. Called whenever the shown list changes, including on refresh. */
    fun quickPicksShown(source: Int, songs: List<MediaMetadata>) {
        val ids = songs.map { it.id }
        if (ids.isEmpty() || ids == shownBuildIds) return
        shownBuildIds = ids
        if (context.dataStore.get(PauseListenHistoryKey, false)) return
        val now = System.currentTimeMillis()
        database.transaction {
            runCatching {
                // The song table is the anchor for everything the engine will ever say about a
                // song, and YouTube's row arrives from the feed, not from the table.
                songs.forEach { if (!songExists(it.id)) insert(it) }
                val sessionId = lastListen()?.sessionId ?: now
                val rowKey = when (source) { 1 -> 3; 2 -> 1; 3 -> 5; else -> 2 }
                val engineRow = lastEngineRow?.takeIf { source == 2 || source == 3 }
                currentBuildId = insert(RowBuild(
                    builtAt = now, rowKey = rowKey, sessionId = sessionId, bucket = dayPartBucket(now),
                    dial = context.dataStore.get(AdventurousnessKey, 15), contextChip = context.dataStore.get(ContextChipKey, 0),
                    seeds = EngineLoader.seedsJson(engineRow?.seeds.orEmpty()),
                    weights = if (engineRow != null) weightsInUse.asMap().entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" } else "{}",
                    pool = engineRow?.let { RowBuildCodec.encode(it.pool) },
                    cards = engineRow?.let { RowBuildCodec.encode(it.cards) },
                ))
                currentBuildSongs = ids
                currentTeam = rowKey
                loggedSlots.clear()
                impressionIds.clear()
            }.onFailure { Log.w("HomeViewModel", "Could not record the Quick picks build", it) }
        }
    }

    /** Weekday or weekend, times night 0 to 5, morning 6 to 11, afternoon 12 to 17, evening 18 to 23. */
    private fun dayPartBucket(at: Long): Int {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = at }
        val weekend = cal.get(java.util.Calendar.DAY_OF_WEEK).let { it == java.util.Calendar.SATURDAY || it == java.util.Calendar.SUNDAY }
        val part = cal.get(java.util.Calendar.HOUR_OF_DAY) / 6
        return (if (weekend) 4 else 0) + part
    }

    /** An impression, with the engine's record of the card when the engine placed it. */
    private fun impression(songId: String, slot: Int, at: Long): Impression {
        val team = if (currentTeam == 5) compareTeams[songId] ?: 1 else currentTeam
        val card = lastEngineRow?.takeIf { team == 1 }?.let { r -> (r.cards + r.pool).firstOrNull { it.songId == songId } }
        return Impression(
            buildId = currentBuildId, songId = songId, slot = slot, team = team, visibleAt = at,
            lane = card?.lane?.ordinal?.plus(1) ?: 0, sampled = card?.sampled ?: false, p = card?.p?.toFloat(),
            features = card?.features?.joinToString(",") { String.format(java.util.Locale.ROOT, "%.4f", it) },
            reasons = card?.reasons?.joinToString(","),
        )
    }

    /** A card has been at least half visible for long enough to count as seen. */
    fun quickPickSeen(slot: Int) {
        if (context.dataStore.get(PauseListenHistoryKey, false) || !context.dataStore.get(LearnFromListeningKey, true)) return
        val now = System.currentTimeMillis()
        database.transaction {
            runCatching {
                val songId = currentBuildSongs.getOrNull(slot) ?: return@transaction
                if (currentBuildId == 0L || !loggedSlots.add(slot)) return@transaction
                insertImpressions(listOf(impression(songId, slot, now))).firstOrNull()?.let { impressionIds[slot] = it }
            }.onFailure { Log.w("HomeViewModel", "Could not record an impression", it) }
        }
    }

    /**
     * A card was tapped. The impression is marked with the moment, and the listen the tap starts
     * carries the same moment, so the two meet without an id crossing the player boundary. A tap
     * before the card had settled long enough to be logged as seen logs it now.
     */
    fun quickPickTapped(slot: Int, tappedAt: Long) {
        if (context.dataStore.get(PauseListenHistoryKey, false) || !context.dataStore.get(LearnFromListeningKey, true)) return
        database.transaction {
            runCatching {
                val songId = currentBuildSongs.getOrNull(slot) ?: return@transaction
                if (currentBuildId == 0L) return@transaction
                val id = impressionIds[slot] ?: run {
                    loggedSlots.add(slot)
                    insertImpressions(listOf(impression(songId, slot, tappedAt))).first().also { impressionIds[slot] = it }
                }
                markImpressionTapped(id, tappedAt)
            }.onFailure { Log.w("HomeViewModel", "Could not record a tap", it) }
        }
    }

    val isRefreshing = MutableStateFlow(false)
    val isLoading = MutableStateFlow(false)

    val quickPicks = MutableStateFlow<List<Song>?>(null)

    /**
     * YouTube Music's own Quick picks for this account, when it sends them.
     *
     * Preferred over [quickPicks] when present. The local one is a query over songs related to
     * things already played, which can only ever recirculate the library; this is YouTube's model
     * of the user. Null when signed out, offline, or when the shelf is simply absent.
     */
    val ytQuickPicks = MutableStateFlow<List<SongItem>?>(null)

    /**
     * True while the Quick picks row has no settled answer yet.
     *
     * Separate from [isLoading], which covers the whole page. This one goes false the moment Quick
     * picks are decided, which for the library source is as soon as the query returns and for the
     * YouTube source is after its shelf has been looked for. The row shows a skeleton while it is
     * true, so a refresh replaces the songs rather than leaving the old ones sitting there.
     */
    val quickPicksLoading = MutableStateFlow(false)
    val forgottenFavorites = MutableStateFlow<List<Song>?>(null)
    val keepListening = MutableStateFlow<List<LocalItem>?>(null)
    val similarRecommendations = MutableStateFlow<List<SimilarRecommendation>?>(null)
    val accountPlaylists = MutableStateFlow<List<PlaylistItem>?>(null)
    val homePage = MutableStateFlow<HomePage?>(null)
    val selectedChip = MutableStateFlow<HomePage.Chip?>(null)

    // A reload asked for while one was already running, and whether it asked to bypass the back-off.
    /**
     * The Quick picks source the last load ran under, and whether that load found YouTube's shelf.
     *
     * Both exist so a refresh can leave the row alone. See [load].
     */
    private var lastQuickPicksSource: QuickPicksSource? = null
    private var foundQuickPicksThisLoad = false

    /**
     * The shelf lifted into the row by this load, chosen over the whole feed at once. A batch
     * scrolled in later lends the row its songs but never replaces the shelf.
     */
    private var lifted: QuickPicksShelf.Lift? = null

    private var pendingRefresh = false
    private var pendingRefreshForce = false
    private val previousHomePage = MutableStateFlow<HomePage?>(null)
    val explorePage = MutableStateFlow<ExplorePage?>(null)
    val playlists = database.playlists(PlaylistFilter.LIBRARY, PlaylistSortType.NAME, true)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
    val recentActivity = database.recentActivity()
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val allLocalItems = MutableStateFlow<List<LocalItem>>(emptyList())
    val allYtItems = MutableStateFlow<List<YTItem>>(emptyList())

    private suspend fun load(force: Boolean) {
        isLoading.value = true
        val source = quickPicksSource()
        foundQuickPicksThisLoad = false
        lifted = null
        ytShelfExtras = emptyList()

        // Blanked only when the source changed, not on every load.
        //
        // Clearing unconditionally is what made a refresh feel slow: the row emptied to a shimmer
        // the instant you pulled, and stayed empty for the second or so the home request takes,
        // even though the songs that came back were usually the same ones. YouTube returns the
        // same Quick picks shelf for the whole session, so that was a second of blank screen to
        // arrive back where it started.
        //
        // It does still have to blank when the source changes. Switching from YouTube to Your
        // library used to leave YouTube's shelf sitting under the heading for the whole load,
        // because the screen draws whatever this flow holds and has no way to know better.
        if (source != lastQuickPicksSource) {
            ytQuickPicksPool = null
            ytQuickPicks.value = null
        }
        lastQuickPicksSource = source

        // Only the YouTube source has anything to wait for, and only when there is nothing to
        // show. A shimmer over a row that already has songs in it is the same lie as blanking it.
        quickPicksLoading.value = source == QuickPicksSource.YOUTUBE && ytQuickPicks.value == null

        // The query already ranks by how many of your seed songs point at each result, strongest
        // first. Shuffling all 100 of them threw that away and gave the 100th the same odds as the
        // 1st. Shuffle inside the strongest 40 instead: still different on each refresh, but drawn
        // from the good end.
        quickPicksPool = database.quickPicks()
            .first().take(60).shuffled()
        if (source == QuickPicksSource.ENGINE || source == QuickPicksSource.COMPARE) {
            val engine = runCatching { buildEngineRow(force) }.onFailure { reportException(it) }.getOrDefault(emptyList())
            enginePoolForCompare = engine
            engineFallback.value = when {
                engine.isNotEmpty() -> { quickPicksPool = engine; 0 }
                quickPicksPool.size >= EngineParams.DEFAULT.minCards -> 1
                else -> 2
            }
        } else {
            engineFallback.value = 0
            shadowBuild()
        }
        rankPools(includeShelf = false)

        // Twice a row's worth, with what was shown lately at the back, so a refresh is the other
        // half rather than the same twenty shuffled.
        forgottenPool = recentlyShown.order("forgotten", database.forgottenFavorites().first().shuffled().take(40)) { it.id }

        val fromTimeStamp = System.currentTimeMillis() - 86400000 * 7 * 2
        val keepListeningSongs = recentlyShown.order("keep", database.mostPlayedSongs(fromTimeStamp, limit = 30, offset = 5).first().shuffled()) { it.id }
            .take(10)
        val keepListeningAlbums = database.mostPlayedAlbums(fromTimeStamp, limit = 8, offset = 2)
            .first().filter { it.album.thumbnailUrl != null }.shuffled().take(5)
        val keepListeningArtists = database.mostPlayedArtists(0, 1)
            .first().filter { it.artist.isYouTubeArtist && it.artist.thumbnailUrl != null }.shuffled().take(5)
        keepListeningPool = (keepListeningSongs + keepListeningAlbums + keepListeningArtists).shuffled()
        tidyRows()

        allLocalItems.value =
            (quickPicks.value.orEmpty() + forgottenFavorites.value.orEmpty() + keepListening.value.orEmpty())
                .filter { it is Song || it is Album }

        // Your own playlists come first, and are exempt from both gates below.
        //
        // Asking YouTube for the playlists you made is not a recommendation, so the source does not
        // govern it. It is also not the kind of request the back-off is protecting you from: the
        // back-off exists to stop a pile of recommendation lookups hammering a network that is
        // already refusing us, and this is one request for your own data. It used to sit below the
        // back-off guard, which meant a back-off silently cost you your own playlists too.
        //
        // Assigned on every path, including failure and signed out, because leaving the previous
        // value in place shows a signed-out user the last account's playlists.
        if (YouTube.cookie != null) {
            YouTube.library("FEmusic_liked_playlists").completed().onSuccess {
                accountPlaylists.value = it.items.filterIsInstance<PlaylistItem>()
            }.onFailure {
                // Left as it was. A transient failure blanking your playlists is worse than
                // briefly showing the previous list, and this runs on every home load.
                reportException(it)
            }
        } else {
            accountPlaylists.value = null
        }

        // The Quick picks source governs the Quick picks row and nothing else. It briefly governed
        // the whole page, hiding YouTube's carousels, its "Similar to" rows and its mood tiles when
        // the library was chosen, and that was wrong: choosing where one row's songs come from is
        // not a request to empty the home screen. See takeQuickPicks, which is the whole of it.

        // Everything below here is remote recommendation work, and opening the app fires all of it:
        // an artist lookup per seed, a related lookup per seed, home, and explore. While YouTube is
        // refusing this network that is a pile of requests that cannot succeed and that make the
        // refusal last longer. Pulling to refresh passes force and still tries, because the user
        // asked for it and one request doubles as the probe that clears the block.
        if (!force && Throttle.isBlocked) {
            Log.d("HomeViewModel", "Skipping remote home load, backing off")
            noteShown()
            quickPicksLoading.value = false
            isLoading.value = false
            return
        }

        // Similar to artists
        val artistRecommendations =
            database.mostPlayedArtists(0, 1, limit = 10).first()
                .filter { it.artist.isYouTubeArtist }
                .shuffled().take(3)
                .mapNotNull {
                    val items = mutableListOf<YTItem>()
                    YouTube.artist(it.id).onSuccess { page ->
                        items += page.sections.getOrNull(page.sections.size - 2)?.items.orEmpty()
                        items += page.sections.lastOrNull()?.items.orEmpty()
                    }
                    SimilarRecommendation(
                        title = it,
                        items = items
                            .shuffled()
                            .ifEmpty { return@mapNotNull null }
                    )
                }
        // Similar to songs
        val songRecommendations =
            database.mostPlayedSongs(fromTimeStamp, limit = 10).first()
                .filter { it.album != null }
                .shuffled().take(2)
                .mapNotNull { song ->
                    val endpoint = YouTube.next(WatchEndpoint(videoId = song.id)).getOrNull()?.relatedEndpoint
                        ?: return@mapNotNull null
                    val page = YouTube.related(endpoint).getOrNull() ?: return@mapNotNull null
                    SimilarRecommendation(
                        title = song,
                        // A row titled "Similar to Bohemian Rhapsody" must not offer Bohemian
                        // Rhapsody live at Live Aid, and the related shelf does sometimes carry a
                        // version of the seed.
                        items = (page.songs
                            .filterNot { it.id == song.id || SongVersions.isVersionOf(it.title, song.song.title) }
                            .shuffled().take(8) +
                                page.albums.shuffled().take(4) +
                                page.artists.shuffled().take(4) +
                                page.playlists.shuffled().take(4))
                            .shuffled()
                            .ifEmpty { return@mapNotNull null }
                    )
                }
        similarPool = (artistRecommendations + songRecommendations).shuffled()
        tidyRows()

        YouTube.home().onSuccess { page ->
            // Read past the first response, because on its own it is not a home feed.
            //
            // Measured against the live API on 8 Sep, signed out: the first response is 3 sections
            // and 25 items, and that is the whole of what a refresh used to show. Two refreshes back
            // to back share about half their items, so pulling to refresh mostly reprinted what was
            // already there, in a different order, and the rest of the feed was only reachable by
            // scrolling far enough to trigger loadMoreYouTubeItems. Walking the continuations
            // instead: batch 1 adds 22 items that were not in batch 0, batch 2 adds 24 more, and
            // then the continuation goes null. 71 unique items rather than 25, so a refresh draws
            // from a pool nearly three times the size and genuinely lands on new songs.
            //
            // Capped rather than exhaustive. That feed ended on its own after two continuations,
            // but a signed-in feed is longer and there is no reason to let one refresh walk it to
            // the end; HOME_REFRESH_BATCHES is the ceiling and scrolling picks up from wherever
            // this left off, since the surviving continuation is carried into merged.
            //
            // Quick picks is one reason to read on: YouTube puts that shelf at index 0 of the first
            // continuation rather than in the first response. It is lifted out once the batches
            // are together, so the choice is made over the whole feed and not batch by batch.
            var merged = page
            var batches = 0
            while (batches < HOME_REFRESH_BATCHES) {
                val next = merged.continuation ?: break
                val page2 = YouTube.home(next).getOrNull() ?: break
                merged = merged.copy(
                    sections = merged.sections + page2.sections,
                    continuation = page2.continuation,
                )
                batches++
            }
            merged = takeQuickPicks(merged)
            Log.d(
                "HomeViewModel",
                "Home loaded: ${merged.sections.size} sections, " +
                        "${merged.sections.sumOf { it.items.size }} items, $batches continuations"
            )
            homePagePool = merged
        }.onFailure {
            reportException(it)
        }
        // The row is kept across loads now, so a shelf that has actually disappeared has to be
        // cleared here or last load's songs would sit there indefinitely.
        if (ytShelfWanted() && !foundQuickPicksThisLoad) {
            ytQuickPicksPool = null
            ytQuickPicks.value = null
        }
        if (foundQuickPicksThisLoad) {
            if (moreOnNextLoad && !Throttle.isBlocked) runCatching { fetchRelatedToShelf() }.onFailure { reportException(it) }
            ytQuickPicksPool = ytRowPool()
            rankPools(includeShelf = true)
        }
        moreOnNextLoad = false
        if (source == QuickPicksSource.COMPARE && engineFallback.value == 0) runCatching { draftCompareRow() }.onFailure { reportException(it) }
        tidyRows()
        noteShown()

        // Settled either way: found, or looked for and not there. Leaving it true on failure would
        // leave a skeleton shimmering over a row that is never going to fill.
        quickPicksLoading.value = false

        YouTube.explore().onSuccess { page ->
            explorePage.value = page
        }.onFailure {
            reportException(it)
        }

        syncUtils.syncRecentActivity()

        allYtItems.value = similarPool?.flatMap { it.items }.orEmpty() +
                homePagePool?.sections?.flatMap { it.items }.orEmpty()

        isLoading.value = false
    }

    /**
     * YouTube's Quick picks shelf in one batch of the feed, chosen by [QuickPicksShelf]: the one
     * carrying that title, else the first list of songs that is mostly songs rather than hour-long
     * mixes. Every song shelf in the batch is logged with its share of songs, since the choice is
     * only as good as the feed it is made from.
     */
    private fun picksShelf(page: HomePage): QuickPicksShelf.Lift? {
        page.sections.filter(QuickPicksShelf::isSongShelf).forEach { c ->
            Log.d("HomeViewModel", "song shelf \"${c.title}\": ${c.items.size} items, ${c.itemsPerColumn} per column, ${"%.0f".format(100 * QuickPicksShelf.songShare(c))}% songs")
        }
        val lift = QuickPicksShelf.choose(page, context.getString(R.string.quick_picks))
        Log.d("HomeViewModel", "picks shelf: ${lift?.section?.title ?: "none"}${if (lift?.titled == true) " (by title)" else ""}")
        return lift
    }

    private fun quickPicksSource(): QuickPicksSource =
        context.dataStore.get(QuickPicksSourceKey, QuickPicksSource.YOUTUBE.name)
            .toEnum(QuickPicksSource.YOUTUBE)

    /** Whether YouTube's shelf is the Quick picks row: chosen as the source, or standing in for the engine. */
    private fun ytShelfWanted(): Boolean {
        val source = quickPicksSource()
        return source == QuickPicksSource.YOUTUBE || source == QuickPicksSource.COMPARE || (source == QuickPicksSource.ENGINE && engineFallback.value == 2)
    }

    /**
     * Lifts YouTube's Quick picks shelf out of the feed into the row, and lends the row the songs
     * of the feed's other song shelves, which stay where they are. With [canLift] false, as for a
     * batch scrolled in after the row is on screen, only the lending happens: the row does not
     * change under the finger.
     */
    private fun takeQuickPicks(page: HomePage, canLift: Boolean = true): HomePage {
        val lift = if (canLift) picksShelf(page) else null
        ytShelfExtras = ytShelfExtras + page.sections.filter { it !== lift?.section && QuickPicksShelf.lendsSongs(it) }.flatMap { it.items.filterIsInstance<SongItem>() }
        lift ?: return page

        // Set to Your library and YouTube's shelf is left where it is, rendering as an ordinary
        // section of the feed rather than being lifted into the row. The row on that setting is the
        // app's own recommendations, so there is nothing to lift it for, and removing it would just
        // lose a section of the feed.
        //
        // It does get renamed. YouTube calls that shelf "Quick picks" too, so leaving its title
        // alone put two rows with the same heading on the same screen, one of them the library row
        // and one of them the thing that row exists instead of. That reads as a bug even though
        // both rows are correct. Only the shelf that really carries the title needs it; one found
        // by content has a title of its own that does not clash.
        if (!ytShelfWanted()) {
            if (!lift.titled) return page
            return page.copy(
                sections = page.sections.map {
                    if (it === lift.section) it.copy(title = context.getString(R.string.youtube_picks)) else it
                }
            )
        }

        lifted = lift
        foundQuickPicksThisLoad = true
        ytShelfSongs = lift.section.items.filterIsInstance<SongItem>()
        Log.d("HomeViewModel", "lifted \"${lift.section.title}\" into Quick picks: ${ytShelfSongs.size} songs, first ${ytShelfSongs.firstOrNull()?.title}")
        return page.copy(sections = page.sections - lift.section)
    }

    /** The YouTube row's pool, tier by tier, no song twice. */
    private fun ytRowPool(): List<SongItem> {
        val seen = HashSet<String>()
        val out = ArrayList<SongItem>()
        ytTier.clear()
        fun add(items: Collection<SongItem>, tier: Int) = items.forEach { if (seen.add(it.id)) { out += it; ytTier[it.id] = tier } }
        add(ytShelfSongs, 0); add(ytShelfExtras, 1); add(ytRelated, 2)
        Log.d("HomeViewModel", "YouTube row pool: ${ytShelfSongs.size} from the shelf, ${ytShelfExtras.size} from the feed's other song shelves, ${ytRelated.size} related, ${out.size} in all")
        return out
    }

    /**
     * A pull to refresh asks for songs that were not there before, and the shelf is the same for a
     * whole session. So two of its songs not yet used are taken as seeds and what YouTube lists as
     * related to them joins the pool: songs only, no mixes, kept across loads up to a hundred with
     * the oldest out first. Two seeds is four requests, about what the Similar to rows already
     * spend on every load.
     */
    private suspend fun fetchRelatedToShelf() {
        val seeds = ytShelfSongs.filter { it.id !in ytRelatedSeeds }.shuffled().take(RELATED_SEEDS_PER_PULL)
        for (seed in seeds) {
            ytRelatedSeeds += seed.id
            val endpoint = YouTube.next(WatchEndpoint(videoId = seed.id)).getOrNull()?.relatedEndpoint ?: continue
            val songs = YouTube.related(endpoint).getOrNull()?.songs.orEmpty().filter { (it.duration ?: 0) <= QuickPicksShelf.SONG_MAX_SEC }
            val known = HashSet<String>()
            ytRelated.forEach { known += it.id }; ytShelfSongs.forEach { known += it.id }; ytShelfExtras.forEach { known += it.id }
            songs.filter { known.add(it.id) }.forEach { ytRelated.addLast(it) }
            while (ytRelated.size > RELATED_POOL_MAX) ytRelated.removeFirst()
            Log.d("HomeViewModel", "related to ${seed.title}: ${songs.size} songs, ${ytRelated.size} related in the pool")
        }
    }

    /** What the rows are showing after this load, remembered so the next refresh brings forward what they have not. */
    private fun noteShown() {
        val src = quickPicksSource()
        val ytShown = ytShelfWanted() && !ytQuickPicksPool.isNullOrEmpty()
        val libraryShown = !ytShown && (src == QuickPicksSource.LIBRARY || src == QuickPicksSource.YOUTUBE || engineFallback.value == 1)
        if (ytShown) recentlyShown.note("yt", ytQuickPicks.value.orEmpty().map { it.id })
        if (libraryShown) recentlyShown.note("lib", quickPicks.value.orEmpty().map { it.id })
        recentlyShown.note("forgotten", forgottenFavorites.value.orEmpty().map { it.id })
        recentlyShown.note("keep", keepListening.value.orEmpty().mapNotNull { (it as? Song)?.id })
    }

    private val _isLoadingMore = MutableStateFlow(false)
    fun loadMoreYouTubeItems(continuation: String?) {
        if (continuation == null || _isLoadingMore.value) return

        viewModelScope.launch(Dispatchers.IO) {
            _isLoadingMore.value = true
            val nextSections = YouTube.home(continuation).getOrNull() ?: run {
                _isLoadingMore.value = false
                return@launch
            }
            val cleaned = takeQuickPicks(nextSections, canLift = lifted == null)
            homePagePool = cleaned.copy(
                chips = homePagePool?.chips,
                sections = homePagePool?.sections.orEmpty() + cleaned.sections
            )
            if (foundQuickPicksThisLoad && ytQuickPicksPool == null) {
                ytQuickPicksPool = ytRowPool()
                rankPools(includeShelf = true)
            }
            tidyRows()
            _isLoadingMore.value = false
        }
    }

    fun toggleChip(chip: HomePage.Chip?) {
        if (chip == null || chip == selectedChip.value && previousHomePage.value != null) {
            homePagePool = previousHomePage.value
            previousHomePage.value = null
            selectedChip.value = null
            applyTidy()
            return
        }

        if (selectedChip.value == null) {
            // store the actual homepage for deselecting chips
            previousHomePage.value = homePagePool
        }
        viewModelScope.launch(Dispatchers.IO) {
            val nextSections = YouTube.home(params = chip?.endpoint?.params).getOrNull() ?: return@launch
            homePagePool = nextSections.copy(
                chips = homePagePool?.chips,
                sections = nextSections.sections,
                continuation = nextSections.continuation
            )
            tidyRows()
            selectedChip.value = chip
        }
    }

    /**
     * Reloads the page.
     *
     * The old guard was `if (isRefreshing.value) return`, which threw the request away whenever a
     * load happened to be in flight. That is fine for a second pull on the refresh indicator and
     * wrong for everything else: the sign-in watcher below is the only thing that reloads when a
     * cookie appears, its upstream is distinctUntilChanged, so a dropped emission never comes back
     * and the page stays signed out for the life of the view model.
     *
     * So a dropped request is remembered instead, and the running load repeats when it finishes.
     * The try/finally matters too: without it a thrown load latched isRefreshing true and disabled
     * both this watcher and pull to refresh permanently.
     */
    fun refresh(force: Boolean = false) {
        if (isRefreshing.value) {
            pendingRefresh = true
            pendingRefreshForce = pendingRefreshForce || force
            return
        }
        viewModelScope.launch(syncCoroutine) {
            isRefreshing.value = true
            try {
                var nextForce = force
                do {
                    pendingRefresh = false
                    load(nextForce)
                    nextForce = pendingRefreshForce
                    pendingRefreshForce = false
                } while (pendingRefresh)
            } finally {
                isRefreshing.value = false
            }
        }
    }

    init {
        refresh()
        viewModelScope.launch(syncCoroutine) {
            syncUtils.tryAutoSync()
        }

        // Signing in changes what this whole page should show, and nothing recreated this view
        // model when it happened, so the feed sat there showing the signed-out page until the app
        // was restarted. Signing out has the same problem in reverse. Changing where Quick picks
        // come from is the same situation: the setting is read while building the page, so without
        // this it would appear to do nothing until something else happened to reload.
        //
        // force, because this is a real change of state rather than a routine open, so it should go
        // out even while backing off. drop(1) because init above has already loaded once, and the
        // flow replays its current value the moment it is collected.
        viewModelScope.launch {
            context.dataStore.data
                .map { it[InnerTubeCookieKey].orEmpty() to it[QuickPicksSourceKey].orEmpty() }
                .distinctUntilChanged()
                .drop(1)
                .collect { refresh(force = true) }
        }
    }
}

/**
 * How many home continuations one load is allowed to read past the first response.
 *
 * Three is the measured shape of the signed-out feed plus headroom: batches 0 to 2 hold the whole
 * of it, and the fourth request is the one that discovers the continuation has gone null. A
 * signed-in feed runs longer, and this is the point at which a refresh stops walking it and leaves
 * the rest to scrolling.
 */
private const val HOME_REFRESH_BATCHES = 3

/** Seeds taken from the shelf on each pull to refresh, for songs related to them. */
private const val RELATED_SEEDS_PER_PULL = 2

/** How many related songs the YouTube row keeps across loads. */
private const val RELATED_POOL_MAX = 100
