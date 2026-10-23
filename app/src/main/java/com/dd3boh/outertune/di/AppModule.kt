package com.dd3boh.outertune.di

import android.content.Context
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.dd3boh.outertune.constants.MaxSongCacheSizeKey
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DownloadCache

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Singleton
    @Provides
    fun provideDatabase(@ApplicationContext context: Context): MusicDatabase =
        InternalDatabase.newInstance(context)

    @Singleton
    @Provides
    fun provideDatabaseProvider(@ApplicationContext context: Context): DatabaseProvider =
        StandaloneDatabaseProvider(context)

    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    annotation class PlayerCache

    /*
     * Each cache is built once. Both used to be built, released and built again (InnerTune's "Fix
     * player error", 2023, which says nothing of why), and a release waits for the first build to
     * read its whole folder and index, so that read ran on the thread that asked first: the main
     * thread, as the first screen or the service was created. A single build reads the folder on
     * a thread of its own, and the first use of the cache waits for it, as it did for the second.
     */

    @Singleton
    @Provides
    @PlayerCache
    fun providePlayerCache(@ApplicationContext context: Context, databaseProvider: DatabaseProvider): SimpleCache =
        SimpleCache(
            context.filesDir.resolve("exoplayer"),
            when (val cacheSize = context.dataStore[MaxSongCacheSizeKey] ?: 0) {
                -1 -> NoOpCacheEvictor()
                else -> LeastRecentlyUsedCacheEvictor(cacheSize * 1024 * 1024L)
            },
            databaseProvider
        )

    @Singleton
    @Provides
    @DownloadCache
    fun provideDownloadCache(@ApplicationContext context: Context, databaseProvider: DatabaseProvider): SimpleCache =
        SimpleCache(context.filesDir.resolve("download"), NoOpCacheEvictor(), databaseProvider)
}
