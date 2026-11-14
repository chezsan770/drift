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
    match core.resolve_link("https://i.imgur.com/abc.gifv".into()).await {
        Ok(m) => println!("link: {m:?}"),
        Err(e) => println!("link error: {e}"),
    }
}
