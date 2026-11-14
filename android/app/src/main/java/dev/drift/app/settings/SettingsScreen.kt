package dev.drift.app.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** One plain list of settings. Changes apply immediately. */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    limitedMode: Boolean,
    hiddenCount: Int,
    onUnhideAll: () -> Unit,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("←", style = MaterialTheme.typography.titleLarge) }
            Text("Settings", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            HomeSection(settings, limitedMode, onChange)
            Choice(
                "Theme", settings.theme,
                listOf(ThemeMode.SYSTEM to "System", ThemeMode.DARK to "Black", ThemeMode.LIGHT to "Light"),
            ) { v -> onChange { it.copy(theme = v) } }
            Choice(
                "Layout", settings.layout,
                listOf(Layout.CARD to "Cards", Layout.COMPACT to "Compact"),
            ) { v -> onChange { it.copy(layout = v) } }
            Choice(
                "Autoplay videos", settings.autoplay,
                listOf(Autoplay.ALWAYS to "Always", Autoplay.WIFI_ONLY to "Wi-Fi only", Autoplay.NEVER to "Never"),
                hint = "Videos always play muted in the feed; tap to open with sound.",
            ) { v -> onChange { it.copy(autoplay = v) } }
            Choice(
                "Video quality", settings.quality,
                listOf(Quality.HIGHEST to "Highest", Quality.ADAPTIVE to "Data saver"),
                hint = "Data saver lets quality follow your connection speed.",
            ) { v -> onChange { it.copy(quality = v) } }
            Toggle(
                "Show NSFW content", settings.showNsfw,
                hint = "When off, NSFW media is blurred and the random button skips NSFW subreddits.",
            ) { v -> onChange { it.copy(showNsfw = v) } }
            Toggle("Blur spoilers", settings.blurSpoilers) { v -> onChange { it.copy(blurSpoilers = v) } }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Hidden posts", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        if (hiddenCount == 0) "None" else "$hiddenCount hidden",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onUnhideAll, enabled = hiddenCount > 0) { Text("Unhide all") }
            }
        }
    }
}

/** The Home feed: a combined list of subreddits, or the user's private RSS link. */
@Composable
private fun HomeSection(settings: AppSettings, limitedMode: Boolean, onChange: ((AppSettings) -> AppSettings) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Text("Home feed", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
        if (limitedMode) {
            Text(
                "Limited mode: no Reddit API access yet, so feeds come from Reddit's public RSS. " +
                    "About one feed load per minute; no scores or comment counts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        OutlinedTextField(
            value = settings.homeSubreddits,
            onValueChange = { v -> onChange { it.copy(homeSubreddits = v) } },
            label = { Text("Subreddits") },
            placeholder = { Text("EarthPorn pics aww") },
            supportingText = {
                Text(
                    if (limitedMode) "Separated by spaces. Loaded one subreddit at a time, taking turns."
                    else "Separated by spaces. Loaded together as one feed."
                )
            },
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
        OutlinedTextField(
            value = settings.privateFeedUrl,
            onValueChange = { v -> onChange { it.copy(privateFeedUrl = v.trim()) } },
            label = { Text("Or: your private Reddit RSS link") },
            placeholder = { Text("https://www.reddit.com/.rss?feed=…&user=…") },
            supportingText = {
                Text("From reddit.com/prefs/feeds (\"your front page\"). Used instead of the list above. Kept only on this phone; treat it like a password.")
            },
            singleLine = true,
            visualTransformation = if (settings.privateFeedUrl.isEmpty()) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun <T> Choice(title: String, value: T, options: List<Pair<T, String>>, hint: String? = null, onPick: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (v, label) ->
                FilterChip(selected = v == value, onClick = { onPick(v) }, label = { Text(label) })
            }
        }
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun Toggle(title: String, value: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = value, onCheckedChange = onChange)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
