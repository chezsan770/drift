//! Drift core: all networking, parsing, media resolving and formatting for
//! the Android app. Exposed to Kotlin through UniFFI (`DriftCore`).

uniffi::setup_scaffolding!();

mod api;
mod demo;
mod error;
pub mod prefetch;
mod format;
mod links;
pub mod media;
mod model;
mod random;
mod raw;
mod rss;

pub use error::CoreError;
pub use model::*;

use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

#[derive(uniffi::Object)]
pub struct DriftCore {
    api: api::Api,
    history: Mutex<random::History>,
}

#[uniffi::export(async_runtime = "tokio")]
impl DriftCore {
    #[uniffi::constructor]
    pub fn new(config: CoreConfig) -> Arc<Self> {
        Arc::new(Self {
            api: api::Api::new(config),
            history: Mutex::new(random::History::new(30)),
        })
    }

    /// One page of a subreddit (`"pics"`, or `""` for the front page).
    pub async fn feed(
        &self,
        subreddit: String,
        sort: Sort,
        after: Option<String>,
    ) -> Result<FeedPage, CoreError> {
        if subreddit.eq_ignore_ascii_case("demo") {
            return Ok(if after.is_none() { demo::page(now()) } else { FeedPage { posts: vec![], after: None } });
        }
        let (segment, t) = sort.parts();
        if self.api.is_anonymous() {
            return self.rss_feed(&subreddit, segment, t, after).await;
        }
        let path = if subreddit.is_empty() {
            format!("/{segment}")
        } else {
            format!("/r/{subreddit}/{segment}")
        };
        let mut q: Vec<(&str, &str)> = vec![("limit", "50")];
        if let Some(t) = t {
            q.push(("t", t));
        }
        if let Some(a) = after.as_deref() {
            q.push(("after", a));
        }
        let body = self.api.get(&path, &q).await?;
        parse_listing(&body, now())
    }

    /// A user's private Reddit RSS link (e.g. their front page from
    /// reddit.com/prefs/feeds). Only reddit.com links are fetched.
    pub async fn feed_private(&self, url: String, after: Option<String>) -> Result<FeedPage, CoreError> {
        let limit = rss::PAGE_SIZE.to_string();
        let mut q: Vec<(&str, &str)> = vec![("limit", &limit)];
        if let Some(a) = after.as_deref() {
            q.push(("after", a));
        }
        let xml = self.api.get_rss(url.trim(), &q).await?;
        Ok(rss::parse_feed(&xml, now()))
    }

    /// True when running without API access (RSS fallback: read-only,
    /// about one feed request per minute, no scores or comment counts).
    pub fn is_limited_mode(&self) -> bool {
        self.api.is_anonymous()
    }

    /// Seconds until Reddit will accept another request (0 = now).
    pub fn rate_limit_wait_secs(&self) -> u32 {
        self.api.wait_secs()
    }

    /// A random, public, non-empty subreddit name. NSFW only if allowed.
    pub async fn random_subreddit(&self, allow_nsfw: bool) -> Result<String, CoreError> {
        if self.api.is_anonymous() {
            // Can't afford an /about check per pick at ~1 request/minute;
            // the seed list is hand-picked public SFW subreddits.
            let _ = allow_nsfw;
            let mut h = self.history.lock().unwrap();
            let pick = random::pick(random::SEED, &h).to_string();
            h.push(&pick);
            return Ok(pick);
        }
        for _ in 0..6 {
            let candidate = {
                let h = self.history.lock().unwrap();
                random::pick(random::SEED, &h).to_string()
            };
            self.history.lock().unwrap().push(&candidate);
            match self.api.about(&candidate).await {
                Ok(a) if a.subreddit_type == "public"
                    && !a.quarantine
                    && (allow_nsfw || !a.over18)
                    && a.subscribers.unwrap_or(0) > 0 =>
                {
                    return Ok(if a.display_name.is_empty() { candidate } else { a.display_name });
                }
                Ok(_) | Err(CoreError::NotFound) => continue,
                Err(e) => return Err(e),
            }
        }
        Err(CoreError::NoSubreddit)
    }

    /// Resolve any link (pasted, shared or from a post) to playable media.
    pub async fn resolve_link(&self, url: String) -> Result<Media, CoreError> {
        Ok(links::resolve(&self.api, url.trim()).await?.media)
    }

    /// Turn shared/pasted text containing a link into a feed post with the
    /// link's media resolved (title from the page or provider when found).
    pub async fn link_post(&self, text: String) -> Result<Post, CoreError> {
        let url = extract_url(text).ok_or(CoreError::NoLink)?;
        let r = links::resolve(&self.api, &url).await?;
        let host = url::Url::parse(&url)
            .ok()
            .and_then(|u| u.host_str().map(|h| h.trim_start_matches("www.").to_string()))
            .unwrap_or_default();
        Ok(Post {
            id: format!("link-{:x}", hash(&url)),
            subreddit: String::new(),
            author: String::new(),
            title: r.title.unwrap_or_else(|| format!("Shared from {host}")),
            permalink: url.clone(),
            url,
            domain: host,
            score_text: String::new(),
            comments_text: String::new(),
            age_text: "now".into(),
            is_nsfw: false,
            is_spoiler: false,
            is_stickied: false,
            media: r.media,
        })
    }

    /// Resolve link posts from known media hosts (Streamable, Redgifs,
    /// Giphy, Imgur albums, ...) into playable media. Returns only the posts
    /// that changed; runs a few lookups in parallel, each time-limited.
    pub async fn upgrade_links(&self, posts: Vec<Post>) -> Vec<Post> {
        use futures_util::stream::{self, StreamExt};
        let candidates = posts.into_iter().filter(|p| match &p.media {
            Media::Link { url, .. } => links::worth_upgrading(url),
            _ => false,
        });
        stream::iter(candidates)
            .map(|mut p| async move {
                let Media::Link { url, thumbnail } = &p.media else { return None };
                let fut = links::resolve(&self.api, url);
                let r = tokio::time::timeout(std::time::Duration::from_secs(8), fut).await.ok()?.ok()?;
                let media = match r.media {
                    Media::Link { .. } => return None,
                    // Keep Reddit's preview if the provider gave none.
                    Media::Video { hls_url, dash_url, mp4_url, width, height, has_audio, preview } => Media::Video {
                        hls_url, dash_url, mp4_url, width, height, has_audio,
                        preview: preview.or_else(|| thumbnail.clone()),
                    },
                    Media::Gif { mp4_url, width, height, preview } => {
                        Media::Gif { mp4_url, width, height, preview: preview.or_else(|| thumbnail.clone()) }
                    }
                    other => other,
                };
                p.media = media;
                Some(p)
            })
            .buffer_unordered(6)
            .filter_map(|x| async move { x })
            .collect()
            .await
    }
}

impl DriftCore {
    async fn rss_feed(
        &self,
        subreddit: &str,
        segment: &str,
        t: Option<&str>,
        after: Option<String>,
    ) -> Result<FeedPage, CoreError> {
        // Hot is the default listing, so it gets no path segment.
        let sort_path = if segment == "hot" { String::new() } else { format!("{segment}/") };
        let url = if subreddit.is_empty() {
            format!("https://www.reddit.com/{sort_path}.rss")
        } else {
            format!("https://www.reddit.com/r/{subreddit}/{sort_path}.rss")
        };
        let limit = rss::PAGE_SIZE.to_string();
        let mut q: Vec<(&str, &str)> = vec![("limit", &limit)];
        if let Some(t) = t {
            q.push(("t", t));
        }
        if let Some(a) = after.as_deref() {
            q.push(("after", a));
        }
        let xml = self.api.get_rss(&url, &q).await?;
        Ok(rss::parse_feed(&xml, now()))
    }
}

/// First http(s) URL in a piece of text (share intents often send
/// "Some title https://..."). Returns `None` if there is none.
#[uniffi::export]
pub fn extract_url(text: String) -> Option<String> {
    text.split_whitespace()
        .map(|w| w.trim_matches(|c: char| matches!(c, '<' | '>' | '"' | '\'' | '(' | ')' | ',')))
        .find(|w| (w.starts_with("https://") || w.starts_with("http://")) && url::Url::parse(w).is_ok())
        .map(str::to_owned)
}

fn hash(s: &str) -> u64 {
    use std::hash::{Hash, Hasher};
    let mut h = std::collections::hash_map::DefaultHasher::new();
    s.hash(&mut h);
    h.finish()
}

/// Parse a listing body into display-ready posts. Fast path is a single
/// typed parse; if any post has an unexpected shape we fall back to parsing
/// posts one by one and dropping only the bad ones.
pub fn parse_listing(body: &[u8], now_utc: f64) -> Result<FeedPage, CoreError> {
    let (after, raws): (Option<String>, Vec<raw::RawPost>) =
        match serde_json::from_slice::<raw::Listing>(body) {
            Ok(l) => (
                l.data.after,
                l.data.children.into_iter().filter(|c| c.kind == "t3").map(|c| c.data).collect(),
            ),
            Err(_) => {
                let v: serde_json::Value = serde_json::from_slice(body)?;
                let data = &v["data"];
                let after = data["after"].as_str().map(str::to_owned);
                let raws = data["children"]
                    .as_array()
                    .map(|cs| {
                        cs.iter()
                            .filter(|c| c["kind"] == "t3")
                            .filter_map(|c| serde_json::from_value(c["data"].clone()).ok())
                            .collect()
                    })
                    .unwrap_or_default();
                (after, raws)
            }
        };
    let posts = raws.iter().map(|p| to_post(p, now_utc)).collect();
    Ok(FeedPage { posts, after })
}

fn to_post(p: &raw::RawPost, now_utc: f64) -> Post {
    Post {
        id: p.id.clone(),
        subreddit: p.subreddit.clone(),
        author: p.author.clone(),
        title: p.title.clone(),
        permalink: format!("https://www.reddit.com{}", p.permalink),
        url: p.url.as_deref().map(format::unescape_url).unwrap_or_default(),
        domain: p.domain.clone(),
        score_text: format::compact_number(p.score),
        comments_text: format::compact_number(p.num_comments),
        age_text: format::relative_age(p.created_utc, now_utc),
        is_nsfw: p.over_18,
        is_spoiler: p.spoiler,
        is_stickied: p.stickied,
        media: media::from_post(p),
    }
}

fn now() -> f64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs_f64()).unwrap_or(0.0)
}
