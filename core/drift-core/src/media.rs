//! Turns Reddit posts and arbitrary URLs into `Media`, always choosing the
//! highest-quality source. Pure functions here (no I/O) are unit tested;
//! `resolve_url_remote` adds the network fallbacks for unknown links.

use crate::format::unescape_url;
use crate::model::{GalleryItem, Media};
use crate::raw::{ImgSrc, PreviewImage, RawPost, RedditVideo};

/// Width the feed card preview should be at least as wide as.
const CARD_PREVIEW_MIN_WIDTH: u32 = 1080;

const IMAGE_EXT: &[&str] = &["jpg", "jpeg", "png", "webp", "avif", "heic", "bmp"];
const VIDEO_EXT: &[&str] = &["mp4", "webm", "mov", "m4v"];

// ---------------------------------------------------------------- posts

pub fn from_post(p: &RawPost) -> Media {
    // Crossposts carry their media on the original post.
    if let Some(parent) = p.crosspost_parent_list.as_ref().and_then(|l| l.first()) {
        return from_post(parent);
    }

    let preview_img = p.preview.as_ref().and_then(|pr| pr.images.first());
    let card_preview = preview_img.map(card_sized);

    // 1. Native galleries.
    if p.is_gallery == Some(true) {
        if let Some(items) = gallery_items(p) {
            if !items.is_empty() {
                return Media::Gallery { items };
            }
        }
    }

    // 2. Native v.redd.it video (also covers uploaded GIFs, which Reddit
    //    converts to silent video with `is_gif = true`).
    let reddit_video = p
        .secure_media
        .as_ref()
        .and_then(|m| m.reddit_video.as_ref())
        .or_else(|| p.media.as_ref().and_then(|m| m.reddit_video.as_ref()));
    if let Some(v) = reddit_video {
        return video(v, card_preview);
    }

    let url = p.url.as_deref().map(unescape_url).unwrap_or_default();

    // 3. Animated images: prefer Reddit's MP4 transcode over the raw GIF.
    if let Some(mp4) = preview_img.and_then(|i| i.variants.mp4.as_ref()) {
        if !mp4.source.url.is_empty() {
            return Media::Gif {
                mp4_url: unescape_url(&mp4.source.url),
                width: mp4.source.width,
                height: mp4.source.height,
                preview: card_preview,
            };
        }
    }

    // 4. Videos Reddit transcoded from external hosts (imgur gifv etc.).
    if let Some(v) = p.preview.as_ref().and_then(|pr| pr.reddit_video_preview.as_ref()) {
        return video(v, card_preview);
    }

    // 5. YouTube and other oEmbed players (before URL rules so we keep
    //    Reddit's thumbnail).
    if let Some(oe) = p.secure_media.as_ref().and_then(|m| m.oembed.as_ref()) {
        return Media::Embed {
            url,
            provider: oe.provider_name.clone().unwrap_or_else(|| p.domain.clone()),
            thumbnail: card_preview.or_else(|| oe.thumbnail_url.as_deref().map(unescape_url)),
        };
    }

    // 6. Direct links we can classify from the URL alone.
    if let Some(m) = classify_url(&url, preview_img) {
        return m;
    }

    // 7. Reddit says it's an image even though the URL didn't look like one.
    if p.post_hint.as_deref() == Some("image") {
        if let Some(img) = preview_img {
            return Media::Image {
                url: unescape_url(&img.source.url),
                width: img.source.width,
                height: img.source.height,
                preview: card_preview,
            };
        }
    }

    if p.is_self {
        return Media::Text { body: p.selftext.clone() };
    }

    Media::Link {
        url,
        thumbnail: card_preview.or_else(|| valid_thumbnail(p.thumbnail.as_deref())),
    }
}

fn video(v: &RedditVideo, preview: Option<String>) -> Media {
    let clean = |s: &Option<String>| s.as_deref().filter(|s| !s.is_empty()).map(unescape_url);
    if v.is_gif {
        if let Some(mp4) = clean(&v.fallback_url) {
            return Media::Gif { mp4_url: mp4, width: v.width, height: v.height, preview };
        }
    }
    Media::Video {
        hls_url: clean(&v.hls_url),
        dash_url: clean(&v.dash_url),
        mp4_url: clean(&v.fallback_url),
        width: v.width,
        height: v.height,
        has_audio: v.has_audio.unwrap_or(true) && !v.is_gif,
        preview,
    }
}

fn gallery_items(p: &RawPost) -> Option<Vec<GalleryItem>> {
    let meta = p.media_metadata.as_ref()?;
    let order = p.gallery_data.as_ref()?;
    let items = order
        .items
        .iter()
        .filter_map(|entry| {
            let m = meta.get(&entry.media_id)?;
            if m.status.as_deref().is_some_and(|s| s != "valid") {
                return None; // still processing / failed upload
            }
            let s = m.s.as_ref()?;
            let animated = m.e.as_deref() == Some("AnimatedImage");
            let url = if animated { s.mp4.as_ref().or(s.gif.as_ref()) } else { s.u.as_ref() }?;
            let preview = if animated {
                None
            } else {
                m.p.iter()
                    .filter(|p| p.x >= CARD_PREVIEW_MIN_WIDTH && p.u.is_some())
                    .min_by_key(|p| p.x)
                    .and_then(|p| p.u.as_deref())
                    .map(unescape_url)
            };
            Some(GalleryItem {
                url: unescape_url(url),
                width: s.x,
                height: s.y,
                is_animated: animated && s.mp4.is_some(),
                caption: entry.caption.clone().filter(|c| !c.is_empty()),
                preview,
            })
        })
        .collect();
    Some(items)
}

/// Smallest preview at least `CARD_PREVIEW_MIN_WIDTH` wide, else the source.
fn card_sized(img: &PreviewImage) -> String {
    let pick: &ImgSrc = img
        .resolutions
        .iter()
        .filter(|r| r.width >= CARD_PREVIEW_MIN_WIDTH)
        .min_by_key(|r| r.width)
        .unwrap_or(&img.source);
    unescape_url(&pick.url)
}

fn valid_thumbnail(t: Option<&str>) -> Option<String> {
    t.filter(|t| t.starts_with("http")).map(unescape_url)
}

// ----------------------------------------------------------------- links

fn extension(u: &url::Url) -> Option<String> {
    let last = u.path_segments()?.next_back()?;
    let (_, ext) = last.rsplit_once('.')?;
    Some(ext.to_ascii_lowercase())
}

/// Classify a URL without touching the network. `preview` (when the URL
/// came from a post) supplies dimensions and a card preview.
pub fn classify_url(raw: &str, preview: Option<&PreviewImage>) -> Option<Media> {
    let u = url::Url::parse(raw).ok()?;
    let host = u.host_str()?.trim_start_matches("www.").to_ascii_lowercase();
    let ext = extension(&u);
    let (w, h) = preview.map(|p| (p.source.width, p.source.height)).unwrap_or((0, 0));
    let card_preview = preview.map(card_sized);

    // imgur: .gifv is an HTML page wrapping an MP4.
    if host.ends_with("imgur.com") && ext.as_deref() == Some("gifv") {
        let mp4 = raw.trim_end_matches(".gifv").to_string() + ".mp4";
        return Some(Media::Gif { mp4_url: mp4, width: w, height: h, preview: card_preview });
    }
    // imgur page link to a single image: i.imgur.com/{id}.jpg serves the original.
    if host == "imgur.com" && ext.is_none() {
        let segs: Vec<&str> = u.path_segments()?.filter(|s| !s.is_empty()).collect();
        if let [id] = segs.as_slice() {
            return Some(Media::Image {
                url: format!("https://i.imgur.com/{id}.jpg"),
                width: w,
                height: h,
                preview: card_preview,
            });
        }
    }

    match ext.as_deref() {
        Some(e) if IMAGE_EXT.contains(&e) => Some(Media::Image {
            url: raw.to_string(),
            width: w,
            height: h,
            preview: card_preview,
        }),
        Some("gif") => Some(Media::Image {
            url: raw.to_string(),
            width: w,
            height: h,
            preview: card_preview,
        }),
        Some(e) if VIDEO_EXT.contains(&e) => Some(Media::Video {
            hls_url: None,
            dash_url: None,
            mp4_url: Some(raw.to_string()),
            width: w,
            height: h,
            has_audio: true,
            preview: card_preview,
        }),
        Some("m3u8") => Some(Media::Video {
            hls_url: Some(raw.to_string()),
            dash_url: None,
            mp4_url: None,
            width: w,
            height: h,
            has_audio: true,
            preview: card_preview,
        }),
        _ => match host.as_str() {
            "youtube.com" | "m.youtube.com" | "youtu.be" => Some(Media::Embed {
                url: raw.to_string(),
                provider: "YouTube".into(),
                thumbnail: card_preview,
            }),
            _ => None,
        },
    }
}

/// Find `<meta property="og:video" content="...">`-style tags. A tiny
/// scanner instead of a full HTML parser: we only need a handful of tags
/// from the document head.
pub fn find_meta(html: &str, keys: &[&str]) -> Option<String> {
    let mut cut = html.len().min(256 * 1024);
    while !html.is_char_boundary(cut) {
        cut -= 1;
    }
    let head = &html[..cut];
    let lower = head.to_ascii_lowercase();
    for key in keys {
        let mut from = 0;
        while let Some(i) = lower[from..].find("<meta") {
            let start = from + i;
            let end = lower[start..].find('>').map(|e| start + e).unwrap_or(lower.len());
            let tag_l = &lower[start..end];
            let tag = &head[start..end];
            from = end;
            let names_key = [format!("property=\"{key}\""), format!("name=\"{key}\""),
                             format!("property='{key}'"), format!("name='{key}'")];
            if !names_key.iter().any(|n| tag_l.contains(n.as_str())) {
                continue;
            }
            if let Some(v) = attr(tag, tag_l, "content") {
                if v.starts_with("http") {
                    return Some(v.replace("&amp;", "&"));
                }
            }
        }
    }
    None
}

fn attr(tag: &str, tag_lower: &str, name: &str) -> Option<String> {
    for q in ['"', '\''] {
        let needle = format!("{name}={q}");
        if let Some(i) = tag_lower.find(&needle) {
            let s = i + needle.len();
            let e = tag[s..].find(q)? + s;
            return Some(tag[s..e].to_string());
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classify_direct_links() {
        assert!(matches!(
            classify_url("https://i.redd.it/abc.jpeg", None),
            Some(Media::Image { .. })
        ));
        match classify_url("https://i.imgur.com/AbC12.gifv", None) {
            Some(Media::Gif { mp4_url, .. }) => assert_eq!(mp4_url, "https://i.imgur.com/AbC12.mp4"),
            other => panic!("{other:?}"),
        }
        match classify_url("https://imgur.com/AbC12", None) {
            Some(Media::Image { url, .. }) => assert_eq!(url, "https://i.imgur.com/AbC12.jpg"),
            other => panic!("{other:?}"),
        }
        assert!(matches!(
            classify_url("https://files.example.com/clip.MP4?x=1", None),
            Some(Media::Video { mp4_url: Some(_), .. })
        ));
        assert!(matches!(
            classify_url("https://youtu.be/dQw4w9WgXcQ", None),
            Some(Media::Embed { .. })
        ));
        assert_eq!(classify_url("https://example.com/article", None), None);
        assert_eq!(classify_url("not a url", None), None);
    }

    #[test]
    fn og_meta() {
        let html = r#"<html><head>
            <meta property="og:title" content="Hi">
            <meta content="https://cdn.x.com/v.mp4?a=1&amp;b=2" property="og:video">
            <META NAME='og:image' CONTENT='https://cdn.x.com/i.jpg'>
        </head></html>"#;
        assert_eq!(
            find_meta(html, &["og:video:secure_url", "og:video"]).as_deref(),
            Some("https://cdn.x.com/v.mp4?a=1&b=2")
        );
        assert_eq!(find_meta(html, &["og:image"]).as_deref(), Some("https://cdn.x.com/i.jpg"));
        assert_eq!(find_meta(html, &["twitter:player:stream"]), None);
    }
}
