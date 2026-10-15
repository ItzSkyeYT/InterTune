package com.dd3boh.outertune.utils

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor

/**
 * A setting changed from a screen that closes at once is kept.
 *
 * The player menu's Toggle lyrics closes the menu and then sets the preference, and the preference
 * was written from the menu's own coroutine scope. Closing the menu cancels that scope, and
 * DataStore runs an edit in its caller's context, so the write could be dropped: once on 15 Oct
 * 2026 the next player opened on lyrics after they had been turned off. The setters in
 * DataStore.kt now write in NonCancellable.
 */
class PreferenceWriteOutlivesScreenTest {

    @get:Rule
    val dir = TemporaryFolder()

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val store: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = storeScope) { dir.root.resolve("settings.preferences_pb") }
    }

    private val key = booleanPreferencesKey("showLyrics")

    @After
    fun close() = storeScope.cancel()

    /** A screen's scope whose coroutines run only when the test lets them, as on its next frame. */
    private class Screen {
        val queued = ConcurrentLinkedQueue<Runnable>()
        val scope = CoroutineScope(Job() + Executor { queued.add(it) }.asCoroutineDispatcher())

        /** Runs what the screen has queued until [done] or two seconds have gone. */
        fun runUntil(done: () -> Boolean) {
            val end = System.currentTimeMillis() + 2_000
            while (!done() && System.currentTimeMillis() < end) {
                queued.poll()?.run() ?: Thread.sleep(5)
            }
        }
    }

    private fun stored(): Boolean? = runBlocking { store.data.first()[key] }

    @Test
    fun aWriteLaunchedAsTheScreenClosesIsLostInTheScreensOwnScope() {
        runBlocking { store.edit { it[key] = true } }
        val screen = Screen()

        val write = screen.scope.launch { store.edit { it[key] = false } }
        screen.scope.cancel()
        screen.runUntil { write.isCompleted }

        assertTrue(write.isCancelled)
        assertEquals("the menu's own scope drops the write", true, stored())
    }

    @Test
    fun aWriteLaunchedAsTheScreenClosesIsKeptInNonCancellable() {
        runBlocking { store.edit { it[key] = true } }
        val screen = Screen()

        val write = screen.scope.launch(NonCancellable) { store.edit { it[key] = false } }
        screen.scope.cancel()
        screen.runUntil { write.isCompleted }

        assertEquals("NonCancellable keeps it", false, stored())
    }

    @Test
    fun everySetterInDataStoreWritesInNonCancellable() {
        val source = File("src/main/java/com/dd3boh/outertune/utils/DataStore.kt").readText()
        val launches = Regex("""coroutineScope\.launch(\([^)]*\))?\s*\{""").findAll(source).map { it.value }.toList()

        assertEquals("rememberPreference and rememberEnumPreference", 2, launches.size)
        launches.forEach { assertEquals("coroutineScope.launch(NonCancellable) {", it) }
    }
}
