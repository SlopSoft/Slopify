use std::{
    collections::HashMap,
    io,
    path::{Path as FilePath, PathBuf},
    sync::Arc,
    time::Duration,
};

use axum::{
    Extension, Json, Router,
    body::Body,
    extract::{DefaultBodyLimit, Path, State},
    http::{HeaderMap, StatusCode, header},
    response::{IntoResponse, Response},
    routing::{get, post, put},
};
use sqlx::{PgPool, types::JsonValue};
use tokio::{
    fs::{self, File},
    io::AsyncWriteExt,
    sync::Semaphore,
    time::timeout,
};
use tonic::{
    Code, Request, Streaming,
    codegen::tokio_stream::{self, StreamExt},
    transport::Channel,
};

use super::{
    DOWNLOAD_PREFIX, STAGING_DIR, artist_id,
    media::MAX_COVER_BYTES,
    scan::{check_cover, check_mp3},
};
use crate::session::authenticated_username;

mod proto {
    tonic::include_proto!("slopify.downloader");
}

use proto::{
    DownloadPart, DownloadRequest, SearchRequest, TrackMetadata, download_part::Part,
    downloader_client::DownloaderClient,
};

const SEARCH_TIMEOUT: Duration = Duration::from_secs(12);
const DOWNLOAD_TIMEOUT: Duration = Duration::from_secs(150);
const MAX_AUDIO_BYTES: u64 = 32 * 1024 * 1024;
const INTERNAL: StatusCode = StatusCode::INTERNAL_SERVER_ERROR;
const BAD_GATEWAY: StatusCode = StatusCode::BAD_GATEWAY;

struct Imports {
    downloader: DownloaderClient<Channel>,
    root: PathBuf,
    searches: Semaphore,
    downloads: Semaphore,
    previews: Arc<Semaphore>,
}

pub(crate) async fn routes(
    endpoint: Option<String>,
    root: Option<PathBuf>,
) -> Result<Router<PgPool>, Box<dyn std::error::Error>> {
    let (Some(endpoint), Some(root)) = (endpoint, root) else {
        return Ok(Router::new());
    };
    let channel = Channel::from_shared(endpoint)?
        .connect_timeout(Duration::from_secs(3))
        .connect_lazy();
    let stage = root.join(STAGING_DIR);
    reset_staging(&stage).await?;
    fs::create_dir_all(stage).await?;
    let imports = Arc::new(Imports {
        downloader: DownloaderClient::new(channel).max_decoding_message_size(128 * 1024),
        root,
        searches: Semaphore::new(2),
        downloads: Semaphore::new(1),
        previews: Arc::new(Semaphore::new(1)),
    });
    Ok(Router::new()
        .route("/search", post(search))
        .route(
            "/search/youtube/{id}/audio",
            get(preview).head(|| async { StatusCode::METHOD_NOT_ALLOWED }),
        )
        .route("/library/imports/youtube/{id}", put(add))
        .layer(DefaultBodyLimit::max(2048))
        .layer(Extension(imports)))
}

async fn search(
    State(pool): State<PgPool>,
    Extension(imports): Extension<Arc<Imports>>,
    headers: HeaderMap,
    Json(fields): Json<HashMap<String, String>>,
) -> Result<impl IntoResponse, StatusCode> {
    authenticated_username(&pool, &headers).await?;
    let query = fields.get("query").map(|query| query.trim()).unwrap_or("");
    if !(1..=150).contains(&query.chars().count()) || query.chars().any(char::is_control) {
        return Err(StatusCode::UNPROCESSABLE_ENTITY);
    }
    let _permit = imports.searches.try_acquire().map_err(busy)?;
    let mut request = Request::new(SearchRequest {
        query: query.to_owned(),
    });
    request.set_timeout(SEARCH_TIMEOUT);
    let response = timeout(SEARCH_TIMEOUT, imports.downloader.clone().search(request))
        .await
        .map_err(|_| StatusCode::GATEWAY_TIMEOUT)?
        .map_err(upstream)?;
    let results: Vec<_> = response
        .into_inner()
        .results
        .into_iter()
        .take(15)
        .filter(|entry| video_id(&entry.video_id))
        .map(|entry| {
            HashMap::from([
                ("id", entry.video_id),
                ("title", entry.title),
                ("artist", entry.artist),
                ("source", "YouTube".to_owned()),
            ])
        })
        .collect();
    Ok(([(header::CACHE_CONTROL, "no-store")], Json(results)))
}

async fn preview(
    State(pool): State<PgPool>,
    Extension(imports): Extension<Arc<Imports>>,
    Path(id): Path<String>,
    headers: HeaderMap,
) -> Result<Response, StatusCode> {
    authenticated_username(&pool, &headers).await?;
    if !video_id(&id) {
        return Err(StatusCode::UNPROCESSABLE_ENTITY);
    }
    let permit = imports.previews.clone().try_acquire_owned().map_err(busy)?;
    let (first, stream) = timeout(DOWNLOAD_TIMEOUT, async {
        let mut stream = download(&imports, &id, true).await?;
        let first = stream
            .message()
            .await
            .map_err(upstream)?
            .ok_or(BAD_GATEWAY)?;
        if !matches!(&first.part, Some(Part::Audio(bytes)) if !bytes.is_empty()) {
            return Err(BAD_GATEWAY);
        }
        Ok::<_, StatusCode>((first, stream))
    })
    .await
    .map_err(|_| StatusCode::GATEWAY_TIMEOUT)??;

    let mut size = 0;
    let body = tokio_stream::once(Ok(first))
        .chain(stream)
        .map(move |part| {
            let _permit = &permit;
            let Some(Part::Audio(bytes)) = part.map_err(io::Error::other)?.part else {
                return Err(io::Error::other("Unexpected YouTube download part"));
            };
            size += bytes.len() as u64;
            if size > MAX_AUDIO_BYTES {
                return Err(io::Error::other("YouTube audio exceeds the size limit"));
            }
            Ok(bytes)
        });
    Ok((
        [
            (header::CONTENT_TYPE, "audio/mpeg"),
            (header::CACHE_CONTROL, "no-store"),
            (header::X_CONTENT_TYPE_OPTIONS, "nosniff"),
        ],
        Body::from_stream(body),
    )
        .into_response())
}

async fn add(
    State(pool): State<PgPool>,
    Extension(imports): Extension<Arc<Imports>>,
    Path(id): Path<String>,
    headers: HeaderMap,
) -> Result<impl IntoResponse, StatusCode> {
    authenticated_username(&pool, &headers).await?;
    if !video_id(&id) {
        return Err(StatusCode::UNPROCESSABLE_ENTITY);
    }
    // Finish publishing even if the HTTP client cancels or disconnects.
    let (album, added) = tokio::spawn(async move {
        let _permit = imports.downloads.try_acquire().map_err(busy)?;
        import_track(&pool, &imports, &id).await
    })
    .await
    .map_err(internal)??;
    Ok((
        if added {
            StatusCode::CREATED
        } else {
            StatusCode::OK
        },
        [(header::CACHE_CONTROL, "no-store")],
        Json(album),
    ))
}

fn video_id(id: &str) -> bool {
    id.len() == 11
        && id
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_'))
}

fn busy(_: tokio::sync::TryAcquireError) -> StatusCode {
    StatusCode::TOO_MANY_REQUESTS
}

fn upstream(error: tonic::Status) -> StatusCode {
    match error.code() {
        Code::InvalidArgument => StatusCode::UNPROCESSABLE_ENTITY,
        Code::NotFound => StatusCode::NOT_FOUND,
        Code::DeadlineExceeded | Code::Cancelled => StatusCode::GATEWAY_TIMEOUT,
        Code::ResourceExhausted | Code::FailedPrecondition => StatusCode::SERVICE_UNAVAILABLE,
        _ => BAD_GATEWAY,
    }
}

fn internal(error: impl std::fmt::Display) -> StatusCode {
    eprintln!("YouTube import: {error}");
    INTERNAL
}

#[derive(sqlx::FromRow)]
struct Single {
    artist: String,
    title: String,
    release_year: i16,
}

impl Single {
    fn from_metadata(meta: TrackMetadata) -> Result<Self, StatusCode> {
        fn safe(name: &str) -> bool {
            !name.is_empty()
                && name.len() <= 120
                && name.trim_matches([' ', '.']) == name
                && !name.contains(['/', '\\'])
                && !name.chars().any(char::is_control)
        }
        if !safe(&meta.artist)
            || !safe(&meta.title)
            || meta.artist == STAGING_DIR
            || !(1..=9999).contains(&meta.year)
        {
            return Err(BAD_GATEWAY);
        }
        Ok(Self {
            artist: meta.artist,
            title: meta.title,
            release_year: meta.year as i16,
        })
    }

    fn directory(&self) -> String {
        format!("{}/[{:04}] {}", self.artist, self.release_year, self.title)
    }

    fn filename(&self) -> String {
        format!("[01] {}.mp3", self.title)
    }

    fn audio_path(&self) -> String {
        format!("{DOWNLOAD_PREFIX}{}/{}", self.directory(), self.filename())
    }
}

async fn import_track(
    pool: &PgPool,
    imports: &Imports,
    id: &str,
) -> Result<(JsonValue, bool), StatusCode> {
    if let Some(single) = sqlx::query_as::<_, Single>(
        "SELECT artist, title, release_year FROM youtube_imports WHERE video_id = $1",
    )
    .bind(id)
    .fetch_optional(pool)
    .await
    .map_err(internal)?
        && fs::try_exists(
            imports
                .root
                .join(single.directory())
                .join(single.filename()),
        )
        .await
        .map_err(internal)?
    {
        let indexed: Option<JsonValue> = sqlx::query_scalar(
            "SELECT jsonb_build_object('id', albums.id, 'artist', $2::text, 'title', albums.title,
                 'release_year', albums.release_year, 'has_cover', albums.cover_path IS NOT NULL)
             FROM tracks JOIN albums ON albums.id = tracks.album_id WHERE tracks.audio_path = $1",
        )
        .bind(single.audio_path())
        .bind(&single.artist)
        .fetch_optional(pool)
        .await
        .map_err(internal)?;
        let album = match indexed {
            Some(album) => album,
            None => index_single(pool, &single).await?,
        };
        return Ok((album, false));
    }

    let stage = imports.root.join(STAGING_DIR).join(id);
    reset_staging(&stage).await.map_err(internal)?;
    fs::create_dir(&stage).await.map_err(internal)?;
    let result = fetch_and_publish(pool, imports, id, &stage).await;
    if let Err(error) = reset_staging(&stage).await {
        internal(error);
    }
    result.map(|album| (album, true))
}

async fn reset_staging(path: &FilePath) -> io::Result<()> {
    match fs::remove_dir_all(path).await {
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
        result => result,
    }
}

async fn fetch_and_publish(
    pool: &PgPool,
    imports: &Imports,
    id: &str,
    stage: &FilePath,
) -> Result<JsonValue, StatusCode> {
    let single = timeout(DOWNLOAD_TIMEOUT, async {
        download_to_stage(download(imports, id, false).await?, stage).await
    })
    .await
    .map_err(|_| StatusCode::GATEWAY_TIMEOUT)??;

    let destination = imports.root.join(single.directory());
    if fs::try_exists(&destination).await.map_err(internal)? {
        return Err(StatusCode::CONFLICT);
    }
    let artist = imports.root.join(&single.artist);
    match fs::symlink_metadata(&artist).await {
        Ok(metadata) if metadata.is_dir() => {}
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            fs::create_dir(&artist).await.map_err(internal)?;
        }
        Ok(_) => return Err(StatusCode::CONFLICT),
        Err(error) => return Err(internal(error)),
    }
    let reserved = sqlx::query(
        "INSERT INTO youtube_imports (video_id, artist, title, release_year)
         SELECT $1, $2, $3, $4
         WHERE NOT EXISTS (
             SELECT 1 FROM albums JOIN artists ON artists.id = albums.artist_id
             WHERE artists.name = $2 AND albums.title = $3 AND albums.release_year = $4
         )
         ON CONFLICT (video_id) DO UPDATE
         SET artist = EXCLUDED.artist, title = EXCLUDED.title, release_year = EXCLUDED.release_year",
    )
    .bind(id)
    .bind(&single.artist)
    .bind(&single.title)
    .bind(single.release_year)
    .execute(pool)
    .await
    .map_err(|error| match error.as_database_error() {
        Some(error) if error.is_unique_violation() => StatusCode::CONFLICT,
        _ => internal(error),
    })?;
    if reserved.rows_affected() == 0 {
        return Err(StatusCode::CONFLICT);
    }

    fs::rename(stage, &destination).await.map_err(internal)?;
    for directory in [&destination, &artist, &imports.root] {
        File::open(directory)
            .await
            .map_err(internal)?
            .sync_all()
            .await
            .map_err(internal)?;
    }
    index_single(pool, &single).await
}

async fn download(
    imports: &Imports,
    id: &str,
    audio_only: bool,
) -> Result<Streaming<DownloadPart>, StatusCode> {
    let mut request = Request::new(DownloadRequest {
        video_id: id.to_owned(),
        audio_only,
    });
    request.set_timeout(DOWNLOAD_TIMEOUT);
    imports
        .downloader
        .clone()
        .download(request)
        .await
        .map(tonic::Response::into_inner)
        .map_err(upstream)
}

async fn download_to_stage(
    mut stream: Streaming<DownloadPart>,
    stage: &FilePath,
) -> Result<Single, StatusCode> {
    let Some(Part::Metadata(meta)) = stream
        .message()
        .await
        .map_err(upstream)?
        .and_then(|part| part.part)
    else {
        return Err(BAD_GATEWAY);
    };
    let single = Single::from_metadata(meta)?;
    let cover_path = stage.join("cover.jpg");
    let audio_path = stage.join(single.filename());
    let mut cover = File::create_new(&cover_path).await.map_err(internal)?;
    let mut audio = File::create_new(&audio_path).await.map_err(internal)?;
    let mut cover_bytes = 0;
    let mut audio_bytes = 0;
    while let Some(part) = stream.message().await.map_err(upstream)? {
        let (chunk, file, size, limit) = match part.part {
            Some(Part::Cover(bytes)) => (bytes, &mut cover, &mut cover_bytes, MAX_COVER_BYTES),
            Some(Part::Audio(bytes)) => (bytes, &mut audio, &mut audio_bytes, MAX_AUDIO_BYTES),
            _ => return Err(BAD_GATEWAY),
        };
        *size += chunk.len() as u64;
        if *size > limit {
            return Err(BAD_GATEWAY);
        }
        file.write_all(&chunk).await.map_err(internal)?;
    }
    cover.sync_all().await.map_err(internal)?;
    audio.sync_all().await.map_err(internal)?;
    tokio::task::spawn_blocking(move || {
        check_mp3(&audio_path)?;
        check_cover(&cover_path, "image/jpeg")
    })
    .await
    .map_err(internal)?
    .map_err(|error| {
        eprintln!("Invalid media from YouTube downloader: {error}");
        BAD_GATEWAY
    })?;
    Ok(single)
}

async fn index_single(pool: &PgPool, single: &Single) -> Result<JsonValue, StatusCode> {
    let mut tx = pool.begin().await.map_err(internal)?;
    let artist = artist_id(&mut tx, &single.artist).await.map_err(internal)?;
    let directory = format!("{DOWNLOAD_PREFIX}{}", single.directory());
    let cover = format!("{directory}/cover.jpg");
    let audio = single.audio_path();
    let album: JsonValue = sqlx::query_scalar(
        "INSERT INTO albums (artist_id, title, release_year, cover_path) VALUES ($1, $2, $3, $4)
         ON CONFLICT (artist_id, release_year, title) DO UPDATE SET cover_path = EXCLUDED.cover_path
         RETURNING jsonb_build_object('id', id, 'artist', $5::text, 'title', title,
             'release_year', release_year, 'has_cover', cover_path IS NOT NULL)",
    )
    .bind(artist)
    .bind(&single.title)
    .bind(single.release_year)
    .bind(cover)
    .bind(&single.artist)
    .fetch_one(&mut *tx)
    .await
    .map_err(internal)?;
    let album_id = album["id"].as_i64().ok_or(INTERNAL)?;
    let stored: String = sqlx::query_scalar(
        "INSERT INTO tracks (album_id, title, track_number, audio_path) VALUES ($1, $2, 1, $3)
         ON CONFLICT (album_id, track_number) DO UPDATE SET track_number = EXCLUDED.track_number
         RETURNING audio_path",
    )
    .bind(album_id)
    .bind(&single.title)
    .bind(&audio)
    .fetch_one(&mut *tx)
    .await
    .map_err(internal)?;
    if stored != audio {
        return Err(StatusCode::CONFLICT);
    }
    tx.commit().await.map_err(internal)?;
    Ok(album)
}
