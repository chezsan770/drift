package dev.drift.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.drift.app.feed.FeedScreen
import dev.drift.app.feed.FeedViewModel
import dev.drift.app.ui.DriftTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val core = (application as DriftApp).core
        setContent {
            DriftTheme {
                val vm: FeedViewModel = viewModel { FeedViewModel(core) }
                FeedScreen(vm)
            }
        }
    }
}
