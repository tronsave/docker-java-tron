# Build stage: download JAR and install libgoogle-perftools4
FROM debian:bullseye-slim AS build
RUN apt-get update && \
    apt-get install -y wget libgoogle-perftools4 bash && \
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

# Copy and make entry.sh executable in build stage
COPY ./entry.sh /src/entry.sh
RUN chmod +x /src/entry.sh

# Copy bash and find its dependencies, then copy them to a staging area
RUN mkdir -p /src/bash-libs/bin && \
    cp /bin/bash /src/bash-libs/bin/ && \
    ldd /bin/bash 2>/dev/null | awk '/=>/ {print $3}' | while read lib; do \
        if [ -f "$lib" ]; then \
            mkdir -p "/src/bash-libs$(dirname "$lib")" && \
            cp "$lib" "/src/bash-libs$lib"; \
        fi; \
    done || true

FROM gcr.io/distroless/java:8-debug

# Copy libgoogle-perftools4 from build stage
# Distroless java:8 is based on Debian and includes standard system libraries
# (libc, libm, libpthread, libdl, etc.) which tcmalloc depends on
# We only need to copy tcmalloc itself as the base libraries are already present
COPY --from=build /usr/lib/x86_64-linux-gnu/libtcmalloc.so.4 /usr/lib/x86_64-linux-gnu/libtcmalloc.so.4

# Copy bash and its dependencies from build stage (needed for entry.sh)
# Copy bash binary
COPY --from=build /src/bash-libs/bin/bash /bin/bash
# Copy all library dependencies preserving their directory structure
COPY --from=build /src/bash-libs/lib/ /lib/
COPY --from=build /src/bash-libs/usr/lib/ /usr/lib/

# Optional: Set tcmalloc preload
ENV LD_PRELOAD="/usr/lib/x86_64-linux-gnu/libtcmalloc.so.4"
ENV TCMALLOC_RELEASE_RATE=10

COPY --from=build /src/FullNode.jar /usr/local/tron/FullNode.jar
COPY ./plugins/ /usr/local/tron/plugins/
COPY ./configs/ /etc/tron/
COPY --from=build /src/entry.sh /usr/bin/entry.sh

ENTRYPOINT [ "/usr/bin/entry.sh" ]
