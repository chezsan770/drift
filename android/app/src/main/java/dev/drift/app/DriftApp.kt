package dev.drift.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dev.drift.app.settings.SettingsStore
import dev.drift.core.CoreConfig
import dev.drift.core.DriftCore
import java.security.SecureRandom

class DriftApp : Application(), SingletonImageLoader.Factory {

    val settings: SettingsStore by lazy { SettingsStore(this) }

    /** Single Rust core for the whole process (owns the HTTP pool and caches). */
    val core: DriftCore by lazy {
        DriftCore(
            CoreConfig(
                clientId = BuildConfig.REDDIT_CLIENT_ID.ifBlank { null },
                userAgent = buildString {
                    append("android:dev.drift.app:v${BuildConfig.VERSION_NAME}")
                    if (BuildConfig.REDDIT_USERNAME.isNotBlank()) append(" (by /u/${BuildConfig.REDDIT_USERNAME})")
                },
                deviceId = deviceId(),
                cacheDir = cacheDir.path,
            )
        )
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory()) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("images"))
                    .maxSizeBytes(512L * 1024 * 1024)
                    .build()
            }
            .crossfade(150)
            .build()

    /** Random per-install id for Reddit's app-only OAuth (not tied to the user). */
    private fun deviceId(): String {
        val prefs = getSharedPreferences("drift", MODE_PRIVATE)
        prefs.getString("device_id", null)?.let { return it }
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val rnd = SecureRandom()
        val id = (1..25).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
        prefs.edit().putString("device_id", id).apply()
        return id
    }
}
