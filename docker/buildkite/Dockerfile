FROM openjdk:8-alpine

# Based on https://github.com/sgerrand/alpine-pkg-glibc
# Needed to run protoc java plugin
RUN wget -q -O /etc/apk/keys/sgerrand.rsa.pub https://alpine-pkgs.sgerrand.com/sgerrand.rsa.pub
RUN wget https://github.com/sgerrand/alpine-pkg-glibc/releases/download/2.33-r0/glibc-2.33-r0.apk
RUN apk add glibc-2.33-r0.apk

# Install dependencies using apk
RUN apk update && apk add --virtual wget ca-certificates wget && apk add protobuf

# Git is needed in order to update the dls submodule
RUN apk add --virtual git

RUN mkdir /temporal-java-client
WORKDIR /temporal-java-client
