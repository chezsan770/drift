package dev.drift.app.feed

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import coil3.compose.AsyncImage
import dev.drift.app.media.FeedPlayer
import dev.drift.core.Media
import dev.drift.core.Post

private val Side = 16.dp

/** Title, media, one quiet metadata line. Nothing else. */
@Composable
fun PostCard(
    post: Post,
    isActiveVideo: Boolean,
    feedPlayer: FeedPlayer,
    onOpenMedia: (page: Int) -> Unit,
) {
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            post.title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = Side),
        )
        PostMedia(
            post = post,
            isActiveVideo = isActiveVideo,
            feedPlayer = feedPlayer,
            onOpenMedia = onOpenMedia,
            onOpenUrl = { runCatching { uri.openUri(it) } },
        )
        Text(
            buildString {
                append("r/${post.subreddit} · ${post.ageText} · ↑ ${post.scoreText} · ${post.commentsText} comments")
                if (post.isNsfw) append(" · NSFW")
                if (post.isSpoiler) append(" · spoiler")
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(horizontal = Side)
                .padding(top = 8.dp)
                .clickable { runCatching { uri.openUri(post.permalink) } },
        )
    }
}

@Composable
private fun PostMedia(
    post: Post,
    isActiveVideo: Boolean,
    feedPlayer: FeedPlayer,
    onOpenMedia: (Int) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    when (val media = post.media) {
        is Media.Text -> if (media.body.isNotBlank()) {
            Text(
                media.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Side).padding(top = 6.dp),
            )
        }

        is Media.Image -> MediaFrame(media.width, media.height) {
            AsyncImage(
                model = media.preview ?: media.url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clickable { onOpenMedia(0) },
            )
        }

        is Media.Gallery -> {
            val first = media.items.first()
            val pager = rememberPagerState { media.items.size }
            MediaFrame(first.width, first.height) {
                HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                    val item = media.items[page]
                    Box(Modifier.fillMaxSize().clickable { onOpenMedia(page) }) {
                        if (item.isAnimated) {
                            Badge("GIF · tap to play", Modifier.align(Alignment.Center))
                        } else {
                            AsyncImage(
                                model = item.preview ?: item.url,
                                contentDescription = item.caption,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
                Badge("${pager.currentPage + 1}/${media.items.size}", Modifier.align(Alignment.TopEnd))
            }
        }

        is Media.Video -> MediaFrame(media.width, media.height) {
            InlineVideo(post.id, media.preview, isActiveVideo, feedPlayer, onClick = { onOpenMedia(0) })
            if (isActiveVideo && media.hasAudio) {
                Badge(
                    if (feedPlayer.muted) "🔇" else "🔊",
                    Modifier.align(Alignment.BottomEnd).clickable { feedPlayer.toggleMute() },
                )
            }
            if (!isActiveVideo) Badge("▶", Modifier.align(Alignment.BottomStart))
        }

        is Media.Gif -> MediaFrame(media.width, media.height) {
            InlineVideo(post.id, media.preview, isActiveVideo, feedPlayer, onClick = { onOpenMedia(0) })
            Badge("GIF", Modifier.align(Alignment.BottomStart))
        }

        is Media.Embed -> LinkRow(media.url, media.thumbnail, media.provider, onOpenUrl)
        is Media.Link -> LinkRow(media.url, media.thumbnail, null, onOpenUrl)
    }
}

/**
 * Preview still, with the shared feed player's surface on top while this is
 * the active card. The still stays until the first video frame is drawn, so
 * there is never a black flash.
 */
@OptIn(UnstableApi::class)
@Composable
private fun BoxScope.InlineVideo(
    key: String,
    preview: String?,
    isActive: Boolean,
    feedPlayer: FeedPlayer,
    onClick: () -> Unit,
) {
    val playing = isActive && feedPlayer.currentKey == key
    if (playing) {
        PlayerSurface(
            player = feedPlayer.player,
            surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
            modifier = Modifier.fillMaxSize(),
        )
    }
    AnimatedVisibility(
        visible = !(playing && feedPlayer.hasFirstFrame),
        exit = fadeOut(tween(150)),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (preview != null) {
            AsyncImage(
                model = preview,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize().background(Color.Black))
        }
    }
    Box(Modifier.matchParentSize().clickable(onClick = onClick))
}

/**
 * Full-width box sized from the media's real aspect ratio, so the list never
 * jumps when images arrive. Very tall images are capped and cropped.
 */
@Composable
private fun MediaFrame(width: UInt, height: UInt, content: @Composable BoxScope.() -> Unit) {
    val ratio = if (width > 0u && height > 0u) width.toFloat() / height.toFloat() else 16f / 9f
    Box(
        Modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .aspectRatio(ratio.coerceIn(0.6f, 2.5f))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        content = content,
    )
}

@Composable
private fun Badge(text: String, modifier: Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun LinkRow(url: String, thumbnail: String?, label: String?, onOpen: (String) -> Unit) {
    Row(
        Modifier
            .padding(horizontal = Side)
            .padding(top = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onOpen(url) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (thumbnail != null) {
            AsyncImage(
                model = thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp),
            )
        } else {
            Spacer(Modifier.height(48.dp))
        }
        Text(
            label?.let { "$it · $url" } ?: url,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp).weight(1f),
        )
    }
}
