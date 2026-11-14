//! On-disk cache of each feed's first page, so the app can show the last
//! feed instantly on launch (and while Reddit's rate limit is in effect),
//! then refresh it. One small JSON file per feed, newest 40 kept.

use crate::model::FeedPage;
use std::path::{Path, PathBuf};

const MAX_FEEDS: usize = 40;

pub struct FeedCache {
    dir: Option<PathBuf>,
}

impl FeedCache {
    pub fn new(cache_dir: Option<&str>) -> Self {
        let dir = cache_dir.filter(|d| !d.is_empty()).map(|d| Path::new(d).join("feeds"));
        if let Some(d) = &dir {
            let _ = std::fs::create_dir_all(d);
        }
        Self { dir }
    }

    fn path(&self, key: &str) -> Option<PathBuf> {
        // FNV-1a: stable across runs (std's hasher is randomly seeded).
        let mut h: u64 = 0xcbf2_9ce4_8422_2325;
        for b in key.as_bytes() {
            h ^= *b as u64;
            h = h.wrapping_mul(0x0100_0000_01b3);
        }
        self.dir.as_ref().map(|d| d.join(format!("{h:016x}.json")))
    }

    pub fn load(&self, key: &str) -> Option<FeedPage> {
        let bytes = std::fs::read(self.path(key)?).ok()?;
        serde_json::from_slice(&bytes).ok()
    }

    pub fn save(&self, key: &str, page: &FeedPage) {
        let (Some(path), Some(dir)) = (self.path(key), self.dir.as_ref()) else { return };
        let Ok(json) = serde_json::to_vec(page) else { return };
        // Write then rename, so a crash never leaves a half-written file.
        let tmp = path.with_extension("tmp");
        if std::fs::write(&tmp, json).is_ok() && std::fs::rename(&tmp, &path).is_ok() {
            prune(dir);
        }
    }
}

/// Keep only the most recently written MAX_FEEDS files.
fn prune(dir: &Path) {
    let Ok(rd) = std::fs::read_dir(dir) else { return };
    let mut files: Vec<(std::time::SystemTime, PathBuf)> = rd
        .filter_map(|e| e.ok())
        .filter(|e| e.path().extension().is_some_and(|x| x == "json"))
        .filter_map(|e| Some((e.metadata().ok()?.modified().ok()?, e.path())))
        .collect();
    if files.len() <= MAX_FEEDS {
        return;
    }
    files.sort_by(|a, b| b.0.cmp(&a.0));
    for (_, p) in files.into_iter().skip(MAX_FEEDS) {
        let _ = std::fs::remove_file(p);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::{Media, Post};

    fn page(n: usize) -> FeedPage {
        FeedPage {
            posts: (0..n)
                .map(|i| Post {
                    id: format!("p{i}"),
                    subreddit: "s".into(),
                    author: "a".into(),
                    title: format!("t{i} — ñ"),
                    permalink: String::new(),
                    url: String::new(),
                    domain: String::new(),
                    score_text: String::new(),
                    comments_text: String::new(),
                    age_text: "1h".into(),
                    is_nsfw: false,
                    is_spoiler: false,
                    is_stickied: false,
                    media: Media::Image { url: "u".into(), width: 1, height: 2, preview: None, thumbnail: Some("t".into()) },
                })
                .collect(),
            after: Some("t3_x".into()),
        }
    }

    #[test]
    fn round_trip_and_prune() {
        let dir = std::env::temp_dir().join(format!("drift-cache-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let c = FeedCache::new(dir.to_str());
        assert_eq!(c.load("r/pics|Hot"), None);
        c.save("r/pics|Hot", &page(3));
        assert_eq!(c.load("r/pics|Hot"), Some(page(3)));
        assert_eq!(c.load("r/pics|New"), None);
        for i in 0..(MAX_FEEDS + 5) {
            c.save(&format!("k{i}"), &page(1));
        }
        let count = std::fs::read_dir(dir.join("feeds")).unwrap().count();
        assert_eq!(count, MAX_FEEDS);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn no_dir_is_a_no_op() {
        let c = FeedCache::new(None);
        c.save("k", &page(1));
        assert_eq!(c.load("k"), None);
    }
}
