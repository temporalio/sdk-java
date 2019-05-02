FROM openjdk:8-jre

COPY --from=thrift:0.9.3 /usr/local/bin/thrift /usr/local/bin/thrift

RUN mkdir /cadence-java-client
WORKDIR /cadence-java-client