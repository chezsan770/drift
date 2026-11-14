package dev.drift.app.feed

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPresentationState
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.drift.app.media.FeedPlayer
import dev.drift.app.settings.Layout
import dev.drift.core.Media
import dev.drift.core.Post

private val Side = 16.dp

/** Everything a post row needs from the screen. */
class PostCallbacks(
    val onOpenMedia: (Post, page: Int) -> Unit,
    val onOpenUrl: (String) -> Unit,
    val onLongPress: (Post) -> Unit,
    val onReveal: (Post) -> Unit,
)

/** Why a post's media is hidden behind a blur, or null when it isn't. */
fun blurReason(post: Post, showNsfw: Boolean, blurSpoilers: Boolean, revealed: Boolean): String? = when {
    revealed -> null
    post.isNsfw && !showNsfw -> "NSFW"
    post.isSpoiler && blurSpoilers -> "Spoiler"
    else -> null
}

private fun Media.isVisual() = this is Media.Image || this is Media.Gallery || this is Media.Video || this is Media.Gif

@Composable
fun PostItem(
    post: Post,
    layout: Layout,
    isActiveVideo: Boolean,
    feedPlayer: FeedPlayer,
    blur: String?,
    highQuality: Boolean,
    cb: PostCallbacks,
) {
    when (layout) {
        Layout.CARD -> PostCard(post, isActiveVideo, feedPlayer, blur, highQuality, cb)
        Layout.COMPACT -> CompactPost(post, blur, cb)
    }
}

/** Primary tap: visual media opens full screen, anything else opens its link. */
private fun primaryTap(post: Post, blur: String?, cb: PostCallbacks): () -> Unit = {
    when {
        blur != null -> cb.onReveal(post)
        post.media.isVisual() -> cb.onOpenMedia(post, 0)
        post.media is Media.Text -> cb.onOpenUrl(post.permalink)
        else -> cb.onOpenUrl(post.url)
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.tapAndHold(onTap: () -> Unit, onHold: () -> Unit) =
    combinedClickable(onClick = onTap, onLongClick = onHold)

// ------------------------------------------------------------------ card

/** Title, media, one quiet metadata line. Nothing else. */
@Composable
private fun PostCard(post: Post, isActiveVideo: Boolean, feedPlayer: FeedPlayer, blur: String?, highQuality: Boolean, cb: PostCallbacks) {
    Column(
        Modifier
            .fillMaxWidth()
            .tapAndHold(onTap = primaryTap(post, blur, cb), onHold = { cb.onLongPress(post) })
            .padding(vertical = 12.dp),
    ) {
        Text(
            post.title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = Side),
        )
        CardMedia(post, isActiveVideo && blur == null, feedPlayer, blur, highQuality, cb)
        MetaLine(post, Modifier.padding(horizontal = Side).padding(top = 8.dp))
    }
}

@Composable
private fun MetaLine(post: Post, modifier: Modifier = Modifier) {
    Text(
        buildString {
            when {
                post.subreddit.isEmpty() -> append("${post.domain} · shared link")
                // RSS (limited mode) has no scores or comment counts.
                post.scoreText.isEmpty() -> append("r/${post.subreddit} · ${post.ageText} · u/${post.author}")
                else -> append("r/${post.subreddit} · ${post.ageText} · ↑ ${post.scoreText} · ${post.commentsText} comments")
            }
            if (post.isNsfw) append(" · NSFW")
            if (post.isSpoiler) append(" · spoiler")
        },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun CardMedia(post: Post, isActiveVideo: Boolean, feedPlayer: FeedPlayer, blur: String?, highQuality: Boolean, cb: PostCallbacks) {
    val hold = { cb.onLongPress(post) }
    val blurMod = if (blur != null) Modifier.blur(40.dp) else Modifier
    when (val media = post.media) {
        // Size unknown up front (RSS feeds, links): the card takes its shape
        // from the image once loaded. With "Highest" quality the original
        // loads, showing the small preview until it arrives.
        is Media.Image if media.width == 0u || media.height == 0u -> {
            var ratio by remember(media.url) { mutableStateOf<Float?>(null) }
            val placeholder = media.preview?.takeIf { highQuality }?.let { rememberAsyncImagePainter(it) }
            MediaFrame(ratio) {
                AsyncImage(
                    model = if (highQuality) media.url else media.preview ?: media.url,
                    contentDescription = null,
                    placeholder = placeholder,
                    contentScale = if (ratio != null) ContentScale.Crop else ContentScale.Fit,
                    onSuccess = { s: AsyncImagePainter.State.Success ->
                        val size = s.painter.intrinsicSize
                        if (size.width > 0f && size.height > 0f) ratio = size.width / size.height
                    },
                    modifier = Modifier.fillMaxSize().then(blurMod)
                        .tapAndHold(onTap = primaryTap(post, blur, cb), onHold = hold),
                )
                BlurLabel(blur)
            }
        }

        is Media.Text -> if (media.body.isNotBlank()) {
            Text(
                media.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Side).padding(top = 6.dp).then(blurMod),
            )
        }

        is Media.Image -> MediaFrame(media.width, media.height) {
            val knownSize = media.width > 0u && media.height > 0u
            AsyncImage(
                model = media.preview ?: media.url,
                contentDescription = null,
                // Unknown size (links): show the whole image, never crop it.
                contentScale = if (knownSize) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier.fillMaxSize().then(blurMod)
                    .tapAndHold(onTap = primaryTap(post, blur, cb), onHold = hold),
            )
            BlurLabel(blur)
        }

        is Media.Gallery -> {
            val first = media.items.first()
            val pager = rememberPagerState { media.items.size }
            MediaFrame(first.width, first.height) {
                HorizontalPager(pager, Modifier.fillMaxSize().then(blurMod), beyondViewportPageCount = 1, userScrollEnabled = blur == null) { page ->
                    val item = media.items[page]
                    Box(
                        Modifier.fillMaxSize().tapAndHold(
                            onTap = { if (blur != null) cb.onReveal(post) else cb.onOpenMedia(post, page) },
                            onHold = hold,
                        )
                    ) {
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
                BlurLabel(blur)
            }
        }

        is Media.Video -> MediaFrame(media.width, media.height) {
            Box(Modifier.matchParentSize().then(blurMod)) {
                InlineVideo(post.id, media.preview, isActiveVideo, feedPlayer)
            }
            Box(Modifier.matchParentSize().tapAndHold(onTap = primaryTap(post, blur, cb), onHold = hold))
            if (isActiveVideo && media.hasAudio) {
                Badge(
                    if (feedPlayer.muted) "🔇" else "🔊",
                    Modifier.align(Alignment.BottomEnd).tapAndHold(onTap = { feedPlayer.toggleMute() }, onHold = hold),
                )
            }
            if (!isActiveVideo && blur == null) Badge("▶", Modifier.align(Alignment.BottomStart))
            BlurLabel(blur)
        }

        is Media.Gif -> MediaFrame(media.width, media.height) {
            Box(Modifier.matchParentSize().then(blurMod)) {
                InlineVideo(post.id, media.preview, isActiveVideo, feedPlayer)
            }
            Box(Modifier.matchParentSize().tapAndHold(onTap = primaryTap(post, blur, cb), onHold = hold))
            if (blur == null) Badge("GIF", Modifier.align(Alignment.BottomStart))
            BlurLabel(blur)
        }

        is Media.Embed -> LinkRow(media.url, media.thumbnail, media.provider, { cb.onOpenUrl(media.url) }, hold)
        is Media.Link -> LinkRow(media.url, media.thumbnail, null, { cb.onOpenUrl(media.url) }, hold)
    }
}

@Composable
private fun BoxScope.BlurLabel(reason: String?) {
    if (reason != null) Badge("$reason · tap to show", Modifier.align(Alignment.Center))
}

/**
 * Preview still, with the shared feed player's surface on top while this is
 * the active card. The still stays until the first video frame is drawn, so
 * there is never a black flash.
 */
@OptIn(UnstableApi::class)
@Composable
private fun BoxScope.InlineVideo(key: String, preview: String?, isActive: Boolean, feedPlayer: FeedPlayer) {
    val playing = isActive && feedPlayer.currentKey == key
    Box(Modifier.matchParentSize().background(Color.Black)) // letterbox bars
    if (playing) {
        // Size the surface from the stream's real dimensions, so videos from
        // links (size unknown up front) are letterboxed, never stretched.
        val presentation = rememberPresentationState(feedPlayer.player)
        PlayerSurface(
            player = feedPlayer.player,
            surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
            modifier = Modifier
                .align(Alignment.Center)
                .resizeWithContentScale(ContentScale.Fit, presentation.videoSizeDp),
        )
    }
    AnimatedVisibility(
        visible = !(playing && feedPlayer.hasFirstFrame),
        exit = fadeOut(tween(150)),
        modifier = Modifier.matchParentSize(),
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
}

/**
 * Full-width box sized from the media's real aspect ratio, so the list never
 * jumps when images arrive. Very tall images are capped and cropped.
 */
@Composable
private fun MediaFrame(width: UInt, height: UInt, content: @Composable BoxScope.() -> Unit) =
    MediaFrame(if (width > 0u && height > 0u) width.toFloat() / height.toFloat() else null, content)

@Composable
private fun MediaFrame(ratio: Float?, content: @Composable BoxScope.() -> Unit) {
    val ratio = ratio ?: (16f / 9f)
    Box(
        Modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .aspectRatio(ratio.coerceIn(0.6f, 2.5f))
            .clip(RoundedCornerShape(0.dp))
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
private fun LinkRow(url: String, thumbnail: String?, label: String?, onOpen: () -> Unit, onHold: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = Side)
            .padding(top = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .tapAndHold(onTap = onOpen, onHold = onHold),
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

// --------------------------------------------------------------- compact

private fun Media.thumbnailUrl(): String? = when (this) {
    is Media.Image -> preview ?: url
    is Media.Gallery -> items.firstOrNull { !it.isAnimated }?.let { it.preview ?: it.url }
    is Media.Video -> preview
    is Media.Gif -> preview
    is Media.Embed -> thumbnail
    is Media.Link -> thumbnail
    is Media.Text -> null
}

private fun Media.kindLabel(): String? = when (this) {
    is Media.Gallery -> "${items.size}"
    is Media.Video -> "▶"
    is Media.Gif -> "GIF"
    is Media.Embed, is Media.Link -> "↗"
    else -> null
}

/** Dense row: title and metadata on the left, a small thumbnail on the right. */
@Composable
private fun CompactPost(post: Post, blur: String?, cb: PostCallbacks) {
    Row(
        Modifier
            .fillMaxWidth()
            .tapAndHold(onTap = primaryTap(post, blur, cb), onHold = { cb.onLongPress(post) })
            .padding(horizontal = Side, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                post.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            MetaLine(post, Modifier.padding(top = 4.dp))
        }
        val thumb = post.media.thumbnailUrl()
        if (thumb != null || post.media.kindLabel() != null) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (thumb != null) {
                    AsyncImage(
                        model = thumb,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().then(if (blur != null) Modifier.blur(16.dp) else Modifier),
                    )
                }
                val label = if (blur != null) blur else post.media.kindLabel()
                if (label != null) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}
