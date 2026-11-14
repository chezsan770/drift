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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** One plain list of settings. Changes apply immediately. */
@Composable
fun SettingsScreen(settings: AppSettings, onChange: ((AppSettings) -> AppSettings) -> Unit, onClose: () -> Unit) {
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
        }
    }
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
