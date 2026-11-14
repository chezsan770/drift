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

### Limited mode (no client id yet)

Until API access is approved, feeds come from Reddit's public RSS/Atom feeds,
which Reddit publishes for feed readers:

- Read-only; no scores, comment counts or NSFW flags; galleries appear as links.
- Reddit allows roughly **one feed request per minute** this way. The core reads
  `x-ratelimit-*` headers and refuses further requests locally until the window
  resets (the app shows a countdown and loads automatically), so it never adds
  load beyond what Reddit permits.
- Combined `a+b` feeds aren't served over RSS, so "Home" takes turns through the
  configured subreddits, one request per page. A user's own private feed link
  (reddit.com/prefs/feeds) can be used instead; it is stored only on the device.
- Once a client id is configured, everything switches to the OAuth API.

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

## Media

- **Feed:** card-sized (~1080px) previews; the most visible video/GIF
  autoplays muted through one shared ExoPlayer (always the highest bitrate the
  device supports).
- **Viewer:** tap any media for full screen. Images load at full resolution
  with pinch/double-tap zoom (subsampled when huge); galleries swipe sideways;
  videos continue from the feed position with sound; swipe down to close.
- **Prefetch:** the Rust core picks which images to download ahead based on
  scroll speed (`prefetch_urls`).
- **Links from anywhere:** share a link to Drift from any app, or paste it in
  the "Go to…" dialog. Direct image/video files, HLS/DASH, Imgur, Giphy,
  Streamable, Redgifs, v.redd.it and Reddit post links resolve to playable
  media; other pages fall back to their OpenGraph image/video. Link posts from
  media hosts inside a feed are upgraded in place.
- **Demo feed:** open `r/demo` (the default when no client id is configured) for
  real high-res test media that doesn't need Reddit API access.

## Speed

Measured on a Redmi Note 12 Pro (Android 14), release build:

- Cold start: ~400–500 ms to the first frame; the last feed is shown from
  the on-disk cache (Rust, JSON per feed) and refreshed in the background.
- Scrolling: 0.66% janky frames, 99th percentile frame 19 ms (120 Hz display),
  after reading image sizes from post titles (no re-layout while scrolling)
  and installing library baseline profiles (`profileinstaller`).
- Release APK ≈ 11 MB (Rust core 3.6 MB for arm64, fat LTO; R8 for the app).

## Saving

"Save to gallery" (long-press menu, or Save in the viewer) puts files in
Pictures/Drift and Movies/Drift. The Rust core streams downloads (size-capped);
Reddit videos, whose audio is a separate stream, are joined into one MP4 with
Media3 Transformer. Galleries save every image. Android 10+.

## UI

Calm and content-first: title, media and one grey metadata line per post.

- **Layouts:** cards (full-width media) or compact (thumbnail on the right).
- **Settings (⋮):** theme (system / black / light), layout, autoplay
  (always / Wi-Fi only / never), video quality (highest / data saver), show
  NSFW, blur spoilers.
- **NSFW / spoilers:** blurred until tapped (NSFW unless enabled); blurred
  videos never autoplay; the random button skips NSFW subreddits unless enabled.
- **Long-press a post:** share, open link, open on Reddit, copy link, hide.
- **Tap the subreddit name:** type a subreddit or paste a link; recent
  subreddits are listed.

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
