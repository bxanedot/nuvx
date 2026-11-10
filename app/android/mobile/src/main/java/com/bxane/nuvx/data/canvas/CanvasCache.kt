package com.bxane.nuvx.data.canvas

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

@UnstableApi
object CanvasCache {

    private const val CACHE_LIMIT_BYTES = 150L * 1024 * 1024

    private lateinit var cache: SimpleCache

    fun init(context: Context) {
        cache = SimpleCache(
            File(context.cacheDir, "canvas"),
            LeastRecentlyUsedCacheEvictor(CACHE_LIMIT_BYTES),
            StandaloneDatabaseProvider(context),
        )
    }

    fun dataSourceFactory(upstream: DataSource.Factory): DataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
}
