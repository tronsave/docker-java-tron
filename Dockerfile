# Build stage: download JAR, install libgoogle-perftools4, and compile Java entry point
FROM debian:bullseye-slim AS build
RUN apt-get update && \
    apt-get install -y wget libgoogle-perftools4 openjdk-17-jdk && \
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

# Copy and compile Java entry point (compile with Java 8 compatibility for distroless/java:8)
COPY ./src/main/java/EntryPoint.java /src/EntryPoint.java
RUN mkdir -p /src/classes && \
    javac -source 8 -target 8 -d /src/classes /src/EntryPoint.java

FROM gcr.io/distroless/java:8

# Copy libgoogle-perftools4 from build stage
# Distroless java:8 is based on Debian and includes standard system libraries
# (libc, libm, libpthread, libdl, etc.) which tcmalloc depends on
# We only need to copy tcmalloc itself as the base libraries are already present
COPY --from=build /usr/lib/x86_64-linux-gnu/libtcmalloc.so.4 /usr/lib/x86_64-linux-gnu/libtcmalloc.so.4

# Copy compiled Java entry point
COPY --from=build /src/classes /usr/local/tron/classes

# Optional: Set tcmalloc preload
ENV LD_PRELOAD="/usr/lib/x86_64-linux-gnu/libtcmalloc.so.4"
ENV TCMALLOC_RELEASE_RATE=10

COPY --from=build /src/FullNode.jar /usr/local/tron/FullNode.jar
COPY ./plugins/ /usr/local/tron/plugins/
COPY ./configs/ /etc/tron/

ENTRYPOINT [ "java", "-cp", "/usr/local/tron/classes", "EntryPoint" ]
