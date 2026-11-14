package dev.drift.app.viewer

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import dev.drift.app.DriftApp
import dev.drift.app.media.Playable
import dev.drift.app.media.SaveItem
import dev.drift.app.media.saveWithToast
import androidx.compose.runtime.rememberCoroutineScope
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.launch
import dev.drift.app.media.buildPlayer
import dev.drift.app.media.playable
import dev.drift.app.media.toMediaItem
import dev.drift.core.Media
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import kotlin.math.abs

/** One swipeable page in the viewer. */
@Immutable
sealed interface ViewerPage {
    data class Still(val url: String, val preview: String?) : ViewerPage
    data class Clip(val playable: Playable) : ViewerPage
}

@Immutable
data class ViewerRequest(
    val title: String,
    val pages: List<ViewerPage>,
    val startPage: Int = 0,
    /** Continue a feed video from where it was. */
    val startPositionMs: Long = 0,
) {
    companion object {
        fun from(title: String, media: Media, startPage: Int, positionMs: Long): ViewerRequest? {
            val pages: List<ViewerPage> = when (media) {
                is Media.Image -> listOf(ViewerPage.Still(media.url, media.preview ?: media.thumbnail))
                is Media.Gallery -> media.items.map { item ->
                    if (item.isAnimated) {
                        ViewerPage.Clip(Playable(item.url, androidx.media3.common.MimeTypes.VIDEO_MP4, hasAudio = false, loop = true))
                    } else {
                        ViewerPage.Still(item.url, item.preview)
                    }
                }
                is Media.Video, is Media.Gif -> listOfNotNull(media.playable()?.let { ViewerPage.Clip(it) })
                else -> emptyList()
            }
            if (pages.isEmpty()) return null
            return ViewerRequest(title, pages, startPage.coerceIn(0, pages.lastIndex), positionMs)
        }
    }
}

/**
 * Full-screen black viewer. Pinch or double-tap to zoom images (loaded at
 * full resolution, subsampled when huge), swipe sideways through galleries,
 * swipe down or press back to close. Tap toggles the top/bottom bars.
 */
@Composable
fun MediaViewer(request: ViewerRequest, highestQuality: Boolean, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val pager = rememberPagerState(initialPage = request.startPage) { request.pages.size }
    var chrome by remember { mutableStateOf(true) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val dismissPx = with(LocalDensity.current) { 140.dp.toPx() }
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val current = request.pages[pager.currentPage]
    val currentUrl = when (current) {
        is ViewerPage.Still -> current.url
        is ViewerPage.Clip -> current.playable.uri
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = (1f - abs(dragY) / (dismissPx * 3)).coerceIn(0.3f, 1f)))
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta -> dragY += delta },
                onDragStopped = {
                    if (abs(dragY) > dismissPx) onClose()
                    else animate(dragY, 0f) { value, _ -> dragY = value }
                },
            ),
    ) {
        HorizontalPager(
            state = pager,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize().graphicsLayer { translationY = dragY },
        ) { index ->
            when (val page = request.pages[index]) {
                is ViewerPage.Still -> StillPage(page, onTap = { chrome = !chrome })
                is ViewerPage.Clip -> ClipPage(
                    page.playable,
                    highestQuality = highestQuality,
                    isCurrent = pager.currentPage == index,
                    startPositionMs = if (index == request.startPage) request.startPositionMs else 0L,
                )
            }
        }

        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onClose) { Text("✕", color = Color.White, style = MaterialTheme.typography.titleMedium) }
                Text(
                    request.title,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (request.pages.size > 1) {
                    Text(
                        "${pager.currentPage + 1}/${request.pages.size}",
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                // Actions live in the top bar so they never cover the video
                // player's own controls at the bottom.
                TextButton(onClick = {
                    val item = when (current) {
                        is ViewerPage.Still -> SaveItem.File(current.url)
                        is ViewerPage.Clip -> current.playable.let { p ->
                            if (p.mimeType == MimeTypes.APPLICATION_M3U8 || p.mimeType == MimeTypes.APPLICATION_MPD) {
                                SaveItem.Stream(p.uri, p.mimeType)
                            } else {
                                SaveItem.File(p.uri)
                            }
                        }
                    }
                    scope.launch { saveWithToast(context, (context.applicationContext as DriftApp).core, listOf(item)) }
                }) { Text("Save", color = Color.White) }
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, currentUrl)
                    context.startActivity(Intent.createChooser(send, null))
                }) { Text("Share", color = Color.White) }
                TextButton(onClick = { runCatching { uri.openUri(currentUrl) } }) { Text("Open", color = Color.White) }
            }
        }
    }
}

@Composable
private fun StillPage(page: ViewerPage.Still, onTap: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // The card-sized preview is usually already cached: show it at once,
        // then the full-resolution image draws over it when it arrives.
        if (page.preview != null) {
            AsyncImage(
                model = page.preview,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        ZoomableAsyncImage(
            model = page.url,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            onClick = { onTap() },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ClipPage(playable: Playable, highestQuality: Boolean, isCurrent: Boolean, startPositionMs: Long) {
    val context = LocalContext.current
    val player = remember(playable) {
        buildPlayer(context, highestQuality).apply {
            repeatMode = if (playable.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            setMediaItem(playable.toMediaItem(), startPositionMs)
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(isCurrent) { player.playWhenReady = isCurrent }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
                controllerShowTimeoutMs = 2500
                setShowNextButton(false)
                setShowPreviousButton(false)
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}
