//! Live check against Reddit: `cargo run --example smoke -- [subreddit]`.
//! Set REDDIT_CLIENT_ID to use OAuth instead of the anonymous endpoints.

use drift_core::{CoreConfig, DriftCore, Media, Sort};

#[tokio::main]
async fn main() {
    let sub = std::env::args().nth(1).unwrap_or_else(|| "pics".into());
    let core = DriftCore::new(CoreConfig {
        client_id: std::env::var("REDDIT_CLIENT_ID").ok(),
        user_agent: "android:dev.drift.app:v0.1.0 (dev smoke test)".into(),
        device_id: "DO_NOT_TRACK_THIS_DEVICE".into(),
        cache_dir: None,
    });

    let t = std::time::Instant::now();
    match core.feed(sub.clone(), Sort::Hot, None).await {
        Ok(page) => {
            println!("r/{sub}: {} posts in {:?}, after={:?}", page.posts.len(), t.elapsed(), page.after);
            for p in page.posts.iter().take(12) {
                let kind = match &p.media {
                    Media::Text { .. } => "text",
                    Media::Image { .. } => "image",
                    Media::Gallery { .. } => "gallery",
                    Media::Video { .. } => "video",
                    Media::Gif { .. } => "gif",
                    Media::Embed { .. } => "embed",
                    Media::Link { .. } => "link",
                };
                println!("  [{kind:7}] {:>6} · {:>3} · {}", p.score_text, p.age_text, p.title.chars().take(60).collect::<String>());
            }
        }
        Err(e) => println!("feed error: {e}"),
    }
    match core.random_subreddit(false).await {
        Ok(s) => println!("random: r/{s}"),
        Err(e) => println!("random error: {e}"),
    }
    let links = std::env::args().skip(2).collect::<Vec<_>>();
    let defaults = [
        "Check this out https://giphy.com/gifs/3o7aD2saalBwwftBIY",
        "https://test-videos.co.uk/vids/jellyfish/mp4/h264/1080/Jellyfish_1080_10s_1MB.mp4",
        "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
        "https://github.com/chezsan770/drift",
        "https://images.unsplash.com/photo-1506744038136-46273834b3fb?w=1080",
    ];
    let list: Vec<String> = if links.is_empty() { defaults.iter().map(|s| s.to_string()).collect() } else { links };
    for text in list {
        let t = std::time::Instant::now();
        match core.link_post(text.clone()).await {
            Ok(p) => println!("link ({:?}) {:?}\n    title={:?}\n    media={:?}", t.elapsed(), text, p.title, p.media),
            Err(e) => println!("link error {text:?}: {e}"),
        }
    }
}
