//! Random subreddit picking. Reddit removed `/r/random`, so we pick from our
//! own pool and validate the pick against `/about`.
//!
//! Phase 0 ships a small hand-picked seed list. Phase 3 replaces it with the
//! crawled list (tens of thousands of subreddits) plus live expansion.

use rand::seq::IndexedRandom;
use std::collections::VecDeque;

pub const SEED: &[&str] = &[
    "EarthPorn", "CityPorn", "SkyPorn", "WaterPorn", "VillagePorn", "AbandonedPorn",
    "ArchitecturePorn", "MachinePorn", "InfrastructurePorn", "spaceporn", "itookapicture",
    "pics", "mildlyinteresting", "interestingasfuck", "Damnthatsinteresting", "oddlysatisfying",
    "BeAmazed", "nextfuckinglevel", "NatureIsFuckingLit", "aww", "AnimalsBeingDerps",
    "Eyebleach", "rarepuffers", "Catswithjobs", "lookatmydog", "birding", "Awwducational",
    "MostBeautiful", "pic", "analog", "AccidentalRenaissance", "ArtPorn", "Art",
    "PixelArt", "glitch_art", "Cinemagraphs", "woodworking", "Blacksmith", "Pottery",
    "FoodPorn", "Breadit", "Baking", "castiron", "houseplants", "bonsai", "gardening",
    "MapPorn", "dataisbeautiful", "Watches", "MechanicalKeyboards", "battlestations",
    "carporn", "Shitty_Car_Mods", "Outdoors", "hiking", "camping", "Microbiology",
    "astrophotography", "WeatherPorn", "Volcanoes", "geology", "whatisthisthing",
    "ThingsCutInHalfPorn", "fractalporn", "LiminalSpace", "RetroFuturism", "Cyberpunk",
    "ImaginaryLandscapes", "ImaginaryCityscapes", "Satisfyingasfuck", "Unexpected",
    "HumansBeingBros", "MadeMeSmile", "wholesomegifs", "gifs", "videos", "PerfectTiming",
];

/// Remembers recent picks so rerolls don't repeat.
pub struct History {
    recent: VecDeque<String>,
    cap: usize,
}

impl History {
    pub fn new(cap: usize) -> Self {
        Self { recent: VecDeque::with_capacity(cap), cap }
    }

    pub fn push(&mut self, name: &str) {
        if self.recent.len() == self.cap {
            self.recent.pop_front();
        }
        self.recent.push_back(name.to_ascii_lowercase());
    }

    pub fn contains(&self, name: &str) -> bool {
        let n = name.to_ascii_lowercase();
        self.recent.iter().any(|r| *r == n)
    }
}

/// Pick a candidate not in recent history. Falls back to any seed entry if
/// history covers the whole pool.
pub fn pick<'a>(pool: &'a [&'a str], history: &History) -> &'a str {
    let mut rng = rand::rng();
    for _ in 0..16 {
        let c = pool.choose(&mut rng).copied().unwrap_or("pics");
        if !history.contains(c) {
            return c;
        }
    }
    pool.choose(&mut rng).copied().unwrap_or("pics")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn avoids_recent() {
        let pool = ["a", "b"];
        let mut h = History::new(4);
        h.push("A");
        for _ in 0..50 {
            assert_eq!(pick(&pool, &h), "b");
        }
    }

    #[test]
    fn history_is_bounded() {
        let mut h = History::new(2);
        h.push("x");
        h.push("y");
        h.push("z");
        assert!(!h.contains("x"));
        assert!(h.contains("Y"));
    }
}
