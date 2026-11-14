package dev.drift.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import coil3.compose.AsyncImage
import dev.drift.core.Media
import dev.drift.core.Post

private val Side = 16.dp

/** Title, media, one quiet metadata line. Nothing else. */
@Composable
fun PostCard(post: Post) {
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            post.title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = Side),
        )
        PostMedia(post.media, onOpen = { runCatching { uri.openUri(it) } })
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
private fun PostMedia(media: Media, onOpen: (String) -> Unit) {
    when (media) {
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
                modifier = Modifier.fillMaxSize().clickable { onOpen(media.url) },
            )
        }

        is Media.Gallery -> {
            val first = media.items.first()
            val pager = rememberPagerState { media.items.size }
            MediaFrame(first.width, first.height) {
                HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                    val item = media.items[page]
                    AsyncImage(
                        model = item.url,
                        contentDescription = item.caption,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().clickable { onOpen(item.url) },
                    )
                }
                Badge("${pager.currentPage + 1}/${media.items.size}", Modifier.align(Alignment.TopEnd))
            }
        }

        // Phase 2 replaces these two with in-feed ExoPlayer playback.
        is Media.Video -> MediaFrame(media.width, media.height) {
            AsyncImage(
                model = media.preview,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clickable {
                    (media.hlsUrl ?: media.mp4Url ?: media.dashUrl)?.let(onOpen)
                },
            )
            Badge("▶ video", Modifier.align(Alignment.BottomStart))
        }

        is Media.Gif -> MediaFrame(media.width, media.height) {
            AsyncImage(
                model = media.preview,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clickable { onOpen(media.mp4Url) },
            )
            Badge("GIF", Modifier.align(Alignment.BottomStart))
        }

        is Media.Embed -> LinkRow(media.url, media.thumbnail, media.provider, onOpen)
        is Media.Link -> LinkRow(media.url, media.thumbnail, null, onOpen)
    }
}

/**
 * Full-width box sized from the media's real aspect ratio, so the list never
 * jumps when images arrive. Very tall images are capped and cropped.
 */
@Composable
private fun MediaFrame(width: UInt, height: UInt, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
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
