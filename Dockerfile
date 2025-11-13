# Build stage: download JAR, install libgoogle-perftools4, and compile Java entry point
FROM debian:bullseye-slim AS build
RUN apt-get update && \
    apt-get install -y wget openjdk-17-jdk && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# java-tron repository git tag
ARG JAVA_TRON_VERSION
ARG NETWORK

WORKDIR /src
RUN echo "NETWORK: $NETWORK" && \
    if [ "$NETWORK" = "nile" ]; then \
        DOWNLOAD_URL="https://github.com/tron-nile-testnet/nile-testnet/releases/download/${JAVA_TRON_VERSION}/FullNode-Nile-${JAVA_TRON_VERSION#*-v}.jar"; \
        echo "Download URL: $DOWNLOAD_URL"; \
        wget "$DOWNLOAD_URL" -O /src/FullNode.jar; \
    elif [ "$NETWORK" = "mainnet" ]; then \
        DOWNLOAD_URL="https://github.com/tronprotocol/java-tron/releases/download/${JAVA_TRON_VERSION}/FullNode.jar"; \
        echo "Download URL: $DOWNLOAD_URL"; \
        wget "$DOWNLOAD_URL" -O /src/FullNode.jar; \
    fi

# Copy and compile Java entry point (compile with Java 8 compatibility for distroless/java:8)
COPY ./src/main/java/EntryPoint.java /src/EntryPoint.java
RUN mkdir -p /src/classes && \
    javac -source 8 -target 8 -d /src/classes /src/EntryPoint.java

FROM gcr.io/distroless/java:8

# Copy compiled Java entry point
COPY --from=build /src/classes /usr/local/tron/classes
COPY --from=build /src/FullNode.jar /usr/local/tron/FullNode.jar
COPY ./plugins/ /usr/local/tron/plugins/
COPY ./configs/ /etc/tron/

ENTRYPOINT [ "java", "-cp", "/usr/local/tron/classes", "EntryPoint" ]
