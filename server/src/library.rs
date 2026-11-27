use std::path::{Path as FilePath, PathBuf};

use axum::{
    Extension, Json, Router,
    extract::{Path, State},
    http::{HeaderMap, StatusCode, header},
    response::IntoResponse,
    routing::get,
};
use sqlx::{PgPool, Postgres, Transaction, types::JsonValue};

use crate::session::authenticated_username;

pub(crate) mod imports;
mod media;
mod scan;

pub(crate) use scan::import_at_startup;

const DOWNLOAD_PREFIX: &str = "/downloads/";
const STAGING_DIR: &str = ".slopify-staging";

#[derive(Clone)]
pub(crate) struct MediaRoots {
    pub local: Option<PathBuf>,
    pub downloads: Option<PathBuf>,
}

impl MediaRoots {
    fn resolve<'a>(&self, path: &'a str) -> Option<(&FilePath, &'a str)> {
        if let Some(relative) = path.strip_prefix(DOWNLOAD_PREFIX) {
            Some((self.downloads.as_deref()?, relative))
        } else {
            Some((self.local.as_deref()?, path))
        }
    }
}

pub(crate) fn routes(roots: MediaRoots) -> Router<PgPool> {
    Router::new()
        .route("/library/albums", get(albums))
        .route("/library/albums/{id}/tracks", get(tracks))
        .route("/library/albums/{id}/cover", get(media::cover))
        .route("/library/tracks/{id}/audio", get(media::audio))
        .layer(Extension(roots))
}

async fn artist_id(tx: &mut Transaction<'_, Postgres>, name: &str) -> Result<i64, sqlx::Error> {
    sqlx::query_scalar(
        "INSERT INTO artists (name) VALUES ($1)
         ON CONFLICT (name) DO UPDATE SET name = EXCLUDED.name
         RETURNING id",
    )
    .bind(name)
    .fetch_one(&mut **tx)
    .await
}

async fn albums(
    State(pool): State<PgPool>,
    headers: HeaderMap,
) -> Result<impl IntoResponse, StatusCode> {
    authenticated_username(&pool, &headers).await?;
    let albums: Vec<JsonValue> = sqlx::query_scalar(
        "SELECT jsonb_build_object(
             'id', albums.id, 'title', albums.title,
             'artist', artists.name, 'release_year', albums.release_year,
             'has_cover', albums.cover_path IS NOT NULL
         )
         FROM albums JOIN artists ON artists.id = albums.artist_id
         ORDER BY artists.name, albums.release_year, albums.title, albums.id",
    )
    .fetch_all(&pool)
    .await
    .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(([(header::CACHE_CONTROL, "no-store")], Json(albums)))
}

async fn tracks(
    State(pool): State<PgPool>,
    Path(album_id): Path<i64>,
    headers: HeaderMap,
) -> Result<impl IntoResponse, StatusCode> {
    authenticated_username(&pool, &headers).await?;
    let tracks: JsonValue = sqlx::query_scalar(
        "SELECT COALESCE((
             SELECT jsonb_agg(jsonb_build_object(
                 'id', tracks.id, 'title', tracks.title, 'track_number', tracks.track_number
             ) ORDER BY tracks.track_number)
             FROM tracks WHERE tracks.album_id = albums.id
         ), '[]'::jsonb)
         FROM albums WHERE albums.id = $1",
    )
    .bind(album_id)
    .fetch_optional(&pool)
    .await
    .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?
    .ok_or(StatusCode::NOT_FOUND)?;
    Ok(([(header::CACHE_CONTROL, "no-store")], Json(tracks)))
}
