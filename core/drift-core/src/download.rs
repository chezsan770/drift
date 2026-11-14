//! Streaming downloads to a local file (saving images / MP4s to the gallery).

use crate::error::CoreError;
use crate::model::Download;
use reqwest::Client;
use tokio::io::AsyncWriteExt;

/// Refuse anything larger than this (a runaway or mislabelled file).
const MAX_BYTES: u64 = 1024 * 1024 * 1024;

pub async fn to_file(http: &Client, url: &str, dest: &str) -> Result<Download, CoreError> {
    let mut resp = http.get(url).send().await?;
    if !resp.status().is_success() {
        return Err(CoreError::Http { status: resp.status().as_u16() });
    }
    let header_type = resp
        .headers()
        .get(reqwest::header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .map(|v| v.split(';').next().unwrap_or("").trim().to_ascii_lowercase())
        .unwrap_or_default();
    if resp.content_length().is_some_and(|n| n > MAX_BYTES) {
        return Err(CoreError::Network { message: "file is too large".into() });
    }

    let mut file = tokio::fs::File::create(dest)
        .await
        .map_err(|e| CoreError::Network { message: format!("can't write file: {e}") })?;
    let mut bytes: u64 = 0;
    while let Some(chunk) = resp.chunk().await? {
        bytes += chunk.len() as u64;
        if bytes > MAX_BYTES {
            drop(file);
            let _ = tokio::fs::remove_file(dest).await;
            return Err(CoreError::Network { message: "file is too large".into() });
        }
        file.write_all(&chunk)
            .await
            .map_err(|e| CoreError::Network { message: format!("can't write file: {e}") })?;
    }
    file.flush().await.map_err(|e| CoreError::Network { message: e.to_string() })?;

    let (mime_type, extension) = kind(&header_type, url);
    Ok(Download { bytes, mime_type, extension })
}

/// MIME type and extension, from the Content-Type header or else the URL.
pub fn kind(content_type: &str, url: &str) -> (String, String) {
    let from_type = match content_type {
        "image/jpeg" | "image/jpg" => Some("jpg"),
        "image/png" => Some("png"),
        "image/webp" => Some("webp"),
        "image/gif" => Some("gif"),
        "image/avif" => Some("avif"),
        "image/heic" => Some("heic"),
        "video/mp4" => Some("mp4"),
        "video/webm" => Some("webm"),
        "video/quicktime" => Some("mov"),
        _ => None,
    };
    if let Some(ext) = from_type {
        return (content_type.replace("image/jpg", "image/jpeg"), ext.to_string());
    }
    let ext = url::Url::parse(url)
        .ok()
        .and_then(|u| u.path_segments()?.next_back().map(str::to_owned))
        .and_then(|last| last.rsplit_once('.').map(|(_, e)| e.to_ascii_lowercase()))
        .unwrap_or_default();
    let mime = match ext.as_str() {
        "jpg" | "jpeg" => "image/jpeg",
        "png" => "image/png",
        "webp" => "image/webp",
        "gif" => "image/gif",
        "mp4" | "m4v" => "video/mp4",
        "webm" => "video/webm",
        _ => "image/jpeg",
    };
    let ext = if ext == "jpeg" || ext.is_empty() { "jpg".into() } else { ext };
    (mime.to_string(), ext)
}

#[cfg(test)]
mod tests {
    use super::kind;

    #[test]
    fn kinds() {
        assert_eq!(kind("image/png", "https://x/y"), ("image/png".into(), "png".into()));
        assert_eq!(kind("image/jpg", "https://x/y"), ("image/jpeg".into(), "jpg".into()));
        assert_eq!(kind("", "https://i.redd.it/abc.jpeg"), ("image/jpeg".into(), "jpg".into()));
        assert_eq!(kind("application/octet-stream", "https://cdn/v.mp4?x=1"), ("video/mp4".into(), "mp4".into()));
        assert_eq!(kind("", "https://images.unsplash.com/photo-1?w=1"), ("image/jpeg".into(), "jpg".into()));
    }
}
