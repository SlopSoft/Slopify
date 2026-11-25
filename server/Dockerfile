FROM rust:1-alpine AS builder
WORKDIR /build
COPY Cargo.toml Cargo.lock build.rs ./
COPY migrations/ migrations/
COPY src/ src/

FROM builder AS dev
RUN addgroup -S -g 65532 slopify \
    && adduser -S -D -u 65532 -G slopify -h /home/slopify slopify \
    && chown -R 65532:65532 /build
ENV CARGO_HOME=/home/slopify/.cargo
USER 65532:65532
RUN cargo build --locked --features swagger
CMD ["cargo", "run", "--locked", "--features", "swagger"]

FROM builder AS release-builder
RUN cargo build --locked --release

FROM scratch AS release
USER 65532:65532
ENTRYPOINT ["/slopify-server"]
COPY --from=release-builder /build/target/release/slopify-server /slopify-server
