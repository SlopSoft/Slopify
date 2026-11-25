use std::{
    io::ErrorKind,
    ops::Range,
    path::{Component, Path as FilePath, PathBuf},
};

use axum::{
    Extension,
    body::Body,
    extract::{Path, State},
    http::{HeaderMap, Method, StatusCode, header},
    response::Response,
};
use sqlx::PgPool;
use tokio::{
    fs::{self, File},
    io::{AsyncReadExt, AsyncSeekExt},
};
use tokio_util::io::ReaderStream;

use crate::session::authenticated_username;

pub(super) const MAX_COVER_BYTES: u64 = 5 * 1024 * 1024;

pub(super) fn cover_mime(path: &FilePath) -> Option<&'static str> {
    match path
        .extension()
        .and_then(|extension| extension.to_str())?
        .to_ascii_lowercase()
        .as_str()
    {
        "jpg" | "jpeg" => Some("image/jpeg"),
        "png" => Some("image/png"),
        "webp" => Some("image/webp"),
        _ => None,
    }
}

pub(super) async fn audio(
    State(pool): State<PgPool>,
    Extension(root): Extension<Option<PathBuf>>,
    Path(track_id): Path<i64>,
    method: Method,
    headers: HeaderMap,
) -> Result<Response, StatusCode> {
    authenticated_username(&pool, &headers).await?;
    let path: String = sqlx::query_scalar("SELECT audio_path FROM tracks WHERE id = $1")
        .bind(track_id)
        .fetch_optional(&pool)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?
        .ok_or(StatusCode::NOT_FOUND)?;
    let (mut file, size) = open(root, &path).await?;
    let mut response = Response::builder()
        .header(header::CONTENT_TYPE, "audio/mpeg")
        .header(header::CACHE_CONTROL, "no-store")
        .header(header::ACCEPT_RANGES, "bytes")
        .header(header::X_CONTENT_TYPE_OPTIONS, "nosniff");

    let range = if method == Method::GET && !headers.contains_key(header::IF_RANGE) {
        headers
            .get(header::RANGE)
            .and_then(|value| value.to_str().ok())
            .and_then(|value| parse_range(value, size))
    } else {
        None
    };
    let bytes = match range {
        Some(Ok(bytes)) => {
            response = response.status(StatusCode::PARTIAL_CONTENT).header(
                header::CONTENT_RANGE,
                format!("bytes {}-{}/{size}", bytes.start, bytes.end - 1),
            );
            file.seek(std::io::SeekFrom::Start(bytes.start))
                .await
                .map_err(file_error)?;
            bytes
        }
        Some(Err(())) => {
            return response
                .status(StatusCode::RANGE_NOT_SATISFIABLE)
                .header(header::CONTENT_RANGE, format!("bytes */{size}"))
                .body(Body::empty())
                .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR);
        }
        None => 0..size,
    };
    let length = bytes.end - bytes.start;
    let body = if method == Method::HEAD {
        Body::empty()
    } else {
        Body::from_stream(ReaderStream::with_capacity(file.take(length), 64 * 1024))
    };
    response
        .header(header::CONTENT_LENGTH, length)
        .body(body)
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)
}

pub(super) async fn cover(
    State(pool): State<PgPool>,
    Extension(root): Extension<Option<PathBuf>>,
    Path(album_id): Path<i64>,
    method: Method,
    headers: HeaderMap,
) -> Result<Response, StatusCode> {
    authenticated_username(&pool, &headers).await?;
    let path: String = sqlx::query_scalar(
        "SELECT cover_path FROM albums WHERE id = $1 AND cover_path IS NOT NULL",
    )
    .bind(album_id)
    .fetch_optional(&pool)
    .await
    .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?
    .ok_or(StatusCode::NOT_FOUND)?;
    let mime = cover_mime(FilePath::new(&path)).ok_or(StatusCode::NOT_FOUND)?;
    let (file, size) = open(root, &path).await?;
    if size > MAX_COVER_BYTES {
        return Err(StatusCode::NOT_FOUND);
    }
    let body = if method == Method::HEAD {
        Body::empty()
    } else {
        Body::from_stream(ReaderStream::new(file.take(size)))
    };
    Response::builder()
        .header(header::CONTENT_TYPE, mime)
        .header(header::CONTENT_LENGTH, size)
        .header(header::CACHE_CONTROL, "no-store")
        .header(header::X_CONTENT_TYPE_OPTIONS, "nosniff")
        .body(body)
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)
}

async fn open(root: Option<PathBuf>, relative: &str) -> Result<(File, u64), StatusCode> {
    let root = root.ok_or(StatusCode::NOT_FOUND)?;
    let relative = FilePath::new(relative);
    if !relative
        .components()
        .all(|part| matches!(part, Component::Normal(_)))
    {
        return Err(StatusCode::NOT_FOUND);
    }
    let root = fs::canonicalize(root).await.map_err(file_error)?;
    let path = fs::canonicalize(root.join(relative))
        .await
        .map_err(file_error)?;
    if !path.starts_with(&root) || !fs::metadata(&path).await.map_err(file_error)?.is_file() {
        return Err(StatusCode::NOT_FOUND);
    }
    let file = File::open(path).await.map_err(file_error)?;
    let size = file.metadata().await.map_err(file_error)?.len();
    Ok((file, size))
}

fn file_error(error: std::io::Error) -> StatusCode {
    match error.kind() {
        ErrorKind::NotFound | ErrorKind::NotADirectory => StatusCode::NOT_FOUND,
        _ => StatusCode::INTERNAL_SERVER_ERROR,
    }
}

fn parse_range(value: &str, size: u64) -> Option<Result<Range<u64>, ()>> {
    let (unit, bytes) = value.split_once('=')?;
    if !unit.eq_ignore_ascii_case("bytes") {
        return None;
    }
    let (start, end) = bytes.split_once('-')?;
    if start.is_empty() {
        let suffix = end.parse::<u64>().ok()?;
        return Some(if suffix == 0 || size == 0 {
            Err(())
        } else {
            Ok(size.saturating_sub(suffix)..size)
        });
    }
    let start = start.parse::<u64>().ok()?;
    let end = if end.is_empty() {
        size
    } else {
        let last = end.parse::<u64>().ok()?;
        if last < start {
            return None;
        }
        last.saturating_add(1).min(size)
    };
    Some(if start < end { Ok(start..end) } else { Err(()) })
}
