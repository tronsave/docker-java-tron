FROM openjdk:9-b179-jdk AS build

# Install wget
RUN apt-get update && apt-get install -y wget && rm -rf /var/lib/apt/lists/*

# java-tron repository git tag
ARG JAVA_TRON_VERSION
ARG NETWORK

WORKDIR /src
RUN if [ "$NETWORK" = "nile" ]; then \
        git clone -b master --depth 1 https://github.com/tron-nile-testnet/nile-testnet.git java-tron && \
        cd java-tron && \
        ./gradlew build -x test && \
        cp build/libs/FullNode.jar /src/FullNode.jar; \
    elif [ "$NETWORK" = "mainnet" ]; then \
        wget https://github.com/tronprotocol/java-tron/releases/download/${JAVA_TRON_VERSION}/FullNode.jar -O /src/FullNode.jar; \
    else \
        git clone -b "${JAVA_TRON_VERSION}" --depth 1 https://github.com/tronprotocol/java-tron.git && \
        cd java-tron && \
        ./gradlew build -x test && \
        cp build/libs/FullNode.jar /src/FullNode.jar; \
    fi

FROM openjdk:9-b179-jdk AS build-plugin

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

COPY --from=build /src/FullNode.jar /usr/local/tron/FullNode.jar
COPY --from=build-plugin /src/event-plugin/build/plugins/ /usr/local/tron/plugins/
COPY ./configs/ /etc/tron/

COPY ./entry.sh /usr/bin/entry.sh

ENTRYPOINT [ "entry.sh" ]
