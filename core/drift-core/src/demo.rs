//! Built-in demo feed (open `r/demo`). Real, publicly hosted high-res media
//! so the media pipeline can be tested on a device without Reddit API
//! access. Every URL here was checked to respond to plain HTTP clients.

use crate::format::relative_age;
use crate::model::{FeedPage, GalleryItem, Media, Post};

/// Unsplash photo: id, original width, original height.
type Photo = (&'static str, u32, u32);

const PHOTOS: &[Photo] = &[
    ("1506744038136-46273834b3fb", 7360, 4912),
    ("1501785888041-af3ef285b470", 5979, 3986),
    ("1470071459604-3b5ec3a7fe05", 7372, 4392),
    ("1441974231531-c6227db76b6e", 2560, 1705),
    ("1469474968028-56623f02e42e", 3506, 2329),
    ("1447752875215-b2761acb3c5d", 5472, 3648),
    ("1500530855697-b586d89ba3ee", 3648, 5472),
    ("1472214103451-9374bd1c798e", 7932, 5291),
    ("1519681393784-d120267933ba", 4096, 2733),
    ("1464822759023-fed622ff2c3b", 5760, 3840),
    ("1433086966358-54859d0ed716", 4000, 6000),
    ("1426604966848-d7adac402bff", 5616, 3744),
    ("1494500764479-0c8f2919a3d8", 5128, 3419),
    ("1518837695005-2083093ee35b", 6000, 4000),
];

/// Full image at 4K width (what the viewer zooms into) and its dimensions.
fn full(p: Photo) -> (String, u32, u32) {
    let w = p.1.min(3840);
    let h = (p.2 as u64 * w as u64 / p.1 as u64) as u32;
    (format!("https://images.unsplash.com/photo-{}?w={w}&q=90&fm=jpg", p.0), w, h)
}

fn card(p: Photo) -> String {
    format!("https://images.unsplash.com/photo-{}?w=1080&q=80&fm=jpg", p.0)
}

fn image(p: Photo) -> Media {
    let (url, width, height) = full(p);
    Media::Image { url, width, height, preview: Some(card(p)), thumbnail: None }
}

fn post(i: usize, title: &str, score: i64, age_secs: f64, now: f64, media: Media) -> Post {
    Post {
        id: format!("demo{i}"),
        subreddit: "demo".into(),
        author: "drift".into(),
        title: title.into(),
        permalink: "https://github.com/chezsan770/drift".into(),
        url: match &media {
            Media::Image { url, .. } => url.clone(),
            Media::Video { hls_url, mp4_url, .. } => hls_url.clone().or(mp4_url.clone()).unwrap_or_default(),
            Media::Gif { mp4_url, .. } => mp4_url.clone(),
            Media::Link { url, .. } | Media::Embed { url, .. } => url.clone(),
            _ => String::new(),
        },
        domain: "demo".into(),
        score_text: crate::format::compact_number(score),
        comments_text: crate::format::compact_number(score / 20),
        age_text: relative_age(now - age_secs, now),
        is_nsfw: false,
        is_spoiler: false,
        is_stickied: false,
        media,
    }
}

pub fn page(now: f64) -> FeedPage {
    let p = PHOTOS;
    let h = 3600.0;
    let mut i = 0;
    let mut next = |title: &str, score: i64, age: f64, media: Media| {
        i += 1;
        post(i, title, score, age, now, media)
    };
    let posts = vec![
        next(
            "Demo feed: real high-res media, no Reddit API needed",
            1,
            30.0,
            Media::Text {
                body: "Scroll to autoplay videos (muted, tap the speaker to unmute). Tap any image, \
                       gallery or video to open it full screen: pinch or double-tap to zoom, \
                       swipe down to close."
                    .into(),
            },
        ),
        next("Mountain lake at golden hour (7360px original)", 48_213, 2.0 * h, image(p[0])),
        next(
            "Big Buck Bunny: adaptive HLS stream with audio",
            9_120,
            3.0 * h,
            Media::Video {
                hls_url: Some("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8".into()),
                dash_url: None,
                mp4_url: None,
                width: 1920,
                height: 1080,
                has_audio: true,
                preview: None,
            },
        ),
        next(
            "Gallery: three landscapes (swipe sideways)",
            3_402,
            4.0 * h,
            Media::Gallery {
                items: [p[2], p[6], p[8]]
                    .into_iter()
                    .enumerate()
                    .map(|(n, ph)| {
                        let (url, width, height) = full(ph);
                        GalleryItem {
                            url,
                            width,
                            height,
                            is_animated: false,
                            caption: Some(format!("Photo {} of 3", n + 1)),
                            preview: Some(card(ph)),
                        }
                    })
                    .collect(),
            },
        ),
        next("Tall waterfall (portrait, 4000x6000)", 15_877, 5.0 * h, image(p[10])),
        next(
            "Jellyfish loop (GIF, served as MP4)",
            2_210,
            6.0 * h,
            Media::Gif {
                mp4_url: "https://test-videos.co.uk/vids/jellyfish/mp4/h264/1080/Jellyfish_1080_10s_1MB.mp4".into(),
                width: 1920,
                height: 1080,
                preview: None,
            },
        ),
        next("Rolling hills at dawn", 7_045, 7.0 * h, image(p[7])),
        next(
            "Apple bipbop: multi-bitrate HLS (should pick the highest quality)",
            640,
            8.0 * h,
            Media::Video {
                hls_url: Some(
                    "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8".into(),
                ),
                dash_url: None,
                mp4_url: None,
                width: 1920,
                height: 1080,
                has_audio: true,
                preview: None,
            },
        ),
        next("Starry night over the peaks", 22_390, 9.0 * h, image(p[8])),
        next(
            "Big Buck Bunny, 1080p MP4 clip",
            1_130,
            10.0 * h,
            Media::Video {
                hls_url: None,
                dash_url: None,
                mp4_url: Some(
                    "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/1080/Big_Buck_Bunny_1080_10s_1MB.mp4".into(),
                ),
                width: 1920,
                height: 1080,
                has_audio: false,
                preview: None,
            },
        ),
        next("Forest light", 5_310, 11.0 * h, image(p[3])),
        next("Coastline from above", 11_008, 12.0 * h, image(p[1])),
        next(
            "A plain link post",
            87,
            13.0 * h,
            Media::Link { url: "https://github.com/chezsan770/drift".into(), thumbnail: None },
        ),
        next("Misty valley", 3_870, 14.0 * h, image(p[4])),
        {
            // Marked as a spoiler so blurring can be tried in the demo.
            let mut spoiler = next("Spoiler test: blurred until tapped", 2_955, 15.0 * h, image(p[5]));
            spoiler.is_spoiler = true;
            spoiler
        },
        next("Snowy ridge", 9_660, 16.0 * h, image(p[9])),
        next("Autumn lake", 4_120, 17.0 * h, image(p[11])),
        next("Night sky over the sea", 6_480, 18.0 * h, image(p[12])),
        next("Sunset clouds", 1_745, 20.0 * h, image(p[13])),
    ];
    FeedPage { posts, after: None }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn demo_page_is_well_formed() {
        let page = page(1_000_000.0);
        assert!(page.posts.len() >= 15);
        let mut ids: Vec<_> = page.posts.iter().map(|p| p.id.clone()).collect();
        ids.dedup();
        assert_eq!(ids.len(), page.posts.len());
        for p in &page.posts {
            if let Media::Image { width, height, .. } = p.media {
                assert!(width <= 3840 && width > 0 && height > 0, "{}", p.title);
            }
        }
    }

    #[test]
    fn full_size_keeps_aspect() {
        let (_, w, h) = full(("x", 7360, 4912));
        assert_eq!((w, h), (3840, 2562));
        let (_, w, h) = full(("x", 2560, 1705));
        assert_eq!((w, h), (2560, 1705));
    }
}
