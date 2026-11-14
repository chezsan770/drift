//! Drift core: all networking, parsing, media resolving and formatting for
//! the Android app. Exposed to Kotlin through UniFFI (`DriftCore`).

uniffi::setup_scaffolding!();

mod api;
mod demo;
mod error;
pub mod prefetch;
mod format;
pub mod media;
mod model;
mod random;
mod raw;

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

    /// A random, public, non-empty subreddit name. NSFW only if allowed.
    pub async fn random_subreddit(&self, allow_nsfw: bool) -> Result<String, CoreError> {
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
        let url = url.trim().to_string();
        if let Some(m) = media::classify_url(&url, None) {
            return Ok(m);
        }
        self.api.resolve_remote(&url).await
    }
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
