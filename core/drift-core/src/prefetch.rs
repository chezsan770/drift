//! Decides which images to download before they scroll into view.
//!
//! Kotlin passes the posts just below the viewport and the current scroll
//! speed; we answer with the exact URLs the feed will request, in order.
//! Faster scrolling looks further ahead so cards are ready on arrival.

use crate::model::{Media, Post};

pub const MIN_AHEAD: usize = 3;
pub const MAX_AHEAD: usize = 12;

/// How many posts ahead to prefetch at `items_per_sec` scroll speed.
pub fn window(items_per_sec: f32) -> usize {
    let v = if items_per_sec.is_finite() { items_per_sec.max(0.0) } else { 0.0 };
    (MIN_AHEAD + (v * 1.5).round() as usize).min(MAX_AHEAD)
}

/// The URL(s) a post's card loads in the feed (must match the UI exactly,
/// or the prefetched bytes are never reused).
pub fn card_urls(media: &Media) -> Vec<String> {
    match media {
        Media::Image { url, preview, .. } => vec![preview.clone().unwrap_or_else(|| url.clone())],
        Media::Gallery { items } => items
            .iter()
            .filter(|i| !i.is_animated)
            .take(2) // first page plus the one a swipe reveals
            .map(|i| i.preview.clone().unwrap_or_else(|| i.url.clone()))
            .collect(),
        Media::Video { preview, .. } | Media::Gif { preview, .. } => preview.iter().cloned().collect(),
        Media::Embed { thumbnail, .. } | Media::Link { thumbnail, .. } => thumbnail.iter().cloned().collect(),
        Media::Text { .. } => vec![],
    }
}

#[uniffi::export]
pub fn prefetch_urls(upcoming: Vec<Post>, items_per_sec: f32) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for p in upcoming.iter().take(window(items_per_sec)) {
        for u in card_urls(&p.media) {
            if !out.contains(&u) {
                out.push(u);
            }
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::GalleryItem;

    fn post(media: Media) -> Post {
        Post {
            id: "x".into(),
            subreddit: String::new(),
            author: String::new(),
            title: String::new(),
            permalink: String::new(),
            url: String::new(),
            domain: String::new(),
            score_text: String::new(),
            comments_text: String::new(),
            age_text: String::new(),
            is_nsfw: false,
            is_spoiler: false,
            is_stickied: false,
            media,
        }
    }

    fn img(n: u32) -> Media {
        Media::Image { url: format!("full{n}"), width: 1, height: 1, preview: Some(format!("card{n}")) }
    }

    #[test]
    fn window_grows_with_speed() {
        assert_eq!(window(0.0), MIN_AHEAD);
        assert_eq!(window(2.0), 6);
        assert_eq!(window(100.0), MAX_AHEAD);
        assert_eq!(window(f32::NAN), MIN_AHEAD);
        assert_eq!(window(-5.0), MIN_AHEAD);
    }

    #[test]
    fn uses_card_preview_not_original() {
        let posts = (0..20).map(|n| post(img(n))).collect();
        let urls = prefetch_urls(posts, 0.0);
        assert_eq!(urls, vec!["card0", "card1", "card2"]);
    }

    #[test]
    fn gallery_first_two_stills_and_dedup() {
        let item = |u: &str, anim| GalleryItem {
            url: u.into(),
            width: 1,
            height: 1,
            is_animated: anim,
            caption: None,
            preview: None,
        };
        let g = Media::Gallery { items: vec![item("a", false), item("m", true), item("b", false), item("c", false)] };
        let urls = prefetch_urls(vec![post(g), post(img(1)), post(img(1))], 0.0);
        assert_eq!(urls, vec!["a", "b", "card1"]);
    }
}
