use drift_core::{parse_listing, GalleryItem, Media};
use pretty_assertions::assert_eq;

const FIXTURE: &[u8] = include_bytes!("fixtures/listing.json");
const NOW: f64 = 1000.0 + 5.0 * 3600.0;

fn media_of(id: &str) -> Media {
    let page = parse_listing(FIXTURE, NOW).unwrap();
    page.posts.into_iter().find(|p| p.id == id).unwrap().media
}

#[test]
fn page_metadata() {
    let page = parse_listing(FIXTURE, NOW).unwrap();
    assert_eq!(page.after.as_deref(), Some("t3_next"));
    assert_eq!(page.posts.len(), 9);
    let p = &page.posts[0];
    assert_eq!(p.score_text, "12.3k");
    assert_eq!(p.comments_text, "230");
    assert_eq!(p.age_text, "5h");
    assert_eq!(p.permalink, "https://www.reddit.com/r/EarthPorn/comments/img1/fog/");
    assert_eq!(page.posts[2].score_text, "1.2m");
}

#[test]
fn image_uses_original_and_card_preview() {
    assert_eq!(
        media_of("img1"),
        Media::Image {
            url: "https://i.redd.it/fog.jpeg".into(),
            width: 4000,
            height: 3000,
            preview: Some("https://preview.redd.it/fog.jpeg?width=1080&s=2".into()),
        }
    );
}

#[test]
fn gallery_keeps_order_and_skips_broken() {
    let Media::Gallery { items } = media_of("gal1") else { panic!() };
    assert_eq!(
        items,
        vec![
            GalleryItem {
                url: "https://preview.redd.it/m2.png?width=1200&s=y".into(),
                width: 1200,
                height: 1600,
                is_animated: false,
                caption: Some("second first".into()),
            },
            GalleryItem {
                url: "https://preview.redd.it/m1.jpg?width=3000&s=x".into(),
                width: 3000,
                height: 2000,
                is_animated: false,
                caption: None,
            },
            GalleryItem {
                url: "https://preview.redd.it/m3.gif?format=mp4&s=z".into(),
                width: 480,
                height: 270,
                is_animated: true,
                caption: None,
            },
        ]
    );
}

#[test]
fn reddit_video_prefers_hls_with_audio() {
    let Media::Video { hls_url, dash_url, mp4_url, width, has_audio, preview, .. } = media_of("vid1") else {
        panic!()
    };
    assert_eq!(hls_url.as_deref(), Some("https://v.redd.it/abc123/HLSPlaylist.m3u8?a=1"));
    assert!(dash_url.is_some() && mp4_url.is_some());
    assert_eq!(width, 1920);
    assert!(has_audio);
    assert_eq!(preview.as_deref(), Some("https://external-preview.redd.it/v.png"));
}

#[test]
fn gif_becomes_mp4() {
    let Media::Gif { mp4_url, .. } = media_of("gif1") else { panic!() };
    assert_eq!(mp4_url, "https://preview.redd.it/loop.gif?format=mp4&s=3");
    let Media::Gif { mp4_url, .. } = media_of("imgur1") else { panic!() };
    assert_eq!(mp4_url, "https://i.imgur.com/Zx9.mp4");
}

#[test]
fn crosspost_uses_parent_media() {
    let Media::Image { url, .. } = media_of("xp1") else { panic!() };
    assert_eq!(url, "https://i.redd.it/orig.png");
}

#[test]
fn embeds_text_and_links() {
    assert!(matches!(media_of("yt1"), Media::Embed { ref provider, .. } if provider == "YouTube"));
    assert_eq!(media_of("self1"), Media::Text { body: "body **text**".into() });
    assert_eq!(
        media_of("link1"),
        Media::Link { url: "https://news.example.com/story".into(), thumbnail: None }
    );
}

#[test]
fn one_bad_post_does_not_fail_the_page() {
    let body = br#"{"data":{"after":null,"children":[
        {"kind":"t3","data":{"id":"ok","title":"fine","url":"https://i.redd.it/a.jpg"}},
        {"kind":"t3","data":{"id":"bad","title":null}}
    ]}}"#;
    let page = parse_listing(body, 0.0).unwrap();
    assert_eq!(page.posts.len(), 1);
    assert_eq!(page.posts[0].id, "ok");
}
