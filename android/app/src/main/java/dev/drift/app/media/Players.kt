package dev.drift.app.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import dev.drift.core.Media

/** Something ExoPlayer can play, picked from a [Media] (best source first). */
data class Playable(val uri: String, val mimeType: String, val hasAudio: Boolean, val loop: Boolean)

fun Media.playable(): Playable? = when (this) {
    is Media.Video -> when {
        hlsUrl != null -> Playable(hlsUrl!!, MimeTypes.APPLICATION_M3U8, hasAudio, loop = true)
        dashUrl != null -> Playable(dashUrl!!, MimeTypes.APPLICATION_MPD, hasAudio, loop = true)
        mp4Url != null -> Playable(mp4Url!!, MimeTypes.VIDEO_MP4, hasAudio, loop = true)
        else -> null
    }
    is Media.Gif -> Playable(mp4Url, MimeTypes.VIDEO_MP4, hasAudio = false, loop = true)
    else -> null
}

fun Playable.toMediaItem(): MediaItem = MediaItem.Builder().setUri(uri).setMimeType(mimeType).build()

@OptIn(UnstableApi::class)
object VideoCache {
    @Volatile private var cache: SimpleCache? = null

    private fun cache(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(
            context.cacheDir.resolve("video"),
            LeastRecentlyUsedCacheEvictor(512L * 1024 * 1024),
            StandaloneDatabaseProvider(context),
        ).also { cache = it }
    }

    /** Every byte played goes through the disk cache, so replays and
     *  re-opening the same clip full screen don't hit the network. */
    fun dataSourceFactory(context: Context): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache(context.applicationContext))
            .setUpstreamDataSourceFactory(
                DefaultHttpDataSource.Factory()
                    .setUserAgent("android:dev.drift.app")
                    .setAllowCrossProtocolRedirects(true)
            )
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
}

/** ExoPlayer that always takes the highest-bitrate track the device can decode. */
@OptIn(UnstableApi::class)
fun buildPlayer(context: Context): ExoPlayer {
    val trackSelector = DefaultTrackSelector(context).apply {
        setParameters(buildUponParameters().setForceHighestSupportedBitrate(true))
    }
    return ExoPlayer.Builder(context)
        .setTrackSelector(trackSelector)
        .setMediaSourceFactory(DefaultMediaSourceFactory(VideoCache.dataSourceFactory(context)))
        .build()
}

/**
 * The one player shared by the whole feed. Only the most visible video card
 * renders it; switching cards just swaps the media item, so there is never
 * more than one hardware decoder busy for the feed.
 */
class FeedPlayer(context: Context) {
    val player: ExoPlayer = buildPlayer(context).apply {
        repeatMode = Player.REPEAT_MODE_ONE
        volume = 0f
    }

    /** Post id of the media loaded in the player. */
    var currentKey by mutableStateOf<String?>(null)
        private set

    /** True once the current item has drawn a frame (hide the preview then). */
    var hasFirstFrame by mutableStateOf(false)
        private set

    var muted by mutableStateOf(true)
        private set

    private var paused = false

    init {
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                hasFirstFrame = true
            }
        })
    }

    fun play(key: String, playable: Playable) {
        if (key == currentKey) return
        currentKey = key
        hasFirstFrame = false
        player.setMediaItem(playable.toMediaItem())
        player.prepare()
        player.playWhenReady = !paused
    }

    fun stop() {
        if (currentKey == null) return
        currentKey = null
        hasFirstFrame = false
        player.stop()
        player.clearMediaItems()
    }

    fun toggleMute() {
        muted = !muted
        player.volume = if (muted) 0f else 1f
    }

    /** Pause while the app is in the background or the viewer is open. */
    fun setPaused(value: Boolean) {
        paused = value
        player.playWhenReady = !value && currentKey != null
    }

    fun positionFor(key: String): Long = if (key == currentKey) player.currentPosition else 0L

    fun release() = player.release()
}
