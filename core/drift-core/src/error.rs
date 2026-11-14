/// Crosses to Kotlin as `CoreException` subclasses whose `message` is the
/// Display text below (flat error), ready to show in the UI.
#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum CoreError {
    #[error("network error: {message}")]
    Network { message: String },
    #[error("HTTP {status}")]
    Http { status: u16 },
    #[error("rate limited, retry in {retry_after_secs}s")]
    RateLimited { retry_after_secs: u32 },
    #[error("could not parse response: {message}")]
    Parse { message: String },
    #[error("not found")]
    NotFound,
    /// Reddit refused the request (403). Without a client id this is the
    /// normal response: anonymous access is blocked.
    #[error("Reddit blocked the request (HTTP 403). Add your Reddit client id (reddit.clientId in android/local.properties).")]
    Blocked,
    #[error("no usable subreddit found")]
    NoSubreddit,
    #[error("that doesn't contain a link")]
    NoLink,
}

impl From<reqwest::Error> for CoreError {
    fn from(e: reqwest::Error) -> Self {
        match e.status() {
            Some(s) => CoreError::Http { status: s.as_u16() },
            None => CoreError::Network { message: e.to_string() },
        }
    }
}

impl From<serde_json::Error> for CoreError {
    fn from(e: serde_json::Error) -> Self {
        CoreError::Parse { message: e.to_string() }
    }
}
