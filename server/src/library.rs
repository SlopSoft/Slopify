use std::path::PathBuf;

use axum::{
    Extension, Json, Router,
    extract::{Path, State},
    http::{HeaderMap, StatusCode, header},
    response::IntoResponse,
    routing::get,
};
use sqlx::{PgPool, types::JsonValue};

use crate::session::authenticated_username;

mod media;
mod scan;

pub(crate) use scan::import_at_startup;

pub fn routes(library_dir: Option<PathBuf>) -> Router<PgPool> {
    Router::new()
        .route("/library/albums", get(albums))
        .route("/library/albums/{id}/tracks", get(tracks))
        .route("/library/albums/{id}/cover", get(media::cover))
        .route("/library/tracks/{id}/audio", get(media::audio))
        .layer(Extension(library_dir))
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
