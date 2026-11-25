CREATE TABLE artists (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name text NOT NULL CHECK (btrim(name) <> ''),
    UNIQUE (name)
);

CREATE TABLE albums (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    artist_id bigint NOT NULL REFERENCES artists (id),
    title text NOT NULL CHECK (btrim(title) <> ''),
    release_year smallint NOT NULL CHECK (release_year BETWEEN 1 AND 9999),
    cover_path text CHECK (btrim(cover_path) <> ''),
    UNIQUE (artist_id, release_year, title)
);

CREATE TABLE tracks (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    album_id bigint NOT NULL REFERENCES albums (id),
    title text NOT NULL CHECK (btrim(title) <> ''),
    track_number smallint NOT NULL CHECK (track_number > 0),
    audio_path text NOT NULL UNIQUE CHECK (btrim(audio_path) <> ''),
    UNIQUE (album_id, track_number)
);
