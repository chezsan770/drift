package dev.drift.app.feed

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.drift.core.Post

/** Long-press menu: the actions that are not worth a button on every card. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostActionsSheet(post: Post, onOpenUrl: (String) -> Unit, onHide: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Text(
                post.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val isRedditPost = post.subreddit.isNotEmpty() && post.subreddit != "demo"
            SheetRow("Share") {
                val text = if (isRedditPost) post.permalink else post.url
                context.startActivity(
                    Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)
                )
                onDismiss()
            }
            if (post.url.isNotEmpty()) SheetRow("Open link in browser") { onOpenUrl(post.url); onDismiss() }
            if (isRedditPost) SheetRow("Open on Reddit") { onOpenUrl(post.permalink); onDismiss() }
            SheetRow("Copy link") {
                copy(context, post.url.ifEmpty { post.permalink })
                onDismiss()
            }
            SheetRow("Hide post") { onHide(); onDismiss() }
        }
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("link", text))
}

@Composable
private fun SheetRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
    )
}

/**
 * Switch feeds: type a subreddit (or paste a link), or pick a recent one.
 * Subscriptions join this list once account login exists (Phase 6).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubredditSheet(
    current: String,
    recent: List<String>,
    onGo: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val isLink = text.trimStart().startsWith("http")
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    prefix = if (isLink || text.isEmpty()) null else ({ Text("r/") }),
                    placeholder = { Text("subreddit, or paste a link") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (text.isNotBlank()) onGo(text) }),
                )
                TextButton(onClick = { onGo(text) }, enabled = text.isNotBlank()) { Text("Go") }
            }
            if (isLink) {
                Text(
                    "Opens the image or video from this link",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
            val choices = (recent + "demo").distinctBy { it.lowercase() }.filterNot { it.equals(current, ignoreCase = true) }
            if (choices.isNotEmpty()) {
                Text(
                    "Recent",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp),
                )
                LazyColumn {
                    items(choices, key = { it.lowercase() }) { name ->
                        SheetRow(if (name == "demo") "r/demo  (test media)" else "r/$name") { onGo(name) }
                    }
                }
            }
        }
    }
}
