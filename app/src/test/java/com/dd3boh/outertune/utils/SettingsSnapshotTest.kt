package com.dd3boh.outertune.utils

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections

/**
 * The settings are read from memory, and a write is there before its edit returns.
 *
 * A read used to be `runBlocking(Dispatchers.IO) { data.first() }`: a thread hop and a flow
 * collection for each of the hundreds of reads a launch makes, the main thread's included. A copy
 * kept by a separate collector of the file would trail each write, and Home reads the setting it
 * watches at the moment the change reaches it. See SettingsSnapshot.
 */
class SettingsSnapshotTest {

    @get:Rule
    val dir = TemporaryFolder()

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val file: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = storeScope) { dir.root.resolve("settings.preferences_pb") }
    }

    private val key = intPreferencesKey("familiarity")

    @After
    fun close() = storeScope.cancel()

    @Test
    fun theFirstValueIsWhatTheFileHeld() {
        runBlocking { file.edit { it[key] = 7 } }
        val settings = SettingsSnapshot(file)
        assertEquals(7, settings[key])
        assertEquals(3, settings.get(intPreferencesKey("missing"), 3))
    }

    @Test
    fun aWriteIsInMemoryBeforeItsEditReturns() {
        val settings = SettingsSnapshot(file)
        repeat(50) { n ->
            runBlocking { settings.edit { it[key] = n } }
            assertEquals(n, settings[key])
        }
        assertEquals("and it is in the file", 49, runBlocking { file.data.first()[key] })
    }

    @Test
    fun aCollectorThatReadsTheSettingOnAChangeReadsTheChange() {
        val settings = SettingsSnapshot(file)
        runBlocking { settings.edit { it[key] = 0 } }
        val behind = Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())
        val seen = Collections.synchronizedList(mutableListOf<Int>())
        val ready = CompletableDeferred<Unit>()
        val watcher = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            watcher.launch {
                settings.data.map { it[key] ?: 0 }.distinctUntilChanged().collect { changed ->
                    if (changed == 0) { ready.complete(Unit); return@collect }
                    // What Home does: rebuild on the change, reading the setting as it builds.
                    val read = settings.get(key, -1)
                    if (read < changed) behind.add(changed to read)
                    seen.add(changed)
                }
            }
            runBlocking {
                // The collector is in place before the first change.
                withTimeout(5_000) { ready.await() }
                for (n in 1..200) settings.edit { it[key] = n }
                withTimeout(5_000) { while (seen.lastOrNull() != 200) delay(5) }
            }
        } finally {
            watcher.cancel()
        }
        assertTrue("reads behind the change they reacted to: $behind", behind.isEmpty())
    }

    @Test
    fun writesAtOnceNeverPutAnOlderCopyBack() {
        val settings = SettingsSnapshot(file)
        runBlocking { settings.edit { it[key] = 0 } }
        val shown = Collections.synchronizedList(mutableListOf<Int>())
        val watcher = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            watcher.launch { settings.data.collect { shown.add(it[key] ?: 0) } }
            runBlocking {
                (1..100).map {
                    async(Dispatchers.IO) { settings.edit { it[key] = (it[key] ?: 0) + 1 } }
                }.awaitAll()
                assertEquals(100, settings[key])
                assertEquals(100, file.data.first()[key])
                withTimeout(5_000) { while (shown.lastOrNull() != 100) delay(5) }
            }
        } finally {
            watcher.cancel()
        }
        val snapshot = synchronized(shown) { shown.toList() }
        assertEquals("never backwards: $snapshot", snapshot.sorted(), snapshot)
    }

    /** A file that takes a write and then loses its caller, as DataStore does when the caller is cancelled while it waits. */
    private class CallerGoneAfterWrite(initial: Preferences) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        private val lock = Mutex()
        @Volatile var dropNextCaller = false
        override val data = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            lock.withLock {
                val after = transform(state.value)
                state.value = after
                if (dropNextCaller) {
                    dropNextCaller = false
                    throw CancellationException("the caller stopped waiting")
                }
                after
            }
    }

    @Test
    fun aWriteThatLandsAfterItsCallerStoppedWaitingStillReachesTheCopy() {
        val file = CallerGoneAfterWrite(emptyPreferences())
        val settings = SettingsSnapshot(file)
        file.dropNextCaller = true
        val gone = runCatching { runBlocking { settings.edit { it[key] = 9 } } }.exceptionOrNull()
        assertTrue("the caller heard nothing back: $gone", gone is CancellationException)
        runBlocking { withTimeout(5_000) { settings.data.first { it[key] == 9 } } }
        assertEquals(9, settings[key])
    }

    @Test
    fun noReadOfASettingBlocks() {
        val source = File("src/main/java/com/dd3boh/outertune/utils/DataStore.kt").readText()
        assertFalse("DataStore.kt reads from memory", "runBlocking" in source)
        // The file is reached only through the copy in memory, so no write can go round it.
        assertEquals(1, Regex("""preferencesDataStore\(""").findAll(source).count())
        assertTrue(Regex("""private val Context\.\w+: DataStore<Preferences> by preferencesDataStore\(""").containsMatchIn(source))
    }

    @Test
    fun theAppReadsTheFileOnceAtStart() {
        val app = File("src/main/java/com/dd3boh/outertune/App.kt").readText()
        assertFalse("ContentFilter starts from the copy in memory", "dataStore.data.first()" in app)
        val snapshot = File("src/main/java/com/dd3boh/outertune/utils/SettingsSnapshot.kt").readText()
        assertEquals("the one read of the file", 1, Regex("""runBlocking""").findAll(snapshot.substringAfter("class SettingsSnapshot")).count())
    }
}
