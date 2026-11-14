package dev.drift.app.feed

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.drift.app.settings.SettingsStore
import dev.drift.core.CoreException
import dev.drift.core.DriftCore
import dev.drift.core.FeedPage
import dev.drift.core.Post
import dev.drift.core.Sort
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class FeedState(
    val subreddit: String,
    val sort: Sort = Sort.HOT,
    val posts: List<Post> = emptyList(),
    val after: String? = null,
    val loading: Boolean = false,
    val endReached: Boolean = false,
    val rolling: Boolean = false,
    val resolvingLink: Boolean = false,
    val error: String? = null,
    /** Seconds until Reddit allows the next feed load (limited mode). */
    val waitSecs: Int? = null,
    /** Showing the saved copy of this feed; a refresh is pending. */
    val fromCache: Boolean = false,
) {
    val cacheKey: String get() = "$subreddit|${sort.name}"

    val title: String
        get() = when (subreddit) {
            HOME -> "Home"
            "" -> "Front page"
            else -> "r/$subreddit"
        }
}

/** Pseudo-subreddit for the user's Home feed (see Settings). */
const val HOME = "home"

/** One-off UI events. */
sealed interface FeedEvent {
    /** Scroll to the top once [postId] is the first post in the list. With
     *  [onlyIfStillAt], only if the user is still at that (previous first)
     *  post, i.e. hasn't started scrolling. */
    data class ScrollToPost(val postId: String, val onlyIfStillAt: String? = null) : FeedEvent
    data class Message(val text: String) : FeedEvent
}

/** Thin state holder: every decision about data lives in the Rust core. */
class FeedViewModel(
    private val core: DriftCore,
    private val store: SettingsStore,
    initialSubreddit: String,
) : ViewModel() {

    private val _state = MutableStateFlow(FeedState(subreddit = initialSubreddit))
    val state: StateFlow<FeedState> = _state.asStateFlow()

    private val _events = Channel<FeedEvent>(Channel.BUFFERED)
    val events: Flow<FeedEvent> = _events.receiveAsFlow()

    private var loadJob: Job? = null

    /** No API access: feeds come from RSS (read-only, ~1 load/minute). */
    val limitedMode: Boolean = core.isLimitedMode()

    // Limited-mode Home: Reddit refuses combined "a+b" RSS feeds, so Home
    // takes turns, one subreddit per load, remembering each one's position.
    // (Declared before `init` below: Kotlin initialises in declaration order.)
    private val homeAfter = HashMap<String, String?>()
    private val homeDone = HashSet<String>()
    private var homeTurn = 0

    init {
        showCachedThenRefresh()
    }

    /** Show this feed's saved first page instantly (from Rust's disk cache),
     *  then load a fresh one: new posts go on top. */
    private fun showCachedThenRefresh() {
        val s = _state.value
        loadJob = viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { core.cachedFeed(s.cacheKey) }
            val cur = _state.value
            if (cached != null && cached.posts.isNotEmpty() && cur.cacheKey == s.cacheKey && cur.posts.isEmpty()) {
                _state.update {
                    it.copy(posts = cached.posts.filter { p -> !store.isHidden(p.id) }, after = cached.after, fromCache = true)
                }
            }
            loadMore()
        }
    }

    fun open(subreddit: String, sort: Sort = _state.value.sort) {
        val name = subreddit.trim().removePrefix("/").removePrefix("r/")
        if (name != HOME && name.isNotEmpty()) store.rememberSubreddit(name)
        loadJob?.cancel()
        homeAfter.clear()
        homeDone.clear()
        homeTurn = 0
        _state.value = FeedState(subreddit = name, sort = sort)
        showCachedThenRefresh()
    }

    fun setSort(sort: Sort) = open(_state.value.subreddit, sort)

    fun retry() {
        _state.update { it.copy(error = null) }
        loadMore()
    }

    /** Load a page for [s]: Home resolves to the private RSS link or the
     *  subreddit list from Settings. */
    private suspend fun fetchPage(s: FeedState): FeedPage {
        if (s.subreddit != HOME) return core.feed(s.subreddit, s.sort, s.after)
        val cfg = store.settings.value
        return when {
            cfg.privateFeedUrl.isNotBlank() -> core.feedPrivate(cfg.privateFeedUrl, s.after)
            cfg.homeMulti.isEmpty() ->
                throw CoreException.NotFound("Home isn't set up yet: add subreddits in ⋮ Settings.")
            // With API access, Reddit combines them in one listing.
            !limitedMode -> core.feed(cfg.homeMulti, s.sort, s.after)
            else -> nextHomeTurn(cfg.homeMulti.split('+'), s.sort)
        }
    }

    private suspend fun nextHomeTurn(subs: List<String>, sort: Sort): FeedPage {
        val pending = subs.indices.map { (homeTurn + it) % subs.size }.firstOrNull { subs[it] !in homeDone }
            ?: return FeedPage(emptyList(), null)
        val sub = subs[pending]
        val page = core.feed(sub, sort, homeAfter[sub])
        homeAfter[sub] = page.after
        if (page.after == null) homeDone += sub
        homeTurn = (pending + 1) % subs.size
        val more = subs.any { it !in homeDone }
        // "after" only tells the screen there's more; Home tracks positions itself.
        return FeedPage(page.posts, if (more) "home" else null)
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.endReached || s.error != null || s.waitSecs != null) return
        _state.update { it.copy(loading = true) }
        // Showing the cached copy: fetch the first page again (a refresh).
        val firstPage = s.fromCache || s.after == null
        loadJob = viewModelScope.launch {
            try {
                val page = fetchPage(if (s.fromCache) s.copy(after = null) else s)
                val fresh = page.posts.filter { !store.isHidden(it.id) }
                val previousFirst = _state.value.posts.firstOrNull()?.id
                _state.update { cur ->
                    if (cur.subreddit != s.subreddit || cur.sort != s.sort) return@update cur
                    val merged = if (s.fromCache) {
                        // New posts on top; the cached ones the user may be
                        // looking at stay where they are.
                        val ids = fresh.mapTo(HashSet()) { it.id }
                        fresh + cur.posts.filter { it.id !in ids }
                    } else {
                        val seen = cur.posts.mapTo(HashSet()) { it.id }
                        cur.posts + fresh.filter { seen.add(it.id) }
                    }
                    cur.copy(
                        posts = merged,
                        after = page.after,
                        endReached = page.after == null,
                        loading = false,
                        fromCache = false,
                    )
                }
                // Refresh put new posts above the cached ones: show them if the
                // user is still at the top (otherwise leave their place alone).
                val newFirst = _state.value.posts.firstOrNull()?.id
                if (s.fromCache && previousFirst != null && newFirst != null && newFirst != previousFirst) {
                    _events.send(FeedEvent.ScrollToPost(newFirst, onlyIfStillAt = previousFirst))
                }
                if (firstPage && page.posts.isNotEmpty()) {
                    withContext(Dispatchers.IO) { core.saveFeed(s.cacheKey, page) }
                }
                upgradeLinks(page.posts)
            } catch (e: CoreException.RateLimited) {
                waitAndRetry()
            } catch (e: CoreException) {
                _state.update { it.copy(loading = false, error = e.message ?: "Something went wrong") }
            }
        }
    }

    /** Reddit's limit is reached: count down, then load automatically. */
    private suspend fun waitAndRetry() {
        val target = _state.value.subreddit to _state.value.sort
        var left = core.rateLimitWaitSecs().toInt().coerceAtLeast(1)
        while (left > 0) {
            _state.update { it.copy(loading = false, waitSecs = left) }
            delay(1000)
            left = core.rateLimitWaitSecs().toInt()
        }
        _state.update { it.copy(waitSecs = null) }
        val cur = _state.value
        if (cur.subreddit to cur.sort == target) loadMore()
    }

    /** Swap link cards from media hosts (Streamable, Redgifs, Imgur...) for
     *  their resolved image/video once the core has looked them up. */
    private fun upgradeLinks(posts: List<Post>) {
        viewModelScope.launch {
            val upgraded = core.upgradeLinks(posts).associateBy { it.id }
            if (upgraded.isEmpty()) return@launch
            _state.update { cur -> cur.copy(posts = cur.posts.map { upgraded[it.id] ?: it }) }
        }
    }

    /** A link shared into the app or pasted: resolve it and put it on top. */
    fun addLink(text: String) {
        _state.update { it.copy(resolvingLink = true) }
        viewModelScope.launch {
            try {
                val post = core.linkPost(text)
                _state.update { cur ->
                    cur.copy(posts = listOf(post) + cur.posts.filter { it.id != post.id }, resolvingLink = false)
                }
                _events.send(FeedEvent.ScrollToPost(post.id))
            } catch (e: CoreException) {
                _state.update { it.copy(resolvingLink = false) }
                _events.send(FeedEvent.Message(e.message ?: "Couldn't open that link"))
            }
        }
    }

    /** Remove a post now and keep it hidden in future loads. */
    fun hide(post: Post) {
        store.hide(post.id)
        _state.update { cur -> cur.copy(posts = cur.posts.filter { it.id != post.id }) }
        viewModelScope.launch { _events.send(FeedEvent.Message("Post hidden")) }
    }

    fun randomSubreddit() {
        if (_state.value.rolling) return
        _state.update { it.copy(rolling = true) }
        viewModelScope.launch {
            try {
                val name = core.randomSubreddit(allowNsfw = store.settings.value.showNsfw)
                open(name)
            } catch (e: CoreException) {
                _state.update { it.copy(rolling = false) }
                _events.send(FeedEvent.Message(e.message ?: "Couldn't find a subreddit"))
            }
        }
    }
}
