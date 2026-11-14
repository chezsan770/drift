//! HTTP layer: one pooled HTTP/2 client, app-only OAuth when a client id is
//! configured, anonymous `.json` endpoints otherwise.

use crate::error::CoreError;
use crate::model::{CoreConfig, Media};
use crate::{media, raw};
use reqwest::header::{HeaderMap, CONTENT_TYPE};
use reqwest::{Client, StatusCode};
use serde::Deserialize;
use std::time::{Duration, Instant};
use tokio::sync::Mutex;

const OAUTH_BASE: &str = "https://oauth.reddit.com";
const ANON_BASE: &str = "https://www.reddit.com";
const TOKEN_URL: &str = "https://www.reddit.com/api/v1/access_token";
const INSTALLED_CLIENT_GRANT: &str = "https://oauth.reddit.com/grants/installed_client";

struct Token {
    access: String,
    expires_at: Instant,
}

pub struct Api {
    http: Client,
    cfg: CoreConfig,
    token: Mutex<Option<Token>>,
}

#[derive(Deserialize)]
struct TokenResponse {
    access_token: String,
    expires_in: u64,
}

impl Api {
    pub fn new(cfg: CoreConfig) -> Self {
        let http = Client::builder()
            .user_agent(cfg.user_agent.clone())
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(30))
            .pool_idle_timeout(Duration::from_secs(90))
            .gzip(true)
            .brotli(true)
            .build()
            .expect("static client config is valid");
        Self { http, cfg, token: Mutex::new(None) }
    }

    async fn bearer(&self, client_id: &str) -> Result<String, CoreError> {
        let mut guard = self.token.lock().await;
        if let Some(t) = guard.as_ref() {
            if t.expires_at > Instant::now() + Duration::from_secs(60) {
                return Ok(t.access.clone());
            }
        }
        let resp = self
            .http
            .post(TOKEN_URL)
            .basic_auth(client_id, Some(""))
            .form(&[("grant_type", INSTALLED_CLIENT_GRANT), ("device_id", &self.cfg.device_id)])
            .send()
            .await?;
        check_status(&resp)?;
        let t: TokenResponse = serde_json::from_slice(&resp.bytes().await?)?;
        *guard = Some(Token {
            access: t.access_token.clone(),
            expires_at: Instant::now() + Duration::from_secs(t.expires_in),
        });
        Ok(t.access_token)
    }

    /// GET a Reddit API path like `/r/pics/hot` and return the raw body.
    pub async fn get(&self, path: &str, query: &[(&str, &str)]) -> Result<bytes::Bytes, CoreError> {
        let mut q: Vec<(&str, &str)> = query.to_vec();
        q.push(("raw_json", "1"));
        let req = match self.cfg.client_id.as_deref().filter(|c| !c.is_empty()) {
            Some(cid) => {
                let token = self.bearer(cid).await?;
                self.http.get(format!("{OAUTH_BASE}{path}")).bearer_auth(token)
            }
            None => self.http.get(format!("{ANON_BASE}{path}.json")),
        };
        let resp = req.query(&q).send().await?;
        check_status(&resp)?;
        Ok(resp.bytes().await?)
    }

    pub async fn about(&self, subreddit: &str) -> Result<raw::AboutData, CoreError> {
        let body = self.get(&format!("/r/{subreddit}/about"), &[]).await?;
        let about: raw::AboutResponse = serde_json::from_slice(&body)?;
        if about.kind != "t5" {
            return Err(CoreError::NotFound);
        }
        Ok(about.data)
    }

    /// Network fallback for links `media::classify_url` couldn't place:
    /// sniff the Content-Type, then look for OpenGraph media in HTML.
    pub async fn resolve_remote(&self, url: &str) -> Result<Media, CoreError> {
        let link = || Media::Link { url: url.to_string(), thumbnail: None };

        let resp = self.http.get(url).header("Range", "bytes=0-262143").send().await?;
        if !resp.status().is_success() {
            return Ok(link());
        }
        let ctype = content_type(resp.headers());
        if ctype.starts_with("image/") {
            return Ok(Media::Image { url: url.to_string(), width: 0, height: 0, preview: None });
        }
        if ctype.starts_with("video/") || ctype.contains("mpegurl") {
            let hls = ctype.contains("mpegurl");
            return Ok(Media::Video {
                hls_url: hls.then(|| url.to_string()),
                dash_url: None,
                mp4_url: (!hls).then(|| url.to_string()),
                width: 0,
                height: 0,
                has_audio: true,
                preview: None,
            });
        }
        if !ctype.contains("html") {
            return Ok(link());
        }
        let html = String::from_utf8_lossy(&resp.bytes().await?).into_owned();
        let image = media::find_meta(&html, &["og:image", "twitter:image"]);
        if let Some(v) = media::find_meta(
            &html,
            &["og:video:secure_url", "og:video:url", "og:video", "twitter:player:stream"],
        ) {
            if let Some(m @ (Media::Video { .. } | Media::Gif { .. })) = media::classify_url(&v, None) {
                return Ok(with_preview(m, image));
            }
        }
        if let Some(img) = image {
            return Ok(Media::Link { url: url.to_string(), thumbnail: Some(img) });
        }
        Ok(link())
    }
}

fn with_preview(m: Media, img: Option<String>) -> Media {
    match m {
        Media::Video { hls_url, dash_url, mp4_url, width, height, has_audio, .. } => {
            Media::Video { hls_url, dash_url, mp4_url, width, height, has_audio, preview: img }
        }
        Media::Gif { mp4_url, width, height, .. } => Media::Gif { mp4_url, width, height, preview: img },
        other => other,
    }
}

fn content_type(h: &HeaderMap) -> String {
    h.get(CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_ascii_lowercase()
}

fn check_status(resp: &reqwest::Response) -> Result<(), CoreError> {
    let s = resp.status();
    if s.is_success() {
        return Ok(());
    }
    if s == StatusCode::TOO_MANY_REQUESTS {
        let secs = resp
            .headers()
            .get("x-ratelimit-reset")
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.parse::<f64>().ok())
            .unwrap_or(60.0);
        return Err(CoreError::RateLimited { retry_after_secs: secs.ceil() as u32 });
    }
    if s == StatusCode::NOT_FOUND {
        return Err(CoreError::NotFound);
    }
    if s == StatusCode::FORBIDDEN {
        // Private/banned subreddits also answer 403, but with a JSON body;
        // the block page is HTML.
        let is_json = content_type(resp.headers()).contains("json");
        return Err(if is_json { CoreError::NotFound } else { CoreError::Blocked });
    }
    Err(CoreError::Http { status: s.as_u16() })
}
