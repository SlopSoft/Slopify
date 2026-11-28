import os
import shlex
import sys
import time
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import grpc

from worker import Downloader, messages, proxy_config, run


class ProxyTests(unittest.TestCase):
    def test_socks5_uses_proxy_dns(self):
        config = proxy_config("socks5://user:p%40ss@proxy.example:1080")
        self.assertEqual(
            shlex.split(config.decode()),
            ["--proxy", "socks5h://user:p%40ss@proxy.example:1080"],
        )

    def test_missing_proxy_does_not_start_yt_dlp(self):
        with patch("worker.run") as launch:
            context = Mock()
            context.abort.side_effect = RuntimeError
            with self.assertRaises(RuntimeError):
                Downloader(None).youtube(context, time.monotonic() + 5)
            context.abort.assert_called_once_with(
                grpc.StatusCode.FAILED_PRECONDITION, "YouTube proxy is not configured",
            )
            launch.assert_not_called()

    def test_inherited_proxy_environment_cannot_override_explicit_proxy(self):
        context = Mock()
        context.time_remaining.return_value = None
        with patch.dict(os.environ, {"NO_PROXY": "*", "https_proxy": "http://other-proxy:80"}):
            output = run(
                context, time.monotonic() + 5, sys.executable, "-c",
                "import os; print([key for key in os.environ if key.lower().endswith('_proxy')])",
                capture=True,
            )
        self.assertEqual(output.strip(), b"[]")


class DownloadTests(unittest.TestCase):
    def test_audio_only_does_not_fetch_or_return_a_cover(self):
        def download(_context, _deadline, *args):
            self.assertNotIn("--write-thumbnail", args)
            template = args[args.index("-o") + 1]
            Path(template.replace("%(ext)s", "m4a")).write_bytes(b"source")
            return {}

        def ffmpeg(_context, _deadline, *args):
            Path(args[-1]).write_bytes(b"audio")

        downloader = Downloader(b"unused")
        with patch.object(downloader, "youtube", side_effect=download), patch("worker.run", side_effect=ffmpeg) as run:
            request = messages.DownloadRequest(video_id="TestVideo_1", audio_only=True)
            parts = list(downloader.Download(request, Mock()))

        run.assert_called_once()
        self.assertEqual(parts, [messages.DownloadPart(audio=b"audio")])

    def test_download_does_not_require_private_filename_metadata(self):
        def download(_context, _deadline, *args):
            for index, arg in enumerate(args):
                if arg == "-o":
                    template = args[index + 1]
                    thumbnail = template.startswith("thumbnail:")
                    extension = "webp" if thumbnail else "m4a"
                    path = template.removeprefix("thumbnail:").replace("%(ext)s", extension)
                    Path(path).write_bytes(b"source")
            return {"title": "Track", "artist": "Artist", "release_year": 2026}

        def ffmpeg(_context, _deadline, *args):
            path = Path(args[-1])
            path.write_bytes(b"cover" if path.suffix == ".jpg" else b"audio")

        downloader = Downloader(b"unused")
        with patch.object(downloader, "youtube", side_effect=download), patch("worker.run", side_effect=ffmpeg):
            parts = list(downloader.Download(messages.DownloadRequest(video_id="TestVideo_1"), Mock()))

        self.assertEqual(
            parts,
            [
                messages.DownloadPart(metadata=messages.TrackMetadata(title="Track", artist="Artist", year=2026)),
                messages.DownloadPart(cover=b"cover"),
                messages.DownloadPart(audio=b"audio"),
            ],
        )
