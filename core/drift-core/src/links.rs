//! Resolving arbitrary links to playable/viewable media.
//!
//! Order, cheapest first:
//! 1. URL rules (no network): direct files, imgur, giphy, v.redd.it, YouTube.
//! 2. Provider APIs: Streamable, Redgifs, Reddit posts/galleries.
//! 3. Generic: Content-Type sniff, then OpenGraph/Twitter tags in the page.

use crate::api::{check_status, content_type, Api};
use crate::error::CoreError;
use crate::format::unescape_url;
use crate::media::{self, classify_url, find_meta, find_meta_text};
use crate::model::Media;
use serde::Deserialize;
use std::collections::HashMap;

/// What a link resolved to, plus a title when one was found.
pub struct Resolved {
    pub media: Media,
    pub title: Option<String>,
}

impl Resolved {
    fn bare(media: Media) -> Self {
        Self { media, title: None }
    }
}

#[derive(Debug, PartialEq, Eq)]
pub enum Provider {
    Streamable(String),
    Redgifs(String),
    /// Reddit post id (base36, no `t3_`).
    RedditPost(String),
}

/// Hosts whose links are worth resolving automatically inside the feed.
/// (Arbitrary article links are only resolved when the user opens/pastes
/// them, to avoid fetching every news page in a listing.)
const FEED_MEDIA_HOSTS: &[&str] = &[
    "streamable.com", "redgifs.com", "giphy.com", "imgur.com", "v.redd.it",
    "i.redd.it", "reddit.com", "redd.it", "catbox.moe", "gfycat.com",
];

pub fn worth_upgrading(url: &str) -> bool {
    let Ok(u) = url::Url::parse(url) else { return false };
    let Some(host) = u.host_str() else { return false };
    let host = host.trim_start_matches("www.").to_ascii_lowercase();
    FEED_MEDIA_HOSTS.iter().any(|h| host == *h || host.ends_with(&format!(".{h}")))
}

pub fn provider(raw: &str) -> Option<Provider> {
    let u = url::Url::parse(raw).ok()?;
    let host = u.host_str()?.trim_start_matches("www.").to_ascii_lowercase();
    let segs: Vec<&str> = u.path_segments()?.filter(|s| !s.is_empty()).collect();
    let valid = |s: &str| !s.is_empty() && s.chars().all(|c| c.is_ascii_alphanumeric());

    match host.as_str() {
        "streamable.com" => {
            let code = match segs.as_slice() {
                ["e" | "o" | "s", code, ..] | [code] => *code,
                _ => return None,
            };
            valid(code).then(|| Provider::Streamable(code.to_string()))
        }
        h if h == "redgifs.com" || h.ends_with(".redgifs.com") => {
            let id = match segs.as_slice() {
                ["watch" | "ifr", id, ..] => *id,
                ["i", file] => file.split('.').next()?,
                _ => return None,
            };
            let id = id.split('-').next()?.to_ascii_lowercase();
            valid(&id).then_some(Provider::Redgifs(id))
        }
        "reddit.com" | "old.reddit.com" | "new.reddit.com" | "m.reddit.com" => {
            let id = match segs.as_slice() {
                ["r", _, "comments", id, ..] | ["comments", id, ..] | ["gallery", id] => *id,
                ["user" | "u", _, "comments", id, ..] => *id,
                _ => return None,
            };
            valid(id).then(|| Provider::RedditPost(id.to_ascii_lowercase()))
        }
        "redd.it" => match segs.as_slice() {
            [id] if valid(id) => Some(Provider::RedditPost(id.to_ascii_lowercase())),
            _ => None,
        },
        _ => None,
    }
}

pub async fn resolve(api: &Api, url: &str) -> Result<Resolved, CoreError> {
    if let Some(m) = classify_url(url, None) {
        return Ok(Resolved::bare(m));
    }
    match provider(url) {
        Some(Provider::Streamable(code)) => {
            if let Ok(r) = streamable(api, &code).await {
                return Ok(r);
            }
        }
        Some(Provider::Redgifs(id)) => return redgifs(api, &id).await,
        Some(Provider::RedditPost(id)) => return reddit_post(api, &id).await,
        None => {}
    }
    generic(api, url).await
}

// ------------------------------------------------------------ Streamable

#[derive(Deserialize, Default)]
#[serde(default)]
pub(crate) struct StreamableResp {
    title: Option<String>,
    thumbnail_url: Option<String>,
    files: HashMap<String, StreamableFile>,
}

#[derive(Deserialize, Default)]
#[serde(default)]
struct StreamableFile {
    url: Option<String>,
    width: u32,
    height: u32,
}

fn absolute(u: &str) -> String {
    if let Some(rest) = u.strip_prefix("//") {
        format!("https://{rest}")
    } else {
        u.to_string()
    }
}

pub(crate) fn parse_streamable(body: &[u8]) -> Result<Resolved, CoreError> {
    let r: StreamableResp = serde_json::from_slice(body)?;
    // Highest quality first.
    let file = ["mp4", "mp4-high", "mp4-mobile"]
        .iter()
        .find_map(|k| r.files.get(*k).filter(|f| f.url.is_some()))
        .ok_or(CoreError::NotFound)?;
    Ok(Resolved {
        media: Media::Video {
            hls_url: None,
            dash_url: None,
            mp4_url: file.url.as_deref().map(absolute),
            width: file.width,
            height: file.height,
            has_audio: true,
            preview: r.thumbnail_url.as_deref().map(absolute),
        },
        title: r.title.filter(|t| !t.is_empty()),
    })
}

async fn streamable(api: &Api, code: &str) -> Result<Resolved, CoreError> {
    let resp = api.http().get(format!("https://api.streamable.com/videos/{code}")).send().await?;
    check_status(&resp)?;
    parse_streamable(&resp.bytes().await?)
}

// --------------------------------------------------------------- Redgifs

#[derive(Deserialize)]
pub(crate) struct RedgifsResp {
    gif: RedgifsGif,
}

#[derive(Deserialize, Default)]
#[serde(default, rename_all = "camelCase")]
struct RedgifsGif {
    urls: RedgifsUrls,
    width: u32,
    height: u32,
    has_audio: bool,
    description: Option<String>,
}

#[derive(Deserialize, Default)]
#[serde(default)]
struct RedgifsUrls {
    hd: Option<String>,
    sd: Option<String>,
    poster: Option<String>,
    thumbnail: Option<String>,
}

pub(crate) fn parse_redgifs(body: &[u8]) -> Result<Resolved, CoreError> {
    let RedgifsResp { gif } = serde_json::from_slice(body)?;
    let mp4 = gif.urls.hd.or(gif.urls.sd).ok_or(CoreError::NotFound)?;
    let preview = gif.urls.poster.or(gif.urls.thumbnail);
    let media = if gif.has_audio {
        Media::Video {
            hls_url: None,
            dash_url: None,
            mp4_url: Some(mp4),
            width: gif.width,
            height: gif.height,
            has_audio: true,
            preview,
        }
    } else {
        Media::Gif { mp4_url: mp4, width: gif.width, height: gif.height, preview }
    };
    Ok(Resolved { media, title: gif.description.filter(|d| !d.is_empty()) })
}

async fn redgifs(api: &Api, id: &str) -> Result<Resolved, CoreError> {
    let url = format!("https://api.redgifs.com/v2/gifs/{id}");
    for attempt in 0..2 {
        let token = api.redgifs_token(attempt > 0).await?;
        let resp = api.http().get(&url).bearer_auth(token).send().await?;
        if resp.status() == reqwest::StatusCode::UNAUTHORIZED && attempt == 0 {
            continue; // expired token: refresh once
        }
        check_status(&resp)?;
        return parse_redgifs(&resp.bytes().await?);
    }
    Err(CoreError::NotFound)
}

// ---------------------------------------------------------------- Reddit

async fn reddit_post(api: &Api, id: &str) -> Result<Resolved, CoreError> {
    let body = api.get(&format!("/by_id/t3_{id}"), &[]).await?;
    let page = crate::parse_listing(&body, 0.0)?;
    let post = page.posts.into_iter().next().ok_or(CoreError::NotFound)?;
    Ok(Resolved { media: post.media, title: Some(post.title) })
}

// --------------------------------------------------------------- Generic

async fn generic(api: &Api, url: &str) -> Result<Resolved, CoreError> {
    let link = |thumbnail: Option<String>, title: Option<String>| Resolved {
        media: Media::Link { url: url.to_string(), thumbnail },
        title,
    };

    let resp = api.http().get(url).header("Range", "bytes=0-262143").send().await?;
    if !resp.status().is_success() {
        return Ok(link(None, None));
    }
    // After redirects the final URL may be classifiable (e.g. short links).
    let final_url = resp.url().to_string();
    if final_url != url {
        if let Some(m) = classify_url(&final_url, None) {
            return Ok(Resolved::bare(m));
        }
    }
    let ctype = content_type(resp.headers());
    if ctype.starts_with("image/") {
        return Ok(Resolved::bare(Media::Image { url: final_url, width: 0, height: 0, preview: None }));
    }
    if ctype.starts_with("video/") || ctype.contains("mpegurl") || ctype.contains("dash+xml") {
        let (hls, dash) = (ctype.contains("mpegurl"), ctype.contains("dash+xml"));
        return Ok(Resolved::bare(Media::Video {
            hls_url: hls.then(|| final_url.clone()),
            dash_url: dash.then(|| final_url.clone()),
            mp4_url: (!hls && !dash).then(|| final_url.clone()),
            width: 0,
            height: 0,
            has_audio: true,
            preview: None,
        }));
    }
    if !ctype.contains("html") {
        return Ok(link(None, None));
    }
    let html = String::from_utf8_lossy(&resp.bytes().await?).into_owned();
    Ok(from_html(url, &html))
}

/// Pick media out of a page's OpenGraph / Twitter card tags.
pub(crate) fn from_html(url: &str, html: &str) -> Resolved {
    let title = find_meta_text(html, &["og:title", "twitter:title"]);
    let image = find_meta(html, &["og:image:secure_url", "og:image", "twitter:image"]).map(|s| unescape_url(&s));
    let dim = |keys: &[&str]| find_meta_text(html, keys).and_then(|v| v.trim().parse::<u32>().ok()).unwrap_or(0);

    if let Some(v) = find_meta(html, &["og:video:secure_url", "og:video:url", "og:video", "twitter:player:stream"]) {
        let (w, h) = (dim(&["og:video:width", "twitter:player:width"]), dim(&["og:video:height", "twitter:player:height"]));
        if let Some(m) = classify_url(&v, None) {
            let media = match m {
                Media::Video { hls_url, dash_url, mp4_url, has_audio, .. } => {
                    Media::Video { hls_url, dash_url, mp4_url, width: w, height: h, has_audio, preview: image }
                }
                Media::Gif { mp4_url, .. } => Media::Gif { mp4_url, width: w, height: h, preview: image },
                // og:video pointing at an embeddable player page: keep the link.
                _ => Media::Link { url: url.to_string(), thumbnail: image },
            };
            return Resolved { media, title };
        }
    }
    // A page that is essentially one picture (image hosts) → show it big.
    let og_type = find_meta_text(html, &["og:type"]).unwrap_or_default();
    if let Some(img) = image.clone() {
        let (w, h) = (dim(&["og:image:width"]), dim(&["og:image:height"]));
        let image_page = media::looks_like_image_host(url) || og_type.contains("image") || og_type.contains("photo");
        if image_page {
            return Resolved { media: Media::Image { url: img, width: w, height: h, preview: None }, title };
        }
    }
    Resolved { media: Media::Link { url: url.to_string(), thumbnail: image }, title }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn providers_from_urls() {
        use Provider::*;
        assert_eq!(provider("https://streamable.com/abc12"), Some(Streamable("abc12".into())));
        assert_eq!(provider("https://streamable.com/e/abc12?autoplay=1"), Some(Streamable("abc12".into())));
        assert_eq!(provider("https://www.redgifs.com/watch/SomeName-Here"), Some(Redgifs("somename".into())));
        assert_eq!(provider("https://v3.redgifs.com/watch/somename"), Some(Redgifs("somename".into())));
        assert_eq!(provider("https://i.redgifs.com/i/somename.jpg"), Some(Redgifs("somename".into())));
        assert_eq!(provider("https://www.reddit.com/r/pics/comments/1abcde/title/"), Some(RedditPost("1abcde".into())));
        assert_eq!(provider("https://old.reddit.com/gallery/1abcde"), Some(RedditPost("1abcde".into())));
        assert_eq!(provider("https://redd.it/1abcde"), Some(RedditPost("1abcde".into())));
        assert_eq!(provider("https://www.reddit.com/r/pics/"), None);
        assert_eq!(provider("https://example.com/abc"), None);
    }

    #[test]
    fn feed_upgrade_hosts() {
        assert!(worth_upgrading("https://streamable.com/abc"));
        assert!(worth_upgrading("https://i.imgur.com/a/x"));
        assert!(worth_upgrading("https://v3.redgifs.com/watch/x"));
        assert!(!worth_upgrading("https://www.nytimes.com/2026/story.html"));
        assert!(!worth_upgrading("https://notimgur.com/x"));
    }

    #[test]
    fn streamable_json() {
        let body = br#"{"status":2,"title":"Nice clip","thumbnail_url":"//cdn-cf-east.streamable.com/image/abc.jpg",
            "files":{"mp4-mobile":{"url":"//cdn.streamable.com/video/mp4-mobile/abc.mp4","width":640,"height":360},
                     "mp4":{"url":"https://cdn.streamable.com/video/mp4/abc.mp4?token=x","width":1920,"height":1080}}}"#;
        let r = parse_streamable(body).unwrap();
        assert_eq!(r.title.as_deref(), Some("Nice clip"));
        match r.media {
            Media::Video { mp4_url, width, preview, .. } => {
                assert_eq!(mp4_url.as_deref(), Some("https://cdn.streamable.com/video/mp4/abc.mp4?token=x"));
                assert_eq!(width, 1920);
                assert_eq!(preview.as_deref(), Some("https://cdn-cf-east.streamable.com/image/abc.jpg"));
            }
            other => panic!("{other:?}"),
        }
        let mobile_only = br#"{"files":{"mp4-mobile":{"url":"//cdn/x.mp4","width":640,"height":360}}}"#;
        assert!(matches!(parse_streamable(mobile_only).unwrap().media, Media::Video { width: 640, .. }));
        assert!(parse_streamable(br#"{"files":{}}"#).is_err());
    }

    #[test]
    fn redgifs_json() {
        let silent = br#"{"gif":{"id":"x","width":1080,"height":1920,"hasAudio":false,
            "urls":{"hd":"https://media.redgifs.com/X.mp4","sd":"https://media.redgifs.com/X-mobile.mp4",
                    "poster":"https://media.redgifs.com/X-poster.jpg"}}}"#;
        match parse_redgifs(silent).unwrap().media {
            Media::Gif { mp4_url, height, preview, .. } => {
                assert_eq!(mp4_url, "https://media.redgifs.com/X.mp4");
                assert_eq!(height, 1920);
                assert!(preview.is_some());
            }
            other => panic!("{other:?}"),
        }
        let audio = br#"{"gif":{"hasAudio":true,"urls":{"sd":"https://m/x.mp4"}}}"#;
        assert!(matches!(parse_redgifs(audio).unwrap().media, Media::Video { has_audio: true, .. }));
    }

    #[test]
    fn html_cards() {
        let video_page = r#"<head><meta property="og:title" content="Cats &amp; dogs">
            <meta property="og:image" content="https://cdn.x/p.jpg">
            <meta property="og:video" content="https://cdn.x/v.mp4"><meta property="og:video:width" content="1280">
            <meta property="og:video:height" content="720"></head>"#;
        let r = from_html("https://x.com/v/1", video_page);
        assert_eq!(r.title.as_deref(), Some("Cats & dogs"));
        assert!(matches!(r.media, Media::Video { width: 1280, height: 720, preview: Some(_), .. }));

        let article = r#"<meta property="og:type" content="article"><meta property="og:image" content="https://cdn.x/a.jpg">"#;
        assert!(matches!(from_html("https://news.x/story", article).media, Media::Link { thumbnail: Some(_), .. }));

        let imgur_album = r#"<meta property="og:image" content="https://i.imgur.com/abc.jpg?fb">"#;
        assert!(matches!(from_html("https://imgur.com/a/xyz", imgur_album).media, Media::Image { .. }));

        let player_embed = r#"<meta property="og:video" content="https://www.youtube.com/embed/abc">"#;
        assert!(matches!(from_html("https://site/x", player_embed).media, Media::Link { .. }));
    }
}
