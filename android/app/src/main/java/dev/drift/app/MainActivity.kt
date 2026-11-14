package dev.drift.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.drift.app.feed.FeedScreen
import dev.drift.app.feed.FeedViewModel
import dev.drift.app.ui.DriftTheme

class MainActivity : ComponentActivity() {

    private val vm: FeedViewModel by viewModels {
        viewModelFactory {
            initializer {
                // Without Reddit API access, start on the built-in demo feed (r/demo).
                val start = if (BuildConfig.REDDIT_CLIENT_ID.isBlank()) "demo" else "EarthPorn"
                FeedViewModel((application as DriftApp).core, start)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Only handle the launch intent once (not again after rotation).
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            DriftTheme { FeedScreen(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        vm.addLink(text)
    }
}
