use std::{
    collections::HashMap,
    error::Error,
    fs::{self, DirEntry, File},
    io::{self, Read, Seek, SeekFrom},
    path::{Component, Path},
};

use sqlx::PgPool;

use super::{
    DOWNLOAD_PREFIX, MediaRoots, STAGING_DIR, artist_id,
    media::{MAX_COVER_BYTES, cover_mime},
};

struct Artist {
    name: String,
    albums: Vec<Album>,
}

struct Album {
    title: String,
    year: i16,
    cover: Option<String>,
    tracks: Vec<Track>,
}

struct Track {
    number: i16,
    title: String,
    path: String,
}

pub(crate) async fn import_at_startup(
    pool: &PgPool,
    roots: MediaRoots,
) -> Result<(), Box<dyn Error>> {
    if roots.local.is_none() && roots.downloads.is_none() {
        return Ok(());
    }
    let mut transaction = pool.begin().await?;
    let track_paths: Vec<String> = sqlx::query_scalar("SELECT audio_path FROM tracks")
        .fetch_all(&mut *transaction)
        .await?;
    let cover_paths: Vec<String> =
        sqlx::query_scalar("SELECT DISTINCT cover_path FROM albums WHERE cover_path IS NOT NULL")
            .fetch_all(&mut *transaction)
            .await?;
    let (artists, missing_tracks, missing_covers) = tokio::task::spawn_blocking(move || {
        let mut artists = Vec::new();
        let mut missing_tracks = Vec::new();
        let mut missing_covers = Vec::new();
        for (root, prefix) in [(roots.local, ""), (roots.downloads, DOWNLOAD_PREFIX)] {
            let Some(root) = root else {
                continue;
            };
            let Some(root_entries) = entries(&root) else {
                continue;
            };
            missing_tracks.extend(missing_paths(&root, prefix, &track_paths));
            missing_covers.extend(missing_paths(&root, prefix, &cover_paths));
            artists.extend(scan(&root, prefix, root_entries));
        }
        (artists, missing_tracks, missing_covers)
    })
    .await?;

    // Free track numbers before importing renamed files in the same scan.
    let affected_albums: Vec<i64> =
        sqlx::query_scalar("DELETE FROM tracks WHERE audio_path = ANY($1) RETURNING album_id")
            .bind(missing_tracks)
            .fetch_all(&mut *transaction)
            .await?;
    sqlx::query("UPDATE albums SET cover_path = NULL WHERE cover_path = ANY($1)")
        .bind(missing_covers)
        .execute(&mut *transaction)
        .await?;

    let mut indexed = 0;
    let mut added = 0;

    for artist in artists {
        let artist_id = artist_id(&mut transaction, &artist.name).await?;
        for album in artist.albums {
            let album_id: i64 = sqlx::query_scalar(
                "INSERT INTO albums (artist_id, title, release_year, cover_path)
                 VALUES ($1, $2, $3, $4)
                 ON CONFLICT (artist_id, release_year, title) DO UPDATE
                 SET cover_path = COALESCE(EXCLUDED.cover_path, albums.cover_path)
                 RETURNING id",
            )
            .bind(artist_id)
            .bind(album.title)
            .bind(album.year)
            .bind(album.cover)
            .fetch_one(&mut *transaction)
            .await?;
            let numbers: Vec<_> = album.tracks.iter().map(|track| track.number).collect();
            let titles: Vec<_> = album
                .tracks
                .iter()
                .map(|track| track.title.as_str())
                .collect();
            let paths: Vec<_> = album
                .tracks
                .iter()
                .map(|track| track.path.as_str())
                .collect();
            added += sqlx::query(
                "INSERT INTO tracks (album_id, track_number, title, audio_path)
                 SELECT $1, number, title, path
                 FROM unnest($2::smallint[], $3::text[], $4::text[]) WITH ORDINALITY
                     AS files(number, title, path, position)
                 ORDER BY position
                 ON CONFLICT DO NOTHING",
            )
            .bind(album_id)
            .bind(numbers)
            .bind(titles)
            .bind(paths)
            .execute(&mut *transaction)
            .await?
            .rows_affected();

            let stored: HashMap<i16, String> =
                sqlx::query_as("SELECT track_number, audio_path FROM tracks WHERE album_id = $1")
                    .bind(album_id)
                    .fetch_all(&mut *transaction)
                    .await?
                    .into_iter()
                    .collect();
            for track in album.tracks {
                match stored.get(&track.number) {
                    Some(path) if *path == track.path => indexed += 1,
                    Some(path) => warn(
                        Path::new(&track.path),
                        format_args!(
                            "track number {} is already assigned to {path:?}",
                            track.number
                        ),
                    ),
                    None => warn(
                        Path::new(&track.path),
                        "audio path is already assigned to another catalog entry",
                    ),
                }
            }
        }
    }

    let removed = affected_albums.len();
    if removed > 0 {
        let affected_artists: Vec<i64> = sqlx::query_scalar(
            "DELETE FROM albums
             WHERE id = ANY($1) AND NOT EXISTS (SELECT 1 FROM tracks WHERE album_id = albums.id)
             RETURNING artist_id",
        )
        .bind(affected_albums)
        .fetch_all(&mut *transaction)
        .await?;
        sqlx::query(
            "DELETE FROM artists
             WHERE id = ANY($1) AND NOT EXISTS (SELECT 1 FROM albums WHERE artist_id = artists.id)",
        )
        .bind(affected_artists)
        .execute(&mut *transaction)
        .await?;
    }
    transaction.commit().await?;
    println!("Library scan finished: {indexed} tracks indexed ({added} added, {removed} removed)");
    Ok(())
}

fn missing_paths(root: &Path, prefix: &str, paths: &[String]) -> Vec<String> {
    paths
        .iter()
        .filter(|stored| {
            let relative = if prefix.is_empty() {
                if stored.starts_with(DOWNLOAD_PREFIX) {
                    return false;
                }
                stored
            } else {
                let Some(relative) = stored.strip_prefix(prefix) else {
                    return false;
                };
                relative
            };
            let components = Path::new(relative).components();
            if !components
                .clone()
                .all(|part| matches!(part, Component::Normal(_)))
            {
                return false;
            }
            let mut path = root.to_path_buf();
            for part in components {
                path.push(part);
                match fs::symlink_metadata(&path) {
                    Ok(metadata) if !metadata.file_type().is_symlink() => {}
                    Err(error) if error.kind() == io::ErrorKind::NotFound => return true,
                    _ => return false,
                }
            }
            false
        })
        .cloned()
        .collect()
}

fn scan(root: &Path, prefix: &str, root_entries: Vec<DirEntry>) -> Vec<Artist> {
    let mut artists = Vec::new();
    for artist_dir in root_entries {
        if !prefix.is_empty() && artist_dir.file_name() == STAGING_DIR {
            continue;
        }
        let Some(name) = directory_name(&artist_dir) else {
            continue;
        };
        let mut albums = Vec::new();
        for album_dir in entries(&artist_dir.path()).unwrap_or_default() {
            let Some(name) = directory_name(&album_dir) else {
                continue;
            };
            let path = album_dir.path();
            let Some((year, title)) = numbered_title(&name).filter(|(year, _)| year.len() == 4)
            else {
                warn(&path, "expected album directory named [YYYY] Title");
                continue;
            };
            let year = year.parse::<i16>().expect("four ASCII digits fit in i16");
            if year == 0 {
                warn(&path, "release year must be between 0001 and 9999");
                continue;
            }
            if let Some(album) = scan_album(root, prefix, &path, year, title) {
                albums.push(album);
            }
        }
        if !albums.is_empty() {
            artists.push(Artist { name, albums });
        }
    }
    artists
}

fn scan_album(root: &Path, prefix: &str, album: &Path, year: i16, title: &str) -> Option<Album> {
    let mut tracks = Vec::new();
    let mut covers = Vec::new();
    for entry in entries(album).unwrap_or_default() {
        let path = entry.path();
        match entry.file_type() {
            Ok(kind) if kind.is_file() => {}
            Ok(_) => {
                warn(&path, "expected a regular media file");
                continue;
            }
            Err(error) => {
                warn(&path, error);
                continue;
            }
        }
        let Some(relative) = path.strip_prefix(root).ok().and_then(Path::to_str) else {
            warn(&path, "media path must be valid UTF-8");
            continue;
        };
        if let Some(mime) = cover_mime(&path) {
            match check_cover(&path, mime) {
                Ok(()) => covers.push(format!("{prefix}{relative}")),
                Err(error) => warn(&path, error),
            }
        } else if path
            .extension()
            .and_then(|ext| ext.to_str())
            .is_some_and(|ext| ext.eq_ignore_ascii_case("mp3"))
        {
            let stem = path
                .file_stem()
                .and_then(|stem| stem.to_str())
                .unwrap_or_default();
            let Some((number, name)) = numbered_title(stem)
                .and_then(|(number, name)| Some((number.parse::<i16>().ok()?, name)))
                .filter(|(number, _)| *number > 0)
            else {
                warn(
                    &path,
                    "expected [number] Title.mp3 with a track number from 1 to 32767",
                );
                continue;
            };
            match check_mp3(&path) {
                Ok(()) => tracks.push(Track {
                    number,
                    title: name.to_owned(),
                    path: format!("{prefix}{relative}"),
                }),
                Err(error) => warn(&path, error),
            }
        } else {
            warn(&path, "unsupported media extension");
        }
    }
    if tracks.is_empty() {
        warn(album, "no valid MP3 tracks");
        return None;
    }
    tracks.sort_unstable_by(|first, second| {
        (first.number, &first.title, &first.path).cmp(&(second.number, &second.title, &second.path))
    });
    covers.sort_by_key(|path| {
        !Path::new(path)
            .file_stem()
            .and_then(|stem| stem.to_str())
            .is_some_and(|stem| stem.eq_ignore_ascii_case("cover"))
    });
    let mut covers = covers.into_iter();
    let cover = covers.next();
    for path in covers {
        warn(Path::new(&path), "album already has a selected cover");
    }
    Some(Album {
        title: title.to_owned(),
        year,
        cover,
        tracks,
    })
}

fn entries(path: &Path) -> Option<Vec<DirEntry>> {
    let directory = match fs::read_dir(path) {
        Ok(directory) => directory,
        Err(error) => {
            warn(path, error);
            return None;
        }
    };
    let mut entries = Vec::new();
    for entry in directory {
        match entry {
            Ok(entry) => entries.push(entry),
            Err(error) => warn(path, error),
        }
    }
    entries.sort_by_cached_key(DirEntry::file_name);
    Some(entries)
}

fn directory_name(entry: &DirEntry) -> Option<String> {
    match entry.file_type() {
        Ok(kind) if kind.is_dir() => {}
        Ok(_) => {
            warn(&entry.path(), "expected a directory");
            return None;
        }
        Err(error) => {
            warn(&entry.path(), error);
            return None;
        }
    }
    match entry.file_name().into_string() {
        Ok(name) if valid_title(&name) => Some(name),
        _ => {
            warn(
                &entry.path(),
                "directory name must be non-empty UTF-8 without control characters",
            );
            None
        }
    }
}

fn valid_title(title: &str) -> bool {
    !title.trim().is_empty() && !title.chars().any(char::is_control)
}

fn numbered_title(name: &str) -> Option<(&str, &str)> {
    let (number, title) = name.strip_prefix('[')?.split_once("] ")?;
    if !number.is_empty() && number.bytes().all(|byte| byte.is_ascii_digit()) && valid_title(title)
    {
        Some((number, title))
    } else {
        None
    }
}

pub(super) fn check_mp3(path: &Path) -> io::Result<()> {
    let mut file = File::open(path)?;
    let mut header = [0; 10];
    file.read_exact(&mut header)?;
    if header.starts_with(b"ID3") {
        let size_bytes = &header[6..];
        if !(2..=4).contains(&header[3])
            || header[4] == 0xff
            || size_bytes.iter().any(|byte| *byte >= 128)
        {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "invalid ID3 tag header",
            ));
        }
        let size = size_bytes
            .iter()
            .fold(0u64, |size, byte| (size << 7) | u64::from(*byte));
        let footer = if header[3] == 4 && header[5] & 0x10 != 0 {
            10
        } else {
            0
        };
        file.seek(SeekFrom::Start(10 + size + footer))?;
        file.read_exact(&mut header)?;
    }
    let version = (header[1] >> 3) & 3;
    let layer = (header[1] >> 1) & 3;
    if header[0] != 0xff
        || header[1] & 0xe0 != 0xe0
        || version == 1
        || layer != 1
        || header[2] >> 4 == 15
        || (header[2] >> 2) & 3 == 3
    {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "missing MPEG Layer III frame header",
        ));
    }
    Ok(())
}

pub(super) fn check_cover(path: &Path, mime: &str) -> io::Result<()> {
    let mut file = File::open(path)?;
    if file.metadata()?.len() > MAX_COVER_BYTES {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "cover exceeds 5 MiB",
        ));
    }
    let mut header = [0; 12];
    file.read_exact(&mut header)?;
    let matches = match mime {
        "image/jpeg" => header.starts_with(b"\xff\xd8\xff"),
        "image/png" => header.starts_with(b"\x89PNG\r\n\x1a\n"),
        "image/webp" => header.starts_with(b"RIFF") && &header[8..] == b"WEBP",
        _ => false,
    };
    if !matches {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "cover header does not match its extension",
        ));
    }
    Ok(())
}

fn warn(path: &Path, reason: impl std::fmt::Display) {
    eprintln!("WARN library scan: {path:?}: {reason}");
}
