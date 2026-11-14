package dev.drift.app.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drift.app.R
import dev.drift.core.Media
import dev.drift.core.Sort
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import dev.drift.core.extractUrl
import dev.drift.core.Post
import dev.drift.app.media.rememberIsMetered
import dev.drift.app.media.setHighestQuality
import dev.drift.app.settings.Autoplay
import dev.drift.app.settings.Layout
import dev.drift.app.settings.Quality
import dev.drift.app.settings.SettingsScreen
import dev.drift.app.settings.SettingsStore
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import dev.drift.app.media.FeedPlayer
import dev.drift.app.media.playable
import dev.drift.app.viewer.MediaViewer
import dev.drift.app.viewer.ViewerRequest
import dev.drift.core.prefetchUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs

private val sortLabels = listOf(
    Sort.HOT to "Hot", Sort.NEW to "New", Sort.RISING to "Rising",
    Sort.TOP_DAY to "Top · today", Sort.TOP_WEEK to "Top · week", Sort.TOP_MONTH to "Top · month",
    Sort.TOP_YEAR to "Top · year", Sort.TOP_ALL to "Top · all time",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(vm: FeedViewModel, store: SettingsStore) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by store.settings.collectAsStateWithLifecycle()
    val recent by store.recentSubreddits.collectAsStateWithLifecycle()
    val posts by rememberUpdatedState(state.posts)
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    var sortMenu by remember { mutableStateOf(false) }
    var subredditSheet by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<Post?>(null) }
    var viewer by remember { mutableStateOf<ViewerRequest?>(null) }
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val snackbar = remember { SnackbarHostState() }

    // Posts the user tapped to un-blur (NSFW/spoiler), for this feed only.
    val revealed = remember(state.subreddit) { mutableStateMapOf<String, Boolean>() }
    val blurOf: (Post) -> String? = { p ->
        blurReason(p, settings.showNsfw, settings.blurSpoilers, revealed[p.id] == true)
    }
    val currentBlurOf by rememberUpdatedState(blurOf)

    // Autoplay setting + network type decide whether the feed plays videos.
    val metered by rememberIsMetered()
    val autoplayOn by rememberUpdatedState(
        settings.layout == Layout.CARD && when (settings.autoplay) {
            Autoplay.ALWAYS -> true
            Autoplay.WIFI_ONLY -> !metered
            Autoplay.NEVER -> false
        }
    )

    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                // The list keeps its current first item anchored when a post is
                // inserted above it, so wait until the new post is laid out at
                // index 0 and only then scroll to it.
                is FeedEvent.ScrollToPost -> {
                    snapshotFlow { listState.layoutInfo.visibleItemsInfo.firstOrNull() to posts.firstOrNull()?.id }
                        .first { (_, firstId) -> firstId == e.postId }
                    withFrameNanos { }
                    listState.animateScrollToItem(0)
                }
                is FeedEvent.Message -> snackbar.showSnackbar(e.text)
            }
        }
    }

    // One player for the whole feed; it follows the most visible video card.
    val feedPlayer = remember { FeedPlayer(context) }
    DisposableEffect(feedPlayer) { onDispose { feedPlayer.release() } }

    val callbacks = remember(feedPlayer, revealed) {
        PostCallbacks(
            onOpenMedia = { post, page ->
                viewer = ViewerRequest.from(post.title, post.media, page, feedPlayer.positionFor(post.id))
            },
            onOpenUrl = { runCatching { uri.openUri(it) } },
            onLongPress = { actionsFor = it },
            onReveal = { revealed[it.id] = true },
        )
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { feedPlayer.setPaused(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { feedPlayer.setPaused(viewer != null) }
    LaunchedEffect(viewer, showSettings) { feedPlayer.setPaused(viewer != null || showSettings) }
    LaunchedEffect(settings.quality) { feedPlayer.player.setHighestQuality(settings.quality == Quality.HIGHEST) }

    val activeVideoId by remember {
        derivedStateOf {
            if (!autoplayOn) null
            else mostVisibleVideo(listState.layoutInfo) { key ->
                posts.firstOrNull { it.id == key }?.let { currentBlurOf(it) == null } == true
            }
        }
    }
    LaunchedEffect(activeVideoId) {
        val post = posts.firstOrNull { it.id == activeVideoId }
        val playable = post?.media?.playable()
        if (post == null || playable == null) feedPlayer.stop() else feedPlayer.play(post.id, playable)
    }

    // Prefetch: Rust picks which card images to download ahead, based on the
    // posts below the viewport and how fast the list is moving.
    LaunchedEffect(listState) {
        val loader = SingletonImageLoader.get(context)
        val requested = HashSet<String>()
        var lastIndex = 0
        var lastTime = System.nanoTime()
        var velocity = 0f
        snapshotFlow { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) to posts.size }
            .collect { (last, size) ->
                val now = System.nanoTime()
                val dt = (now - lastTime) / 1e9f
                if (dt > 0f) velocity = 0.6f * velocity + 0.4f * (abs(last - lastIndex) / dt).coerceAtMost(50f)
                lastIndex = last
                lastTime = now
                val from = (last + 1).coerceAtMost(size)
                val upcoming = posts.subList(from, minOf(size, from + 12))
                if (upcoming.isEmpty()) return@collect
                val urls = withContext(Dispatchers.Default) { prefetchUrls(upcoming, velocity) }
                for (url in urls) {
                    if (!requested.add(url)) continue
                    loader.enqueue(
                        ImageRequest.Builder(context)
                            .data(url)
                            .memoryCachePolicy(CachePolicy.DISABLED) // disk only; cards decode at their own size
                            .build()
                    )
                }
            }
    }

    // Back to the top whenever a different feed opens.
    LaunchedEffect(state.subreddit, state.sort) { listState.scrollToItem(0) }

    // Infinite scroll: ask for the next page 10 items before the end.
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 10
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { nearEnd }.distinctUntilChanged().filter { it }.collect { vm.loadMore() }
    }

    // A full turn of the die while a random pick is in flight.
    val diceTurn by animateFloatAsState(
        targetValue = if (state.rolling) 360f else 0f,
        animationSpec = tween(if (state.rolling) 600 else 0),
        label = "dice",
    )

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
          Column {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
                title = {
                    TextButton(onClick = { subredditSheet = true }) {
                        Text(
                            "r/${state.subreddit}  ▾",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = vm::randomSubreddit) {
                        Icon(
                            painterResource(R.drawable.ic_dice),
                            contentDescription = "Random subreddit",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.rotate(diceTurn),
                        )
                    }
                    Box {
                        TextButton(onClick = { sortMenu = true }) {
                            Text(
                                sortLabels.first { it.first == state.sort }.second,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            sortLabels.forEach { (sort, label) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(label, fontWeight = if (sort == state.sort) FontWeight.SemiBold else null)
                                    },
                                    onClick = { sortMenu = false; vm.setSort(sort) },
                                )
                            }
                        }
                    }
                    TextButton(onClick = { showSettings = true }) {
                        Text("⋮", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
            // Thin line under the bar while a shared/pasted link resolves.
            if (state.resolvingLink) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            }
          }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
        ) {
            items(
                items = state.posts,
                key = { it.id },
                contentType = { it.media.kindKey() },
            ) { post ->
                PostItem(
                    post = post,
                    layout = settings.layout,
                    isActiveVideo = post.id == activeVideoId,
                    feedPlayer = feedPlayer,
                    blur = blurOf(post),
                    cb = callbacks,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item(key = "footer") {
                FeedFooter(
                    loading = state.loading,
                    endReached = state.endReached && state.posts.isNotEmpty(),
                    error = state.error,
                    onRetry = vm::retry,
                )
            }
        }
    }
    viewer?.let { MediaViewer(it, settings.quality == Quality.HIGHEST, onClose = { viewer = null }) }
    if (showSettings) {
        SettingsScreen(settings, onChange = store::update, onClose = { showSettings = false })
    }
    }

    if (subredditSheet) {
        SubredditSheet(
            current = state.subreddit,
            recent = recent,
            onGo = { text ->
                subredditSheet = false
                if (extractUrl(text) != null) vm.addLink(text) else vm.open(text)
            },
            onDismiss = { subredditSheet = false },
        )
    }
    actionsFor?.let { post ->
        PostActionsSheet(
            post = post,
            onOpenUrl = { runCatching { uri.openUri(it) } },
            onHide = { vm.hide(post) },
            onDismiss = { actionsFor = null },
        )
    }
}

private const val KIND_VIDEO = 3
private const val KIND_GIF = 4

/**
 * The video/GIF card that should be playing: at least 60% visible, and
 * closest to the middle of the screen when several qualify.
 */
private fun mostVisibleVideo(info: LazyListLayoutInfo, canPlay: (String) -> Boolean): String? {
    val top = info.viewportStartOffset
    val bottom = info.viewportEndOffset
    val center = (top + bottom) / 2
    return info.visibleItemsInfo
        .asSequence()
        .filter { it.contentType == KIND_VIDEO || it.contentType == KIND_GIF }
        .filter { item ->
            val shown = minOf(item.offset + item.size, bottom) - maxOf(item.offset, top)
            item.size > 0 && shown.toFloat() / item.size >= 0.6f
        }
        .filter { (it.key as? String)?.let(canPlay) == true }
        .minByOrNull { abs(it.offset + it.size / 2 - center) }
        ?.key as? String
}

private fun Media.kindKey(): Int = when (this) {
    is Media.Text -> 0
    is Media.Image -> 1
    is Media.Gallery -> 2
    is Media.Video -> KIND_VIDEO
    is Media.Gif -> KIND_GIF
    is Media.Embed -> 5
    is Media.Link -> 6
}

@Composable
private fun FeedFooter(loading: Boolean, endReached: Boolean, error: String?, onRetry: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            error != null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = onRetry) { Text("Retry") }
            }
            loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            endReached -> Text(
                "That's everything",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

