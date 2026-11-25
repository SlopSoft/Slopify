FROM rust:1-alpine AS builder
WORKDIR /build
COPY Cargo.toml Cargo.lock build.rs ./
COPY migrations/ migrations/
COPY src/ src/
RUN cargo build --locked --release

FROM scratch
COPY --from=builder /build/target/release/slopify-server /slopify-server
USER 65532:65532
ENTRYPOINT ["/slopify-server"]
