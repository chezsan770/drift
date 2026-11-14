# Drift

A minimal, personal Android client for browsing Reddit media: images, galleries
and videos at full quality, with a "random subreddit" button for discovering
smaller communities.

Personal, non-commercial project. Not distributed on any app store.

## How it uses the Reddit API

- **Read-only.** It loads subreddit listings (`/r/{sub}/hot|new|rising|top`),
  subreddit info (`/r/{sub}/about`) and, later, post comments.
- **No automation.** It doesn't post, comment, vote, send messages or perform
  any action on behalf of users.
- **Low volume.** A handful of requests per minute while someone is actively
  scrolling, well below the 100 requests/minute OAuth limit. Rate-limit headers
  (`x-ratelimit-*`) are respected and `429` responses are backed off.
- **Identifies itself.** Every request sends a descriptive User-Agent
  (`android:dev.drift.app:v{version} (by /u/{username})`).
- **No data retention or sharing.** Only a short-lived on-device cache. No
  analytics, no ads, no data sold, shared or used to train AI models.
- **Auth:** OAuth "installed app" client, app-only grant
  (`https://oauth.reddit.com/grants/installed_client`). The client id is kept in
  a local, untracked file and never committed.

## Architecture

| Part | Tech |
|---|---|
| Networking, OAuth, JSON parsing, media resolving, formatting | Rust (`core/drift-core`): `reqwest` + rustls, `serde`, `tokio` |
| Rust ↔ Kotlin bridge | [UniFFI](https://github.com/mozilla/uniffi-rs) (Kotlin bindings generated at build time) |
| UI | Kotlin + Jetpack Compose (`android/`) |
| Images | Coil 3 |

The Rust core picks the highest-quality source for every post: original
`i.redd.it` images, galleries in their posted order, HLS/DASH streams for
`v.redd.it` (video with audio), MP4 versions of GIFs, and crosspost parents.

## Building

Requirements: Rust (with the `aarch64-linux-android` and `x86_64-linux-android`
targets), [`cargo-ndk`](https://github.com/bbqsrc/cargo-ndk), Android SDK +
NDK 29, JDK 17+.

```bash
# Rust tests
cd core && cargo test

# Android app (builds the Rust library and bindings automatically)
cd android && ./gradlew installDebug
```

Create `android/local.properties` with:

```properties
sdk.dir=/path/to/Android/Sdk
reddit.clientId=YOUR_INSTALLED_APP_CLIENT_ID
reddit.username=your_reddit_username
```
