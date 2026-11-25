FROM rust:1-alpine AS builder
WORKDIR /build
COPY Cargo.toml Cargo.lock build.rs ./
COPY migrations/ migrations/
COPY src/ src/

FROM builder AS dev-builder
RUN cargo build --locked --features swagger

FROM builder AS release-builder
RUN cargo build --locked --release

FROM scratch AS runtime
USER 65532:65532
ENTRYPOINT ["/slopify-server"]

FROM runtime AS dev
COPY --from=dev-builder /build/target/debug/slopify-server /slopify-server

FROM runtime AS release
COPY --from=release-builder /build/target/release/slopify-server /slopify-server
