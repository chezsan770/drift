//! Fallback feed source: Reddit's public RSS (Atom) feeds, used while the app
//! has no API client id. Read-only, ~1 request/minute, no scores or comment
//! counts, galleries only as links. Reddit's Atom output is very regular, so
//! a small scanner is enough (no XML dependency).

use crate::format::{relative_age, unescape_url};
use crate::media::classify_url;
use crate::model::{FeedPage, Media, Post};

/// Posts requested per RSS page (Reddit allows up to 100).
pub const PAGE_SIZE: u32 = 50;

pub fn parse_feed(xml: &str, now_utc: f64) -> FeedPage {
    let entries: Vec<&str> = xml.split("<entry>").skip(1).collect();
    let mut last_id = None;
    let posts: Vec<Post> = entries
        .iter()
        .filter_map(|e| {
            let e = e.split("</entry>").next().unwrap_or(e);
            let p = parse_entry(e, now_utc)?;
            last_id = Some(format!("t3_{}", p.id));
            Some(p)
        })
        .collect();
    // A full page means there is probably more after it.
    let after = if posts.len() as u32 >= PAGE_SIZE { last_id } else { None };
    FeedPage { posts, after }
}

fn parse_entry(e: &str, now_utc: f64) -> Option<Post> {
    let id = tag_text(e, "id")?.trim_start_matches("t3_").to_string();
    if id.is_empty() {
        return None;
    }
    let title = xml_unescape(&tag_text(e, "title").unwrap_or_default());
    let subreddit = attr(e, "<category ", "term").unwrap_or_default();
    let author = tag_text(e, "name").map(|n| xml_unescape(&n).trim_start_matches("/u/").to_string()).unwrap_or_default();
    let permalink = attr(e, "<link ", "href").map(|s| xml_unescape(&s)).unwrap_or_default();
    let thumbnail = attr(e, "<media:thumbnail ", "url").map(|s| unescape_url(&xml_unescape(&s)));
    let published = tag_text(e, "published").and_then(|t| parse_rfc3339(&t)).unwrap_or(now_utc);
    let html = between(e, "<content type=\"html\">", "</content>").map(xml_unescape).unwrap_or_default();

    let link = link_target(&html).unwrap_or_else(|| permalink.clone());
    let is_self = same_post(&link, &permalink, &id);
    let media = if is_self {
        Media::Text { body: self_text(&html) }
    } else {
        with_size_from_title(media_for(&link, thumbnail), &title)
    };
    let domain = url::Url::parse(&link)
        .ok()
        .and_then(|u| u.host_str().map(|h| h.trim_start_matches("www.").to_string()))
        .unwrap_or_default();

    Some(Post {
        id,
        subreddit,
        author,
        title,
        url: if is_self { permalink.clone() } else { link },
        permalink,
        domain,
        // Not available over RSS: the UI shows author instead.
        score_text: String::new(),
        comments_text: String::new(),
        age_text: relative_age(published, now_utc),
        is_nsfw: false,
        is_spoiler: false,
        is_stickied: false,
        media,
    })
}

/// Media for a post's link, using the RSS thumbnail as the feed preview.
fn media_for(link: &str, thumbnail: Option<String>) -> Media {
    match classify_url(link, None) {
        // The RSS thumbnail is only 640px: a placeholder, not the card image.
        Some(Media::Image { url, width, height, preview, thumbnail: t }) => {
            Media::Image { url, width, height, preview, thumbnail: t.or(thumbnail) }
        }
        Some(Media::Video { hls_url, dash_url, mp4_url, width, height, has_audio, preview }) => {
            Media::Video { hls_url, dash_url, mp4_url, width, height, has_audio, preview: preview.or(thumbnail) }
        }
        Some(Media::Gif { mp4_url, width, height, preview }) => {
            Media::Gif { mp4_url, width, height, preview: preview.or(thumbnail) }
        }
        Some(Media::Embed { url, provider, thumbnail: t }) => Media::Embed { url, provider, thumbnail: t.or(thumbnail) },
        Some(other) => other,
        // Galleries (reddit.com/gallery/…) and articles: link card.
        None => Media::Link { url: link.to_string(), thumbnail },
    }
}

/// Photo subreddits put the original size in the title ("… [4800x3198]").
/// Using it lets the feed size the card before the image loads, so the list
/// doesn't re-layout while scrolling.
fn with_size_from_title(media: Media, title: &str) -> Media {
    match media {
        Media::Image { url, width: 0, height: 0, preview, thumbnail } => {
            let (width, height) = size_in_title(title).unwrap_or((0, 0));
            Media::Image { url, width, height, preview, thumbnail }
        }
        other => other,
    }
}

/// Last "WxH" in brackets/parentheses, e.g. "[4800x3198]", "(4000 × 3000)".
pub(crate) fn size_in_title(title: &str) -> Option<(u32, u32)> {
    let mut found = None;
    for (open, close) in [('[', ']'), ('(', ')')] {
        let mut rest = title;
        while let Some(a) = rest.find(open) {
            let after = &rest[a + open.len_utf8()..];
            let Some(b) = after.find(close) else { break };
            let inner: String = after[..b].chars().filter(|c| !c.is_whitespace() && *c != ',').collect();
            let mut parts = inner.split(['x', 'X', '×']);
            if let (Some(w), Some(h), None) = (parts.next(), parts.next(), parts.next()) {
                if let (Ok(w), Ok(h)) = (w.parse::<u32>(), h.parse::<u32>()) {
                    if (100..=40_000).contains(&w) && (100..=40_000).contains(&h) {
                        found = Some((w, h));
                    }
                }
            }
            rest = &after[b..];
        }
    }
    found
}

/// The `[link]` anchor in the entry HTML: the post's actual target.
fn link_target(html: &str) -> Option<String> {
    let end = html.find("\">[link]</a>")?;
    let start = html[..end].rfind("href=\"")? + "href=\"".len();
    Some(unescape_url(&html_unescape(&html[start..end])))
}

fn same_post(link: &str, permalink: &str, id: &str) -> bool {
    link.trim_end_matches('/') == permalink.trim_end_matches('/')
        || (link.contains("reddit.com/r/") && link.contains(&format!("/comments/{id}")))
}

/// Self-post body as plain text (Reddit wraps it in `<!-- SC_OFF -->…<!-- SC_ON -->`).
fn self_text(html: &str) -> String {
    let Some(body) = between(html, "<!-- SC_OFF -->", "<!-- SC_ON -->") else { return String::new() };
    let mut out = String::with_capacity(body.len());
    let mut in_tag = false;
    let mut tag = String::new();
    for c in body.chars() {
        match (in_tag, c) {
            (false, '<') => {
                in_tag = true;
                tag.clear();
            }
            (true, '>') => {
                in_tag = false;
                let t = tag.trim_start_matches('/').split_whitespace().next().unwrap_or("").to_ascii_lowercase();
                if matches!(t.as_str(), "p" | "br" | "li" | "div" | "h1" | "h2" | "h3" | "blockquote") && !out.ends_with('\n') {
                    out.push('\n');
                }
            }
            (true, _) => tag.push(c),
            (false, _) => out.push(c),
        }
    }
    let text = html_unescape(&out);
    text.lines().map(str::trim).filter(|l| !l.is_empty()).collect::<Vec<_>>().join("\n")
}

// ------------------------------------------------------------ helpers

fn between<'a>(s: &'a str, open: &str, close: &str) -> Option<&'a str> {
    let a = s.find(open)? + open.len();
    let b = s[a..].find(close)? + a;
    Some(&s[a..b])
}

fn tag_text(s: &str, tag: &str) -> Option<String> {
    between(s, &format!("<{tag}>"), &format!("</{tag}>")).map(str::to_string)
}

/// Attribute of the first tag starting with `tag_start` (e.g. `<link `).
fn attr(s: &str, tag_start: &str, name: &str) -> Option<String> {
    let t = s.find(tag_start)?;
    let tag_end = s[t..].find('>')? + t;
    let tag = &s[t..tag_end];
    between(tag, &format!("{name}=\""), "\"").map(str::to_string)
}

/// XML-level entities (the HTML content is escaped once more inside).
fn xml_unescape(s: &str) -> String {
    decode_entities(s)
}

fn html_unescape(s: &str) -> String {
    decode_entities(s)
}

fn decode_entities(s: &str) -> String {
    if !s.contains('&') {
        return s.to_string();
    }
    let mut out = String::with_capacity(s.len());
    let mut rest = s;
    while let Some(i) = rest.find('&') {
        out.push_str(&rest[..i]);
        let after = &rest[i..];
        // Byte search: ';' is ASCII, so the index is always a char boundary
        // (slicing by a fixed byte length could split a multi-byte char).
        let Some(semi) = after.bytes().take(12).position(|b| b == b';') else {
            out.push('&');
            rest = &after[1..];
            continue;
        };
        let ent = &after[1..semi];
        let decoded = match ent {
            "amp" => Some('&'),
            "lt" => Some('<'),
            "gt" => Some('>'),
            "quot" => Some('"'),
            "apos" => Some('\''),
            "nbsp" => Some(' '),
            _ if ent.starts_with("#x") || ent.starts_with("#X") => {
                u32::from_str_radix(&ent[2..], 16).ok().and_then(char::from_u32)
            }
            _ if ent.starts_with('#') => ent[1..].parse::<u32>().ok().and_then(char::from_u32),
            _ => None,
        };
        match decoded {
            Some(c) => {
                out.push(c);
                rest = &after[semi + 1..];
            }
            None => {
                out.push('&');
                rest = &after[1..];
            }
        }
    }
    out.push_str(rest);
    out
}

/// `2026-11-14T14:43:25+00:00` → seconds since the Unix epoch.
fn parse_rfc3339(s: &str) -> Option<f64> {
    let s = s.trim();
    let (date, time) = s.split_once('T')?;
    let mut d = date.split('-').map(|p| p.parse::<i64>());
    let (y, m, day) = (d.next()?.ok()?, d.next()?.ok()?, d.next()?.ok()?);
    let (clock, offset_secs) = if let Some(c) = time.strip_suffix('Z') {
        (c, 0)
    } else {
        let pos = time.rfind(['+', '-'])?;
        let (c, off) = time.split_at(pos);
        let sign = if off.starts_with('-') { -1 } else { 1 };
        let mut o = off[1..].split(':').map(|p| p.parse::<i64>());
        let (oh, om) = (o.next()?.ok()?, o.next().and_then(|r| r.ok()).unwrap_or(0));
        (c, sign * (oh * 3600 + om * 60))
    };
    let mut c = clock.split(':');
    let (hh, mm) = (c.next()?.parse::<i64>().ok()?, c.next()?.parse::<i64>().ok()?);
    let ss = c.next().unwrap_or("0").split('.').next()?.parse::<i64>().ok()?;
    // Days from civil (Howard Hinnant's algorithm).
    let y2 = if m <= 2 { y - 1 } else { y };
    let era = if y2 >= 0 { y2 } else { y2 - 399 } / 400;
    let yoe = y2 - era * 400;
    let mp = (m + 9) % 12;
    let doy = (153 * mp + 2) / 5 + day - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146_097 + doe - 719_468;
    Some((days * 86_400 + hh * 3600 + mm * 60 + ss - offset_secs) as f64)
}

#[cfg(test)]
mod tests {
    use super::*;

    const FEED: &str = include_str!("../tests/fixtures/feed.rss");

    #[test]
    fn rfc3339() {
        assert_eq!(parse_rfc3339("1970-01-01T00:00:00+00:00"), Some(0.0));
        assert_eq!(parse_rfc3339("2026-11-14T14:43:25+00:00"), Some(1_794_667_405.0));
        assert_eq!(parse_rfc3339("2026-11-14T20:13:25+05:30"), Some(1_794_667_405.0));
        assert_eq!(parse_rfc3339("2026-11-14T14:43:25Z"), Some(1_794_667_405.0));
        assert_eq!(parse_rfc3339("garbage"), None);
    }

    #[test]
    fn sizes_in_titles() {
        assert_eq!(size_in_title("Fog [OC] [4800x3198]"), Some((4800, 3198)));
        assert_eq!(size_in_title("Lake (4000 × 3,000) [OC]"), Some((4000, 3000)));
        assert_eq!(size_in_title("Peak [OC][6000X4000] taken at dawn"), Some((6000, 4000)));
        assert_eq!(size_in_title("No size here [OC]"), None);
        assert_eq!(size_in_title("Score [3x2] game"), None); // too small to be pixels
        assert_eq!(size_in_title("Unclosed [4800x3198"), None);
    }

    #[test]
    fn entities() {
        assert_eq!(decode_entities("a &amp;amp; b"), "a &amp; b");
        assert_eq!(decode_entities("&lt;p&gt;&#32;x&#x41;&quot;"), "<p> xA\"");
        assert_eq!(decode_entities("AT&T & co"), "AT&T & co");
        // Non-ASCII right after '&' must not panic.
        assert_eq!(decode_entities("Peña & Señor &amp; año"), "Peña & Señor & año");
        assert_eq!(decode_entities("&ñññññññ"), "&ñññññññ");
    }

    #[test]
    fn parses_entries() {
        let now = 1_794_667_405.0 + 2.0 * 3600.0;
        let page = parse_feed(FEED, now);
        assert_eq!(page.posts.len(), 5);
        assert_eq!(page.after, None); // fewer than a full page

        let img = &page.posts[0];
        assert_eq!(img.id, "1abc001");
        assert_eq!(img.subreddit, "EarthPorn");
        assert_eq!(img.author, "someone");
        assert_eq!(img.title, "Fog & hills [OC] [4800x3198]");
        assert_eq!(img.age_text, "2h");
        assert_eq!(img.score_text, "");
        match &img.media {
            Media::Image { url, preview, width, height, thumbnail } => {
                assert_eq!((*width, *height), (4800, 3198)); // from the title
                assert_eq!(url, "https://i.redd.it/abc001.jpeg");
                assert_eq!(*preview, None);
                assert_eq!(
                    thumbnail.as_deref(),
                    Some("https://preview.redd.it/abc001.jpeg?width=640&crop=smart&auto=webp&s=sig1")
                );
            }
            other => panic!("{other:?}"),
        }

        assert!(matches!(&page.posts[1].media, Media::Video { hls_url: Some(h), preview: Some(_), .. }
            if h == "https://v.redd.it/vid002/HLSPlaylist.m3u8"));

        match &page.posts[2].media {
            Media::Text { body } => assert_eq!(body, "First paragraph & more.\nSecond line"),
            other => panic!("{other:?}"),
        }
        assert_eq!(page.posts[2].url, page.posts[2].permalink);

        assert!(matches!(&page.posts[3].media, Media::Link { url, thumbnail: Some(_) }
            if url == "https://www.reddit.com/gallery/gal004"));
        assert!(matches!(&page.posts[4].media, Media::Link { url, thumbnail: None }
            if url == "https://example.com/story?a=1&b=2"));
        assert_eq!(page.posts[4].domain, "example.com");
    }

    #[test]
    fn full_page_has_after() {
        let one = FEED.split("<entry>").nth(1).unwrap().split("</entry>").next().unwrap();
        let mut xml = String::from("<feed>");
        for n in 0..PAGE_SIZE {
            xml.push_str("<entry>");
            xml.push_str(&one.replace("t3_1abc001", &format!("t3_x{n}")));
            xml.push_str("</entry>");
        }
        let page = parse_feed(&xml, 0.0);
        assert_eq!(page.posts.len(), PAGE_SIZE as usize);
        assert_eq!(page.after.as_deref(), Some(format!("t3_x{}", PAGE_SIZE - 1).as_str()));
    }
}
