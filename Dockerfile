FROM openjdk:8-jdk AS build

# java-tron repository git tag
ARG JAVA_TRON_VERSION
ARG NETWORK

WORKDIR /src
RUN if [ "$NETWORK" = "nile" ]; then \
    git clone -b master --depth 1 https://github.com/tron-nile-testnet/nile-testnet.git java-tron; \
    else \
    git clone -b "${JAVA_TRON_VERSION}" --depth 1 https://github.com/tronprotocol/java-tron.git; \
    fi

RUN cd java-tron && \
    ./gradlew build -x test

FROM openjdk:8-jdk AS build-plugin

WORKDIR /src
RUN git clone --depth 1 https://github.com/tronprotocol/event-plugin.git

RUN cd event-plugin && \
    ./gradlew build -x test

FROM openjdk:8-jre
# Install libgoogle-perftools4 for tcmalloc
RUN apt-get update && \
    apt-get install -y libgoogle-perftools4 && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Optional: Set tcmalloc preload
ENV LD_PRELOAD="/usr/lib/x86_64-linux-gnu/libtcmalloc.so.4"
ENV TCMALLOC_RELEASE_RATE=10

COPY --from=build /src/java-tron/build/libs/FullNode.jar /usr/local/tron/FullNode.jar
COPY --from=build-plugin /src/event-plugin/build/plugins/ /usr/local/tron/plugins/
COPY ./configs/ /etc/tron/

COPY ./entry.sh /usr/bin/entry.sh

ENTRYPOINT [ "entry.sh" ]
