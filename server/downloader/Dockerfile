FROM ghcr.io/astral-sh/uv:python3.15-alpine AS source
WORKDIR /app
ENV UV_PYTHON_DOWNLOADS=never UV_LINK_MODE=copy PYTHONUNBUFFERED=1
COPY downloader/pyproject.toml downloader/uv.lock ./

FROM source AS lint
COPY downloader/worker.py downloader/test_worker.py ./
RUN uv run --locked --only-group dev ruff check

FROM source AS build
COPY proto/downloader.proto /proto/downloader.proto
RUN uv sync --locked --no-default-groups --group build \
    && .venv/bin/python -m grpc_tools.protoc -I/proto --python_out=. --grpc_python_out=. /proto/downloader.proto \
    && uv sync --locked --no-default-groups

FROM build AS test
COPY downloader/worker.py downloader/test_worker.py ./
RUN .venv/bin/python -m unittest -q

FROM source AS release
RUN apk add --no-cache ffmpeg nodejs
COPY --from=build /app/.venv /app/.venv
COPY --from=build /app/downloader_pb2.py /app/downloader_pb2_grpc.py ./
COPY downloader/worker.py ./
ENV PATH="/app/.venv/bin:$PATH"
USER 65532:65532
CMD ["/app/.venv/bin/python", "worker.py"]
