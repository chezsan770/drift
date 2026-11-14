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

private val sortLabels = listOf(
    Sort.HOT to "Hot", Sort.NEW to "New", Sort.RISING to "Rising",
    Sort.TOP_DAY to "Top · today", Sort.TOP_WEEK to "Top · week", Sort.TOP_MONTH to "Top · month",
    Sort.TOP_YEAR to "Top · year", Sort.TOP_ALL to "Top · all time",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(vm: FeedViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    var sortMenu by remember { mutableStateOf(false) }
    var goToDialog by remember { mutableStateOf(false) }

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

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
                title = {
                    TextButton(onClick = { goToDialog = true }) {
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
                },
            )
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
                PostCard(post)
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

    if (goToDialog) {
        GoToSubredditDialog(
            onDismiss = { goToDialog = false },
            onGo = { goToDialog = false; vm.open(it) },
        )
    }
}

private fun Media.kindKey(): Int = when (this) {
    is Media.Text -> 0
    is Media.Image -> 1
    is Media.Gallery -> 2
    is Media.Video -> 3
    is Media.Gif -> 4
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

@Composable
private fun GoToSubredditDialog(onDismiss: () -> Unit, onGo: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to subreddit") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                prefix = { Text("r/") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onGo(text) }, enabled = text.isNotBlank()) { Text("Open") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
