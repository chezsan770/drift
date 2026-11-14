//! Serde mirrors of Reddit's JSON. Every field is defaulted: Reddit omits,
//! nulls and reshapes fields freely, and one odd post must never fail a page.

use serde::Deserialize;
use std::collections::HashMap;

#[derive(Deserialize, Default, Debug)]
#[serde(default)]
pub struct Listing {
    pub data: ListingData,
}

#[derive(Deserialize, Default, Debug)]
#[serde(default)]
pub struct ListingData {
    pub after: Option<String>,
    pub children: Vec<Child>,
}

#[derive(Deserialize, Default, Debug)]
#[serde(default)]
pub struct Child {
    pub kind: String,
    pub data: RawPost,
}

#[derive(Deserialize, Default, Debug, Clone)]
#[serde(default)]
pub struct RawPost {
    pub id: String,
    pub title: String,
    pub subreddit: String,
    pub author: String,
    pub score: i64,
    pub num_comments: i64,
    pub created_utc: f64,
    pub permalink: String,
    pub url: Option<String>,
    pub domain: String,
    pub over_18: bool,
    pub spoiler: bool,
    pub stickied: bool,
    pub is_self: bool,
    pub selftext: String,
    pub thumbnail: Option<String>,
    pub post_hint: Option<String>,
    pub is_video: bool,
    pub is_gallery: Option<bool>,
    pub preview: Option<Preview>,
    pub secure_media: Option<RawMedia>,
    pub media: Option<RawMedia>,
    pub media_metadata: Option<HashMap<String, MediaMeta>>,
    pub gallery_data: Option<GalleryData>,
    pub crosspost_parent_list: Option<Vec<RawPost>>,
}

#[derive(Deserialize, Default, Debug, Clone)]
#[serde(default)]
pub struct Preview {
    pub images: Vec<PreviewImage>,
    pub reddit_video_preview: Option<RedditVideo>,
}

#[derive(Deserialize, Default, Debug, Clone)]
#[serde(default)]
pub struct PreviewImage {
    pub source: ImgSrc,
    pub resolutions: Vec<ImgSrc>,
    pub variants: Variants,
}

#[derive(Deserialize, Default, Debug, Clone)]
#[serde(default)]
pub struct Variants {
    pub mp4: Option<VariantSet>,
    pub gif: Option<VariantSet>,
}

#[derive(Deserialize, Default, Debug, Clone)]
#[serde(default)]
pub struct VariantSet {
    pub source: ImgSrc,
}

#[derive(Deserialize, Default, Debug, Clone)]
#[serde(default)]
pub struct ImgSrc {
    pub url: String,
    pub width: u32,
    pub height: u32,
}

#[derive(Deserialize, Default, Debug, Clone)]
#[serde(default)]
pub struct RawMedia {
    pub reddit_video: Option<RedditVideo>,
    pub oembed: Option<Oembed>,
    #[serde(rename = "type")]
    pub kind: Option<String>,
}

#[derive(Deserialize, Default, Debug, Clone)]
#[serde(default)]
pub struct RedditVideo {
    pub hls_url: Option<String>,
    pub dash_url: Option<String>,
    pub fallback_url: Option<String>,
    pub width: u32,
    pub height: u32,
    pub has_audio: Option<bool>,
    pub is_gif: bool,
}

#[derive(Deserialize, Default, Debug, Clone)]
#[serde(default)]
pub struct Oembed {
    pub thumbnail_url: Option<String>,
    pub provider_name: Option<String>,
}

#[derive(Deserialize, Default, Debug, Clone)]
#[serde(default)]
pub struct MediaMeta {
    pub status: Option<String>,
    /// "Image" | "AnimatedImage" | "RedditVideo"
    pub e: Option<String>,
    pub s: Option<MetaSource>,
}

#[derive(Deserialize, Default, Debug, Clone)]
#[serde(default)]
pub struct MetaSource {
    pub u: Option<String>,
    pub gif: Option<String>,
    pub mp4: Option<String>,
    pub x: u32,
    pub y: u32,
}

#[derive(Deserialize, Default, Debug, Clone)]
#[serde(default)]
pub struct GalleryData {
    pub items: Vec<GalleryEntry>,
}

#[derive(Deserialize, Default, Debug, Clone)]
#[serde(default)]
pub struct GalleryEntry {
    pub media_id: String,
    pub caption: Option<String>,
}

/// `/r/{name}/about`
#[derive(Deserialize, Default, Debug)]
#[serde(default)]
pub struct AboutResponse {
    pub kind: String,
    pub data: AboutData,
}

#[derive(Deserialize, Default, Debug)]
#[serde(default)]
pub struct AboutData {
    pub display_name: String,
    pub subreddit_type: String,
    pub over18: bool,
    pub subscribers: Option<u64>,
    pub quarantine: bool,
}
