package dev.drift.app.feed

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.drift.core.CoreException
import dev.drift.core.DriftCore
import dev.drift.core.Post
import dev.drift.core.Sort
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val error: String? = null,
)

/** Thin state holder: every decision about data lives in the Rust core. */
class FeedViewModel(private val core: DriftCore) : ViewModel() {

    private val _state = MutableStateFlow(FeedState(subreddit = "EarthPorn"))
    val state: StateFlow<FeedState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        loadMore()
    }

    fun open(subreddit: String, sort: Sort = _state.value.sort) {
        val name = subreddit.trim().removePrefix("/").removePrefix("r/")
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
                        posts = cur.posts + page.posts.filter { seen.add(it.id) },
                        after = page.after,
                        endReached = page.after == null,
                        loading = false,
                    )
                }
            } catch (e: CoreException) {
                _state.update { it.copy(loading = false, error = e.message ?: "Something went wrong") }
            }
        }
    }

    fun randomSubreddit() {
        if (_state.value.rolling) return
        _state.update { it.copy(rolling = true) }
        viewModelScope.launch {
            try {
                val name = core.randomSubreddit(allowNsfw = false)
                open(name)
            } catch (e: CoreException) {
                _state.update { it.copy(rolling = false, error = e.message ?: "Couldn't find a subreddit") }
            }
        }
    }
}
