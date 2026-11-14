//! Display formatting done once in Rust so the UI thread never does it.

/// 999 -> "999", 12_345 -> "12.3k", 1_250_000 -> "1.2m"
pub fn compact_number(n: i64) -> String {
    let sign = if n < 0 { "-" } else { "" };
    let a = n.unsigned_abs();
    let (value, suffix) = match a {
        0..=999 => return format!("{sign}{a}"),
        1_000..=999_999 => (a as f64 / 1_000.0, "k"),
        _ => (a as f64 / 1_000_000.0, "m"),
    };
    // Truncate (not round) so 999_999 shows "999k", never "1000k".
    // Values >= 100 drop the decimal to keep the text short.
    let truncated = (value * 10.0).floor() / 10.0;
    if truncated >= 100.0 || truncated.fract() == 0.0 {
        format!("{sign}{}{suffix}", truncated as u64)
    } else {
        format!("{sign}{truncated:.1}{suffix}")
    }
}

/// Seconds-since-epoch pair -> "now", "5m", "3h", "2d", "4mo", "3y".
pub fn relative_age(created_utc: f64, now_utc: f64) -> String {
    let s = (now_utc - created_utc).max(0.0) as u64;
    match s {
        0..=59 => "now".into(),
        60..=3_599 => format!("{}m", s / 60),
        3_600..=86_399 => format!("{}h", s / 3_600),
        86_400..=2_591_999 => format!("{}d", s / 86_400),
        2_592_000..=31_535_999 => format!("{}mo", s / 2_592_000),
        _ => format!("{}y", s / 31_536_000),
    }
}

/// Defensive: `raw_json=1` should already return unescaped URLs, but some
/// nested fields (and the anonymous endpoints) still contain `&amp;`.
pub fn unescape_url(u: &str) -> String {
    if u.contains("&amp;") {
        u.replace("&amp;", "&")
    } else {
        u.to_owned()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn numbers() {
        assert_eq!(compact_number(0), "0");
        assert_eq!(compact_number(999), "999");
        assert_eq!(compact_number(1_000), "1k");
        assert_eq!(compact_number(12_345), "12.3k");
        assert_eq!(compact_number(999_999), "999k");
        assert_eq!(compact_number(1_250_000), "1.2m");
        assert_eq!(compact_number(-1_500), "-1.5k");
    }

    #[test]
    fn ages() {
        assert_eq!(relative_age(100.0, 130.0), "now");
        assert_eq!(relative_age(0.0, 300.0), "5m");
        assert_eq!(relative_age(0.0, 3.0 * 3600.0), "3h");
        assert_eq!(relative_age(0.0, 2.0 * 86400.0), "2d");
        assert_eq!(relative_age(0.0, 40.0 * 86400.0), "1mo");
        assert_eq!(relative_age(0.0, 800.0 * 86400.0), "2y");
        assert_eq!(relative_age(500.0, 100.0), "now"); // clock skew
    }
}
