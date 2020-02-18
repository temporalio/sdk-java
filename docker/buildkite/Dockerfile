FROM openjdk:8-alpine

# Apache Thrift version
ENV APACHE_THRIFT_VERSION=0.9.3

# Install dependencies using apk
RUN apk update && apk add --virtual wget ca-certificates wget && apk add --virtual build-dependencies build-base gcc

# Compile source
RUN set -ex ;\
	wget http://www-us.apache.org/dist/thrift/${APACHE_THRIFT_VERSION}/thrift-${APACHE_THRIFT_VERSION}.tar.gz ;\
	tar -xvf thrift-${APACHE_THRIFT_VERSION}.tar.gz ;\
	rm thrift-${APACHE_THRIFT_VERSION}.tar.gz ;\
	cd thrift-${APACHE_THRIFT_VERSION}/ ;\
	./configure --enable-libs=no --enable-tests=no --enable-tutorial=no --with-cpp=no --with-c_glib=no --with-java=yes --with-ruby=no --with-erlang=no --with-go=no --with-nodejs=no --with-python=no ;\
	make -j2 && make install ;\
	cd .. && rm -rf thrift-${APACHE_THRIFT_VERSION}

# Build protobuf
ENV PROTOBUF_REVISION 3.6.0
RUN curl -sLO https://github.com/google/protobuf/releases/download/v${PROTOBUF_REVISION}/protoc-${PROTOBUF_REVISION}-linux-x86_64.zip \
  && unzip protoc-${PROTOBUF_REVISION}-linux-x86_64.zip -d ./usr/local \
  && chmod +x /usr/local/bin/protoc \
  && chmod -R 755 /usr/local/include/ \
  && rm protoc-${PROTOBUF_REVISION}-linux-x86_64.zip

# Cleanup packages and remove cache
RUN apk del build-dependencies wget && rm -rf /var/cache/apk/*

RUN mkdir /temporal-java-client
WORKDIR /temporal-java-client