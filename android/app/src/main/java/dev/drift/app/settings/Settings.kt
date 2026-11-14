package dev.drift.app.settings

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ThemeMode { SYSTEM, DARK, LIGHT }
enum class Layout { CARD, COMPACT }
enum class Autoplay { ALWAYS, WIFI_ONLY, NEVER }
enum class Quality { HIGHEST, ADAPTIVE }

@Immutable
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val layout: Layout = Layout.CARD,
    val autoplay: Autoplay = Autoplay.ALWAYS,
    val quality: Quality = Quality.HIGHEST,
    /** Off: NSFW media is blurred and the random button skips NSFW subreddits. */
    val showNsfw: Boolean = false,
    val blurSpoilers: Boolean = true,
    /** Subreddits combined into the Home feed, e.g. "EarthPorn pics aww". */
    val homeSubreddits: String = "",
    /** Private Reddit RSS link for the user's own front page (stays on device). */
    val privateFeedUrl: String = "",
) {
    /** "a+b+c" for a combined feed, or "" when no subreddits are set. */
    val homeMulti: String
        get() = homeSubreddits.split(' ', ',', '\n', '+')
            .map { it.trim().removePrefix("r/").removePrefix("/r/") }
            .filter { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '_' } }
            .distinctBy { it.lowercase() }
            .joinToString("+")

    val hasHome: Boolean get() = privateFeedUrl.isNotBlank() || homeMulti.isNotEmpty()
}

/**
 * Small on-device preferences: settings, recently visited subreddits and
 * hidden posts. Exposed as StateFlows so the UI updates instantly.
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("drift_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _recent = MutableStateFlow(
        prefs.getString(KEY_RECENT, "").orEmpty().split('\n').filter { it.isNotBlank() }
    )
    val recentSubreddits: StateFlow<List<String>> = _recent.asStateFlow()

    private val hidden: LinkedHashSet<String> =
        LinkedHashSet(prefs.getString(KEY_HIDDEN, "").orEmpty().split('\n').filter { it.isNotBlank() })

    fun update(change: (AppSettings) -> AppSettings) {
        _settings.update(change)
        val s = _settings.value
        prefs.edit()
            .putString("theme", s.theme.name)
            .putString("layout", s.layout.name)
            .putString("autoplay", s.autoplay.name)
            .putString("quality", s.quality.name)
            .putBoolean("nsfw", s.showNsfw)
            .putBoolean("blur_spoilers", s.blurSpoilers)
            .putString("home_subreddits", s.homeSubreddits)
            .putString("private_feed_url", s.privateFeedUrl)
            .apply()
    }

    fun rememberSubreddit(name: String) {
        if (name.isBlank()) return
        _recent.update { list ->
            (listOf(name) + list.filterNot { it.equals(name, ignoreCase = true) }).take(MAX_RECENT)
        }
        prefs.edit().putString(KEY_RECENT, _recent.value.joinToString("\n")).apply()
    }

    fun isHidden(postId: String): Boolean = postId in hidden

    private val _hiddenCount = MutableStateFlow(hidden.size)
    val hiddenCount: StateFlow<Int> = _hiddenCount.asStateFlow()

    fun hide(postId: String) {
        hidden.add(postId)
        while (hidden.size > MAX_HIDDEN) hidden.remove(hidden.first())
        prefs.edit().putString(KEY_HIDDEN, hidden.joinToString("\n")).apply()
        _hiddenCount.value = hidden.size
    }

    fun unhideAll() {
        hidden.clear()
        prefs.edit().remove(KEY_HIDDEN).apply()
        _hiddenCount.value = 0
    }

    private fun load() = AppSettings(
        theme = enumOr(prefs.getString("theme", null), ThemeMode.SYSTEM),
        layout = enumOr(prefs.getString("layout", null), Layout.CARD),
        autoplay = enumOr(prefs.getString("autoplay", null), Autoplay.ALWAYS),
        quality = enumOr(prefs.getString("quality", null), Quality.HIGHEST),
        showNsfw = prefs.getBoolean("nsfw", false),
        blurSpoilers = prefs.getBoolean("blur_spoilers", true),
        homeSubreddits = prefs.getString("home_subreddits", "").orEmpty(),
        privateFeedUrl = prefs.getString("private_feed_url", "").orEmpty(),
    )

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default

    private companion object {
        const val KEY_RECENT = "recent_subreddits"
        const val KEY_HIDDEN = "hidden_posts"
        const val MAX_RECENT = 12
        const val MAX_HIDDEN = 1000
    }
}
