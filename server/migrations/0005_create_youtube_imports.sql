-- Save the import before publishing its files so a retry can finish indexing
-- if the API stops between the filesystem rename and the catalog commit.
CREATE TABLE youtube_imports (
    video_id text PRIMARY KEY CHECK (video_id ~ '^[A-Za-z0-9_-]{11}$'),
    artist text NOT NULL,
    title text NOT NULL,
    release_year smallint NOT NULL CHECK (release_year BETWEEN 1 AND 9999),
    UNIQUE (artist, release_year, title)
);
