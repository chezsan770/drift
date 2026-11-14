//! Live check of the RSS fallback: `cargo run --example rss_check -- [subreddit]`.
//! Makes ONE real request (Reddit allows about one per minute without API
//! access), then shows that a second request is refused locally.

use drift_core::{CoreConfig, DriftCore, Media, Sort};

#[tokio::main]
async fn main() {
    let sub = std::env::args().nth(1).unwrap_or_else(|| "EarthPorn".into());
    let core = DriftCore::new(CoreConfig {
        client_id: None,
        user_agent: "android:dev.drift.app:v0.1.0 (rss check)".into(),
        device_id: "DO_NOT_TRACK_THIS_DEVICE".into(),
        cache_dir: None,
    });
    println!("limited mode: {}", core.is_limited_mode());
    let t = std::time::Instant::now();
    match core.feed(sub.clone(), Sort::Hot, None).await {
        Ok(page) => {
            println!("r/{sub}: {} posts in {:?}, after={:?}", page.posts.len(), t.elapsed(), page.after);
            let mut kinds = std::collections::BTreeMap::new();
            for p in &page.posts {
                let k = match &p.media {
                    Media::Text { .. } => "text",
                    Media::Image { .. } => "image",
                    Media::Gallery { .. } => "gallery",
                    Media::Video { .. } => "video",
                    Media::Gif { .. } => "gif",
                    Media::Embed { .. } => "embed",
                    Media::Link { .. } => "link",
                };
                *kinds.entry(k).or_insert(0) += 1;
            }
            println!("media kinds: {kinds:?}");
            if let Some(p) = page.posts.iter().find(|p| matches!(p.media, Media::Image { .. })) {
                println!("sample image post: age={} has_preview={} url_host={}",
                    p.age_text,
                    matches!(&p.media, Media::Image { preview: Some(_), .. }),
                    url_host(&p.url));
            }
        }
        Err(e) => println!("feed error: {e}"),
    }
    println!("wait before next request: {}s", core.rate_limit_wait_secs());
    match core.feed("pics".into(), Sort::Hot, None).await {
        Ok(p) => println!("second request unexpectedly allowed ({} posts)", p.posts.len()),
        Err(e) => println!("second request refused locally: {e}"),
    }
}

fn url_host(u: &str) -> String {
    u.split('/').nth(2).unwrap_or("").to_string()
}
