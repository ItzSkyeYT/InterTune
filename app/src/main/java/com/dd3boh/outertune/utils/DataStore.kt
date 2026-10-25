package com.dd3boh.outertune.utils

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.dd3boh.outertune.constants.NewInstallSongCache
import com.dd3boh.outertune.lyrics.LyricsTimingOnTime
import com.dd3boh.outertune.extensions.toEnum
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.properties.ReadOnlyProperty

/**
 * The file itself, reached only through [dataStore], so that no write goes round the copy in memory.
 * A new install's song cache is written in as the file is first read: see [NewInstallSongCache].
 * At the same moment, once, the lyrics timing saved before the lyrics clock was put right is moved
 * 100 ms later: see [LyricsTimingOnTime].
 */
private val Context.settingsFile: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    produceMigrations = { listOf(NewInstallSongCache, LyricsTimingOnTime) },
)

@Volatile
private var settings: SettingsSnapshot? = null

/**
 * The app's settings, read from memory: see [SettingsSnapshot]. The first use reads the file,
 * which is App.onCreate's first read of a setting.
 */
val Context.dataStore: SettingsSnapshot
    get() = settings ?: synchronized(SettingsSnapshot::class.java) {
        settings ?: SettingsSnapshot(applicationContext.settingsFile).also { settings = it }
    }

operator fun <T> SettingsSnapshot.get(key: Preferences.Key<T>): T? = current[key]

fun <T> SettingsSnapshot.get(key: Preferences.Key<T>, defaultValue: T): T = current[key] ?: defaultValue

fun <T> preference(
    context: Context,
    key: Preferences.Key<T>,
    defaultValue: T,
) = ReadOnlyProperty<Any?, T> { _, _ -> context.dataStore[key] ?: defaultValue }

inline fun <reified T : Enum<T>> enumPreference(
    context: Context,
    key: Preferences.Key<String>,
    defaultValue: T,
) = ReadOnlyProperty<Any?, T> { _, _ -> context.dataStore[key].toEnum(defaultValue) }

@Composable
fun <T> rememberPreference(
    key: Preferences.Key<T>,
    defaultValue: T,
): MutableState<T> {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Remembered, not inline: as a plain argument the read re-ran on every recomposition, when it
    // was still a blocking IO read. Same reasoning as rememberNullablePreference below.
    val initialValue = remember(key) { context.dataStore[key] ?: defaultValue }
    val state = remember {
        context.dataStore.data
            .map { it[key] ?: defaultValue }
            .distinctUntilChanged()
    }.collectAsState(initialValue)

    return remember {
        object : MutableState<T> {
            override var value: T
                get() = state.value
                set(value) {
                    // NonCancellable: the screen that set it may close at once (the player menu's
                    // Toggle lyrics does), which cancels this scope, and DataStore runs the edit in
                    // its caller's context, so the write was sometimes dropped.
                    coroutineScope.launch(NonCancellable) {
                        context.dataStore.edit {
                            it[key] = value
                        }
                    }
                }

            override fun component1() = value
            override fun component2(): (T) -> Unit = { value = it }
        }
    }
}

/**
 * Reads a preference without collapsing "never set" into the default.
 *
 * [rememberPreference] substitutes the default for a missing key, so "said no" and "was never
 * asked" arrive identical. Asking a question exactly once needs to tell those apart, and needs to
 * see the answer land in the same session rather than at the next launch.
 *
 * The initial value is remembered rather than passed inline: a plain argument would be
 * re-evaluated on every recomposition, which cost a blocking IO read while reads still were one.
 */
@Composable
fun <T> rememberNullablePreference(key: Preferences.Key<T>): State<T?> {
    val context = LocalContext.current
    val initial = remember(key) { context.dataStore[key] }
    val flow = remember(key) {
        context.dataStore.data
            .map { it[key] }
            .distinctUntilChanged()
    }
    return flow.collectAsState(initial)
}

@Composable
inline fun <reified T : Enum<T>> rememberEnumPreference(
    key: Preferences.Key<String>,
    defaultValue: T,
): MutableState<T> {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Remembered for the same reason as in rememberPreference: the read must not be re-evaluated
    // on every recomposition.
    val initialValue = remember(key) { context.dataStore[key].toEnum(defaultValue = defaultValue) }
    val state = remember {
        context.dataStore.data
            .map { it[key].toEnum(defaultValue = defaultValue) }
            .distinctUntilChanged()
    }.collectAsState(initialValue)

    return remember {
        object : MutableState<T> {
            override var value: T
                get() = state.value
                set(value) {
                    // NonCancellable for the same reason as in rememberPreference.
                    coroutineScope.launch(NonCancellable) {
                        context.dataStore.edit {
                            it[key] = value.name
                        }
                    }
                }

            override fun component1() = value
            override fun component2(): (T) -> Unit = { value = it }
        }
    }
}
