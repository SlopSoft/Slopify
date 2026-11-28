use std::{
    collections::{HashMap, VecDeque},
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
    io::{AsyncReadExt, AsyncWriteExt},
    sync::{Mutex, OnceCell, Semaphore},
    time::timeout,
};
use tokio_util::io::ReaderStream;
use tonic::{Code, Request, Streaming, transport::Channel};

use super::{
    DOWNLOAD_PREFIX, MediaRoots, STAGING_DIR, artist_id,
    media::{MAX_COVER_BYTES, open},
    scan::{check_cover, check_mp3},
};
use crate::session::authenticated_username;

mod proto {
    tonic::include_proto!("slopify.downloader");
}

use proto::{
    DownloadPart, DownloadRequest, SearchRequest, ThumbnailRequest, TrackMetadata,
    download_part::Part, downloader_client::DownloaderClient,
};

const SEARCH_TIMEOUT: Duration = Duration::from_secs(12);
const THUMBNAIL_TIMEOUT: Duration = Duration::from_secs(8);
const DOWNLOAD_TIMEOUT: Duration = Duration::from_secs(150);
const MAX_AUDIO_BYTES: u64 = 32 * 1024 * 1024;
const MAX_THUMBNAIL_BYTES: usize = 96 * 1024;
const PREPARED_TRACK_LIMIT: usize = 8;
const INTERNAL: StatusCode = StatusCode::INTERNAL_SERVER_ERROR;
const BAD_GATEWAY: StatusCode = StatusCode::BAD_GATEWAY;

struct Imports {
    downloader: DownloaderClient<Channel>,
    root: PathBuf,
    searches: Semaphore,
    downloads: Semaphore,
    previews: Semaphore,
    thumbnails: Semaphore,
    prepared: Mutex<VecDeque<(String, Arc<PreparedTrack>)>>,
}

struct PreparedTrack {
    directory: PathBuf,
    single: OnceCell<Single>,
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
    fs::create_dir_all(stage.join("prepared")).await?;
    let imports = Arc::new(Imports {
        downloader: DownloaderClient::new(channel).max_decoding_message_size(128 * 1024),
        root,
        searches: Semaphore::new(2),
        downloads: Semaphore::new(1),
        previews: Semaphore::new(1),
        thumbnails: Semaphore::new(1),
        prepared: Mutex::new(VecDeque::new()),
    });
    Ok(Router::new()
        .route("/search", post(search))
        .route(
            "/search/youtube/{id}/audio",
            get(preview).head(|| async { StatusCode::METHOD_NOT_ALLOWED }),
        )
        .route(
            "/search/youtube/{id}/cover",
            get(thumbnail).head(|| async { StatusCode::METHOD_NOT_ALLOWED }),
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
    let _permit = imports.previews.try_acquire().map_err(busy)?;
    let (file, metadata) = match existing_import(&pool, &imports, &id).await? {
        Some(single) => {
            let roots = MediaRoots {
                local: None,
                downloads: Some(imports.root.clone()),
            };
            open(&roots, &single.audio_path()).await?
        }
        None => {
            let prepared = prepare_track(&imports, &id).await?;
            let single = prepared.single.get().ok_or(INTERNAL)?;
            let file = File::open(prepared.directory.join(single.filename()))
                .await
                .map_err(internal)?;
            let metadata = file.metadata().await.map_err(internal)?;
            (file, metadata)
        }
    };
    let size = metadata.len();
    Response::builder()
        .header(header::CONTENT_TYPE, "audio/mpeg")
        .header(header::CONTENT_LENGTH, size)
        .header(header::CACHE_CONTROL, "no-store")
        .header(header::X_CONTENT_TYPE_OPTIONS, "nosniff")
        .body(Body::from_stream(ReaderStream::with_capacity(
            file.take(size),
            64 * 1024,
        )))
        .map_err(internal)
}

async fn thumbnail(
    State(pool): State<PgPool>,
    Extension(imports): Extension<Arc<Imports>>,
    Path(id): Path<String>,
    headers: HeaderMap,
) -> Result<Response, StatusCode> {
    authenticated_username(&pool, &headers).await?;
    if !video_id(&id) {
        return Err(StatusCode::UNPROCESSABLE_ENTITY);
    }
    let jpeg = timeout(THUMBNAIL_TIMEOUT, async {
        let _permit = imports.thumbnails.acquire().await.map_err(internal)?;
        let mut request = Request::new(ThumbnailRequest { video_id: id });
        request.set_timeout(THUMBNAIL_TIMEOUT);
        imports
            .downloader
            .clone()
            .thumbnail(request)
            .await
            .map(|response| response.into_inner().jpeg)
            .map_err(upstream)
    })
    .await
    .map_err(|_| StatusCode::GATEWAY_TIMEOUT)??;
    if jpeg.len() > MAX_THUMBNAIL_BYTES || !jpeg.starts_with(&[0xff, 0xd8, 0xff]) {
        return Err(BAD_GATEWAY);
    }
    Response::builder()
        .header(header::CONTENT_TYPE, "image/jpeg")
        .header(header::CONTENT_LENGTH, jpeg.len())
        .header(header::CACHE_CONTROL, "no-store")
        .header(header::X_CONTENT_TYPE_OPTIONS, "nosniff")
        .body(Body::from(jpeg))
        .map_err(internal)
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
    imports: &Arc<Imports>,
    id: &str,
) -> Result<(JsonValue, bool), StatusCode> {
    if let Some(single) = existing_import(pool, imports, id).await? {
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
            None => index_single(pool, imports, &single).await?,
        };
        return Ok((album, false));
    }

    let prepared = prepare_track(imports, id).await?;
    let single = prepared.single.get().ok_or(INTERNAL)?;
    let stage = imports.root.join(STAGING_DIR).join(id);
    reset_staging(&stage).await.map_err(internal)?;
    fs::create_dir(&stage).await.map_err(internal)?;
    let result = publish_track(pool, imports, id, &stage, &prepared.directory, single).await;
    if let Err(error) = reset_staging(&stage).await {
        internal(error);
    }
    result.map(|album| (album, true))
}

async fn existing_import(
    pool: &PgPool,
    imports: &Imports,
    id: &str,
) -> Result<Option<Single>, StatusCode> {
    let single = sqlx::query_as::<_, Single>(
        "SELECT artist, title, release_year FROM youtube_imports WHERE video_id = $1",
    )
    .bind(id)
    .fetch_optional(pool)
    .await
    .map_err(internal)?;
    if let Some(single) = single
        && fs::try_exists(
            imports
                .root
                .join(single.directory())
                .join(single.filename()),
        )
        .await
        .map_err(internal)?
    {
        return Ok(Some(single));
    }
    Ok(None)
}

async fn prepare_track(imports: &Arc<Imports>, id: &str) -> Result<Arc<PreparedTrack>, StatusCode> {
    // Finish eviction and update the map even if the initiating client disconnects.
    let cache = imports.clone();
    let video = id.to_owned();
    let track = tokio::spawn(async move {
        let mut entries = cache.prepared.lock().await;
        let track = if let Some(index) = entries.iter().position(|(key, _)| key == &video) {
            entries.remove(index).ok_or(INTERNAL)?.1
        } else {
            if entries.len() >= PREPARED_TRACK_LIMIT {
                let index = entries
                    .iter()
                    .position(|(_, track)| Arc::strong_count(track) == 1)
                    .ok_or(StatusCode::TOO_MANY_REQUESTS)?;
                reset_staging(&entries[index].1.directory)
                    .await
                    .map_err(internal)?;
                entries.remove(index);
            }
            Arc::new(PreparedTrack {
                directory: cache.root.join(STAGING_DIR).join("prepared").join(&video),
                single: OnceCell::new(),
            })
        };
        entries.push_back((video, track.clone()));
        Ok::<_, StatusCode>(track)
    })
    .await
    .map_err(internal)??;
    timeout(
        DOWNLOAD_TIMEOUT,
        track.single.get_or_try_init(|| async {
            reset_staging(&track.directory).await.map_err(internal)?;
            fs::create_dir(&track.directory).await.map_err(internal)?;
            download_to_stage(download(imports, id).await?, &track.directory).await
        }),
    )
    .await
    .map_err(|_| StatusCode::GATEWAY_TIMEOUT)??;
    Ok(track)
}

async fn reset_staging(path: &FilePath) -> io::Result<()> {
    match fs::remove_dir_all(path).await {
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
        result => result,
    }
}

async fn publish_track(
    pool: &PgPool,
    imports: &Imports,
    id: &str,
    stage: &FilePath,
    prepared: &FilePath,
    single: &Single,
) -> Result<JsonValue, StatusCode> {
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
    let filename = single.filename();
    fs::hard_link(prepared.join(&filename), stage.join(&filename))
        .await
        .map_err(internal)?;
    let cover = prepared.join("cover.jpg");
    if fs::try_exists(&cover).await.map_err(internal)? {
        fs::hard_link(cover, stage.join("cover.jpg"))
            .await
            .map_err(internal)?;
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
    index_single(pool, imports, single).await
}

async fn download(imports: &Imports, id: &str) -> Result<Streaming<DownloadPart>, StatusCode> {
    let mut request = Request::new(DownloadRequest {
        video_id: id.to_owned(),
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
    let mut cover = None;
    let mut audio = File::create_new(&audio_path).await.map_err(internal)?;
    let mut cover_bytes = 0;
    let mut audio_bytes = 0;
    while let Some(part) = stream.message().await.map_err(upstream)? {
        let (chunk, file, size, limit) = match part.part {
            Some(Part::Cover(bytes)) if !bytes.is_empty() => {
                if cover.is_none() {
                    cover = Some(File::create_new(&cover_path).await.map_err(internal)?);
                }
                (
                    bytes,
                    cover.as_mut().ok_or(INTERNAL)?,
                    &mut cover_bytes,
                    MAX_COVER_BYTES,
                )
            }
            Some(Part::Audio(bytes)) => (bytes, &mut audio, &mut audio_bytes, MAX_AUDIO_BYTES),
            _ => return Err(BAD_GATEWAY),
        };
        *size += chunk.len() as u64;
        if *size > limit {
            return Err(BAD_GATEWAY);
        }
        file.write_all(&chunk).await.map_err(internal)?;
    }
    if let Some(cover) = cover {
        cover.sync_all().await.map_err(internal)?;
    }
    audio.sync_all().await.map_err(internal)?;
    tokio::task::spawn_blocking(move || {
        check_mp3(&audio_path)?;
        if cover_bytes > 0 {
            check_cover(&cover_path, "image/jpeg")?;
        }
        Ok::<_, io::Error>(())
    })
    .await
    .map_err(internal)?
    .map_err(|error| {
        eprintln!("Invalid media from YouTube downloader: {error}");
        BAD_GATEWAY
    })?;
    Ok(single)
}

async fn index_single(
    pool: &PgPool,
    imports: &Imports,
    single: &Single,
) -> Result<JsonValue, StatusCode> {
    let directory = single.directory();
    let cover = fs::try_exists(imports.root.join(&directory).join("cover.jpg"))
        .await
        .map_err(internal)?
        .then(|| format!("{DOWNLOAD_PREFIX}{directory}/cover.jpg"));
    let audio = single.audio_path();
    let mut tx = pool.begin().await.map_err(internal)?;
    let artist = artist_id(&mut tx, &single.artist).await.map_err(internal)?;
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
