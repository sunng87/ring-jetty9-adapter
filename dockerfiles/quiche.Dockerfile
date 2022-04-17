FROM rust:1.60.0-buster AS builder

RUN apt-get update && apt-get install -y cmake

RUN git clone --recursive https://github.com/cloudflare/quiche
WORKDIR quiche
RUN git checkout 0.12.0

RUN cargo build --release --features ffi,pkg-config-meta,qlog
## output: target/release/libquiche.so

FROM openjdk:17-jdk-slim-buster
COPY --from=builder quiche/quiche/include/quiche.h /usr/include/
COPY --from=builder quiche/target/release/libquiche.a /usr/lib/
COPY --from=builder quiche/target/release/libquiche.so /usr/lib/
ADD quiche.pc /usr/lib/pkgconfig/
