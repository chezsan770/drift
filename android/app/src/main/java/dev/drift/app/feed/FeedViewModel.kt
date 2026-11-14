package dev.drift.app.feed

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.drift.app.settings.SettingsStore
import dev.drift.core.CoreException
import dev.drift.core.DriftCore
import dev.drift.core.Post
import dev.drift.core.Sort
import kotlinx.coroutines.Job
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
)

/** One-off UI events. */
sealed interface FeedEvent {
    /** Scroll to the top once [postId] is the first post in the list. */
    data class ScrollToPost(val postId: String) : FeedEvent
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

    init {
        loadMore()
    }

    fun open(subreddit: String, sort: Sort = _state.value.sort) {
        val name = subreddit.trim().removePrefix("/").removePrefix("r/")
        store.rememberSubreddit(name)
        loadJob?.cancel()
        _state.value = FeedState(subreddit = name, sort = sort)
        loadMore()
    }

    fun setSort(sort: Sort) = open(_state.value.subreddit, sort)

    fun retry() {
        _state.update { it.copy(error = null) }
        loadMore()
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.endReached || s.error != null) return
        _state.update { it.copy(loading = true) }
        loadJob = viewModelScope.launch {
            try {
                val page = core.feed(s.subreddit, s.sort, s.after)
                _state.update { cur ->
                    if (cur.subreddit != s.subreddit || cur.sort != s.sort) return@update cur
                    val seen = cur.posts.mapTo(HashSet()) { it.id }
                    cur.copy(
                        posts = cur.posts + page.posts.filter { !store.isHidden(it.id) && seen.add(it.id) },
                        after = page.after,
                        endReached = page.after == null,
                        loading = false,
                    )
                }
                upgradeLinks(page.posts)
            } catch (e: CoreException) {
                _state.update { it.copy(loading = false, error = e.message ?: "Something went wrong") }
            }
        }
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
