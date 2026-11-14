//! Types that cross the FFI boundary. Everything here is immutable and
//! display-ready: Kotlin renders these as-is and never re-formats them.

/// Settings passed in once when the core starts.
#[derive(uniffi::Record, Clone, Debug)]
pub struct CoreConfig {
    /// Reddit OAuth "installed app" client id. `None` = anonymous `.json`
    /// endpoints (heavily rate limited, fine for development only).
    pub client_id: Option<String>,
    /// Reddit requires `platform:app-id:version (by /u/name)`.
    pub user_agent: String,
    /// Stable random id per install (20-30 chars), used for app-only OAuth.
    pub device_id: String,
}

#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum Sort {
    Hot,
    New,
    Rising,
    TopDay,
    TopWeek,
    TopMonth,
    TopYear,
    TopAll,
}

impl Sort {
    /// (path segment, optional `t=` value)
    pub(crate) fn parts(self) -> (&'static str, Option<&'static str>) {
        match self {
            Sort::Hot => ("hot", None),
            Sort::New => ("new", None),
            Sort::Rising => ("rising", None),
            Sort::TopDay => ("top", Some("day")),
            Sort::TopWeek => ("top", Some("week")),
            Sort::TopMonth => ("top", Some("month")),
            Sort::TopYear => ("top", Some("year")),
            Sort::TopAll => ("top", Some("all")),
        }
    }
}

#[derive(uniffi::Record, Clone, Debug, PartialEq)]
pub struct FeedPage {
    pub posts: Vec<Post>,
    /// Pass back into `feed()` to load the next page. `None` = end of feed.
    pub after: Option<String>,
}

#[derive(uniffi::Record, Clone, Debug, PartialEq)]
pub struct Post {
    pub id: String,
    pub subreddit: String,
    pub author: String,
    pub title: String,
    pub permalink: String,
    pub url: String,
    pub domain: String,
    /// "12.3k"
    pub score_text: String,
    /// "230"
    pub comments_text: String,
    /// "5h"
    pub age_text: String,
    pub is_nsfw: bool,
    pub is_spoiler: bool,
    pub is_stickied: bool,
    pub media: Media,
}

#[derive(uniffi::Record, Clone, Debug, PartialEq)]
pub struct GalleryItem {
    /// Original, full-resolution file.
    pub url: String,
    pub width: u32,
    pub height: u32,
    /// True when `url` is an MP4/GIF that should be played as video.
    pub is_animated: bool,
    pub caption: Option<String>,
}

/// What a post (or any link) resolves to. `url`/`*_url` fields always point
/// at the highest quality source available; `preview` is a card-sized
/// (~1080px wide) image to show in the feed before the original loads.
#[derive(uniffi::Enum, Clone, Debug, PartialEq)]
pub enum Media {
    Text {
        body: String,
    },
    Image {
        url: String,
        width: u32,
        height: u32,
        preview: Option<String>,
    },
    Gallery {
        items: Vec<GalleryItem>,
    },
    /// Prefer `hls_url` (video + audio, adaptive), then `dash_url`.
    /// `mp4_url` is a progressive fallback and may have no audio track.
    Video {
        hls_url: Option<String>,
        dash_url: Option<String>,
        mp4_url: Option<String>,
        width: u32,
        height: u32,
        has_audio: bool,
        preview: Option<String>,
    },
    /// Silent looping clip, always delivered as MP4.
    Gif {
        mp4_url: String,
        width: u32,
        height: u32,
        preview: Option<String>,
    },
    /// Third-party player (YouTube etc.): show thumbnail, open externally.
    Embed {
        url: String,
        provider: String,
        thumbnail: Option<String>,
    },
    Link {
        url: String,
        thumbnail: Option<String>,
    },
}
