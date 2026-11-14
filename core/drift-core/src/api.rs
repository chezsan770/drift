//! HTTP layer: one pooled HTTP/2 client, app-only OAuth when a client id is
//! configured, anonymous `.json` endpoints otherwise.

use crate::error::CoreError;
use crate::model::CoreConfig;
use crate::raw;
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
    redgifs_token: Mutex<Option<Token>>,
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
        Self { http, cfg, token: Mutex::new(None), redgifs_token: Mutex::new(None) }
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

    /// The shared client, for requests to non-Reddit hosts (link resolving).
    pub fn http(&self) -> &Client {
        &self.http
    }

    /// Short-lived anonymous token for the Redgifs API, cached in memory.
    pub async fn redgifs_token(&self, force_refresh: bool) -> Result<String, CoreError> {
        let mut guard = self.redgifs_token.lock().await;
        if !force_refresh {
            if let Some(t) = guard.as_ref() {
                if t.expires_at > Instant::now() {
                    return Ok(t.access.clone());
                }
            }
        }
        #[derive(Deserialize)]
        struct Temp {
            token: String,
        }
        let resp = self.http.get("https://api.redgifs.com/v2/auth/temporary").send().await?;
        check_status(&resp)?;
        let t: Temp = serde_json::from_slice(&resp.bytes().await?)?;
        *guard = Some(Token {
            access: t.token.clone(),
            // Tokens last ~24h; refresh well before that.
            expires_at: Instant::now() + Duration::from_secs(12 * 3600),
        });
        Ok(t.token)
    }
}

pub(crate) fn content_type(h: &HeaderMap) -> String {
    h.get(CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_ascii_lowercase()
}

pub(crate) fn check_status(resp: &reqwest::Response) -> Result<(), CoreError> {
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
