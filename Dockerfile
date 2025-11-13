# Build stage: download JAR and install libgoogle-perftools4
FROM debian:bullseye-slim AS build
RUN apt-get update && \
    apt-get install -y wget libgoogle-perftools4 && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# java-tron repository git tag
ARG JAVA_TRON_VERSION
ARG NETWORK

WORKDIR /src
RUN if [ "$NETWORK" = "nile" ]; then \
        wget https://github.com/tron-nile-testnet/nile-testnet/releases/download/${JAVA_TRON_VERSION}/FullNode-Nile-${JAVA_TRON_VERSION#*-v}.jar -O /src/FullNode.jar; \
    elif [ "$NETWORK" = "mainnet" ]; then \
        wget https://github.com/tronprotocol/java-tron/releases/download/${JAVA_TRON_VERSION}/FullNode.jar -O /src/FullNode.jar; \
    fi

FROM gcr.io/distroless/java:8-debug

# Copy libgoogle-perftools4 from build stage
# Distroless java:8 is based on Debian and includes standard system libraries
# (libc, libm, libpthread, libdl, etc.) which tcmalloc depends on
# We only need to copy tcmalloc itself as the base libraries are already present
COPY --from=build /usr/lib/x86_64-linux-gnu/libtcmalloc.so.4 /usr/lib/x86_64-linux-gnu/libtcmalloc.so.4

# Optional: Set tcmalloc preload
ENV LD_PRELOAD="/usr/lib/x86_64-linux-gnu/libtcmalloc.so.4"
ENV TCMALLOC_RELEASE_RATE=10

COPY --from=build /src/FullNode.jar /usr/local/tron/FullNode.jar
COPY ./plugins/ /usr/local/tron/plugins/
COPY ./configs/ /etc/tron/

COPY ./entry.sh /usr/bin/entry.sh

ENTRYPOINT [ "entry.sh" ]
