FROM openjdk:8-jre-alpine

# Install dependencies using apt-get
RUN apk update && apk add --virtual wget ca-certificates wget && apk add --virtual build-dependencies build-base gcc

# Install protoc
ENV PROTOBUF_URL https://github.com/google/protobuf/releases/download/v3.3.0/protobuf-cpp-3.3.0.tar.gz
RUN curl -L -o /tmp/protobuf.tar.gz $PROTOBUF_URL
WORKDIR /tmp/
RUN tar xvzf protobuf.tar.gz
WORKDIR /tmp/protobuf-3.3.0
RUN mkdir /export
RUN ./autogen.sh && \
    ./configure --prefix=/export && \
    make -j 3 && \
    make check && \
    make install

# Cleanup packages and remove cache
RUN apk del build-dependencies wget && rm -rf /var/cache/apk/*

RUN mkdir /temporal-java-client
WORKDIR /temporal-java-client