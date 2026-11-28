import json
import os
import re
import shlex
import signal
import subprocess
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor
from contextlib import suppress
from pathlib import Path
from urllib.parse import urlsplit

import downloader_pb2 as messages
import downloader_pb2_grpc as service
import grpc
from yt_dlp import YoutubeDL
from yt_dlp.networking.exceptions import HTTPError, RequestError

MAX_DURATION = 20 * 60
CHUNK_BYTES = 64 * 1024
MAX_COVER_BYTES = 5 * 1024 * 1024
MAX_AUDIO_BYTES = 32 * 1024 * 1024
MAX_THUMBNAIL_BYTES = 96 * 1024
VIDEO_ID = re.compile(r"[A-Za-z0-9_-]{11}")
YTDLP = (
    "yt-dlp", "--ignore-config", "--config-locations", "-", "--no-cache-dir",
    "--no-plugin-dirs", "--no-js-runtimes", "--js-runtimes", "node",
)
FFMPEG = ("ffmpeg", "-nostdin", "-v", "error")
MP3 = ("-c:a", "libmp3lame", "-b:a", "192k")


def audio_parts(path):
    with path.open("rb") as file:
        while chunk := file.read(CHUNK_BYTES):
            yield messages.DownloadPart(audio=chunk)


def run(context, deadline, *command, input_data=None, capture=False):
    remaining = deadline - time.monotonic()
    if (rpc_limit := context.time_remaining()) is not None:
        remaining = min(remaining, rpc_limit)
    if remaining <= 0 or not context.is_active():
        context.abort(grpc.StatusCode.DEADLINE_EXCEEDED, "Operation timed out")
    with subprocess.Popen(
        command,
        # urllib consults NO_PROXY even when yt-dlp has an explicit HTTP proxy.
        env={key: value for key, value in os.environ.items() if not key.lower().endswith("_proxy")},
        stdin=subprocess.PIPE if input_data is not None else subprocess.DEVNULL,
        stdout=subprocess.PIPE if capture else subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        start_new_session=True,
    ) as process:
        def stop_process():
            if process.poll() is None:
                with suppress(ProcessLookupError):
                    os.killpg(process.pid, signal.SIGKILL)

        if not context.add_callback(stop_process):
            stop_process()
        try:
            output, _ = process.communicate(input=input_data, timeout=remaining)
        except subprocess.TimeoutExpired:
            stop_process()
            context.abort(grpc.StatusCode.DEADLINE_EXCEEDED, "Operation timed out")
        if process.returncode:
            context.abort(grpc.StatusCode.UNAVAILABLE, "YouTube or media processing failed")
        return output


def proxy_config(value):
    if not value or any(char.isspace() or not char.isprintable() for char in value):
        return None
    try:
        proxy = urlsplit(value)
        if (
            proxy.scheme not in {"http", "socks4a", "socks5", "socks5h"}
            or not proxy.hostname
            or proxy.port == 0
            or proxy.path not in {"", "/"}
            or proxy.query
            or proxy.fragment
        ):
            return None
    except ValueError:
        return None
    # Keep destination DNS on the proxy for SOCKS5 URLs.
    proxy = proxy._replace(scheme="socks5h" if proxy.scheme == "socks5" else proxy.scheme)
    return proxy.geturl()


def label(value, fallback):
    if not isinstance(value, str):
        return fallback
    cleaned = "".join(
        char if char.isprintable() and char not in "/\\" else " " for char in value
    ).strip(" .")
    return cleaned.encode()[:120].decode(errors="ignore").rstrip(" .") or fallback


def title(info):
    return label(info.get("track"), label(info.get("title"), "Без названия"))


def artist(info):
    for field in ("artist", "uploader", "channel"):
        if name := label(info.get(field), ""):
            return name
    return "YouTube"


def year(info):
    for value in (info.get("release_year"), str(info.get("upload_date"))[:4]):
        if re.fullmatch(r"[0-9]{4}", str(value)) and int(value) > 0:
            return int(value)
    return time.gmtime().tm_year


class Downloader(service.DownloaderServicer):
    def __init__(self, proxy):
        self.proxy = proxy

    def require_proxy(self, context):
        if self.proxy is None:
            context.abort(grpc.StatusCode.FAILED_PRECONDITION, "YouTube proxy is not configured")

    def youtube(self, context, deadline, *args):
        self.require_proxy(context)
        config = (shlex.join(("--proxy", self.proxy)) + "\n").encode()
        data = run(context, deadline, *YTDLP, *args, input_data=config, capture=True)
        try:
            return json.loads(data)
        except ValueError:
            context.abort(grpc.StatusCode.UNAVAILABLE, "YouTube did not return results")

    def Search(self, request, context):
        query = request.query.strip()
        if not 1 <= len(query) <= 150 or not query.isprintable():
            context.abort(grpc.StatusCode.INVALID_ARGUMENT, "Invalid search query")
        deadline = time.monotonic() + 10
        data = self.youtube(
            context, deadline, "--flat-playlist", "--dump-single-json",
            "--socket-timeout", "5", "--extractor-retries", "0", "--", f"ytsearch15:{query}",
        )
        results = []
        seen = set()
        for entry in data.get("entries") or []:
            if not entry:
                continue
            video_id = entry.get("id", "")
            duration = entry.get("duration")
            if (
                isinstance(video_id, str)
                and VIDEO_ID.fullmatch(video_id)
                and video_id not in seen
                and isinstance(duration, (int, float))
                and 0 < duration <= MAX_DURATION
            ):
                seen.add(video_id)
                results.append(messages.SearchResult(
                    video_id=video_id, title=title(entry), artist=artist(entry),
                ))
        return messages.SearchResponse(results=results)

    def Thumbnail(self, request, context):
        if not VIDEO_ID.fullmatch(request.video_id):
            context.abort(grpc.StatusCode.INVALID_ARGUMENT, "Invalid YouTube video ID")
        self.require_proxy(context)
        url = f"https://i.ytimg.com/vi/{request.video_id}/mqdefault.jpg"
        try:
            with YoutubeDL({"proxy": self.proxy, "socket_timeout": 4, "quiet": True}, auto_init=False) as ydl:
                with ydl.urlopen(url) as response:
                    jpeg = response.read(MAX_THUMBNAIL_BYTES + 1)
        except HTTPError as error:
            error.close()
            if error.status == 404:
                context.abort(grpc.StatusCode.NOT_FOUND, "YouTube did not return a thumbnail")
            context.abort(grpc.StatusCode.UNAVAILABLE, "YouTube thumbnail request failed")
        except RequestError:
            context.abort(grpc.StatusCode.UNAVAILABLE, "YouTube thumbnail request failed")
        if len(jpeg) > MAX_THUMBNAIL_BYTES or not jpeg.startswith(b"\xff\xd8\xff"):
            context.abort(grpc.StatusCode.UNAVAILABLE, "Invalid YouTube thumbnail")
        return messages.ThumbnailResponse(jpeg=jpeg)

    def Download(self, request, context):
        if not VIDEO_ID.fullmatch(request.video_id):
            context.abort(grpc.StatusCode.INVALID_ARGUMENT, "Invalid YouTube video ID")
        deadline = time.monotonic() + 140
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            info = self.youtube(
                context, deadline, "--no-playlist", "--no-simulate",
                "--dump-single-json", "--write-thumbnail", "--no-progress",
                "--socket-timeout", "10", "--retries", "1", "--extractor-retries", "1",
                "--max-filesize", "64M",
                "--match-filter", f"!is_live & duration > 0 & duration <= {MAX_DURATION}",
                "-f", "bestaudio[protocol=https]/best[protocol=https]",
                "-o", str(root / "source.%(ext)s"),
                "-o", f"thumbnail:{root}/source-cover.%(ext)s", "--",
                f"https://www.youtube.com/watch?v={request.video_id}",
            )
            sources = list(root.glob("source.*"))
            if len(sources) != 1:
                context.abort(grpc.StatusCode.NOT_FOUND, "YouTube did not return audio")
            audio = root / "track.mp3"
            thumbnails = list(root.glob("source-cover.*"))
            cover = root / "cover.jpg"
            cover_input = ()
            cover_map = ()
            if len(thumbnails) == 1:
                run(
                    context, deadline, *FFMPEG, "-protocol_whitelist", "file,pipe",
                    "-i", str(thumbnails[0]), "-frames:v", "1",
                    "-vf", "scale=640:640:force_original_aspect_ratio=decrease",
                    "-q:v", "3", str(cover),
                )
                if not 0 < cover.stat().st_size <= MAX_COVER_BYTES:
                    context.abort(grpc.StatusCode.RESOURCE_EXHAUSTED, "Cover exceeds the size limit")
                cover_input = ("-i", str(cover))
                cover_map = ("-map", "1:v:0", "-c:v", "copy", "-disposition:v:0", "attached_pic")
            track_title, track_artist, release_year = title(info), artist(info), year(info)
            run(
                context, deadline, *FFMPEG, "-protocol_whitelist", "file,pipe",
                "-i", str(sources[0]), *cover_input, "-map", "0:a:0", *cover_map,
                "-map_metadata", "-1", *MP3,
                "-id3v2_version", "3", "-metadata", f"title={track_title}",
                "-metadata", f"album={track_title}", "-metadata", f"artist={track_artist}",
                "-metadata", f"date={release_year}", "-metadata", "track=1", str(audio),
            )
            if not 0 < audio.stat().st_size <= MAX_AUDIO_BYTES:
                context.abort(grpc.StatusCode.RESOURCE_EXHAUSTED, "Audio exceeds the size limit")
            yield messages.DownloadPart(metadata=messages.TrackMetadata(
                title=track_title, artist=track_artist, year=release_year,
            ))
            if cover_input:
                with cover.open("rb") as file:
                    while chunk := file.read(CHUNK_BYTES):
                        yield messages.DownloadPart(cover=chunk)
            yield from audio_parts(audio)


if __name__ == "__main__":
    proxy = proxy_config(os.environ.pop("SLOPIFY_YOUTUBE_PROXY", ""))
    if proxy is None:
        print("YouTube search and download require a valid SLOPIFY_YOUTUBE_PROXY")
    server = grpc.server(ThreadPoolExecutor(max_workers=4), maximum_concurrent_rpcs=4)
    service.add_DownloaderServicer_to_server(Downloader(proxy), server)
    server.add_insecure_port("0.0.0.0:50051")
    server.start()
    server.wait_for_termination()
